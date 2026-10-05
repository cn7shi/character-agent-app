import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from uuid import uuid4

from fastapi.testclient import TestClient

from character_app.adapters.storage.chat import SqliteChatRepository
from character_app.adapters.storage.sqlite import Database
from character_app.config import BACKEND_ROOT, Settings
from character_app.main import create_app

CHARACTER = "11111111-1111-4111-8111-111111111111"


class ControlledModel:
    name = "test-model"
    is_simulated = True

    def __init__(self):
        self.mode = "success"
        self.contexts = []

    async def stream(self, context):
        self.contexts.append(context)
        yield "first"
        if self.mode == "wait":
            await asyncio.sleep(60)
        if self.mode == "error":
            raise RuntimeError("PRIVATE_PROVIDER_DETAIL")
        yield " second"


class ChatTests(unittest.TestCase):
    def setUp(self):
        runtime = (BACKEND_ROOT / ".runtime").resolve()
        runtime.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix="chat-test-", dir=runtime)
        self.directory = Path(temp.name).resolve()
        assert self.directory.is_relative_to(runtime)
        self.addCleanup(temp.cleanup)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.model = ControlledModel()
        self.settings = Settings(self.directory, True)
        self.client = self.stack.enter_context(TestClient(create_app(self.settings, model=self.model), client=("127.0.0.1", 50000)))
        self.users = json.loads((self.directory / "development-users.json").read_text(encoding="utf-8"))
        self.auth = [{"Authorization": "Bearer " + u["token"]} for u in self.users]
        self.db = Database(self.directory / "character.sqlite3")
        self.repo = SqliteChatRepository(self.db)
        self.conversation = self.new_conversation()

    def new_conversation(self, user=0):
        response = self.client.post("/v1/conversations", json={"character_id": CHARACTER}, headers={**self.auth[user], "Idempotency-Key": str(uuid4())})
        self.assertEqual(response.status_code, 201)
        return response.json()["id"]

    def send(self, text="你好", *, key=None, client_id=None, conversation=None, user=0):
        return self.client.post(f"/v1/conversations/{conversation or self.conversation}/runs",
                                json={"text": text, "client_message_id": client_id or str(uuid4())},
                                headers={**self.auth[user], "Idempotency-Key": key or str(uuid4())})

    def wait_for(self, run_id, statuses=("completed", "failed", "cancelled")):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            response = self.client.get(f"/v1/runs/{run_id}", headers=self.auth[0])
            self.assertEqual(response.status_code, 200)
            run = response.json()
            if run["status"] in statuses:
                return run
            time.sleep(.01)
        self.fail("Task did not reach expected state")

    def event_list(self, run_id, after=None):
        headers = self.auth[0] | ({"Last-Event-ID": str(after)} if after is not None else {})
        response = self.client.get(f"/v1/runs/{run_id}/events", headers=headers)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]

    def test_persisted_stream_history_and_replay(self):
        response = self.send()
        self.assertEqual(response.status_code, 202)
        run = self.wait_for(response.json()["id"])
        self.assertEqual(run["status"], "completed")
        self.assertTrue(run["is_simulated"])
        self.assertEqual(run["output_message"]["blocks"], [{"type": "text", "text": "first second"}])
        events = self.event_list(run["id"])
        self.assertEqual([e["sequence"] for e in events], list(range(1, run["last_event_sequence"] + 1)))
        self.assertEqual([e["type"] for e in events], ["run.accepted", "run.started", "message.updated", "message.updated", "run.completed"])
        self.assertEqual(events[-1]["data"]["output_message"], run["output_message"])
        self.assertEqual(self.event_list(run["id"], 3), events[3:])
        self.assertEqual(self.event_list(run["id"], len(events)), [])
        page = self.client.get(f"/v1/conversations/{self.conversation}/messages?limit=1", headers=self.auth[0]).json()
        self.assertEqual(page["items"][0]["id"], run["output_message"]["id"])
        older = self.client.get(f"/v1/conversations/{self.conversation}/messages", headers=self.auth[0], params={"cursor": page["next_cursor"]}).json()
        self.assertEqual(older["items"][0]["id"], run["input_message"]["id"])
        self.assertIsNone(older["next_cursor"])
        other = self.new_conversation()
        self.assertEqual(self.client.get(f"/v1/conversations/{other}/messages", headers=self.auth[0], params={"cursor": page["next_cursor"]}).status_code, 422)

    def test_concurrent_retries_and_conflicts(self):
        self.model.mode = "wait"
        key, client_id = str(uuid4()), str(uuid4())
        with ThreadPoolExecutor(max_workers=6) as pool:
            responses = list(pool.map(lambda _: self.send(key=key, client_id=client_id), range(6)))
        self.assertEqual(sorted(r.status_code for r in responses), [200] * 5 + [202])
        self.assertEqual(len({r.json()["id"] for r in responses}), 1)
        run_id = responses[0].json()["id"]
        self.wait_for(run_id, ("running",))
        self.assertEqual(self.send("另一个", key=key, client_id=client_id).json()["error"]["code"], "IDEMPOTENCY_CONFLICT")
        self.assertEqual(self.send(client_id=client_id).json()["error"]["code"], "CLIENT_MESSAGE_CONFLICT")
        self.assertEqual(self.send().json()["error"]["code"], "CONVERSATION_BUSY")
        with self.db.connect() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM runs").fetchone()[0], 1)
            self.assertEqual(connection.execute("SELECT count(*) FROM messages").fetchone()[0], 2)
        self.assertEqual(len(self.model.contexts), 1)

    def test_cross_user_access_and_validation(self):
        run_id = self.send().json()["id"]
        for path in [f"/v1/runs/{run_id}", f"/v1/runs/{run_id}/events", f"/v1/conversations/{self.conversation}/messages"]:
            self.assertEqual(self.client.get(path, headers=self.auth[1]).status_code, 404)
            self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post(f"/v1/runs/{run_id}/cancel", headers=self.auth[1]).status_code, 404)
        self.assertEqual(self.send(user=1).status_code, 404)
        for text in [" ", "x" * 8001, 42]:
            self.assertEqual(self.send(text).status_code, 422)
        for after in ["bad", "-1", "999999999", "1.0", ""]:
            self.assertEqual(self.client.get(f"/v1/runs/{run_id}/events", headers=self.auth[0] | {"Last-Event-ID": after}).status_code, 400)

    def test_cancellation_preserves_partial_reply_and_terminal_state(self):
        self.model.mode = "wait"
        run_id = self.send().json()["id"]
        deadline = time.monotonic() + 5
        while not self.repo.get_run(self.users[0]["id"], run_id).output_message.blocks:
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.01)
        response = self.client.post(f"/v1/runs/{run_id}/cancel", headers=self.auth[0])
        self.assertEqual(response.status_code, 200)
        run = self.wait_for(run_id)
        self.assertEqual(run["status"], "cancelled")
        self.assertEqual(run["output_message"]["blocks"][0]["text"], "first")
        self.assertEqual(self.client.post(f"/v1/runs/{run_id}/cancel", headers=self.auth[0]).json(), run)
        self.assertEqual(self.event_list(run_id)[-1]["type"], "run.cancelled")
        self.model.mode = "success"
        next_run = self.wait_for(self.send("下一条").json()["id"])
        self.assertEqual(next_run["status"], "completed")
        self.assertEqual(self.client.post(f"/v1/runs/{next_run['id']}/cancel", headers=self.auth[0]).json(), next_run)
        self.assertEqual([m.text for m in self.model.contexts[-1].messages], ["下一条"])

    def test_provider_errors_are_safe_and_context_is_scoped(self):
        first = self.wait_for(self.send("第一条").json()["id"])
        self.wait_for(self.send("第二条").json()["id"])
        self.assertEqual([m.text for m in self.model.contexts[-1].messages], ["第一条", "first second", "第二条"])
        self.assertTrue(self.model.contexts[-1].system_prompt)
        other = self.new_conversation()
        self.model.mode = "error"
        failed = self.wait_for(self.send("独立会话", conversation=other).json()["id"])
        self.assertEqual(failed["status"], "failed")
        self.assertEqual(failed["error"]["code"], "MODEL_ERROR")
        self.assertNotIn("PRIVATE_PROVIDER_DETAIL", json.dumps(failed))
        self.assertEqual([m.text for m in self.model.contexts[-1].messages], ["独立会话"])
        self.assertEqual(first["status"], "completed")

    def test_timeout_and_shutdown_do_not_leave_running_tasks(self):
        self.stack.close()
        self.model.mode = "wait"
        self.client = self.stack.enter_context(TestClient(create_app(self.settings, model=self.model, run_timeout=.15), client=("127.0.0.1", 1)))
        timed_out = self.wait_for(self.send().json()["id"])
        self.assertEqual(timed_out["error"]["code"], "MODEL_TIMEOUT")
        self.assertEqual(timed_out["output_message"]["status"], "failed")
        run_id = self.send().json()["id"]
        self.wait_for(run_id, ("running",))
        self.stack.close()
        run = self.repo.get_run(self.users[0]["id"], run_id)
        self.assertEqual(run.status, "failed")
        self.assertEqual(run.error["code"], "RUN_INTERRUPTED")

    def test_run_transaction_rolls_back_all_related_rows(self):
        with self.db.connect() as connection:
            connection.execute("CREATE TRIGGER block_event BEFORE INSERT ON run_events BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        with self.assertLogs("character_app.main", level="ERROR"):
            self.assertEqual(self.send().status_code, 500)
        with self.db.connect() as connection:
            for table in ("runs", "messages", "run_events"):
                self.assertEqual(connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT count(*) FROM request_receipts WHERE operation = 'create_run'").fetchone()[0], 0)

    def test_restart_recovery_and_single_executor_lock(self):
        with self.assertRaisesRegex(RuntimeError, "already owns"):
            with TestClient(create_app(self.settings), client=("127.0.0.1", 1)):
                pass
        completed = self.wait_for(self.send().json()["id"])
        self.stack.close()
        user_id = self.users[0]["id"]
        queued = self.repo.create_run(user_id, self.conversation, str(uuid4()), "crash", str(uuid4()), "fingerprint", "test-model", True).run
        self.repo.start(user_id, queued.id)
        self.repo.update_text(user_id, queued.id, "partial before crash")
        self.client = self.stack.enter_context(TestClient(create_app(self.settings, model=self.model), client=("127.0.0.1", 1)))
        recovered = self.wait_for(queued.id)
        self.assertEqual(recovered["error"]["code"], "RUN_INTERRUPTED")
        self.assertEqual(recovered["output_message"]["blocks"][0]["text"], "partial before crash")
        self.assertEqual(self.wait_for(completed["id"]), completed)
        self.assertEqual(self.event_list(queued.id)[-1]["type"], "run.failed")
        self.assertEqual(len(self.model.contexts), 1)

    def test_queued_cancel_and_foreign_key_integrity(self):
        self.stack.close()
        user_id = self.users[0]["id"]
        queued = self.repo.create_run(user_id, self.conversation, str(uuid4()), "queued", str(uuid4()), "f", "test-model", True).run
        cancelled = self.repo.cancel(user_id, queued.id)
        self.assertEqual(cancelled.status, "cancelled")
        self.assertFalse(self.repo.start(user_id, queued.id))
        self.assertEqual(self.repo.cancel(user_id, queued.id), cancelled)
        with self.db.connect() as connection:
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute("UPDATE messages SET user_id = ? WHERE id = ?", (self.users[1]["id"], queued.input_message.id))
            self.assertEqual(connection.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_event_replay_drains_multiple_batches_before_closing(self):
        self.stack.close()
        user_id = self.users[0]["id"]
        run = self.repo.create_run(user_id, self.conversation, str(uuid4()), "many", str(uuid4()), "f", "test-model", True).run
        self.repo.start(user_id, run.id)
        for i in range(105):
            self.repo.update_text(user_id, run.id, str(i))
        completed = self.repo.finish(user_id, run.id, "completed")
        self.client = self.stack.enter_context(TestClient(create_app(self.settings), client=("127.0.0.1", 1)))
        events = self.event_list(run.id)
        self.assertEqual(len(events), completed.last_event_sequence)
        self.assertEqual(events[-1]["type"], "run.completed")

    def test_existing_catalog_database_upgrades_without_data_loss(self):
        migrations = self.directory / "old-migrations"
        migrations.mkdir()
        first_migration = BACKEND_ROOT / "migrations" / "001_catalog.sql"
        (migrations / first_migration.name).write_bytes(first_migration.read_bytes())
        db = Database(self.directory / "old.sqlite3")
        db.migrate(migrations)
        from character_app.domain.entities import Principal
        from character_app.adapters.storage.sqlite import SqliteCatalogRepository
        principal = Principal(self.users[0]["id"], "Upgrade test")
        db.seed([principal])
        catalog = SqliteCatalogRepository(db)
        conversation = catalog.create_conversation(principal.id, CHARACTER, "old", str(uuid4()), "f").conversation
        db.migrate(BACKEND_ROOT / "migrations")
        self.assertEqual(catalog.conversation(principal.id, conversation.id), conversation)
        self.assertEqual(catalog.messages(principal.id, conversation.id, None, 20), [])


if __name__ == "__main__":
    unittest.main()
