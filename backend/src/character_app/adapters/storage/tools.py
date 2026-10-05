from dataclasses import asdict
import hashlib
import json
from uuid import uuid4

from character_app.domain.entities import not_found
from character_app.domain.tools import Note, NoteDraft, ToolCall, ToolFailure, TOOL_ERRORS
from .chat import SqliteChatRepository
from .sqlite import Database, now


def call_from(row):
    return ToolCall(row["id"], row["conversation_id"], row["run_id"], row["call_id"], row["tool_id"],
                    json.loads(row["arguments"]), row["status"], json.loads(row["result"]) if row["result"] else None,
                    {"code": row["error_code"], "message": row["error_message"]} if row["error_code"] else None, row["created_at"])


def note_from(row):
    return Note(**{field: row[field] for field in Note.__dataclass_fields__})


class SqliteToolStore:
    def __init__(self, database: Database):
        self.database = database

    def save_note(self, user_id, run_id, request, draft: NoteDraft):
        return self._apply(user_id, run_id, request, asdict(draft), draft=draft)

    def reject(self, user_id, run_id, request, code):
        # Invalid, untrusted arguments are deliberately not persisted or echoed in public records.
        return self._apply(user_id, run_id, request, {}, error_code=code)

    def _apply(self, user_id, run_id, request, arguments, draft=None, error_code=None):
        try:
            raw_request = json.dumps([request.tool_id, request.arguments], sort_keys=True, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError):
            raise ToolFailure("TOOL_INVALID_ARGUMENTS") from None
        if len(raw_request) > 65536:
            raise ToolFailure("TOOL_INVALID_ARGUMENTS")
        # Keep the original request fingerprint even on failure, so retrying returns the same receipt.
        fingerprint = hashlib.sha256(raw_request.encode()).hexdigest()
        with self.database.transaction() as connection:
            run = SqliteChatRepository._row(connection, user_id, run_id)
            existing = connection.execute("SELECT * FROM tool_calls WHERE user_id = ? AND run_id = ? AND call_id = ?", (user_id, run_id, request.call_id)).fetchone()
            if existing:
                if existing["request_hash"] != fingerprint:
                    raise ToolFailure("TOOL_CALL_CONFLICT")
                return call_from(existing)
            # D16: cancellation and note creation serialize on the same write transaction.
            if run["status"] != "running":
                return None
            allowed = connection.execute("SELECT cr.allowed_tool_ids FROM conversations c JOIN character_revisions cr ON cr.id = c.character_revision_id WHERE c.id = ? AND c.user_id = ?", (run["conversation_id"], user_id)).fetchone()[0]
            if request.tool_id not in json.loads(allowed):
                error_code = "TOOL_NOT_ALLOWED"
            if connection.execute("SELECT count(*) FROM tool_calls WHERE run_id = ?", (run_id,)).fetchone()[0] >= 3:
                raise ToolFailure("AGENT_STEP_LIMIT")
            call_id, timestamp = str(uuid4()), now()
            error = {"code": error_code, "message": TOOL_ERRORS[error_code]} if error_code else None
            result = {"note_id": str(uuid4()), "title": draft.title} if error is None and draft else None
            if result is None and error is None:
                raise ToolFailure("TOOL_EXECUTION_FAILED")
            status = "failed" if error else "completed"
            connection.execute("INSERT INTO tool_calls VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                               (call_id, user_id, run["conversation_id"], run_id, request.call_id, request.tool_id,
                                json.dumps(arguments, ensure_ascii=False), fingerprint, status,
                                json.dumps(result, ensure_ascii=False) if result else None,
                                error["code"] if error else None, error["message"] if error else None, timestamp))
            if result:
                connection.execute("INSERT INTO notes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                   (result["note_id"], user_id, run["conversation_id"], run_id, call_id, draft.title, draft.content, timestamp))
                SqliteChatRepository._event(connection, run_id, "tool.started", {"tool_call_id": call_id, "tool_id": request.tool_id}, timestamp)
            call = call_from(connection.execute("SELECT * FROM tool_calls WHERE id = ?", (call_id,)).fetchone())
            # D16: note + receipt + events commit together; a statement failure creates none of them.
            SqliteChatRepository._event(connection, run_id, f"tool.{status}", {"tool_call": asdict(call)}, timestamp)
            return call

    def calls(self, user_id, run_id):
        with self.database.connect() as connection:
            connection.execute("BEGIN")
            SqliteChatRepository._row(connection, user_id, run_id)
            return [call_from(row) for row in connection.execute("SELECT * FROM tool_calls WHERE user_id = ? AND run_id = ? ORDER BY created_at, id", (user_id, run_id))]

    def notes(self, user_id, conversation_id, before, limit):
        with self.database.connect() as connection:
            if conversation_id is not None and not connection.execute("SELECT 1 FROM conversations WHERE user_id = ? AND id = ?", (user_id, conversation_id)).fetchone():
                raise not_found()
            sql = "SELECT * FROM notes WHERE user_id = ?"
            params = [user_id]
            if conversation_id is not None:
                sql += " AND conversation_id = ?"
                params.append(conversation_id)
            if before:
                sql += " AND (created_at, id) < (?, ?)"
                params.extend(before)
            sql += " ORDER BY created_at DESC, id DESC LIMIT ?"
            return [note_from(row) for row in connection.execute(sql, (*params, limit))]

    def note(self, user_id, note_id):
        with self.database.connect() as connection:
            row = connection.execute("SELECT * FROM notes WHERE user_id = ? AND id = ?", (user_id, note_id)).fetchone()
            if row is None:
                raise not_found()
            return note_from(row)
