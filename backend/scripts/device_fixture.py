"""Device-test-only model gate. Production startup never imports this module."""
import asyncio

from character_app.adapters.models.mock import MockTextModel
from character_app.config import Settings
from character_app.main import create_app


def create_device_app():
    settings = Settings.from_env()

    class HeldMockModel(MockTextModel):
        async def stream(self, context):
            held = False
            async for chunk in super().stream(context):
                yield chunk
                # ToolRequest passes through first: the note is genuinely committed before the
                # partial answer is held. Cancellation must interrupt this await, not undo the note.
                if not held and isinstance(chunk, str) and "设备暂停" in context.messages[-1].text:
                    held = True
                    (settings.data_dir / "hold-started").touch()
                    while (settings.data_dir / "hold-stream").exists():
                        await asyncio.sleep(0.02)

    return create_app(settings, model=HeldMockModel(), run_timeout=60)
