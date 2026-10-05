from dataclasses import dataclass
from typing import Protocol

TOOL_ERRORS = {
    "TOOL_NOT_FOUND": "当前工具尚未注册。",
    "TOOL_NOT_ALLOWED": "当前会话的人设版本未开启这个工具，请使用支持该能力的新会话。",
    "TOOL_INVALID_ARGUMENTS": "工具参数不符合要求。",
    "TOOL_CALL_CONFLICT": "同一个工具调用标识不能用于不同内容。",
    "TOOL_EXECUTION_FAILED": "工具执行失败，未确认保存成功。",
    "AGENT_STEP_LIMIT": "本次任务已达到工具调用步数上限。",
}


class ToolFailure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(TOOL_ERRORS[code])


@dataclass(frozen=True)
class ToolRequest:
    call_id: str
    tool_id: str
    arguments: dict


@dataclass(frozen=True)
class ToolCall:
    id: str
    conversation_id: str
    run_id: str
    call_id: str
    tool_id: str
    arguments: dict
    status: str
    result: dict | None
    error: dict | None
    created_at: str


@dataclass(frozen=True)
class Note:
    id: str
    conversation_id: str
    source_run_id: str
    source_tool_call_id: str
    title: str
    content: str
    created_at: str


@dataclass(frozen=True)
class NoteDraft:
    title: str
    content: str

    @classmethod
    def parse(cls, arguments):
        if not isinstance(arguments, dict) or set(arguments) != {"title", "content"}:
            raise ToolFailure("TOOL_INVALID_ARGUMENTS")
        title, content = arguments["title"], arguments["content"]
        if not isinstance(title, str) or not isinstance(content, str):
            raise ToolFailure("TOOL_INVALID_ARGUMENTS")
        title, content = title.strip(), content.strip()
        if not 1 <= len(title) <= 100 or not 1 <= len(content) <= 16000:
            raise ToolFailure("TOOL_INVALID_ARGUMENTS")
        return cls(title, content)


class ToolStore(Protocol):
    def save_note(self, user_id: str, run_id: str, request: ToolRequest, draft: NoteDraft) -> ToolCall | None: ...
    def reject(self, user_id: str, run_id: str, request: ToolRequest, code: str) -> ToolCall | None: ...
    def calls(self, user_id: str, run_id: str) -> list[ToolCall]: ...
    def notes(self, user_id: str, conversation_id: str | None, before: tuple[str, str] | None, limit: int) -> list[Note]: ...
    def note(self, user_id: str, note_id: str) -> Note: ...


class Tool(Protocol):
    id: str

    def execute(self, user_id: str, run_id: str, request: ToolRequest) -> ToolCall | None: ...
