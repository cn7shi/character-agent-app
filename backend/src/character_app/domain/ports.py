from typing import Protocol

from .entities import Character, Conversation, CreateConversationResult, Principal
from .chat import Message

Position = tuple[str, str]


class IdentityResolver(Protocol):
    def resolve(self, token: str) -> Principal | None: ...


class CatalogRepository(Protocol):
    def characters(self, after: Position | None, limit: int) -> list[Character]: ...

    def character(self, character_id: str) -> Character: ...

    def create_conversation(
        self, user_id: str, character_id: str, title: str | None,
        key: str, fingerprint: str,
    ) -> CreateConversationResult: ...

    def conversations(
        self, user_id: str, after: Position | None, limit: int,
    ) -> list[Conversation]: ...

    def conversation(self, user_id: str, conversation_id: str) -> Conversation: ...

    def messages(self, user_id: str, conversation_id: str, before: int | None, limit: int) -> list[Message]: ...
