"""Repeatable Android device checkpoints using an isolated APK, mock server and temporary identities."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time

from smoke_server import backend, request, runtime, server

ROOT = backend.parent
PACKAGE = "com.character.app.devicecheck"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "com.character.app.DeviceAcceptanceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device; never selects one implicitly")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                        or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    parser.add_argument("--skip-build", action="store_true", help="Use already built deviceCheck APKs")
    args = parser.parse_args()
    adb_path = args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if not adb_path.is_file():
        parser.error("Android SDK platform-tools not found; pass --sdk")
    secrets = []

    def safe(value):
        for secret in secrets:
            value = value.replace(secret, "[REDACTED]")
        return value

    def adb(*parts, data=None, check=True, timeout=150):
        result = subprocess.run([str(adb_path), "-s", args.serial, *parts], input=data,
                                capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"adb {' '.join(parts)} returned {result.returncode}: " + safe(result.stderr.decode("utf-8", errors="replace")))
        return result

    if adb("get-state").stdout.strip() != b"device":
        raise RuntimeError("Selected device is not ready")
    if not args.skip_build:
        subprocess.run([str(ROOT / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")),
                        ":app:assembleDeviceCheck", ":app:assembleDeviceCheckAndroidTest", "-PdeviceChecks=true", "--console=plain", "-q"],
                       cwd=ROOT / "android", check=True)
    apk = ROOT / "android/app/build/outputs/apk/deviceCheck/app-deviceCheck.apk"
    test_apk = ROOT / "android/app/build/outputs/apk/androidTest/deviceCheck/app-deviceCheck-androidTest.apk"
    for path in (apk, test_apk):
        if not path.is_file():
            raise RuntimeError(f"Missing deviceCheck APK: {path.name}")
    # Only this reserved test package may be reset, and only after verifying our ownership marker.
    installed = adb("shell", "pm", "list", "packages", PACKAGE).stdout.splitlines()
    if ("package:" + PACKAGE).encode() in installed:
        marker = adb("exec-out", "run-as", PACKAGE, "cat", "files/device-check-owner", check=False)
        if marker.stdout.strip() != b"character-device-check-v1":
            raise RuntimeError("Existing test package has no harness marker; refusing to reset its data")
        adb("shell", "am", "force-stop", PACKAGE)
        if b"Success" not in adb("shell", "pm", "clear", PACKAGE).stdout:
            raise RuntimeError("Could not reset the dedicated test package")
    for path in (apk, test_apk):
        if b"Success" not in adb("install", "--no-streaming", "-r", str(path)).stdout:
            raise RuntimeError("Test APK installation failed")
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")

    def write_private(name, data):
        # Generated credentials go through stdin directly into app-private storage, never argv/logs.
        assert re.fullmatch(r"[a-z-]+(?:\.json)?", name)
        adb("exec-in", "run-as", PACKAGE, "tee", f"files/{name}", data=data)
        adb("shell", "run-as", PACKAGE, "chmod", "600", f"files/{name}")
        if adb("exec-out", "run-as", PACKAGE, "cat", f"files/{name}").stdout != data:
            raise RuntimeError("Could not write private test configuration")

    write_private("device-check-owner", b"character-device-check-v1")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    evidence = runtime / f"device-evidence-{stamp}"
    evidence.mkdir()
    report = {
        "started_at": stamp, "serial": args.serial, "package": PACKAGE,
        "android": adb("shell", "getprop", "ro.build.version.release").stdout.decode().strip(),
        "api": adb("shell", "getprop", "ro.build.version.sdk").stdout.decode().strip(),
        "model": adb("shell", "getprop", "ro.product.model").stdout.decode().strip(),
        "display": adb("shell", "wm", "size").stdout.decode().strip(),
        "git_base": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree_changed": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "test_apk_sha256": hashlib.sha256(test_apk.read_bytes()).hexdigest(),
        "backend_provider": "mock", "network_fault": "stop isolated backend process, then restart same database",
        "checkpoints": [], "passed": False,
    }

    def checkpoint(name, screenshot=None):
        adb("shell", "am", "force-stop", PACKAGE)
        started = time.monotonic()
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + name, RUNNER, check=False)
        output = safe(result.stdout.decode("utf-8", errors="replace") + result.stderr.decode("utf-8", errors="replace"))
        (evidence / f"{name}.txt").write_text(output, encoding="utf-8")
        passed = result.returncode == 0 and bool(re.search(r"OK \(1 test\)", output)) and "FAILURES" not in output
        report["checkpoints"].append({"name": name, "passed": passed, "seconds": round(time.monotonic() - started, 2)})
        print(f"{name}: {'PASS' if passed else 'FAIL'}", flush=True)
        if not passed:
            raise RuntimeError(f"Checkpoint failed: {name}; see redacted evidence")
        if screenshot:
            for name in screenshot:
                (evidence / (name + ".png")).write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", f"files/{name}.png").stdout)

    reverse = None
    try:
        with tempfile.TemporaryDirectory(prefix="device-server-", dir=runtime) as temporary:
            directory = Path(temporary).resolve()
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            mapping = f"tcp:{port}"
            adb("reverse", "--no-rebind", mapping, mapping)
            reverse = mapping  # Only remove a mapping this invocation actually created.
            with server(directory, port) as base:
                users = json.loads((directory / "development-users.json").read_text(encoding="utf-8"))
                secrets.extend(user["token"] for user in users)
                config = {"url": base + "/", "tokenA": secrets[0], "tokenB": secrets[1]}
                write_private("device-check.json", json.dumps(config).encode())
                checkpoint("connectChatAndSaveDraft", ["01-draft"])
                checkpoint("restoreAndSendWithNewDraft", ["02-next-draft"])
                checkpoint("restoreNewDraftAndClear")
                checkpoint("clearedDraftNoteThemeModeAndIdentity", ["03-note", "04-light-polling"])
            try:
                connection = socket.create_connection(("127.0.0.1", port), timeout=1)
            except OSError:
                report["offline_port_closed"] = True
            else:
                connection.close()
                raise RuntimeError("Isolated backend is still listening; offline evidence would be invalid")
            checkpoint("offlineCacheAndQueue", ["05-offline-queue"])
            pending = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/offline-request.json").stdout)
            with server(directory, port) as base:
                checkpoint("recoverOriginalRequest", ["06-recovered"])
                status, replay = request(base, f"/v1/conversations/{pending['conversationId']}/runs", token=secrets[0],
                                         payload={"client_message_id": pending["id"], "text": "断网期间的原请求"}, key=pending["requestKey"])
                if status != 200 or replay["input_message"]["client_message_id"] != pending["id"] or replay["status"] != "completed":
                    raise RuntimeError("Original request identity or completed status was not preserved")
                _, messages = request(base, f"/v1/conversations/{pending['conversationId']}/messages", token=secrets[0])
                if sum(message["client_message_id"] == pending["id"] for message in messages["items"]) != 1:
                    raise RuntimeError("Recovered input message is missing or duplicated")
                _, notes = request(base, "/v1/notes", token=secrets[0])
                if len(notes["items"]) != 1 or notes["items"][0]["title"] != "设备验收":
                    raise RuntimeError("Saved note was lost or duplicated")
                report["original_request_replay"] = "same id/key, HTTP 200, one input message"
                report["persisted_notes"] = 1
                report["passed"] = True
    except Exception as error:
        report["error"] = safe(str(error))
        raise RuntimeError(report["error"]) from None
    finally:
        # The dedicated app remains installed for inspection, but the plaintext bootstrap is removed.
        cleanup_errors = []
        commands = [
            ("shell", "am", "force-stop", PACKAGE),
            ("shell", "run-as", PACKAGE, "rm", "-f", "files/device-check.json", "files/offline-request.json"),
        ]
        if reverse:
            commands.append(("reverse", "--remove", reverse))
        for command in commands:
            try:
                adb(*command, timeout=15)
            except Exception as error:
                cleanup_errors.append(safe(str(error)))
        try:
            remaining = adb("exec-out", "run-as", PACKAGE, "ls", "files", timeout=15).stdout.splitlines()
            if b"device-check.json" in remaining or b"offline-request.json" in remaining:
                cleanup_errors.append("Private bootstrap cleanup did not complete")
        except Exception as error:
            cleanup_errors.append(safe(str(error)))
        was_passed = report["passed"]
        report["cleanup_complete"] = not cleanup_errors
        if cleanup_errors:
            report["passed"] = False
            report["cleanup_errors"] = cleanup_errors
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        (evidence / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Device evidence: {evidence}", flush=True)
        if was_passed and cleanup_errors:
            raise RuntimeError("Device checks passed, but cleanup failed; see evidence")


if __name__ == "__main__":
    main()
