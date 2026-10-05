import asyncio

from character_app.domain.chat import ModelContext


class MockTextModel:
    name = "mock-v1"
    is_simulated = True

    async def stream(self, context: ModelContext):
        # Explicit labeling keeps development simulation distinct from a real model.
        text = f"【模拟回复】我是{context.character_name}。已收到：{context.messages[-1].text[:80]}。这段内容用于验证聊天流程，尚未接入真实模型。"
        for offset in range(0, len(text), 6):
            await asyncio.sleep(0.04)
            yield text[offset:offset + 6]
