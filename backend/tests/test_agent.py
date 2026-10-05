from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
from pathlib import Path
import tempfile
import time
import unittest
from uuid import uuid4

from fastapi.testclient import TestClient

from character_app.adapters.models.mock import FIXED_REPLIES
from character_app.adapters.storage.chat import SqliteChatRepository
from character_app.adapters.storage.sqlite import Database
from character_app.adapters.storage.tools import SqliteToolStore
from character_app.adapters.tools.notes import SaveNoteTool
from character_app.application.agent import ToolRegistry
from character_app.config import BACKEND_ROOT, Settings
from character_app.domain.tools import ToolFailure, ToolRequest
from character_app.main import create_app

LIN = "11111111-1111-4111-8111-111111111111"
XING = "77777777-7777-4777-8777-777777777777"


class LoopModel:
    name = "repeated-tool-test"
    is_simulated = True

    def __init__(self):
        self.rounds = 0

    async def stream(self, context):
        self.rounds += 1
        yield ToolRequest("same-call", "notes.save", {"title": "一次", "content": "只能保存一次"})


class AgentTests(unittest.TestCase):
    def setUp(self):
        runtime = (BACKEND_ROOT / ".runtime").resolve()
        runtime.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix="agent-test-", dir=runtime)
        self.directory = Path(temp.name).resolve()
        assert self.directory.is_relative_to(runtime)
        self.addCleanup(temp.cleanup)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.settings = Settings(self.directory, True)
        self.start()
        self.users = json.loads((self.directory / "development-users.json").read_text(encoding="utf-8"))
        self.auth = [{"Authorization": "Bearer " + user["token"]} for user in self.users]
        self.conversation = self.new_conversation()
        self.db = Database(self.directory / "character.sqlite3")
        self.runs = SqliteChatRepository(self.db)
        self.tools = SqliteToolStore(self.db)
        self.registry = ToolRegistry(self.tools, [SaveNoteTool(self.tools)])

    def start(self, model=None):
        self.client = self.stack.enter_context(TestClient(create_app(self.settings, model=model), client=("127.0.0.1", 1)))

    def new_conversation(self, character=LIN, user=0):
        response = self.client.post("/v1/conversations", headers=self.auth[user] | {"Idempotency-Key": str(uuid4())}, json={"character_id": character})
        self.assertEqual(response.status_code, 201)
        return response.json()["id"]

    def send(self, text, *, conversation=None, key=None, client_id=None):
        return self.client.post(f"/v1/conversations/{conversation or self.conversation}/runs", headers=self.auth[0] | {"Idempotency-Key": key or str(uuid4())},
                                json={"client_message_id": client_id or str(uuid4()), "text": text})

    def wait(self, run_id):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            run = self.client.get(f"/v1/runs/{run_id}", headers=self.auth[0]).json()
            if run["status"] in {"completed", "failed", "cancelled"}:
                return run
            time.sleep(.01)
        self.fail("Agent task timed out")

    def manual_run(self):
        run = self.runs.create_run(self.users[0]["id"], self.conversation, str(uuid4()), "tool test", str(uuid4()), "f", "test", True).run
        self.runs.start(self.users[0]["id"], run.id)
        return run

    def test_two_fixed_replies_need_no_ai_or_tools(self):
        for message, expected in zip(("你好", "今天有点累"), FIXED_REPLIES):
            run = self.wait(self.send(message).json()["id"])
            self.assertEqual(run["status"], "completed")
            self.assertTrue(run["is_simulated"])
            self.assertEqual(run["output_message"]["blocks"][0]["text"], expected)
            self.assertEqual(self.client.get(f"/v1/runs/{run['id']}/tool-calls", headers=self.auth[0]).json()["items"], [])
        self.assertEqual(self.client.get("/v1/notes", headers=self.auth[0]).json()["items"], [])

    def test_mock_agent_saves_real_note_and_retries_do_not_duplicate(self):
        key, client_id = str(uuid4()), str(uuid4())
        with ThreadPoolExecutor(max_workers=5) as pool:
            responses = list(pool.map(lambda _: self.send("记笔记：周末安排；买咖啡；看电影", key=key, client_id=client_id), range(5)))
        self.assertEqual(sorted(r.status_code for r in responses), [200] * 4 + [202])
        run = self.wait(responses[0].json()["id"])
        self.assertEqual(run["status"], "completed")
        notes = self.client.get("/v1/notes", headers=self.auth[0]).json()["items"]
        self.assertEqual(len(notes), 1)
        note = notes[0]
        self.assertEqual(note["title"], "周末安排")
        self.assertEqual(note["content"], "- 周末安排\n- 买咖啡\n- 看电影")
        calls = self.client.get(f"/v1/runs/{run['id']}/tool-calls", headers=self.auth[0]).json()["items"]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["result"]["note_id"], note["id"])
        self.assertEqual(note["source_tool_call_id"], calls[0]["id"])
        events = self.client.get(f"/v1/runs/{run['id']}/events", headers=self.auth[0]).text
        self.assertLess(events.index("tool.started"), events.index("tool.completed"))
        self.assertLess(events.index("tool.completed"), events.index("message.updated"))
        self.stack.close()
        self.start()
        self.assertEqual(self.client.get(f"/v1/notes/{note['id']}", headers=self.auth[0]).json(), note)
        self.assertEqual(self.send("记笔记：周末安排；买咖啡；看电影", key=key, client_id=client_id).status_code, 200)
        self.assertEqual(len(self.client.get("/v1/notes", headers=self.auth[0]).json()["items"]), 1)

    def test_notes_and_tool_records_are_user_scoped(self):
        run = self.wait(self.send("记笔记：我的私人笔记").json()["id"])
        note = self.client.get("/v1/notes", headers=self.auth[0]).json()["items"][0]
        for path in [f"/v1/notes/{note['id']}", f"/v1/runs/{run['id']}/tool-calls", f"/v1/notes?conversation_id={self.conversation}"]:
            self.assertEqual(self.client.get(path, headers=self.auth[1]).status_code, 404)
            self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.get("/v1/notes", headers=self.auth[1]).json()["items"], [])

    def test_permissions_and_old_conversation_revisions_stay_fixed(self):
        restricted = self.new_conversation(XING)
        denied = self.wait(self.send("记笔记：不应该保存", conversation=restricted).json()["id"])
        self.assertEqual(denied["error"]["code"], "TOOL_NOT_ALLOWED")
        with self.db.connect() as connection:
            connection.execute("UPDATE conversations SET character_revision_id = ? WHERE id = ?", (LIN, self.conversation))
        self.stack.close()
        self.start()  # seeding must not silently grant new capabilities to old conversations
        old = self.wait(self.send("记笔记：旧人设").json()["id"])
        self.assertEqual(old["error"]["code"], "TOOL_NOT_ALLOWED")
        new = self.new_conversation()
        self.assertEqual(self.wait(self.send("记笔记：新会话", conversation=new).json()["id"])["status"], "completed")
        self.assertEqual(len(self.client.get("/v1/notes", headers=self.auth[0]).json()["items"]), 1)

    def test_argument_validation_cannot_override_identity(self):
        invalid = self.wait(self.send("记笔记： ").json()["id"])
        self.assertEqual(invalid["error"]["code"], "TOOL_INVALID_ARGUMENTS")
        self.stack.close()
        run = self.manual_run()
        request = ToolRequest("forged-user", "notes.save", {"title": "t", "content": "c", "user_id": self.users[1]["id"]})
        call = self.registry.execute(self.users[0]["id"], run.id, request)
        self.assertEqual(call.error["code"], "TOOL_INVALID_ARGUMENTS")
        self.assertEqual(call.arguments, {})
        self.assertEqual(self.tools.notes(self.users[0]["id"], None, None, 20), [])

    def test_same_tool_call_is_atomic_idempotent_and_conflicts_on_different_content(self):
        self.stack.close()
        run = self.manual_run()
        user_id = self.users[0]["id"]
        request = ToolRequest("one", "notes.save", {"title": "固定", "content": "只保存一次"})
        with ThreadPoolExecutor(max_workers=6) as pool:
            calls = list(pool.map(lambda _: self.registry.execute(user_id, run.id, request), range(6)))
        self.assertEqual(len({c.id for c in calls}), 1)
        self.assertEqual(len(self.tools.notes(user_id, None, None, 20)), 1)
        with self.assertRaises(ToolFailure) as raised:
            self.registry.execute(user_id, run.id, ToolRequest("one", "notes.save", {"title": "changed", "content": "different"}))
        self.assertEqual(raised.exception.code, "TOOL_CALL_CONFLICT")
        self.assertEqual(len(self.runs.events(user_id, run.id, 0)[0]), 4)

    def test_tool_failure_rolls_back_note_and_records_failed_receipt(self):
        self.stack.close()
        run = self.manual_run()
        user_id = self.users[0]["id"]
        request = ToolRequest("failing", "notes.save", {"title": "test", "content": "test"})
        with self.db.connect() as connection:
            connection.execute("CREATE TRIGGER reject_note BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT, 'PRIVATE FAILURE'); END")
        first = self.registry.execute(user_id, run.id, request)
        self.assertEqual(first.error["code"], "TOOL_EXECUTION_FAILED")
        self.assertEqual(self.registry.execute(user_id, run.id, request), first)
        self.assertEqual(self.tools.notes(user_id, None, None, 20), [])
        events = self.runs.events(user_id, run.id, 0)[0]
        self.assertEqual([e["type"] for e in events], ["run.accepted", "run.started", "tool.failed"])
        self.assertNotIn("PRIVATE FAILURE", json.dumps(events))

    def test_cancel_before_tool_blocks_write_and_cancel_after_keeps_committed_note(self):
        self.stack.close()
        user_id = self.users[0]["id"]
        request = ToolRequest("cancel-test", "notes.save", {"title": "test", "content": "test"})
        run = self.manual_run()
        self.runs.cancel(user_id, run.id)
        self.assertIsNone(self.registry.execute(user_id, run.id, request))
        self.assertEqual(self.tools.notes(user_id, None, None, 20), [])
        self.runs.finish(user_id, run.id, "cancelled")
        run = self.manual_run()
        call = self.registry.execute(user_id, run.id, request)
        self.runs.cancel(user_id, run.id)
        self.runs.finish(user_id, run.id, "cancelled")
        self.assertEqual(self.tools.note(user_id, call.result["note_id"]).source_run_id, run.id)

    def test_crash_after_note_commit_does_not_reexecute_on_restart(self):
        self.stack.close()
        run = self.manual_run()
        user_id = self.users[0]["id"]
        call = self.registry.execute(user_id, run.id, ToolRequest("before-crash", "notes.save", {"title": "已保存", "content": "保存后服务中断"}))
        self.start()
        recovered = self.wait(run.id)
        self.assertEqual(recovered["error"]["code"], "RUN_INTERRUPTED")
        self.assertEqual(len(self.client.get("/v1/notes", headers=self.auth[0]).json()["items"]), 1)
        self.assertEqual(self.client.get(f"/v1/runs/{run.id}/tool-calls", headers=self.auth[0]).json()["items"][0]["id"], call.id)

    def test_repeating_planner_is_bounded_without_duplicate_notes(self):
        self.stack.close()
        model = LoopModel()
        self.start(model)
        run = self.wait(self.send("start").json()["id"])
        self.assertEqual(run["error"]["code"], "AGENT_STEP_LIMIT")
        self.assertEqual(model.rounds, 4)
        self.assertEqual(len(self.client.get("/v1/notes", headers=self.auth[0]).json()["items"]), 1)

    def test_note_pagination_and_cursor_scope(self):
        for text in ["记笔记：第一条", "记笔记：第二条", "记笔记：第三条"]:
            self.assertEqual(self.wait(self.send(text).json()["id"])["status"], "completed")
        first = self.client.get("/v1/notes?limit=2", headers=self.auth[0]).json()
        self.assertEqual(len(first["items"]), 2)
        second = self.client.get("/v1/notes", headers=self.auth[0], params={"cursor": first["next_cursor"]}).json()
        self.assertEqual(len(second["items"]), 1)
        self.assertEqual(len({n["id"] for n in first["items"] + second["items"]}), 3)
        self.assertIsNone(second["next_cursor"])
        self.assertEqual(self.client.get("/v1/notes", headers=self.auth[1], params={"cursor": first["next_cursor"]}).status_code, 422)
        self.assertEqual(self.client.get("/v1/notes", headers=self.auth[0], params={"cursor": first["next_cursor"], "conversation_id": self.conversation}).status_code, 422)
