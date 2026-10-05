import json

import httpx

from character_app.domain.chat import ModelContext
from character_app.domain.model_errors import ModelFailure
from character_app.model_config import ModelSettings


async def sse_frames(response: httpx.Response):
    """Bound line/event memory while preserving UTF-8 across arbitrary network chunks."""
    buffer = b""
    data = []
    event_size = 0
    event_type = "message"
    async for chunk in response.aiter_bytes():
        lines = (buffer + chunk).split(b"\n")
        buffer = lines.pop()
        if len(buffer) > 65536:
            raise ModelFailure("MODEL_PROTOCOL_ERROR")
        for raw in lines:
            if len(raw) > 65536:
                raise ModelFailure("MODEL_PROTOCOL_ERROR")
            try:
                line = raw.rstrip(b"\r").decode("utf-8")
            except UnicodeError:
                raise ModelFailure("MODEL_PROTOCOL_ERROR") from None
            if not line:
                if data:
                    yield event_type, "\n".join(data)
                data, event_size, event_type = [], 0, "message"
            elif line.startswith("data:"):
                event_size += len(raw)
                if event_size > 262144:
                    raise ModelFailure("MODEL_PROTOCOL_ERROR")
                data.append(line[5:].removeprefix(" "))
            elif line.startswith("event:"):
                event_type = line[6:].strip()
    # A tail without its terminating blank line is not a complete SSE event.


class OpenAICompatibleTextModel:
    is_simulated = False

    def __init__(self, settings: ModelSettings, client: httpx.AsyncClient):
        self.settings = settings
        self.client = client
        self.name = settings.model_name

    async def stream(self, context: ModelContext):
        payload = {"model": self.name, "stream": True,
                   "messages": [{"role": "system", "content": context.system_prompt}]
                               + [{"role": m.role, "content": m.text} for m in context.messages],
                   self.settings.token_limit_parameter: self.settings.max_tokens}
        headers = {"Authorization": "Bearer " + self.settings.api_key.get_secret_value(),
                   "Accept": "text/event-stream"}
        stopped = False
        has_text = False
        try:
            # D14: no automatic retries or redirects; the original POST may already have been billed.
            async with self.client.stream("POST", self.settings.base_url.rstrip("/") + "/chat/completions",
                                          json=payload, headers=headers, follow_redirects=False) as response:
                status = response.status_code
                if status != 200:
                    code = ("MODEL_AUTH_FAILED" if status in (401, 403) else "MODEL_RATE_LIMITED" if status == 429
                            else "MODEL_UNAVAILABLE" if status >= 500 else "MODEL_REQUEST_REJECTED")
                    raise ModelFailure(code)
                if response.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "text/event-stream":
                    raise ModelFailure("MODEL_PROTOCOL_ERROR")
                async for kind, data in sse_frames(response):
                    if kind == "error":
                        raise ModelFailure("MODEL_UNAVAILABLE")
                    if data == "[DONE]":
                        if not stopped:
                            raise ModelFailure("MODEL_STREAM_INTERRUPTED")
                        if not has_text:
                            raise ModelFailure("MODEL_EMPTY_RESPONSE")
                        return
                    try:
                        event = json.loads(data)
                        if not isinstance(event, dict) or "error" in event:
                            raise ValueError()
                        choices = event["choices"]
                        if not isinstance(choices, list):
                            raise ValueError()
                        if not choices:  # Optional trailing usage frame.
                            continue
                        if len(choices) != 1 or choices[0].get("index") != 0 or stopped:
                            raise ValueError()
                        choice = choices[0]
                        delta = choice["delta"]
                        if not isinstance(delta, dict):
                            raise ValueError()
                        if delta.get("tool_calls") or delta.get("function_call"):
                            raise ModelFailure("MODEL_TOOLS_UNSUPPORTED")
                        if delta.get("refusal"):
                            raise ModelFailure("MODEL_CONTENT_FILTERED")
                        text = delta.get("content")
                        if text is not None and not isinstance(text, str):
                            raise ValueError()
                        if text:
                            has_text = True
                            yield text
                        reason = choice.get("finish_reason")
                        if reason is None:
                            continue
                        if reason == "stop":
                            stopped = True
                        elif reason == "length":
                            raise ModelFailure("MODEL_OUTPUT_TRUNCATED")
                        elif reason == "content_filter":
                            raise ModelFailure("MODEL_CONTENT_FILTERED")
                        elif reason in ("tool_calls", "function_call"):
                            raise ModelFailure("MODEL_TOOLS_UNSUPPORTED")
                        else:
                            raise ValueError()
                    except (ValueError, KeyError, TypeError, AttributeError):
                        raise ModelFailure("MODEL_PROTOCOL_ERROR") from None
                raise ModelFailure("MODEL_STREAM_INTERRUPTED")
        except httpx.TimeoutException:
            raise ModelFailure("MODEL_TIMEOUT") from None
        except httpx.HTTPError:
            raise ModelFailure("MODEL_UNAVAILABLE") from None
