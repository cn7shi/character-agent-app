from concurrent.futures import ThreadPoolExecutor
import shutil
import threading
import unittest

from fastapi.testclient import TestClient
from character_app.adapters.storage.sqlite import Database
from character_app.config import BACKEND_ROOT
from character_app.main import create_app
import test_catalog as catalog_tests


class ProfileTests(unittest.TestCase):
    setUp = catalog_tests.CatalogTests.setUp
    create = catalog_tests.CatalogTests.create

    def save(self, auth=None, **changes):
        return self.client.put("/v1/me/profile", headers=auth or self.auth_a,
                               json={"nickname": "  夜航 ☕ ", "bio": "第一行\n第二行", "expected_version": 0, **changes})

    def get(self, auth=None):
        return self.client.get("/v1/me/profile", headers=auth or self.auth_a).json()

    def reset(self, version, auth=None):
        return self.client.post("/v1/me/profile/reset", headers=auth or self.auth_a, json={"expected_version": version})

    def test_profile_is_private_and_reset_preserves_identity_and_conversations(self):
        empty = {"nickname": "", "bio": "", "version": 0}
        self.assertEqual(self.get(), empty)
        conversation = self.create().json()
        roles = self.client.get("/v1/characters", headers=self.auth_a).json()
        self.assertEqual(self.save().json(), {"nickname": "夜航 ☕", "bio": "第一行\n第二行", "version": 1})
        self.assertEqual(self.get(self.auth_b), empty)
        self.save(self.auth_b, nickname="另一位用户")
        self.assertEqual(self.reset(1).json(), {**empty, "version": 2})
        self.assertEqual(self.get(self.auth_b)["nickname"], "另一位用户")
        self.assertEqual(self.client.get("/v1/characters", headers=self.auth_a).json(), roles)
        self.assertEqual(self.client.get("/v1/conversations", headers=self.auth_a).json()["items"], [conversation])
        with Database(self.directory / "character.sqlite3").connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM users").fetchone()[0], 2)
            self.assertNotEqual(db.execute("SELECT display_name FROM users WHERE id = ?", (self.users[0]["id"],)).fetchone()[0], "夜航 ☕")

    def test_exact_retries_and_stale_resets_cannot_overwrite_newer_changes(self):
        first = self.save().json()
        self.assertEqual(self.save().json(), first)
        self.assertEqual(self.save(nickname="竞争修改").status_code, 409)
        reset = self.reset(1).json()
        self.assertEqual(self.reset(1).json(), reset)
        self.assertEqual(self.save(expected_version=2, nickname="新名字").json()["version"], 3)
        self.assertEqual(self.reset(1).status_code, 409)
        self.assertEqual(self.save().status_code, 409)
        self.assertEqual(self.get()["nickname"], "新名字")

    def test_validation_and_auth_never_create_a_profile(self):
        for changes in [{"nickname": " "}, {"nickname": "字" * 51}, {"bio": "字" * 501},
                        {"nickname": None}, {"expected_version": -1}, {"expected_version": True},
                        {"expected_version": "0"}, {"user_id": self.users[1]["id"]}]:
            self.assertEqual(self.save(**changes).status_code, 422)
        for method, path in [("GET", "/v1/me/profile"), ("PUT", "/v1/me/profile"), ("POST", "/v1/me/profile/reset")]:
            self.assertEqual(self.client.request(method, path).status_code, 401)
        self.assertEqual(self.client.post("/v1/me/profile/reset", headers=self.auth_a,
            json={"expected_version": 0, "user_id": self.users[1]["id"]}).status_code, 422)
        self.get()
        with Database(self.directory / "character.sqlite3").connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM user_profiles").fetchone()[0], 0)

    def test_concurrent_replay_increments_version_once(self):
        barrier = threading.Barrier(6)
        def submit(_):
            barrier.wait(timeout=5)
            return self.save()
        with ThreadPoolExecutor(max_workers=6) as pool:
            replies = list(pool.map(submit, range(6)))
        self.assertEqual([r.status_code for r in replies], [200] * 6)
        self.assertEqual(self.get()["version"], 1)

    def test_failed_reset_and_service_restart_preserve_profile(self):
        saved = self.save().json()
        database = Database(self.directory / "character.sqlite3")
        with database.connect() as db:
            db.execute("CREATE TRIGGER reject_profile BEFORE UPDATE ON user_profiles BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        with self.assertLogs("character_app.main", level="ERROR"):
            self.assertEqual(self.reset(1).status_code, 500)
        self.assertEqual(self.get(), saved)
        self.contexts.close()
        with TestClient(create_app(self.settings), client=("127.0.0.1", 50000)) as client:
            self.assertEqual(client.get("/v1/me/profile", headers=self.auth_a).json(), saved)

    def test_additive_migration_preserves_existing_users(self):
        migrations = self.directory / "old-migrations"
        migrations.mkdir()
        for path in (BACKEND_ROOT / "migrations").glob("00[1234]_*.sql"):
            shutil.copyfile(path, migrations / path.name)
        database = Database(self.directory / "old.sqlite3")
        database.migrate(migrations)
        with database.transaction() as db:
            db.execute("INSERT INTO users VALUES ('owner', '原身份', 'now')")
        database.migrate(BACKEND_ROOT / "migrations")
        with database.connect() as db:
            self.assertEqual(tuple(db.execute("SELECT * FROM users").fetchone()), ("owner", "原身份", "now"))
            self.assertEqual(db.execute("SELECT count(*) FROM user_profiles").fetchone()[0], 0)
