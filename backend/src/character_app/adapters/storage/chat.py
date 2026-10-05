from dataclasses import asdict
import json
from uuid import uuid4

from character_app.domain.chat import CreateRunResult, Message, ModelContext, ModelMessage, Run, TERMINAL
from character_app.domain.entities import AppError, not_found
from .sqlite import Database, now


def message_from(row) -> Message:
    values = {key: row[key] for key in Message.__dataclass_fields__}
    values["blocks"] = json.loads(values["blocks"])
    return Message(**values)


class SqliteChatRepository:
    def __init__(self, database: Database):
        self.database = database

    @staticmethod
    def _row(connection, user_id, run_id):
        row = connection.execute("SELECT * FROM runs WHERE user_id = ? AND id = ?", (user_id, run_id)).fetchone()
        if row is None:
            raise not_found()
        return row

    def _snapshot(self, connection, row) -> Run:
        messages = {r["id"]: message_from(r) for r in connection.execute("SELECT * FROM messages WHERE user_id = ? AND run_id = ?", (row["user_id"], row["id"]))}
        error = {"code": row["error_code"], "message": row["error_message"]} if row["error_code"] else None
        return Run(row["id"], row["conversation_id"], row["executor"], row["model_name"], bool(row["is_simulated"]), row["status"], row["last_event_sequence"], messages[row["input_message_id"]], messages[row["output_message_id"]], error, row["created_at"], row["updated_at"])

    @staticmethod
    def _event(connection, run_id, kind, data, timestamp):
        # D09: message/state + event commit together; subscribers only see committed work.
        sequence = connection.execute("SELECT last_event_sequence FROM runs WHERE id = ?", (run_id,)).fetchone()[0] + 1
        connection.execute("INSERT INTO run_events VALUES (?, ?, ?, ?, ?)", (run_id, sequence, kind, json.dumps(data, ensure_ascii=False), timestamp))
        connection.execute("UPDATE runs SET last_event_sequence = ?, updated_at = ? WHERE id = ?", (sequence, timestamp, run_id))

    def create_run(self, user_id, conversation_id, client_message_id, text, key, fingerprint, model_name, is_simulated):
        with self.database.transaction() as connection:
            conversation = connection.execute("SELECT id FROM conversations WHERE user_id = ? AND id = ?", (user_id, conversation_id)).fetchone()
            if conversation is None:
                raise not_found()
            receipt = connection.execute("SELECT * FROM request_receipts WHERE user_id = ? AND operation = 'create_run' AND idempotency_key = ?", (user_id, key)).fetchone()
            if receipt:
                if receipt["request_hash"] != fingerprint:
                    raise AppError(409, "IDEMPOTENCY_CONFLICT", "同一个请求标识不能用于不同内容。")
                return CreateRunResult(self._snapshot(connection, self._row(connection, user_id, receipt["resource_id"])), False)
            if connection.execute("SELECT 1 FROM messages WHERE user_id = ? AND client_message_id = ?", (user_id, client_message_id)).fetchone():
                raise AppError(409, "CLIENT_MESSAGE_CONFLICT", "这条客户端消息已经提交。")
            if connection.execute("SELECT 1 FROM runs WHERE conversation_id = ? AND status IN ('queued', 'running', 'cancel_requested')", (conversation_id,)).fetchone():
                raise AppError(409, "CONVERSATION_BUSY", "当前会话还有一个任务未结束。")
            run_id, input_id, output_id = str(uuid4()), str(uuid4()), str(uuid4())
            timestamp = now()
            sequence = connection.execute("SELECT coalesce(max(sequence), 0) + 1 FROM messages WHERE conversation_id = ?", (conversation_id,)).fetchone()[0]
            connection.execute(
                "INSERT INTO runs VALUES (?, ?, ?, ?, ?, 'server', ?, ?, 'queued', 0, NULL, NULL, ?, ?)",
                (run_id, user_id, conversation_id, input_id, output_id, model_name, int(is_simulated), timestamp, timestamp),
            )
            for message_id, client_id, seq, role, blocks, status in [
                (input_id, client_message_id, sequence, "user", [{"type": "text", "text": text}], "completed"),
                (output_id, None, sequence + 1, "assistant", [], "pending"),
            ]:
                connection.execute("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (message_id, user_id, conversation_id, run_id, client_id, seq, role, json.dumps(blocks, ensure_ascii=False), status, timestamp, timestamp))
            connection.execute("INSERT INTO request_receipts VALUES (?, 'create_run', ?, ?, ?, ?)", (user_id, key, fingerprint, run_id, timestamp))
            connection.execute("UPDATE conversations SET updated_at = ?, version = version + 1 WHERE id = ?", (timestamp, conversation_id))
            self._event(connection, run_id, "run.accepted", {"status": "queued"}, timestamp)
            return CreateRunResult(self._snapshot(connection, self._row(connection, user_id, run_id)), True)

    def get_run(self, user_id, run_id):
        with self.database.connect() as connection:
            # A read transaction prevents mixed message/state snapshots during generation.
            connection.execute("BEGIN")
            return self._snapshot(connection, self._row(connection, user_id, run_id))

    def queued(self, limit):
        with self.database.connect() as connection:
            return [(row["user_id"], row["id"]) for row in connection.execute("SELECT user_id, id FROM runs WHERE status = 'queued' ORDER BY created_at, id LIMIT ?", (limit,))]

    def start(self, user_id, run_id):
        with self.database.transaction() as connection:
            row = self._row(connection, user_id, run_id)
            if row["status"] != "queued":
                return False
            connection.execute("UPDATE runs SET status = 'running' WHERE id = ?", (run_id,))
            self._event(connection, run_id, "run.started", {"status": "running"}, now())
            return True

    def context(self, user_id, run_id):
        with self.database.connect() as connection:
            connection.execute("BEGIN")
            run = self._row(connection, user_id, run_id)
            persona = connection.execute("SELECT ch.name, cr.system_prompt, cr.allowed_tool_ids FROM conversations c JOIN characters ch ON ch.id = c.character_id JOIN character_revisions cr ON cr.id = c.character_revision_id WHERE c.user_id = ? AND c.id = ?", (user_id, run["conversation_id"])).fetchone()
            # D12: include completed prior turns, not failed/cancelled partial replies.
            rows = connection.execute(
                "SELECT m.* FROM messages m JOIN runs r ON r.id = m.run_id WHERE m.user_id = ? AND m.conversation_id = ? AND m.status = 'completed' AND (r.status = 'completed' OR r.id = ?) ORDER BY m.sequence DESC LIMIT 21",
                (user_id, run["conversation_id"], run_id),
            ).fetchall()
            messages = tuple(ModelMessage(row["role"], "\n".join(b["text"] for b in json.loads(row["blocks"]))) for row in reversed(rows))
            # Current user input + up to ten complete turns; evict pairs, never start with an orphan assistant.
            while len(messages) > 1 and sum(len(m.text) for m in messages) > 48000:
                messages = messages[2:]
            return ModelContext(persona["name"], persona["system_prompt"], messages, tuple(json.loads(persona["allowed_tool_ids"])))

    def update_text(self, user_id, run_id, text):
        with self.database.transaction() as connection:
            row = self._row(connection, user_id, run_id)
            if row["status"] != "running":
                return False
            timestamp = now()
            connection.execute("UPDATE messages SET blocks = ?, status = 'streaming', updated_at = ? WHERE id = ?", (json.dumps([{"type": "text", "text": text}], ensure_ascii=False), timestamp, row["output_message_id"]))
            message = message_from(connection.execute("SELECT * FROM messages WHERE id = ?", (row["output_message_id"],)).fetchone())
            self._event(connection, run_id, "message.updated", {"message": asdict(message)}, timestamp)
            return True

    def _finish(self, connection, row, status, error=None):
        if row["status"] in TERMINAL:
            return self._snapshot(connection, row)
        if row["status"] == "cancel_requested" and status == "completed":
            status = "cancelled"
        timestamp = now()
        connection.execute("UPDATE runs SET status = ?, error_code = ?, error_message = ? WHERE id = ?", (status, error["code"] if error else None, error["message"] if error else None, row["id"]))
        connection.execute("UPDATE messages SET status = ?, updated_at = ? WHERE id = ?", (status, timestamp, row["output_message_id"]))
        message = message_from(connection.execute("SELECT * FROM messages WHERE id = ?", (row["output_message_id"],)).fetchone())
        data = {"status": status, "output_message": asdict(message)}
        if error:
            data["error"] = error
        self._event(connection, row["id"], f"run.{status}", data, timestamp)
        connection.execute("UPDATE conversations SET updated_at = ?, version = version + 1 WHERE id = ?", (timestamp, row["conversation_id"]))
        return self._snapshot(connection, self._row(connection, row["user_id"], row["id"]))

    def finish(self, user_id, run_id, status, error=None):
        if status not in TERMINAL:
            raise ValueError("finish requires a terminal status")
        with self.database.transaction() as connection:
            return self._finish(connection, self._row(connection, user_id, run_id), status, error)

    def cancel(self, user_id, run_id):
        with self.database.transaction() as connection:
            row = self._row(connection, user_id, run_id)
            if row["status"] == "queued":
                return self._finish(connection, row, "cancelled")
            if row["status"] == "running":
                connection.execute("UPDATE runs SET status = 'cancel_requested' WHERE id = ?", (run_id,))
                self._event(connection, run_id, "run.cancel_requested", {"status": "cancel_requested"}, now())
            return self._snapshot(connection, self._row(connection, user_id, run_id))

    def events(self, user_id, run_id, after, limit=100):
        with self.database.connect() as connection:
            connection.execute("BEGIN")
            row = self._row(connection, user_id, run_id)
            if after < 0 or after > row["last_event_sequence"]:
                raise AppError(400, "INVALID_EVENT_CURSOR", "事件序号不在当前任务范围内。")
            events = [{"run_id": r["run_id"], "sequence": r["sequence"], "type": r["type"], "data": json.loads(r["data"]), "occurred_at": r["occurred_at"]} for r in connection.execute("SELECT * FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence LIMIT ?", (run_id, after, limit))]
            return events, row["status"], row["last_event_sequence"]

    def recover(self):
        with self.database.transaction() as connection:
            rows = connection.execute("SELECT * FROM runs WHERE status IN ('queued', 'running', 'cancel_requested')").fetchall()
            for row in rows:
                self._finish(connection, row, "failed", {"code": "RUN_INTERRUPTED", "message": "服务已重启或停止，任务未完成。"})
