from character_app.domain.tools import NoteDraft, ToolRequest, ToolStore


class SaveNoteTool:
    id = "notes.save"

    def __init__(self, store: ToolStore):
        self.store = store

    def execute(self, user_id, run_id, request):
        draft = NoteDraft.parse(request.arguments)
        return self.store.save_note(user_id, run_id, request, draft)
