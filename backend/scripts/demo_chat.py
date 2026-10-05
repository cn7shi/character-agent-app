"""Show fixed replies and a real saved note in a disposable, offline development session."""
import json
from pathlib import Path
import sys
import tempfile
import time
from uuid import uuid4

backend = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(backend / "src"))

from fastapi.testclient import TestClient
from character_app.config import Settings
from character_app.main import create_app


def main():
    # Explicit UTF-8 also keeps redirected output readable on Windows.
    sys.stdout.reconfigure(encoding="utf-8")
    runtime = (backend / ".runtime").resolve()
    runtime.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="demo-", dir=runtime) as temporary:
        directory = Path(temporary).resolve()
        assert directory.is_relative_to(runtime)
        # Explicit Settings selects mock mode, independent of private model configuration.
        with TestClient(create_app(Settings(directory, True)), client=("127.0.0.1", 1)) as client:
            user = json.loads((directory / "development-users.json").read_text(encoding="utf-8"))[0]
            auth = {"Authorization": "Bearer " + user["token"]}
            conversation = client.post("/v1/conversations", headers=auth | {"Idempotency-Key": str(uuid4())}, json={"character_id": "11111111-1111-4111-8111-111111111111"}).json()["id"]
            transcript = []
            for text in ("你好", "今天有点累", "记笔记：周末安排；买咖啡；看电影"):
                response = client.post(f"/v1/conversations/{conversation}/runs", headers=auth | {"Idempotency-Key": str(uuid4())}, json={"client_message_id": str(uuid4()), "text": text})
                assert response.status_code == 202
                run_id = response.json()["id"]
                deadline = time.monotonic() + 5
                while True:
                    run = client.get(f"/v1/runs/{run_id}", headers=auth).json()
                    if run["status"] in {"completed", "cancelled", "failed"}:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Demo task timed out")
                    time.sleep(.02)
                assert run["status"] == "completed", run["error"]
                transcript.append({"input": text, "reply": run["output_message"]["blocks"][0]["text"]})
            notes = client.get("/v1/notes", headers=auth).json()["items"]
            assert len(notes) == 1
            print(json.dumps({"mode": "offline demonstration", "transcript": transcript,
                              "saved_note": {"title": notes[0]["title"], "content": notes[0]["content"]}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
