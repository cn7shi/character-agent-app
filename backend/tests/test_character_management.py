from concurrent.futures import ThreadPoolExecutor
import shutil
import threading
import unittest
from uuid import uuid4

from character_app.adapters.storage.chat import SqliteChatRepository
from character_app.adapters.storage.sqlite import Database
from character_app.config import BACKEND_ROOT
import test_catalog as catalog_tests
from test_catalog import CHARACTER_A


class CharacterManagementTests(unittest.TestCase):
    setUp = catalog_tests.CatalogTests.setUp
    create = catalog_tests.CatalogTests.create

    def save(self, character_id, *, auth=None, **changes):
        body = {"name": "  旅行伙伴 ☕  ", "summary": "一起探索城市", "system_prompt": "你喜欢旅行，回答简洁。",
                "notes_enabled": True, "expected_version": 0, **changes}
        return self.client.put(f"/v1/characters/{character_id}", headers=auth or self.auth_a, json=body)

    def test_private_crud_and_builtin_protection(self):
        identifier = str(uuid4())
        created = self.save(identifier)
        self.assertEqual(created.status_code, 200)
        self.assertEqual(created.json()["name"], "旅行伙伴 ☕")
        mine = self.client.get("/v1/characters", headers=self.auth_a).json()["items"]
        self.assertTrue(next(c for c in mine if c["id"] == identifier)["editable"])
        self.assertTrue(all(not c["editable"] for c in mine if c["id"] != identifier))
        self.assertNotIn("system_prompt", str(mine))
        self.assertNotIn(identifier, self.client.get("/v1/characters", headers=self.auth_b).text)
        for target, auth in [(identifier, self.auth_b), (CHARACTER_A, self.auth_a)]:
            self.assertEqual(self.client.get(f"/v1/characters/{target}/definition", headers=auth).status_code, 404)
            self.assertEqual(self.save(target, auth=auth).status_code, 404)
            self.assertEqual(self.client.delete(f"/v1/characters/{target}", headers=auth).status_code, 404)
        self.assertEqual(self.create(payload={"character_id": identifier}, auth=self.auth_b).status_code, 404)
        self.assertEqual(self.client.get(f"/v1/characters/{identifier}", headers=self.auth_b).status_code, 404)

    def test_exact_retries_do_not_republish_and_stale_changes_conflict(self):
        identifier = str(uuid4())
        first = self.save(identifier).json()
        self.assertEqual(self.save(identifier).json(), first)
        edited = self.save(identifier, expected_version=1, name="新名字").json()
        self.assertEqual(edited["version"], 2)
        self.assertEqual(self.save(identifier, expected_version=1, name="新名字").json(), edited)
        for changes in ({}, {"expected_version": 1, "name": "覆盖别人修改"}):
            self.assertEqual(self.save(identifier, **changes).status_code, 409)
        with Database(self.directory / "character.sqlite3").connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM character_revisions WHERE character_id = ?", (identifier,)).fetchone()[0], 2)

    def test_concurrent_create_retries_publish_one_revision(self):
        identifier = str(uuid4())
        barrier = threading.Barrier(6)
        def submit(_):
            barrier.wait(timeout=5)
            return self.save(identifier)
        with ThreadPoolExecutor(max_workers=6) as pool:
            replies = list(pool.map(submit, range(6)))
        self.assertEqual([r.status_code for r in replies], [200] * 6)
        with Database(self.directory / "character.sqlite3").connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM character_revisions WHERE character_id = ?", (identifier,)).fetchone()[0], 1)

    def test_old_conversations_pin_persona_and_survive_delete(self):
        identifier = str(uuid4())
        self.save(identifier)
        key = str(uuid4())
        old = self.create(key=key, payload={"character_id": identifier}).json()
        self.save(identifier, expected_version=1, name="新名字", system_prompt="新的设定", notes_enabled=False)
        fresh = self.create(payload={"character_id": identifier}).json()
        database = Database(self.directory / "character.sqlite3")
        for conversation, expected in [(old, ("旅行伙伴 ☕", "你喜欢旅行，回答简洁。", ("notes.save",))),
                                        (fresh, ("新名字", "新的设定", ()) )]:
            run = self.client.post(f"/v1/conversations/{conversation['id']}/runs", headers={**self.auth_a, "Idempotency-Key": str(uuid4())},
                                   json={"client_message_id": str(uuid4()), "text": "你好"})
            self.assertEqual(run.status_code, 202)
            context = SqliteChatRepository(database).context(self.users[0]["id"], run.json()["id"])
            self.assertEqual((context.character_name, context.system_prompt, context.allowed_tool_ids), expected)
        for _ in range(2):
            self.assertEqual(self.client.delete(f"/v1/characters/{identifier}", headers=self.auth_a).status_code, 200)
        self.assertNotIn(identifier, self.client.get("/v1/characters", headers=self.auth_a).text)
        self.assertEqual(self.save(identifier).status_code, 404)  # deleted IDs cannot be recreated
        self.assertEqual(self.create(payload={"character_id": identifier}).status_code, 404)
        self.assertEqual(self.create(key=key, payload={"character_id": identifier}).json()["id"], old["id"])
        self.assertEqual(self.client.get(f"/v1/conversations/{old['id']}/messages", headers=self.auth_a).status_code, 200)
        with database.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM conversations WHERE character_id = ?", (identifier,)).fetchone()[0], 2)

    def test_validation_auth_and_rejected_save_leave_no_row(self):
        identifier = str(uuid4())
        for changes in [{"name": "  "}, {"name": "字" * 101}, {"summary": "字" * 501}, {"system_prompt": " "},
                        {"system_prompt": "字" * 8001}, {"expected_version": -1}, {"expected_version": True},
                        {"notes_enabled": "yes"}, {"owner_id": self.users[1]["id"]}, {"allowed_tool_ids": ["arbitrary"]}]:
            self.assertEqual(self.save(identifier, **changes).status_code, 422)
        self.assertEqual(self.client.put(f"/v1/characters/{identifier}", json={}).status_code, 401)
        self.assertEqual(self.client.delete(f"/v1/characters/{identifier}").status_code, 401)
        self.assertEqual(self.client.get(f"/v1/characters/{identifier}/definition").status_code, 401)
        self.assertEqual(self.client.get(f"/v1/characters/{identifier}", headers=self.auth_a).status_code, 404)

    def test_revision_failure_rolls_back_metadata(self):
        identifier = str(uuid4())
        self.save(identifier)
        database = Database(self.directory / "character.sqlite3")
        with database.connect() as db:
            db.execute("CREATE TRIGGER reject_revision BEFORE INSERT ON character_revisions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        with self.assertLogs("character_app.main", level="ERROR"):
            self.assertEqual(self.save(identifier, expected_version=1, name="失败改名").status_code, 500)
        saved = self.client.get(f"/v1/characters/{identifier}/definition", headers=self.auth_a).json()
        self.assertEqual((saved["name"], saved["version"]), ("旅行伙伴 ☕", 1))

    def test_additive_migration_preserves_old_persona_and_conversation(self):
        migrations = self.directory / "old-migrations"
        migrations.mkdir()
        for path in (BACKEND_ROOT / "migrations").glob("00[123]_*.sql"):
            shutil.copyfile(path, migrations / path.name)
        database = Database(self.directory / "old.sqlite3")
        database.migrate(migrations)
        with database.transaction() as db:
            db.execute("INSERT INTO users VALUES ('owner', '名字', 'now')")
            db.execute("INSERT INTO characters VALUES ('role', '旧角色', '简介', NULL, NULL, 1, 'now', 'now')")
            db.execute("INSERT INTO character_revisions VALUES ('rev', 'role', 1, '旧人设', '[]')")
            db.execute("INSERT INTO conversations VALUES ('chat', 'owner', 'role', 'rev', '旧聊天', 1, 'now', 'now')")
        database.migrate(BACKEND_ROOT / "migrations")
        with database.connect() as db:
            self.assertEqual(db.execute("SELECT name, system_prompt FROM character_revisions").fetchone()[:], ("旧角色", "旧人设"))
            self.assertEqual(db.execute("SELECT title FROM conversations").fetchone()[0], "旧聊天")
            self.assertIsNone(db.execute("SELECT owner_id FROM characters").fetchone()[0])
