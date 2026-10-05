"""Build the historical schema-1 app with only a test-package overlay; never modify the checkout."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from smoke_server import backend, runtime

BASELINE = "b1f27ae6d91ad444ced71ef85a80a148e66ca2e1"


def main():
    directory = runtime / "device-baseline"
    directory.mkdir(exist_ok=True)
    archive = directory / "source.zip"
    subprocess.run(["git", "archive", "--format=zip", "--output", str(archive), BASELINE, "android"], cwd=backend.parent, check=True)
    source = directory / "source"
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if not (source / entry.filename).resolve().is_relative_to(source.resolve()):
                raise RuntimeError("Archive entry escaped the fixture directory")
        bundle.extractall(source)
    android = source / "android"
    gradle = android / "app/build.gradle.kts"
    text = gradle.read_text(encoding="utf-8")
    original = 'applicationId = "com.character.app"'
    if text.count(original) != 1:
        raise RuntimeError("Historical package declaration changed")
    gradle.write_text(text.replace(original, 'applicationId = "com.character.app.devicecheck"'), encoding="utf-8")
    # Machine SDK configuration remains ignored. No schema or production behavior is patched.
    local = backend.parent / "android/local.properties"
    if local.is_file():
        shutil.copyfile(local, android / "local.properties")
    subprocess.run([str(android / ("gradlew.bat" if os.name == "nt" else "gradlew")),
                    ":app:assembleDebug", "--console=plain", "-q"], cwd=android, check=True)
    apk = directory / "app-schema1.apk"
    shutil.copyfile(android / "app/build/outputs/apk/debug/app-debug.apk", apk)
    metadata = {"commit": BASELINE, "schema": 1, "overlay": "applicationId only: com.character.app.devicecheck",
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    (directory / "baseline.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"Schema-1 baseline APK: {apk}")


if __name__ == "__main__":
    main()
