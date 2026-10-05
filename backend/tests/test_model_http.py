"""Real loopback HTTP against a synthetic provider, never a paid external model."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

from character_app.config import BACKEND_ROOT
from character_app.main import create_app


class ProviderHTTPTests(unittest.TestCase):
    def setUp(self):
        runtime = (BACKEND_ROOT / ".runtime").resolve()
        runtime.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix="provider-http-test-", dir=runtime)
        directory = Path(temp.name).resolve()
        assert directory.is_relative_to(runtime)
        self.addCleanup(temp.cleanup)
        self.key = "synthetic-" + secrets.token_hex(24)
        self.requests = []
        self.connection_closed = threading.Event()
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                test.requests.append({"path": self.path, "body": payload,
                                      "authenticated": self.headers.get("Authorization") == "Bearer " + test.key})
                mode = payload["messages"][-1]["content"]
                self.send_response(429 if mode == "rate limit" else 200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                if mode == "rate limit":
                    self.wfile.write(test.key.encode())
                    return
                event = {"choices": [{"index": 0, "delta": {"content": "本机测试回复"}, "finish_reason": None}]}
                self.wfile.write(("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode())
                self.wfile.flush()
                if mode == "wait":
                    self.connection.settimeout(3)
                    if self.connection.recv(1) == b"":
                        test.connection_closed.set()
                    return
                if mode == "break":
                    return
                event["choices"][0].update(delta={}, finish_reason="stop")
                self.wfile.write(("data: " + json.dumps(event) + "\n\ndata: [DONE]\n\n").encode())

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()

        def stop_server():
            server.shutdown()
            server.server_close()
            worker.join(timeout=3)

        self.addCleanup(stop_server)
        config_path = directory / "model.local.json"
        config_path.write_text(json.dumps({"provider": "openai_compatible", "base_url": f"http://127.0.0.1:{server.server_port}/v1",
                                           "model_name": "local-test-provider", "api_key": self.key}), encoding="utf-8")
        env = {"CHARACTER_DEV_AUTH": "1", "CHARACTER_DATA_DIR": str(directory / "app"), "CHARACTER_MODEL_CONFIG_FILE": str(config_path)}
        # Preserve unrelated OS variables needed by Windows asyncio, discard only model overrides.
        import os
        env = {**{k: v for k, v in os.environ.items() if not k.startswith("CHARACTER_")}, **env}
        patcher = patch.dict("os.environ", env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(create_app(), client=("127.0.0.1", 50000))
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)
        user = json.loads((directory / "app" / "development-users.json").read_text(encoding="utf-8"))[0]
        self.auth = {"Authorization": "Bearer " + user["token"]}
        self.conversation = self.client.post("/v1/conversations", headers=self.auth | {"Idempotency-Key": str(uuid4())},
                                            json={"character_id": "11111111-1111-4111-8111-111111111111"}).json()["id"]

    def send(self, text):
        payload = {"client_message_id": str(uuid4()), "text": text}
        headers = self.auth | {"Idempotency-Key": str(uuid4())}
        path = f"/v1/conversations/{self.conversation}/runs"
        response = self.client.post(path, headers=headers, json=payload)
        self.assertEqual(response.status_code, 202)
        return response.json()["id"], path, headers, payload

    def wait_run(self, run_id, *, partial=False):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            response = self.client.get(f"/v1/runs/{run_id}", headers=self.auth)
            self.assertNotIn(self.key, response.text)
            run = response.json()
            if run["status"] in {"completed", "failed", "cancelled"} or (partial and run["output_message"]["blocks"]):
                return run
            time.sleep(.01)
        self.fail("Local provider run timed out")

    def test_configured_provider_round_trip_persistence_and_retry(self):
        run_id, path, headers, payload = self.send("hello")
        run = self.wait_run(run_id)
        self.assertEqual(run["status"], "completed")
        self.assertFalse(run["is_simulated"])
        self.assertEqual(run["model_name"], "local-test-provider")
        self.assertEqual(run["output_message"]["blocks"][0]["text"], "本机测试回复")
        retry = self.client.post(path, headers=headers, json=payload)
        self.assertEqual(retry.status_code, 200)
        self.assertEqual(retry.json()["id"], run_id)
        self.assertEqual(len(self.requests), 1)
        self.assertTrue(self.requests[0]["authenticated"])
        self.assertEqual(self.requests[0]["path"], "/v1/chat/completions")
        for endpoint in [f"/v1/runs/{run_id}/events", f"/v1/conversations/{self.conversation}/messages", "/openapi.json"]:
            response = self.client.get(endpoint, headers=self.auth)
            self.assertEqual(response.status_code, 200)
            self.assertNotIn(self.key, response.text)
            self.assertNotIn("api_key", response.text)

    def test_cancel_releases_actual_tcp_connection(self):
        run_id, *_ = self.send("wait")
        self.wait_run(run_id, partial=True)
        self.client.post(f"/v1/runs/{run_id}/cancel", headers=self.auth)
        self.assertEqual(self.wait_run(run_id)["status"], "cancelled")
        self.assertTrue(self.connection_closed.wait(timeout=2))

    def test_http_failure_and_partial_eof_persist_safe_errors(self):
        for text, code in [("rate limit", "MODEL_RATE_LIMITED"), ("break", "MODEL_STREAM_INTERRUPTED")]:
            run_id, *_ = self.send(text)
            run = self.wait_run(run_id)
            self.assertEqual(run["status"], "failed")
            self.assertEqual(run["error"]["code"], code)
            if text == "break":
                self.assertEqual(run["output_message"]["blocks"][0]["text"], "本机测试回复")
