from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Protocol

TERMINAL = frozenset({"completed", "failed", "cancelled"})


@dataclass(frozen=True)
class Message:
    id: str
    conversation_id: str
    run_id: str
    client_message_id: str | None
    sequence: int
    role: str
    blocks: list[dict[str, str]]
    status: str
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class Run:
    id: str
    conversation_id: str
    executor: str
    model_name: str
    is_simulated: bool
    status: str
    last_event_sequence: int
    input_message: Message
    output_message: Message
    error: dict[str, str] | None
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class CreateRunResult:
    run: Run
    created: bool


@dataclass(frozen=True)
class ModelMessage:
    role: str
    text: str


@dataclass(frozen=True)
class ModelContext:
    character_name: str
    system_prompt: str
    messages: tuple[ModelMessage, ...]


class TextModel(Protocol):
    name: str
    is_simulated: bool

    def stream(self, context: ModelContext) -> AsyncIterator[str]: ...


class ChatRepository(Protocol):
    def create_run(self, user_id: str, conversation_id: str, client_message_id: str, text: str, key: str, fingerprint: str, model_name: str, is_simulated: bool) -> CreateRunResult: ...
    def get_run(self, user_id: str, run_id: str) -> Run: ...
    def queued(self, limit: int) -> list[tuple[str, str]]: ...
    def start(self, user_id: str, run_id: str) -> bool: ...
    def context(self, user_id: str, run_id: str) -> ModelContext: ...
    def update_text(self, user_id: str, run_id: str, text: str) -> bool: ...
    def finish(self, user_id: str, run_id: str, status: str, error: dict[str, str] | None = None) -> Run: ...
    def cancel(self, user_id: str, run_id: str) -> Run: ...
    def events(self, user_id: str, run_id: str, after: int, limit: int = 100) -> tuple[list[dict], str, int]: ...
    def recover(self) -> None: ...
