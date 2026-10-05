from dataclasses import dataclass


@dataclass(frozen=True)
class Principal:
    id: str
    display_name: str


@dataclass(frozen=True)
class Character:
    id: str
    name: str
    summary: str
    avatar_asset_id: str | None
    background_asset_id: str | None
    version: int
    created_at: str
    editable: bool = False


@dataclass(frozen=True)
class CharacterDefinition:
    id: str
    name: str
    summary: str
    system_prompt: str
    notes_enabled: bool
    version: int


@dataclass(frozen=True)
class Conversation:
    id: str
    character_id: str
    title: str
    version: int
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class CreateConversationResult:
    conversation: Conversation
    created: bool


class AppError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def not_found() -> AppError:
    return AppError(404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问。")
