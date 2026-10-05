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
    scenarios = parser.add_mutually_exclusive_group()
    scenarios.add_argument("--extended", action="store_true", help="Also verify foreground kill, partial cancellation and a real schema-1 APK upgrade")
    scenarios.add_argument("--characters", action="store_true", help="Run the character create/edit/delete and preserved-chat device scenario")
    args = parser.parse_args()
    adb_path = args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if not adb_path.is_file():
        parser.error("Android SDK platform-tools not found; pass --sdk")
    legacy_apk = runtime / "device-baseline/app-schema1.apk"
    legacy_metadata = None
    if args.extended:
        if not legacy_apk.is_file() or not (legacy_apk.parent / "baseline.json").is_file():
            parser.error("Build the historical APK with build_device_baseline.py before --extended")
        legacy_metadata = json.loads((legacy_apk.parent / "baseline.json").read_text(encoding="utf-8"))
        if legacy_metadata["schema"] != 1 or hashlib.sha256(legacy_apk.read_bytes()).hexdigest() != legacy_metadata["sha256"]:
            raise RuntimeError("Historical APK does not match its build record; rerun build_device_baseline.py")
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
        "scenario": "characters" if args.characters else "extended" if args.extended else "base",
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

    def kill_foreground():
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "run-as", PACKAGE, "rm", "-f", "files/kill-ready.json", "files/kill-clean-exit")
        command = [str(adb_path), "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + "#holdForegroundForKill", RUNNER]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        started = time.monotonic()
        passed = False
        try:
            while True:
                ready = adb("exec-out", "run-as", PACKAGE, "cat", "files/kill-ready.json", check=False, timeout=5)
                if ready.stdout.startswith(b"{"):
                    pid = str(json.loads(ready.stdout)["pid"])
                    break
                if process.poll() is not None or time.monotonic() - started > 35:
                    raise RuntimeError("Foreground checkpoint did not reach the kill barrier")
                time.sleep(0.1)
            if adb("shell", "pidof", PACKAGE).stdout.decode().strip() != pid:
                raise RuntimeError("Foreground process identity changed before kill")
            activities = adb("shell", "dumpsys", "activity", "activities").stdout.decode(errors="replace")
            if not any("ResumedActivity" in line and PACKAGE + "/" in line for line in activities.splitlines()):
                raise RuntimeError("Target app was not the resumed foreground activity")
            (evidence / "07-before-kill.png").write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "files/07-before-kill.png").stdout)
            adb("shell", "am", "force-stop", PACKAGE)
            output, _ = process.communicate(timeout=10)
            (evidence / "foreground-kill.txt").write_text(safe(output.decode(errors="replace")), encoding="utf-8")
            remaining = adb("exec-out", "run-as", PACKAGE, "ls", "files").stdout.splitlines()
            if adb("shell", "pidof", PACKAGE, check=False).stdout.strip() or b"kill-clean-exit" in remaining or b"OK (1 test)" in output:
                raise RuntimeError("Expected abrupt process termination was not observed")
            passed = True
        finally:
            if process.poll() is None:
                adb("shell", "am", "force-stop", PACKAGE, check=False)
                process.communicate(timeout=10)
            report["checkpoints"].append({"name": "foregroundKillBarrier", "passed": passed, "seconds": round(time.monotonic() - started, 2)})
        print("foregroundKillBarrier: PASS", flush=True)

    def verify_replay(base, token, pending, text):
        status, replay = request(base, f"/v1/conversations/{pending['conversationId']}/runs", token=token,
                                 payload={"client_message_id": pending["id"], "text": text}, key=pending["requestKey"])
        if status != 200 or replay["input_message"]["client_message_id"] != pending["id"] or replay["status"] != "completed":
            raise RuntimeError("Original request identity or completed status was not preserved")
        return replay

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
            with server(directory, port, device_fixture=args.extended) as base:
                users = json.loads((directory / "development-users.json").read_text(encoding="utf-8"))
                secrets.extend(user["token"] for user in users)
                config = {"url": base + "/", "tokenA": secrets[0], "tokenB": secrets[1]}
                write_private("device-check.json", json.dumps(config).encode())
                if args.characters:
                    report["network_fault"] = "not injected in this scenario"
                    checkpoint("createCharacterAndChat", ["13-character-editor", "14-created-character"])
                    _, visible = request(base, "/v1/characters", token=secrets[0])
                    own = next(role for role in visible["items"] if role["editable"])
                    _, other = request(base, "/v1/characters", token=secrets[1])
                    if any(role["id"] == own["id"] for role in other["items"]):
                        raise RuntimeError("Private role leaked to another identity")
                    checkpoint("editDeleteCharacterAndContinue", ["15-edited-character", "16-deleted-role-existing-chat"])
                    _, remaining = request(base, "/v1/characters", token=secrets[0])
                    if any(role["id"] == own["id"] for role in remaining["items"]):
                        raise RuntimeError("Deleted role still visible")
                    _, notes = request(base, "/v1/notes", token=secrets[0])
                    if {note["title"] for note in notes["items"]} != {"自建角色笔记", "删除角色后"} or len(notes["items"]) != 2:
                        raise RuntimeError("Existing chat lost its pinned note capability")
                    report["character_management"] = "created; reopened; edited; delete cancelled then confirmed; private; old chat and tool permission retained"
                    report["passed"] = True
                    return  # Context managers and the common finally still clean up and write results.
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
            with server(directory, port, device_fixture=args.extended) as base:
                checkpoint("recoverOriginalRequest", ["06-recovered"])
                verify_replay(base, secrets[0], pending, "断网期间的原请求")
                _, messages = request(base, f"/v1/conversations/{pending['conversationId']}/messages", token=secrets[0])
                if sum(message["client_message_id"] == pending["id"] for message in messages["items"]) != 1:
                    raise RuntimeError("Recovered input message is missing or duplicated")
                _, notes = request(base, "/v1/notes", token=secrets[0])
                if len(notes["items"]) != 1 or notes["items"][0]["title"] != "设备验收":
                    raise RuntimeError("Saved note was lost or duplicated")
                report["original_request_replay"] = "same id/key, HTTP 200, one input message"
                report["persisted_notes"] = 1
                if args.extended:
                    previous_ids = {message["id"] for message in messages["items"]}
                    kill_foreground()
                    checkpoint("recoverForegroundKill", ["08-after-kill"])
                    _, after = request(base, f"/v1/conversations/{pending['conversationId']}/messages", token=secrets[0])
                    if {message["id"] for message in after["items"]} != previous_ids:
                        raise RuntimeError("Restart unexpectedly sent a message")
                    (directory / "hold-stream").touch()
                    checkpoint("cancelPartialNoteThenContinue", ["09-cancel-and-continue"])
                    if not (directory / "hold-started").is_file():
                        raise RuntimeError("Mock never reached the partial-stream barrier")
                    _, history = request(base, f"/v1/conversations/{pending['conversationId']}/messages", token=secrets[0])
                    cancelled_input = next(message for message in history["items"] if any("设备暂停" in block["text"] for block in message["blocks"]))
                    _, cancelled = request(base, f"/v1/runs/{cancelled_input['run_id']}", token=secrets[0])
                    if cancelled["status"] != "cancelled" or not cancelled["output_message"]["blocks"]:
                        raise RuntimeError("Did not cancel a partially generated reply")
                    _, notes = request(base, "/v1/notes", token=secrets[0])
                    if len(notes["items"]) != 2 or sum(note["title"] == "设备暂停" for note in notes["items"]) != 1:
                        raise RuntimeError("Cancellation lost or duplicated a committed note")
                    # The same mock text occurs earlier in this chat. Confirm a NEW run finished,
                    # so a stale bubble cannot make the next-reply device assertion pass by itself.
                    following = next(message for message in history["items"] if message["id"] not in previous_ids
                                     and message["role"] == "user" and any(block["text"] == "今天有点累" for block in message["blocks"]))
                    _, next_run = request(base, f"/v1/runs/{following['run_id']}", token=secrets[0])
                    if next_run["status"] != "completed" or not next_run["output_message"]["blocks"]:
                        raise RuntimeError("The reply after cancellation did not complete")
                    report["foreground_kill"] = "resumed activity; live PID killed before @After; draft restored without sending"
                    report["partial_cancellation"] = "partial output cancelled; committed note retained once; next reply completed"
                    report["notes_after_cancellation"] = len(notes["items"])
            if args.extended:
                # Reset only before installing schema 1. The schema-1 -> schema-2 transition below
                # is an ordinary install -r: no clear, uninstall, SQL injection or DB replacement.
                adb("shell", "am", "force-stop", PACKAGE)
                if b"Success" not in adb("shell", "pm", "clear", PACKAGE).stdout:
                    raise RuntimeError("Cannot reset the owned device-test package")
                if b"Success" not in adb("install", "--no-streaming", "-r", str(legacy_apk)).stdout:
                    raise RuntimeError("Historical APK installation failed")
                adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
                write_private("device-check-owner", b"character-device-check-v1")
                legacy_directory = directory / "legacy"
                legacy_directory.mkdir()
                with server(legacy_directory, port) as base:
                    users = json.loads((legacy_directory / "development-users.json").read_text(encoding="utf-8"))
                    legacy_token = users[0]["token"]
                    secrets.extend(user["token"] for user in users)
                    write_private("device-check.json", json.dumps({"url": base + "/", "tokenA": legacy_token}).encode())
                    checkpoint("prepareLegacyOnline")
                checkpoint("prepareLegacyOffline", ["10-legacy-outbox"])
                before = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/legacy-state.json").stdout)
                if b"Success" not in adb("install", "--no-streaming", "-r", str(apk)).stdout:
                    raise RuntimeError("Upgrade installation failed")
                checkpoint("verifyLegacyMigration", ["11-upgraded-outbox"])
                with server(legacy_directory, port) as base:
                    checkpoint("retryLegacyRequestAfterUpgrade", ["12-upgraded-recovered"])
                    verify_replay(base, legacy_token, before["outbox"][0], "旧版待发送消息")
                report["apk_upgrade"] = {"baseline": legacy_metadata, "tables_preserved": list(before), "schema": "1 -> 2", "original_request_replay": "HTTP 200", "new_draft_persisted": True}
            report["passed"] = True
    except Exception as error:
        report["error"] = safe(str(error))
        raise RuntimeError(report["error"]) from None
    finally:
        # The dedicated app remains installed for inspection, but the plaintext bootstrap is removed.
        cleanup_errors = []
        commands = [
            ("shell", "am", "force-stop", PACKAGE),
            ("shell", "run-as", PACKAGE, "rm", "-f", "files/device-check.json", "files/offline-request.json", "files/kill-ready.json", "files/kill-clean-exit", "files/legacy-state.json", "files/legacy-cipher"),
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
            if set(remaining) & {b"device-check.json", b"offline-request.json", b"kill-ready.json", b"kill-clean-exit", b"legacy-state.json", b"legacy-cipher"}:
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
