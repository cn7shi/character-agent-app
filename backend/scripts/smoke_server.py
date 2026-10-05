"""Check real local HTTP, then restart the process and verify persistent state."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import psutil
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler, Request
from uuid import uuid4

backend = Path(__file__).resolve().parents[1]
runtime = (backend / ".runtime").resolve()
runtime.mkdir(exist_ok=True)
opener = build_opener(ProxyHandler({}))


def request(base, path, *, token=None, payload=None, key=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    try:
        response = opener.open(Request(base + path, data=data, headers=headers), timeout=3)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def events(base, run_id, token, after=0, stop_after_update=False):
    headers = {"Authorization": "Bearer " + token, "Last-Event-ID": str(after)}
    result = []
    with opener.open(Request(f"{base}/v1/runs/{run_id}/events", headers=headers), timeout=5) as response:
        for raw in response:
            line = raw.decode("utf-8").strip()
            if line.startswith("data: "):
                event = json.loads(line[6:])
                result.append(event)
                if stop_after_update and event["type"] == "message.updated":
                    break
    return result


@contextmanager
def server(directory, port):
    # Keep smoke tests offline even after the workstation is configured for a paid provider.
    config = directory / "model.local.json"
    config.write_text('{"provider": "mock"}', encoding="utf-8")
    env = {**{k: v for k, v in os.environ.items() if not k.startswith("CHARACTER_MODEL_")},
           "CHARACTER_DATA_DIR": str(directory), "CHARACTER_DEV_AUTH": "1",
           "CHARACTER_MODEL_CONFIG_FILE": str(config), "CHARACTER_MODEL_PROVIDER": "mock"}
    base = f"http://127.0.0.1:{port}"
    with (directory / "server.log").open("a", encoding="utf-8") as log:
        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "character_app.main:create_app", "--factory", "--app-dir", str(backend / "src"), "--host", "127.0.0.1", "--port", str(port), "--no-proxy-headers"],
            cwd=backend, env=env, stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        try:
            deadline = time.monotonic() + 20
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Server exited during startup; inspect the temporary server log")
                try:
                    if request(base, "/health") == (200, {"status": "ok"}):
                        break
                except (URLError, TimeoutError):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("Server startup exceeded 20 seconds")
                time.sleep(0.1)
            yield base
        finally:
            if process.poll() is None:
                # Windows venv Python may be a launcher with a child interpreter. Stopping only
                # the launcher leaves uvicorn alive, invalidating offline tests and holding files.
                if os.name == "nt":
                    parent = psutil.Process(process.pid)
                    owned = parent.children(recursive=True) + [parent]
                    # Native termination is immediate; starting taskkill can let a short mock reply
                    # finish before the intended crash, making the interruption test meaningless.
                    for child in owned:
                        try:
                            child.kill()
                        except psutil.NoSuchProcess:
                            pass  # A child exiting between the snapshot and kill is already stopped.
                    _, alive = psutil.wait_procs(owned, timeout=5)
                    if alive:
                        raise RuntimeError("Owned test server processes did not stop")
                else:
                    process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    with tempfile.TemporaryDirectory(prefix="smoke-", dir=runtime) as temporary:
        directory = Path(temporary).resolve()
        assert directory.is_relative_to(runtime)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        key = str(uuid4())
        with server(directory, port) as base:
            users = json.loads((directory / "development-users.json").read_text(encoding="utf-8"))
            token_a, token_b = users[0]["token"], users[1]["token"]
            assert request(base, "/v1/characters")[0] == 401
            status, catalog = request(base, "/v1/characters", token=token_a)
            assert status == 200 and len(catalog["items"]) == 2
            payload = {"character_id": catalog["items"][0]["id"], "title": "进程重启验证"}
            status, conversation = request(base, "/v1/conversations", token=token_a, payload=payload, key=key)
            assert status == 201
            assert request(base, f"/v1/conversations/{conversation['id']}/messages", token=token_b)[0] == 404
            status, schema = request(base, "/openapi.json")
            assert status == 200 and len(schema["paths"]) == 12
            run_path = f"/v1/conversations/{conversation['id']}/runs"
            run_payload = {"client_message_id": str(uuid4()), "text": "真实连接断流验证"}
            run_key = str(uuid4())
            status, run = request(base, run_path, token=token_a, payload=run_payload, key=run_key)
            assert status == 202 and run["is_simulated"]
            partial = events(base, run["id"], token_a, stop_after_update=True)
            after = partial[-1]["sequence"]
            # Closing a live TCP subscription must not cancel or recreate generation.
            replay = events(base, run["id"], token_a, after)
            assert replay and all(e["sequence"] > after for e in replay)
            assert replay[-1]["type"] == "run.completed"
            assert "模拟回复" in replay[-1]["data"]["output_message"]["blocks"][0]["text"]
            _, cancel_run = request(base, run_path, token=token_a, payload={"client_message_id": str(uuid4()), "text": "取消验证"}, key=str(uuid4()))
            events(base, cancel_run["id"], token_a, stop_after_update=True)
            request(base, f"/v1/runs/{cancel_run['id']}/cancel", token=token_a, payload={})
            assert events(base, cancel_run["id"], token_a)[-1]["type"] == "run.cancelled"
            note_key = str(uuid4())
            note_payload = {"client_message_id": str(uuid4()), "text": "记笔记：本机联调；保存和重启；不调用真实 AI"}
            _, note_run = request(base, run_path, token=token_a, payload=note_payload, key=note_key)
            note_events = events(base, note_run["id"], token_a)
            assert note_events[-1]["type"] == "run.completed"
            tool_event = next(e for e in note_events if e["type"] == "tool.completed")
            note_id = tool_event["data"]["tool_call"]["result"]["note_id"]
            status, note = request(base, f"/v1/notes/{note_id}", token=token_a)
            assert status == 200 and note["title"] == "本机联调"
            assert request(base, f"/v1/notes/{note_id}", token=token_b)[0] == 404
            _, interrupted = request(base, run_path, token=token_a, payload={"client_message_id": str(uuid4()), "text": "进程中断验证"}, key=str(uuid4()))
            events(base, interrupted["id"], token_a, stop_after_update=True)
        with server(directory, port) as base:
            status, conversations = request(base, "/v1/conversations", token=token_a)
            assert status == 200 and [x["id"] for x in conversations["items"]] == [conversation["id"]]
            status, replay = request(base, "/v1/conversations", token=token_a, payload=payload, key=key)
            assert status == 200 and replay["id"] == conversation["id"]
            status, retry = request(base, run_path, token=token_a, payload=run_payload, key=run_key)
            assert status == 200 and retry["id"] == run["id"] and retry["status"] == "completed"
            assert events(base, run["id"], token_a, after)[-1]["type"] == "run.completed"
            _, recovered = request(base, f"/v1/runs/{interrupted['id']}", token=token_a)
            assert recovered["status"] == "failed" and recovered["error"]["code"] == "RUN_INTERRUPTED"
            assert recovered["output_message"]["blocks"]
            _, history = request(base, f"/v1/conversations/{conversation['id']}/messages", token=token_a)
            assert len(history["items"]) == 8
            assert request(base, f"/v1/notes/{note_id}", token=token_a)[1] == note
            status, note_retry = request(base, run_path, token=token_a, payload=note_payload, key=note_key)
            assert status == 200 and note_retry["id"] == note_run["id"]
            assert len(request(base, "/v1/notes", token=token_a)[1]["items"]) == 1
        print(json.dumps({"real_http": "passed", "anonymous_rejected": True, "cross_user_isolation": True, "live_sse_disconnect_and_resume": True, "cancellation": True, "process_restart_persistence": True, "interrupted_run_recovery": True, "retry_after_restart": True, "tool_note_persistence_and_deduplication": True, "server_stopped": True}, indent=2))


if __name__ == "__main__":
    main()
