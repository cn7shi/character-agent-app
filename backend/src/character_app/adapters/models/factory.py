from contextlib import asynccontextmanager

import httpx

from character_app.model_config import ModelSettings
from .mock import MockTextModel
from .openai_compatible import OpenAICompatibleTextModel


@asynccontextmanager
async def configured_model(settings: ModelSettings):
    if settings.provider == "mock":
        yield MockTextModel()
    else:
        # One client per app lifespan; cancellation closes the response, shutdown closes the pool.
        # Do not implicitly pick up proxy credentials or netrc from the machine environment.
        async with httpx.AsyncClient(timeout=httpx.Timeout(settings.timeout_seconds, connect=10, pool=10),
                                     trust_env=False, follow_redirects=False) as client:
            yield OpenAICompatibleTextModel(settings, client)
