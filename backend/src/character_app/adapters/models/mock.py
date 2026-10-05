import asyncio
import re

from character_app.domain.chat import ModelContext
from character_app.domain.tools import ToolRequest

FIXED_REPLIES = (
    "【模拟回复】你好，我在。今天想聊些什么？",
    "【模拟回复】那就先慢下来吧。你可以说说今天发生的事，我会陪你聊一会儿。",
)


class MockTextModel:
    name = "mock-v2"
    is_simulated = True

    async def stream(self, context: ModelContext):
        latest = context.messages[-1].text
        if context.tool_results:
            result = context.tool_results[-1]
            # Report success only after an actual tool receipt exists; never infer it from requested text.
            text = f"【模拟回复】已保存笔记《{result.result['title']}》，可以在笔记列表查看。"
        elif latest.startswith(("记笔记：", "记笔记:")):
            body = latest[4:].strip()
            lines = [line.strip() for line in re.split(r"[；;\n]+", body) if line.strip()]
            yield ToolRequest("save-note-1", "notes.save", {"title": lines[0][:60] if lines else "", "content": "\n".join("- " + line for line in lines)})
            return
        else:
            # D15: two explicit fixtures unblock UI work without relying on a paid model.
            text = FIXED_REPLIES[1] if latest == "今天有点累" else FIXED_REPLIES[0]
        for offset in range(0, len(text), 3):
            await asyncio.sleep(0.04)
            yield text[offset:offset + 3]
