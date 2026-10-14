from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from uuid import uuid4

from fastapi.testclient import TestClient

from character_app.adapters.storage.sqlite import Database
from character_app.config import BACKEND_ROOT, Settings
from character_app.main import create_app

CHARACTER_A = "11111111-1111-4111-8111-111111111111"
CHARACTER_B = "77777777-7777-4777-8777-777777777777"


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.runtime = (BACKEND_ROOT / ".runtime").resolve()
        self.runtime.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="test-", dir=self.runtime)
        self.directory = Path(self.temporary.name).resolve()
        self.assertTrue(self.directory.is_relative_to(self.runtime))
        self.addCleanup(self.temporary.cleanup)
        self.settings = Settings(self.directory, dev_auth=True)
        self.contexts = ExitStack()
        self.addCleanup(self.contexts.close)
        self.client = self.contexts.enter_context(TestClient(create_app(self.settings), client=("127.0.0.1", 50000)))
        self.users = json.loads((self.directory / "development-users.json").read_text(encoding="utf-8"))
        self.auth_a = {"Authorization": "Bearer " + self.users[0]["token"]}
        self.auth_b = {"Authorization": "Bearer " + self.users[1]["token"]}

    def create(self, key=None, payload=None, auth=None, client=None):
        return (client or self.client).post(
            "/v1/conversations",
            json=payload if payload is not None else {"character_id": CHARACTER_A},
            headers={**(auth or self.auth_a), "Idempotency-Key": key or str(uuid4())},
        )

    def test_health_and_authentication(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})
        for path in ["/v1/characters", f"/v1/characters/{CHARACTER_A}", "/v1/conversations", f"/v1/conversations/{uuid4()}/messages"]:
            response = self.client.get(path)
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.json()["error"]["code"], "AUTH_REQUIRED")
            self.assertEqual(response.headers["www-authenticate"], "Bearer")
        response = self.client.post("/v1/conversations", json={"character_id": CHARACTER_A}, headers={"Idempotency-Key": str(uuid4())})
        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.client.get("/v1/characters", headers={"Authorization": "Bearer invalid"}).status_code, 401)

    def test_default_mode_rejects_development_credentials(self):
        with TestClient(create_app(Settings(self.directory, False)), client=("127.0.0.1", 50001)) as client:
            self.assertEqual(client.get("/health").status_code, 200)
            self.assertEqual(client.get("/v1/characters", headers=self.auth_a).status_code, 401)
            self.assertEqual(client.get("/docs").status_code, 404)
            self.assertEqual(client.get("/openapi.json").status_code, 404)

    def test_development_mode_requires_loopback(self):
        with TestClient(create_app(Settings(self.directory / "nonlocal", True)), client=("203.0.113.9", 50002)) as client:
            response = client.get("/v1/characters", headers={**self.auth_a, "X-Forwarded-For": "127.0.0.1"})
            self.assertEqual(response.status_code, 403)
            self.assertEqual(response.json()["error"]["code"], "DEV_AUTH_LOCAL_ONLY")

    def test_character_pagination_and_public_fields(self):
        first = self.client.get("/v1/characters?limit=1", headers=self.auth_a).json()
        second = self.client.get("/v1/characters", params={"limit": 1, "cursor": first["next_cursor"]}, headers=self.auth_a).json()
        self.assertEqual({first["items"][0]["id"], second["items"][0]["id"]}, {CHARACTER_A, CHARACTER_B})
        self.assertIsNone(second["next_cursor"])
        public_fields = {"id", "name", "summary", "avatar_asset_id", "background_asset_id", "version", "editable"}
        self.assertEqual(set(first["items"][0]), public_fields)
        detail = self.client.get(f"/v1/characters/{CHARACTER_A}", headers=self.auth_a)
        self.assertEqual(detail.status_code, 200)
        self.assertNotIn("system_prompt", detail.text)
        self.assertEqual(self.client.get(f"/v1/characters/{uuid4()}", headers=self.auth_a).status_code, 404)

    def test_titles_are_normalized_and_validated(self):
        response = self.create(payload={"character_id": CHARACTER_A, "title": "  我的会话  "})
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["title"], "我的会话")
        self.assertEqual(self.create().json()["title"], "开发角色 · 林舟")
        self.assertEqual(self.create(payload={"character_id": CHARACTER_A, "title": None}).json()["title"], "开发角色 · 林舟")
        for title in ["", " \n ", "字" * 101, 12, ["wrong"]]:
            response = self.create(payload={"character_id": CHARACTER_A, "title": title})
            self.assertEqual(response.status_code, 422)
            self.assertEqual(response.json()["error"]["code"], "VALIDATION_ERROR")
        response = self.create(payload={"character_id": CHARACTER_A, "user_id": self.users[1]["id"]})
        self.assertEqual(response.status_code, 422)
        self.assertEqual(self.create(key="not-a-uuid").status_code, 422)
        self.assertEqual(self.create(payload={"character_id": "bad"}).status_code, 422)
        self.assertEqual(self.client.post("/v1/conversations", headers=self.auth_a, json={"character_id": CHARACTER_A}).status_code, 422)

    def test_idempotent_retry_and_conflict(self):
        key = str(uuid4())
        first = self.create(key, {"character_id": CHARACTER_A, "title": "  旅行  "})
        retry = self.create(key, {"character_id": CHARACTER_A, "title": "旅行"})
        self.assertEqual(first.status_code, 201)
        self.assertEqual(retry.status_code, 200)
        self.assertEqual(first.json(), retry.json())
        for payload in [{"character_id": CHARACTER_A, "title": "不同"}, {"character_id": CHARACTER_B, "title": "旅行"}]:
            response = self.create(key, payload)
            self.assertEqual(response.status_code, 409)
            self.assertEqual(response.json()["error"]["code"], "IDEMPOTENCY_CONFLICT")

    def test_users_and_same_character_conversations_are_isolated(self):
        key = str(uuid4())
        first = self.create(key).json()
        second = self.create().json()
        other_user = self.create(key, auth=self.auth_b).json()
        self.assertEqual(len({first["id"], second["id"], other_user["id"]}), 3)
        mine = self.client.get("/v1/conversations", headers=self.auth_a).json()["items"]
        theirs = self.client.get("/v1/conversations", headers=self.auth_b).json()["items"]
        self.assertEqual({x["id"] for x in mine}, {first["id"], second["id"]})
        self.assertEqual([x["id"] for x in theirs], [other_user["id"]])
        empty = self.client.get(f"/v1/conversations/{first['id']}/messages", headers=self.auth_a)
        self.assertEqual(empty.status_code, 200)
        self.assertEqual(empty.json(), {"items": [], "next_cursor": None})
        forbidden = self.client.get(f"/v1/conversations/{first['id']}/messages", headers=self.auth_b)
        missing = self.client.get(f"/v1/conversations/{uuid4()}/messages", headers=self.auth_b)
        self.assertEqual(forbidden.status_code, 404)
        self.assertEqual(forbidden.json()["error"]["code"], missing.json()["error"]["code"])
        self.assertEqual(forbidden.json()["error"]["message"], missing.json()["error"]["message"])

    def test_conversation_pagination_and_cursor_scope(self):
        expected = {self.create().json()["id"] for _ in range(5)}
        cursor = None
        seen = []
        first_cursor = None
        while True:
            params = {"limit": 2}
            if cursor:
                params["cursor"] = cursor
            response = self.client.get("/v1/conversations", params=params, headers=self.auth_a)
            self.assertEqual(response.status_code, 200)
            page = response.json()
            seen.extend(item["id"] for item in page["items"])
            cursor = page["next_cursor"]
            first_cursor = first_cursor or cursor
            if cursor is None:
                break
        self.assertEqual(len(seen), 5)
        self.assertEqual(set(seen), expected)
        self.assertEqual(self.client.get("/v1/conversations", params={"cursor": first_cursor}, headers=self.auth_b).status_code, 422)
        self.assertEqual(self.client.get("/v1/characters", params={"cursor": first_cursor}, headers=self.auth_a).status_code, 422)
        for params in [{"cursor": "bad"}, {"cursor": ""}, {"limit": 0}, {"limit": 101}]:
            self.assertEqual(self.client.get("/v1/conversations", params=params, headers=self.auth_a).status_code, 422)

    def test_concurrent_retries_create_exactly_one_conversation(self):
        key = str(uuid4())
        barrier = threading.Barrier(8)
        def submit(_):
            barrier.wait(timeout=5)
            return self.create(key)
        with ThreadPoolExecutor(max_workers=8) as pool:
            responses = list(pool.map(submit, range(8)))
        self.assertEqual(sorted(r.status_code for r in responses), [200] * 7 + [201])
        self.assertEqual(len({r.json()["id"] for r in responses}), 1)
        with Database(self.directory / "character.sqlite3").connect() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM conversations").fetchone()[0], 1)
            self.assertEqual(connection.execute("SELECT count(*) FROM request_receipts").fetchone()[0], 1)

    def test_concurrent_different_payloads_conflict(self):
        key = str(uuid4())
        barrier = threading.Barrier(2)
        def submit(title):
            barrier.wait(timeout=5)
            return self.create(key, {"character_id": CHARACTER_A, "title": title})
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(submit, ["甲", "乙"]))
        self.assertEqual(sorted(r.status_code for r in responses), [201, 409])

    def test_restart_preserves_data_receipts_and_cursors(self):
        key = str(uuid4())
        created = self.create(key).json()
        self.create()
        page = self.client.get("/v1/conversations?limit=1", headers=self.auth_a).json()
        self.contexts.close()
        with TestClient(create_app(self.settings), client=("127.0.0.1", 50003)) as restarted:
            self.assertEqual(len(restarted.get("/v1/conversations", headers=self.auth_a).json()["items"]), 2)
            retry = self.create(key, client=restarted)
            self.assertEqual(retry.status_code, 200)
            self.assertEqual(retry.json(), created)
            next_page = restarted.get("/v1/conversations", params={"cursor": page["next_cursor"], "limit": 1}, headers=self.auth_a)
            self.assertEqual(next_page.status_code, 200)
            self.assertEqual(next_page.json()["items"][0]["id"], created["id"])

    def test_failed_write_rolls_back_conversation_and_receipt(self):
        database = Database(self.directory / "character.sqlite3")
        with database.connect() as connection:
            connection.execute("CREATE TRIGGER block_receipt BEFORE INSERT ON request_receipts BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        key = str(uuid4())
        with self.assertLogs("character_app.main", level="ERROR"):
            response = self.create(key)
        self.assertEqual(response.status_code, 500)
        self.assertNotIn("test failure", response.text)
        with database.connect() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM conversations").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT count(*) FROM request_receipts").fetchone()[0], 0)
            connection.execute("DROP TRIGGER block_receipt")
        self.assertEqual(self.create(key).status_code, 201)

    def test_missing_character_does_not_consume_request_key(self):
        key = str(uuid4())
        self.assertEqual(self.create(key, {"character_id": str(uuid4())}).status_code, 404)
        self.assertEqual(self.create(key).status_code, 201)

    def test_schema_guards_character_revision_relationship(self):
        conversation_id = self.create().json()["id"]
        database = Database(self.directory / "character.sqlite3")
        with database.connect() as connection:
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute("UPDATE conversations SET character_revision_id = ? WHERE id = ?", (CHARACTER_B, conversation_id))

    def test_openapi_matches_implemented_operations_and_errors(self):
        schema = self.client.get("/openapi.json").json()
        operations = {(method.upper(), path) for path, methods in schema['paths'].items() for method in methods if method in {'get', 'post', 'put', 'delete'}}
        self.assertEqual(len(operations), 21)
        self.assertIn(("POST", "/v1/conversations/{conversation_id}/runs"), operations)
        self.assertIn(("POST", "/v1/tts/synthesize"), operations)
        self.assertIn(("GET", "/v1/tts/synthesize"), operations)
        for path, methods in schema["paths"].items():
            if path == "/health":
                continue
            for operation in methods.values():
                self.assertTrue(operation["security"])
                self.assertIn("ErrorResponse", json.dumps(operation["responses"]["422"]))
        response = self.client.get("/v1/characters/not-a-uuid", headers=self.auth_a)
        self.assertEqual(response.json()["error"]["request_id"], response.headers["x-request-id"])

    def test_migration_is_atomic_and_detects_changed_history(self):
        directory = self.directory / "migration-cases"
        directory.mkdir()
        migration = directory / "001.sql"
        migration.write_text("CREATE TABLE probe (id TEXT);\nINVALID SQL;\n", encoding="utf-8")
        database = Database(self.directory / "migration-test.sqlite3")
        with self.assertRaises(sqlite3.OperationalError):
            database.migrate(directory)
        with database.connect() as connection:
            self.assertIsNone(connection.execute("SELECT name FROM sqlite_master WHERE name = 'probe'").fetchone())
        migration.write_text("CREATE TABLE probe (id TEXT);\n", encoding="utf-8")
        database.migrate(directory)
        database.migrate(directory)
        migration.write_text("CREATE TABLE probe (id INTEGER);\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "modified"):
            database.migrate(directory)


if __name__ == "__main__":
    unittest.main()
