from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
from uuid import uuid4

from character_app.domain.entities import Character, CharacterDefinition, Conversation, CreateConversationResult, AppError, not_found
from character_app.domain.ports import Position


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


class Database:
    def __init__(self, path: Path):
        self.path = path

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            yield connection
        finally:
            # D08: SQLite's own context manager commits/rolls back but does not close.
            connection.close()

    @contextmanager
    def transaction(self):
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise

    def migrate(self, directory: Path):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
        with self.transaction() as connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations "
                "(name TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)"
            )
            files = sorted(directory.glob("*.sql"))
            if not files:
                raise RuntimeError("No database migrations found")
            recorded = {r['name']: r['checksum'] for r in connection.execute("SELECT * FROM schema_migrations")}
            if set(recorded) - {p.name for p in files}:
                raise RuntimeError("Database contains migrations not present in this application")
            for path in files:
                sql = path.read_text(encoding="utf-8")
                checksum = hashlib.sha256(sql.encode()).hexdigest()
                if path.name in recorded:
                    if recorded[path.name] != checksum:
                        raise RuntimeError(f"Applied migration was modified: {path.name}")
                    continue
                # execute each complete statement without executescript's implicit COMMIT.
                statement = ""
                for line in sql.splitlines(keepends=True):
                    statement += line
                    if sqlite3.complete_statement(statement):
                        connection.execute(statement)
                        statement = ""
                if statement.strip():
                    raise RuntimeError(f"Incomplete migration statement: {path.name}")
                connection.execute("INSERT INTO schema_migrations VALUES (?, ?, ?)", (path.name, checksum, now()))

    def seed(self, users):
        characters = [
            ("11111111-1111-4111-8111-111111111111", "开发角色 · 林舟", "喜欢旅行和整理计划的开发示例角色。", "你是林舟，温和且条理清晰。"),
            ("77777777-7777-4777-8777-777777777777", "开发角色 · 星禾", "喜欢故事与观察日常的开发示例角色。", "你是星禾，善于倾听并表达好奇。"),
        ]
        with self.transaction() as connection:
            timestamp = now()
            for user in users:
                connection.execute("INSERT OR IGNORE INTO users VALUES (?, ?, ?)", (user.id, user.display_name, timestamp))
            for character_id, name, summary, prompt in characters:
                connection.execute(
                    "INSERT OR IGNORE INTO characters (id, name, summary, avatar_asset_id, background_asset_id, version, created_at, updated_at) VALUES (?, ?, ?, NULL, NULL, 1, ?, ?)",
                    (character_id, name, summary, timestamp, timestamp),
                )
                connection.execute(
                    "INSERT OR IGNORE INTO character_revisions (id, character_id, version, system_prompt, allowed_tool_ids, name) VALUES (?, ?, 1, ?, '[]', ?)",
                    (character_id, character_id, prompt, name),
                )
            # D17: publish a new demo revision; existing conversations retain their original permissions.
            connection.execute("INSERT OR IGNORE INTO character_revisions (id, character_id, version, system_prompt, allowed_tool_ids, name) VALUES (?, ?, 2, ?, '[\"notes.save\"]', ?)",
                               ("11111111-1111-4111-8111-111111111112", characters[0][0], characters[0][3], characters[0][1]))
            connection.execute("UPDATE characters SET version = 2, updated_at = ? WHERE id = ? AND version = 1", (timestamp, characters[0][0]))


def character_from(row, user_id) -> Character:
    return Character(**{key: row[key] for key in Character.__dataclass_fields__ if key != "editable"},
                     editable=row["owner_id"] == user_id)


def conversation_from(row) -> Conversation:
    return Conversation(**{key: row[key] for key in Conversation.__dataclass_fields__})


class SqliteCatalogRepository:
    def messages(self, user_id, conversation_id, before, limit):
        from .chat import message_from
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM messages WHERE user_id = ? AND conversation_id = ? AND (? IS NULL OR sequence < ?) ORDER BY sequence DESC LIMIT ?",
                (user_id, conversation_id, before, before, limit),
            ).fetchall()
            return [message_from(row) for row in rows]

    def __init__(self, database: Database):
        self.database = database

    def characters(self, user_id: str, after: Position | None, limit: int):
        with self.database.connect() as connection:
            if after:
                rows = connection.execute(
                    "SELECT * FROM characters WHERE deleted_at IS NULL AND (owner_id IS NULL OR owner_id = ?) AND (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?",
                    (user_id, *after, limit),
                ).fetchall()
            else:
                rows = connection.execute("SELECT * FROM characters WHERE deleted_at IS NULL AND (owner_id IS NULL OR owner_id = ?) ORDER BY created_at, id LIMIT ?", (user_id, limit)).fetchall()
        return [character_from(row, user_id) for row in rows]

    def character(self, user_id: str, character_id: str):
        with self.database.connect() as connection:
            row = connection.execute("SELECT * FROM characters WHERE id = ? AND deleted_at IS NULL AND (owner_id IS NULL OR owner_id = ?)", (character_id, user_id)).fetchone()
        if row is None:
            raise not_found()
        return character_from(row, user_id)

    @staticmethod
    def _definition(connection, user_id, character_id):
        row = connection.execute(
            "SELECT c.*, r.system_prompt, r.allowed_tool_ids FROM characters c JOIN character_revisions r "
            "ON r.character_id = c.id AND r.version = c.version WHERE c.id = ? AND c.owner_id = ? AND c.deleted_at IS NULL",
            (character_id, user_id),
        ).fetchone()
        if row is None:
            raise not_found()
        return CharacterDefinition(row["id"], row["name"], row["summary"], row["system_prompt"],
                                   "notes.save" in json.loads(row["allowed_tool_ids"]), row["version"])

    def definition(self, user_id, character_id):
        with self.database.connect() as connection:
            return self._definition(connection, user_id, character_id)

    def save_character(self, user_id, character_id, name, summary, system_prompt, notes_enabled, expected_version):
        # D29: a fixed client ID plus the expected version identifies a write, including lost responses.
        # Keep the comparison and immutable revision publication in one transaction.
        desired = (name, summary, system_prompt, notes_enabled)
        with self.database.transaction() as connection:
            existing = connection.execute("SELECT * FROM characters WHERE id = ?", (character_id,)).fetchone()
            timestamp = now()
            if existing:
                current = self._definition(connection, user_id, character_id)  # owner-only; deleted IDs never revive
                if current.version == expected_version + 1 and desired == (current.name, current.summary, current.system_prompt, current.notes_enabled):
                    return current
                if current.version != expected_version:
                    raise AppError(409, "CHARACTER_VERSION_CONFLICT", "角色已被修改，请重新读取后再编辑。")
                connection.execute("UPDATE characters SET name = ?, summary = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                   (name, summary, timestamp, character_id))
            else:
                if expected_version != 0:
                    raise not_found()
                connection.execute("INSERT INTO characters (id, name, summary, version, created_at, updated_at, owner_id) VALUES (?, ?, ?, 1, ?, ?, ?)",
                                   (character_id, name, summary, timestamp, timestamp, user_id))
            connection.execute("INSERT INTO character_revisions (id, character_id, version, system_prompt, allowed_tool_ids, name) VALUES (?, ?, ?, ?, ?, ?)",
                               (str(uuid4()), character_id, expected_version + 1, system_prompt, json.dumps(["notes.save"] if notes_enabled else []), name))
            return self._definition(connection, user_id, character_id)

    def delete_character(self, user_id, character_id):
        # Hide from future discovery/creation, but retain pinned personas and all previous chats.
        with self.database.transaction() as connection:
            row = connection.execute("SELECT id FROM characters WHERE id = ? AND owner_id = ?", (character_id, user_id)).fetchone()
            if row is None:
                raise not_found()
            connection.execute("UPDATE characters SET deleted_at = COALESCE(deleted_at, ?) WHERE id = ?", (now(), character_id))

    def create_conversation(self, user_id, character_id, title, key, fingerprint):
        # D03: lock before the receipt lookup so concurrent retries cannot both create.
        with self.database.transaction() as connection:
            receipt = connection.execute(
                "SELECT * FROM request_receipts WHERE user_id = ? AND operation = 'create_conversation' AND idempotency_key = ?",
                (user_id, key),
            ).fetchone()
            if receipt:
                if receipt["request_hash"] != fingerprint:
                    raise AppError(409, "IDEMPOTENCY_CONFLICT", "同一个请求标识不能用于不同内容。")
                row = connection.execute("SELECT * FROM conversations WHERE user_id = ? AND id = ?", (user_id, receipt["resource_id"])).fetchone()
                if row is None:
                    raise not_found()
                return CreateConversationResult(conversation_from(row), False)
            character = connection.execute("SELECT * FROM characters WHERE id = ? AND deleted_at IS NULL AND (owner_id IS NULL OR owner_id = ?)", (character_id, user_id)).fetchone()
            if character is None:
                raise not_found()
            revision = connection.execute(
                "SELECT id FROM character_revisions WHERE character_id = ? AND version = ?",
                (character_id, character["version"]),
            ).fetchone()
            if revision is None:
                raise RuntimeError("Published character revision is missing")
            conversation_id, timestamp = str(uuid4()), now()
            connection.execute(
                "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                (conversation_id, user_id, character_id, revision["id"], title if title is not None else character["name"], timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO request_receipts VALUES (?, 'create_conversation', ?, ?, ?, ?)",
                (user_id, key, fingerprint, conversation_id, timestamp),
            )
            row = connection.execute("SELECT * FROM conversations WHERE id = ?", (conversation_id,)).fetchone()
            return CreateConversationResult(conversation_from(row), True)

    def conversations(self, user_id: str, after: Position | None, limit: int):
        with self.database.connect() as connection:
            if after:
                rows = connection.execute(
                    "SELECT * FROM conversations WHERE user_id = ? AND (updated_at, id) < (?, ?) ORDER BY updated_at DESC, id DESC LIMIT ?",
                    (user_id, *after, limit),
                ).fetchall()
            else:
                rows = connection.execute("SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?", (user_id, limit)).fetchall()
        return [conversation_from(row) for row in rows]

    def conversation(self, user_id: str, conversation_id: str):
        with self.database.connect() as connection:
            row = connection.execute("SELECT * FROM conversations WHERE user_id = ? AND id = ?", (user_id, conversation_id)).fetchone()
        if row is None:
            raise not_found()
        return conversation_from(row)

    def rename_conversation(self, user_id: str, conversation_id: str, title: str, expected_version: int) -> Conversation:
        with self.database.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM conversations WHERE user_id = ? AND id = ?",
                (user_id, conversation_id),
            ).fetchone()
            if row is None:
                raise not_found()
            current = conversation_from(row)
            if current.version == expected_version + 1 and current.title == title:
                return current
            if current.version != expected_version:
                raise AppError(409, "CONVERSATION_VERSION_CONFLICT", "会话已被修改，请重新读取后再试。")
            timestamp = now()
            connection.execute(
                "UPDATE conversations SET title = ?, version = version + 1, updated_at = ? WHERE user_id = ? AND id = ?",
                (title, timestamp, user_id, conversation_id),
            )
            updated = connection.execute(
                "SELECT * FROM conversations WHERE user_id = ? AND id = ?",
                (user_id, conversation_id),
            ).fetchone()
            return conversation_from(updated)

