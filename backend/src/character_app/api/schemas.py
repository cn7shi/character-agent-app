from typing import Generic, TypeVar, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class CharacterResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    name: str
    summary: str
    avatar_asset_id: UUID | None
    background_asset_id: UUID | None
    version: int


class ConversationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    character_id: UUID
    title: str
    version: int
    created_at: str
    updated_at: str


class CreateConversationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    character_id: UUID
    title: str | None = Field(default=None, min_length=1, max_length=100)

    @field_validator("title", mode="before")
    @classmethod
    def validate_title(cls, value):
        if value is None:
            return None
        if not isinstance(value, str) or not 1 <= len(value.strip()) <= 100:
            raise ValueError("title must contain 1 to 100 characters")
        return value.strip()


class MessageBlock(BaseModel):
    type: Literal["text", "narration"]
    text: str


class MessageResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    conversation_id: UUID
    run_id: UUID
    client_message_id: UUID | None
    sequence: int
    role: Literal["user", "assistant"]
    blocks: list[MessageBlock]
    status: Literal["pending", "streaming", "completed", "failed", "cancelled"]
    created_at: str
    updated_at: str


class CreateRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    client_message_id: UUID
    text: str = Field(min_length=1, max_length=8000)

    @field_validator("text", mode="before")
    @classmethod
    def strip_text(cls, value):
        return value.strip() if isinstance(value, str) else value


class RunError(BaseModel):
    code: str
    message: str


class RunResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    conversation_id: UUID
    executor: Literal["server"]
    model_name: str
    is_simulated: bool
    status: Literal["queued", "running", "cancel_requested", "completed", "failed", "cancelled"]
    last_event_sequence: int
    input_message: MessageResponse
    output_message: MessageResponse
    error: RunError | None
    created_at: str
    updated_at: str


T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    next_cursor: str | None


class HealthResponse(BaseModel):
    status: Literal["ok"]


class ErrorDetail(BaseModel):
    code: str
    message: str
    request_id: UUID


class ErrorResponse(BaseModel):
    error: ErrorDetail
