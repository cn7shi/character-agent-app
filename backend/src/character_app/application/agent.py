from character_app.domain.tools import Tool, ToolFailure, ToolRequest, ToolStore


class ToolRegistry:
    """Registered implementations only; model output never supplies executable code or user scope."""

    def __init__(self, store: ToolStore, tools: list[Tool]):
        self.store = store
        self.tools = {tool.id: tool for tool in tools}
        if len(self.tools) != len(tools):
            raise ValueError("Duplicate tool identifier")

    def execute(self, user_id, run_id, request: ToolRequest):
        if (not isinstance(request.call_id, str) or not 1 <= len(request.call_id) <= 100
                or not isinstance(request.tool_id, str) or not 1 <= len(request.tool_id) <= 100):
            raise ToolFailure("TOOL_INVALID_ARGUMENTS")
        tool = self.tools.get(request.tool_id)
        if tool is None:
            return self.store.reject(user_id, run_id, request, "TOOL_NOT_FOUND")
        try:
            return tool.execute(user_id, run_id, request)
        except ToolFailure as error:
            if error.code != "TOOL_INVALID_ARGUMENTS":
                raise
            return self.store.reject(user_id, run_id, request, error.code)
        except Exception:
            # Local note transactions roll back before recording failure. Do not echo SQL/provider errors.
            return self.store.reject(user_id, run_id, request, "TOOL_EXECUTION_FAILED")


class NotesService:
    def __init__(self, store: ToolStore, cursors):
        self.store = store
        self.cursors = cursors

    def list(self, user_id, conversation_id, cursor, limit):
        scope = f"notes:{user_id}:{conversation_id or 'all'}"
        rows = self.store.notes(user_id, conversation_id, self.cursors.decode(cursor, scope), limit + 1)
        items = rows[:limit]
        next_cursor = self.cursors.encode(scope, (items[-1].created_at, items[-1].id)) if len(rows) > limit else None
        return {"items": items, "next_cursor": next_cursor}
