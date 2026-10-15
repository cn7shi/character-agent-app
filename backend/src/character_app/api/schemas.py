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
    editable: bool


class CharacterDefinitionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    name: str
    summary: str
    system_prompt: str
    notes_enabled: bool
    version: int


class SaveCharacterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=100)
    summary: str = Field(max_length=500)
    system_prompt: str = Field(min_length=1, max_length=8000)
    notes_enabled: bool = Field(strict=True)
    expected_version: int = Field(ge=0, strict=True)

    @field_validator("name", "summary", "system_prompt", mode="before")
    @classmethod
    def normalize_text(cls, value):
        return value.strip() if isinstance(value, str) else value


class DeleteCharacterResponse(BaseModel):
    deleted: Literal[True] = True


class ProfileResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    nickname: str
    bio: str
    version: int


class ResetProfileRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected_version: int = Field(ge=0, strict=True)


class SaveProfileRequest(ResetProfileRequest):
    nickname: str = Field(min_length=1, max_length=50)
    bio: str = Field(max_length=500)

    @field_validator("nickname", "bio", mode="before")
    @classmethod
    def normalize_text(cls, value):
        return value.strip() if isinstance(value, str) else value


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


class RenameConversationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=100)
    expected_version: int = Field(ge=1, strict=True)

    @field_validator("title", mode="before")
    @classmethod
    def validate_title(cls, value):
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


class ToolCallResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    conversation_id: UUID
    run_id: UUID
    call_id: str
    tool_id: str
    arguments: dict
    status: Literal["completed", "failed"]
    result: dict | None
    error: RunError | None
    created_at: str


class ToolCallList(BaseModel):
    items: list[ToolCallResponse]


class NoteResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    conversation_id: UUID
    source_run_id: UUID
    source_tool_call_id: UUID
    title: str
    content: str
    created_at: str


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


class TtsSynthesizeRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    character_name: str | None = None
    character_id: str | None = None
