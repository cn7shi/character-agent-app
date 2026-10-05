import base64
import hashlib
import hmac
import json
from datetime import datetime
from uuid import UUID

from character_app.domain.entities import AppError
from character_app.domain.ports import CatalogRepository, Position


class CursorCodec:
    """Cursors are signed, scoped, and stable across service restarts."""

    def __init__(self, key: bytes):
        self.key = key

    def encode(self, scope: str, position: Position | int) -> str:
        payload = json.dumps([1, scope, position], separators=(",", ":")).encode()
        signature = hmac.digest(self.key, payload, "sha256")
        return base64.urlsafe_b64encode(signature + payload).rstrip(b"=").decode()

    def decode(self, cursor: str | None, scope: str) -> Position | int | None:
        if cursor is None:
            return None
        try:
            raw = base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True)
            signature, payload = raw[:32], raw[32:]
            if not hmac.compare_digest(signature, hmac.digest(self.key, payload, "sha256")):
                raise ValueError()
            version, saved_scope, position = json.loads(payload)
            if version != 1 or saved_scope != scope:
                raise ValueError()
            if scope.startswith("messages:"):
                if type(position) is not int or position <= 0:
                    raise ValueError()
                return position
            if len(position) != 2:
                raise ValueError()
            timestamp, resource_id = position
            datetime.fromisoformat(timestamp)
            UUID(resource_id)
            return timestamp, resource_id
        except (ValueError, TypeError, KeyError, UnicodeError):
            raise AppError(422, "VALIDATION_ERROR", "分页游标无效或不适用于当前查询。") from None


class CatalogService:
    def __init__(self, repository: CatalogRepository, cursors: CursorCodec):
        self.repository = repository
        self.cursors = cursors

    def characters(self, user_id: str, cursor: str | None, limit: int):
        scope = f"characters:{user_id}"
        rows = self.repository.characters(self.cursors.decode(cursor, scope), limit + 1)
        items = rows[:limit]
        next_cursor = None
        if len(rows) > limit:
            last = items[-1]
            next_cursor = self.cursors.encode(scope, (last.created_at, last.id))
        return {"items": items, "next_cursor": next_cursor}

    def character(self, character_id: str):
        return self.repository.character(character_id)

    def create_conversation(self, user_id: str, character_id: str, title: str | None, key: str):
        # Hash the normalized request, not a mutable default character name.
        payload = json.dumps({"character_id": character_id, "title": title}, sort_keys=True)
        fingerprint = hashlib.sha256(payload.encode()).hexdigest()
        return self.repository.create_conversation(user_id, character_id, title, key, fingerprint)

    def conversations(self, user_id: str, cursor: str | None, limit: int):
        scope = f"conversations:{user_id}"
        rows = self.repository.conversations(user_id, self.cursors.decode(cursor, scope), limit + 1)
        items = rows[:limit]
        next_cursor = None
        if len(rows) > limit:
            last = items[-1]
            next_cursor = self.cursors.encode(scope, (last.updated_at, last.id))
        return {"items": items, "next_cursor": next_cursor}

    def messages(self, user_id: str, conversation_id: str, cursor: str | None, limit: int):
        self.repository.conversation(user_id, conversation_id)
        scope = f"messages:{user_id}:{conversation_id}"
        rows = self.repository.messages(user_id, conversation_id, self.cursors.decode(cursor, scope), limit + 1)
        items = rows[:limit]
        cursor = self.cursors.encode(scope, items[-1].sequence) if len(rows) > limit else None
        return {"items": items, "next_cursor": cursor}
