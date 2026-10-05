import asyncio
from contextlib import aclosing
import io
import json
import logging
from pathlib import Path
import tempfile
import traceback
import unittest
from unittest.mock import patch

import httpx
from pydantic import SecretStr, ValidationError

from character_app.adapters.models.logging_guard import redact_model_secret
from character_app.adapters.models.openai_compatible import OpenAICompatibleTextModel
from character_app.config import BACKEND_ROOT
from character_app.domain.chat import ModelContext, ModelMessage
from character_app.domain.model_errors import ModelFailure
from character_app.model_config import ModelSettings, load_model_settings

CONTEXT = ModelContext("Test", "private persona", (ModelMessage("user", "你好"),))
FAKE_KEY = "synthetic-" + "credential-for-tests-only"


def settings(**overrides):
    return ModelSettings(provider="openai_compatible", base_url="https://provider.invalid/v1",
                         model_name="test-model", api_key=SecretStr(FAKE_KEY), **overrides)


def frame(text=None, reason=None, **delta):
    value = {"choices": [{"index": 0, "delta": {"content": text, **delta}, "finish_reason": reason}]}
    return ("data: " + json.dumps(value, ensure_ascii=False) + "\r\n\r\n").encode()


class Chunks(httpx.AsyncByteStream):
    def __init__(self, chunks, hang=False):
        self.chunks = chunks
        self.hang = hang
        self.closed = False
        self.waiting = asyncio.Event()

    async def __aiter__(self):
        for chunk in self.chunks:
            yield chunk
        if self.hang:
            self.waiting.set()
            await asyncio.sleep(60)

    async def aclose(self):
        self.closed = True


class ModelAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def collect(self, chunks, *, status=200, content_type="text/event-stream", config=None):
        stream = Chunks(chunks)
        requests = []

        def handler(request):
            requests.append(request)
            return httpx.Response(status, headers={"content-type": content_type, "location": "https://other.invalid"}, stream=stream)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = OpenAICompatibleTextModel(config or settings(), client)
            text = "".join([chunk async for chunk in model.stream(CONTEXT)])
        self.assertTrue(stream.closed)
        return text, requests

    async def test_request_and_unicode_stream_with_heartbeat_and_usage(self):
        content = b": heartbeat\r\n\r\n" + frame(role="assistant") + frame("你好") + frame("，世界") + frame(reason="stop")
        content += b'data: {"choices": [], "usage": {"total_tokens": 5}}\n\ndata: [DONE]\n\n'
        text, requests = await self.collect([content[i:i + 1] for i in range(len(content))])
        self.assertEqual(text, "你好，世界")
        self.assertEqual(len(requests), 1)
        req = requests[0]
        self.assertEqual(str(req.url), "https://provider.invalid/v1/chat/completions")
        self.assertEqual(req.headers["authorization"], "Bearer " + FAKE_KEY)
        payload = json.loads(req.content)
        self.assertEqual(payload["messages"], [{"role": "system", "content": "private persona"}, {"role": "user", "content": "你好"}])
        self.assertEqual(payload["max_tokens"], 1024)
        self.assertNotIn(FAKE_KEY, req.content.decode())
        self.assertTrue(payload["stream"])

    async def test_alternate_token_parameter_and_multiline_sse(self):
        multiline = b'data: {"choices":\ndata: [{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\n'
        text, requests = await self.collect([multiline, b"data: [DONE]\n\n"], config=settings(token_limit_parameter="max_completion_tokens"))
        self.assertEqual(text, "ok")
        self.assertIn("max_completion_tokens", json.loads(requests[0].content))

    async def test_http_errors_are_safe_without_retries_or_redirects(self):
        for status, code in [(401, "MODEL_AUTH_FAILED"), (403, "MODEL_AUTH_FAILED"), (429, "MODEL_RATE_LIMITED"),
                             (503, "MODEL_UNAVAILABLE"), (400, "MODEL_REQUEST_REJECTED"), (307, "MODEL_REQUEST_REJECTED")]:
            with self.subTest(status=status):
                requests = []

                def handler(request):
                    requests.append(request)
                    return httpx.Response(status, headers={"location": "https://other.invalid"}, text=FAKE_KEY)

                async with httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=True) as client:
                    with self.assertRaises(ModelFailure) as raised:
                        _ = [chunk async for chunk in OpenAICompatibleTextModel(settings(), client).stream(CONTEXT)]
                self.assertEqual(raised.exception.code, code)
                self.assertNotIn(FAKE_KEY, str(raised.exception))
                self.assertEqual(len(requests), 1)

    async def test_incomplete_or_truncated_replies_never_report_success(self):
        cases = [([frame("partial")], "MODEL_STREAM_INTERRUPTED"),
                 ([frame("partial", "stop")], "MODEL_STREAM_INTERRUPTED"),
                 ([frame("partial"), b"data: [DONE]\n\n"], "MODEL_STREAM_INTERRUPTED"),
                 ([frame("partial", "length")], "MODEL_OUTPUT_TRUNCATED"),
                 ([frame(reason="content_filter")], "MODEL_CONTENT_FILTERED"),
                 ([frame(reason="tool_calls", tool_calls=[{"id": "test"}])], "MODEL_TOOLS_UNSUPPORTED"),
                 ([frame(reason="stop"), b"data: [DONE]\n\n"], "MODEL_EMPTY_RESPONSE")]
        for chunks, code in cases:
            with self.subTest(code=code), self.assertRaises(ModelFailure) as raised:
                await self.collect(chunks)
            self.assertEqual(raised.exception.code, code)

    async def test_invalid_protocol_and_oversized_events_fail_safely(self):
        cases = [[b"data: not-json\n\n"], [b"data: {}\n\n"], [b"data: []\n\n"],
                 [frame(text={"unexpected": "object"})], [b"data: " + b"x" * 65537],
                 [(b"data: " + b"x" * 60000 + b"\n") * 5 + b"\n"], [b"data: \xff\n\n"]]
        for chunks in cases:
            with self.subTest(length=sum(map(len, chunks))), self.assertRaises(ModelFailure) as raised:
                await self.collect(chunks)
            self.assertEqual(raised.exception.code, "MODEL_PROTOCOL_ERROR")
        with self.assertRaises(ModelFailure):
            await self.collect([b"{}"], content_type="application/json")

    async def test_timeout_is_mapped_without_provider_details(self):
        def handler(request):
            raise httpx.ReadTimeout(FAKE_KEY, request=request)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaises(ModelFailure) as raised:
                _ = [part async for part in OpenAICompatibleTextModel(settings(), client).stream(CONTEXT)]
        self.assertEqual(raised.exception.code, "MODEL_TIMEOUT")
        self.assertNotIn(FAKE_KEY, str(raised.exception))

    async def test_cancellation_closes_active_provider_stream(self):
        source = Chunks([frame("partial")], hang=True)
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=source))) as client:
            async with aclosing(OpenAICompatibleTextModel(settings(), client).stream(CONTEXT)) as stream:
                self.assertEqual(await anext(stream), "partial")
                pending = asyncio.create_task(anext(stream))
                await asyncio.wait_for(source.waiting.wait(), 1)
                pending.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await pending
            self.assertTrue(source.closed)


class ModelConfigurationTests(unittest.TestCase):
    def setUp(self):
        runtime = (BACKEND_ROOT / ".runtime").resolve()
        runtime.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="model-config-test-", dir=runtime)
        self.path = Path(temporary.name).resolve() / "model.local.json"
        assert self.path.is_relative_to(runtime)
        self.addCleanup(temporary.cleanup)

    def test_missing_config_defaults_to_mock_and_env_can_select_provider(self):
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(load_model_settings(self.path).provider, "mock")
        env = {"CHARACTER_MODEL_PROVIDER": "openai_compatible", "CHARACTER_MODEL_BASE_URL": "https://provider.invalid/v1",
               "CHARACTER_MODEL_NAME": "test-model", "CHARACTER_MODEL_API_KEY": FAKE_KEY}
        with patch.dict("os.environ", env, clear=True):
            config = load_model_settings(self.path)
            self.assertEqual(config.provider, "openai_compatible")
            self.assertNotIn(FAKE_KEY, repr(config))
            self.assertNotIn(FAKE_KEY, config.model_dump_json())

    def test_private_json_is_read_but_errors_do_not_echo_inputs(self):
        self.path.write_text(json.dumps({"provider": "openai_compatible", "base_url": "https://provider.invalid/v1",
                                         "model_name": "test-model", "api_key": FAKE_KEY}), encoding="utf-8")
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(load_model_settings(self.path).api_key.get_secret_value(), FAKE_KEY)
            for invalid in [json.dumps({"unexpected": FAKE_KEY}), FAKE_KEY + "{invalid json}"]:
                self.path.write_text(invalid, encoding="utf-8")
                try:
                    load_model_settings(self.path)
                except ValueError:
                    self.assertNotIn(FAKE_KEY, traceback.format_exc())
                else:
                    self.fail("Invalid model configuration was accepted")

    def test_insecure_urls_and_missing_config_are_rejected(self):
        for url in ["http://example.com/v1", "https://user:password@example.com/v1", "https://example.com?key=value",
                    "https://example.com/#fragment", "https://example.com/v1/chat/completions"]:
            with self.subTest(url=url), self.assertRaises(ValidationError):
                ModelSettings(provider="openai_compatible", base_url=url, model_name="test", api_key=SecretStr(FAKE_KEY))
        with self.assertRaises(ValidationError):
            ModelSettings(provider="openai_compatible")
        self.assertEqual(ModelSettings(provider="openai_compatible", base_url="http://127.0.0.1:8001/v1", model_name="test", api_key=SecretStr(FAKE_KEY)).provider, "openai_compatible")

    def test_log_redaction_covers_interpolation_exceptions_and_restores_factory(self):
        previous = logging.getLogRecordFactory()
        output = io.StringIO()
        logger = logging.getLogger("model-test")
        handler = logging.StreamHandler(output)
        logger.addHandler(handler)
        try:
            with redact_model_secret(FAKE_KEY):
                logger.warning("credential=%s", FAKE_KEY)
                try:
                    raise RuntimeError("provider echoed " + FAKE_KEY)
                except RuntimeError:
                    logger.exception("request failed")
        finally:
            logger.removeHandler(handler)
        self.assertNotIn(FAKE_KEY, output.getvalue())
        self.assertIn("[REDACTED]", output.getvalue())
        self.assertIs(logging.getLogRecordFactory(), previous)
