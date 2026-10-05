from contextlib import asynccontextmanager, nullcontext
import ipaddress
import logging
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from character_app.adapters.storage.sqlite import Database, SqliteCatalogRepository
from character_app.adapters.storage.chat import SqliteChatRepository
from character_app.adapters.storage.tools import SqliteToolStore
from character_app.adapters.tools.notes import SaveNoteTool
from character_app.adapters.storage.service_lock import ServiceLock
from character_app.adapters.models.factory import configured_model
from character_app.adapters.models.logging_guard import redact_model_secret
from character_app.application.chat import ChatService
from character_app.application.agent import NotesService, ToolRegistry
from character_app.domain.chat import TextModel
from character_app.api.auth import DevelopmentIdentityResolver, RejectingIdentityResolver
from character_app.api.routes import router
from character_app.api.schemas import HealthResponse
from character_app.application.catalog import CatalogService, CursorCodec
from character_app.config import BACKEND_ROOT, Settings, load_cursor_key, load_development_users
from character_app.domain.entities import AppError
from character_app.model_config import ModelSettings, load_model_settings

logger = logging.getLogger(__name__)


def error_response(request: Request, status: int, code: str, message: str):
    request_id = getattr(request.state, "request_id", str(uuid4()))
    headers = {"X-Request-ID": request_id}
    if status == 401:
        headers["WWW-Authenticate"] = "Bearer"
    return JSONResponse({"error": {"code": code, "message": message, "request_id": request_id}}, status_code=status, headers=headers)


def create_app(settings: Settings | None = None, *, model: TextModel | None = None, run_timeout: float | None = None,
               model_settings: ModelSettings | None = None) -> FastAPI:
    # Explicit settings keep tests/schema export independent of private workstation configuration.
    use_local_model_config = settings is None and model is None and model_settings is None
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        with ServiceLock(settings.data_dir) if settings.dev_auth else nullcontext():
            key = load_cursor_key(settings.data_dir)
            users = load_development_users(settings.data_dir) if settings.dev_auth else []
            database = Database(settings.data_dir / "character.sqlite3")
            database.migrate(BACKEND_ROOT / "migrations")
            if settings.dev_auth:
                database.seed([principal for principal, _ in users])
            app.state.identities = DevelopmentIdentityResolver(users) if settings.dev_auth else RejectingIdentityResolver()
            app.state.catalog = CatalogService(SqliteCatalogRepository(database), CursorCodec(key))
            tool_store = SqliteToolStore(database)
            registry = ToolRegistry(tool_store, [SaveNoteTool(tool_store)])
            app.state.notes = NotesService(tool_store, CursorCodec(key))
            config = load_model_settings() if use_local_model_config else model_settings or ModelSettings()
            with redact_model_secret(config.api_key.get_secret_value()):
                async with configured_model(config) as selected_model:
                    service = ChatService(SqliteChatRepository(database), model or selected_model,
                                          run_timeout if run_timeout is not None else config.timeout_seconds, registry)
                    app.state.chat = service
                    if settings.dev_auth:
                        await service.start()
                    try:
                        yield
                    finally:
                        if settings.dev_auth:
                            await service.close()

    app = FastAPI(
        title="Character App API", version="0.6.0", lifespan=lifespan,
        description="Local development chat with fixed demo replies, bounded tool execution and persisted notes. Optional compatible text provider; live model tool calling is not integrated.",
        docs_url="/docs" if settings.dev_auth else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.dev_auth else None,
    )

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request.state.request_id = str(uuid4())
        if settings.dev_auth:
            try:
                local = request.client is not None and ipaddress.ip_address(request.client.host).is_loopback
            except ValueError:
                local = False
            if not local:
                return error_response(request, 403, "DEV_AUTH_LOCAL_ONLY", "开发模式仅允许本机访问。")
        try:
            response = await call_next(request)
        except Exception:
            logger.exception("Unhandled error, request_id=%s", request.state.request_id)
            return error_response(request, 500, "INTERNAL_ERROR", "服务暂时无法处理请求。")
        response.headers["X-Request-ID"] = request.state.request_id
        return response

    @app.exception_handler(AppError)
    async def app_error(request, exc):
        return error_response(request, exc.status, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return error_response(request, 422, "VALIDATION_ERROR", "请求字段、路径或查询参数不符合约定。")

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        code = "RESOURCE_NOT_FOUND" if exc.status_code == 404 else "HTTP_ERROR"
        return error_response(request, exc.status_code, code, "请求的资源或操作不可用。")

    @app.get("/health", response_model=HealthResponse, tags=["Health"])
    def health():
        return {"status": "ok"}

    app.include_router(router)
    return app
