import asyncio
import re
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, Request, Response
from fastapi.responses import StreamingResponse

from urllib.parse import quote

from character_app.domain.entities import Principal, AppError
from .auth import current_user
from .schemas import CharacterResponse, ConversationResponse, CreateConversationRequest, ErrorResponse, MessageResponse, Page
from .schemas import CreateRunRequest, RunResponse
from .schemas import NoteResponse, ToolCallList
from .schemas import CharacterDefinitionResponse, SaveCharacterRequest, DeleteCharacterResponse
from .schemas import ProfileResponse, SaveProfileRequest, ResetProfileRequest, TtsSynthesizeRequest

router = APIRouter(prefix="/v1", responses={
    code: {"model": ErrorResponse} for code in (401, 403, 404, 422, 500)
})
User = Annotated[Principal, Depends(current_user)]
Limit = Annotated[int, Query(ge=1, le=100)]
Cursor = Annotated[str | None, Query(min_length=1, max_length=2048)]


@router.get("/me/profile", response_model=ProfileResponse, tags=["Profile"])
def profile(request: Request, user: User):
    return request.app.state.profiles.get(user.id)


@router.put("/me/profile", response_model=ProfileResponse,
            responses={409: {"model": ErrorResponse}}, tags=["Profile"])
def save_profile(payload: SaveProfileRequest, request: Request, user: User):
    return request.app.state.profiles.save(user.id, **payload.model_dump())


@router.post("/me/profile/reset", response_model=ProfileResponse,
             responses={409: {"model": ErrorResponse}}, tags=["Profile"])
def reset_profile(payload: ResetProfileRequest, request: Request, user: User):
    return request.app.state.profiles.reset(user.id, payload.expected_version)


@router.get("/characters", response_model=Page[CharacterResponse], tags=["Characters"])
def characters(request: Request, user: User, limit: Limit = 20, cursor: Cursor = None):
    return request.app.state.catalog.characters(user.id, cursor, limit)


@router.get("/characters/{character_id}", response_model=CharacterResponse, tags=["Characters"])
def character(character_id: UUID, request: Request, user: User):
    return request.app.state.catalog.character(user.id, str(character_id))


@router.get("/characters/{character_id}/definition", response_model=CharacterDefinitionResponse, tags=["Characters"])
def definition(character_id: UUID, request: Request, user: User):
    return request.app.state.catalog.definition(user.id, str(character_id))


@router.put("/characters/{character_id}", response_model=CharacterDefinitionResponse,
            responses={409: {"model": ErrorResponse}}, tags=["Characters"])
def save_character(character_id: UUID, payload: SaveCharacterRequest, request: Request, user: User):
    return request.app.state.catalog.save_character(user.id, str(character_id), **payload.model_dump())


@router.delete("/characters/{character_id}", response_model=DeleteCharacterResponse, tags=["Characters"])
def delete_character(character_id: UUID, request: Request, user: User):
    request.app.state.catalog.delete_character(user.id, str(character_id))
    return {"deleted": True}


@router.post(
    "/conversations", status_code=201, response_model=ConversationResponse,
    responses={200: {"model": ConversationResponse, "description": "Existing conversation from an idempotent retry"}, 409: {"model": ErrorResponse}},
    tags=["Conversations"],
)
def create_conversation(
    payload: CreateConversationRequest, request: Request, response: Response, user: User,
    idempotency_key: Annotated[UUID, Header(alias="Idempotency-Key")],
):
    result = request.app.state.catalog.create_conversation(user.id, str(payload.character_id), payload.title, str(idempotency_key))
    response.status_code = 201 if result.created else 200
    return result.conversation


@router.get("/conversations", response_model=Page[ConversationResponse], tags=["Conversations"])
def conversations(request: Request, user: User, limit: Limit = 20, cursor: Cursor = None):
    return request.app.state.catalog.conversations(user.id, cursor, limit)


@router.get("/conversations/{conversation_id}/messages", response_model=Page[MessageResponse], tags=["Conversations"])
def messages(conversation_id: UUID, request: Request, user: User, limit: Limit = 20, cursor: Cursor = None):
    return request.app.state.catalog.messages(user.id, str(conversation_id), cursor, limit)


@router.post("/conversations/{conversation_id}/runs", status_code=202, response_model=RunResponse,
             responses={200: {"model": RunResponse}, 409: {"model": ErrorResponse}}, tags=["Runs"])
async def create_run(conversation_id: UUID, payload: CreateRunRequest, request: Request,
                     response: Response, user: User,
                     idempotency_key: Annotated[UUID, Header(alias="Idempotency-Key")]):
    result = await request.app.state.chat.create(user.id, str(conversation_id), str(payload.client_message_id), payload.text, str(idempotency_key))
    response.status_code = 202 if result.created else 200
    return result.run


@router.get("/runs/{run_id}", response_model=RunResponse, tags=["Runs"])
def run(run_id: UUID, request: Request, user: User):
    return request.app.state.chat.repository.get_run(user.id, str(run_id))


@router.post("/runs/{run_id}/cancel", response_model=RunResponse, tags=["Runs"])
async def cancel_run(run_id: UUID, request: Request, user: User):
    return await request.app.state.chat.cancel(user.id, str(run_id))


@router.get("/runs/{run_id}/tool-calls", response_model=ToolCallList, tags=["Tools"])
def tool_calls(run_id: UUID, request: Request, user: User):
    return {"items": request.app.state.notes.store.calls(user.id, str(run_id))}


@router.get("/notes", response_model=Page[NoteResponse], tags=["Notes"])
def notes(request: Request, user: User, limit: Limit = 20, cursor: Cursor = None, conversation_id: UUID | None = None):
    return request.app.state.notes.list(user.id, str(conversation_id) if conversation_id else None, cursor, limit)


@router.get("/notes/{note_id}", response_model=NoteResponse, tags=["Notes"])
def note(note_id: UUID, request: Request, user: User):
    return request.app.state.notes.store.note(user.id, str(note_id))


@router.get("/runs/{run_id}/events", response_class=StreamingResponse,
            responses={200: {"content": {"text/event-stream": {"schema": {"type": "string"}}}}, 400: {"model": ErrorResponse}}, tags=["Runs"])
async def events(run_id: UUID, request: Request, user: User,
                 last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None):
    service = request.app.state.chat
    # Validate ownership before opening a successful streaming response (or revealing cursor bounds).
    await asyncio.to_thread(service.repository.get_run, user.id, str(run_id))
    if last_event_id is not None and not re.fullmatch(r"[0-9]{1,18}", last_event_id):
        raise AppError(400, "INVALID_EVENT_CURSOR", "事件序号无效。")
    after = int(last_event_id) if last_event_id is not None else 0
    await asyncio.to_thread(service.repository.events, user.id, str(run_id), after)
    return StreamingResponse(service.events(user.id, str(run_id), after), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/tts/synthesize", response_class=Response, tags=["TTS"])
async def synthesize_speech(payload: TtsSynthesizeRequest, request: Request, user: User):
    tts_service = request.app.state.tts
    ident = payload.character_name or payload.character_id
    result = await tts_service.synthesize(payload.text, ident)
    return Response(
        content=result.audio_bytes,
        media_type=result.mime_type,
        headers={
            "Content-Disposition": "inline; filename=\"speech.mp3\"",
            "X-Audio-Duration": str(result.duration_seconds),
            "X-Clean-Text": quote(result.clean_text),
            "X-Voice": result.voice,
        }
    )


@router.get("/tts/synthesize", response_class=Response, tags=["TTS"])
async def synthesize_speech_get(
    request: Request,
    user: User,
    text: str = Query(min_length=1, max_length=2000),
    character_name: str | None = Query(default=None),
    character_id: str | None = Query(default=None),
):
    tts_service = request.app.state.tts
    ident = character_name or character_id
    result = await tts_service.synthesize(text, ident)
    return Response(
        content=result.audio_bytes,
        media_type=result.mime_type,
        headers={
            "Content-Disposition": "inline; filename=\"speech.mp3\"",
            "X-Audio-Duration": str(result.duration_seconds),
            "X-Clean-Text": quote(result.clean_text),
            "X-Voice": result.voice,
        }
    )
