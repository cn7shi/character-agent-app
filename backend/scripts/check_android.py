"""Run native client repository tests against an isolated mock backend; never print credentials."""
import os
from pathlib import Path
import socket
import subprocess
import tempfile

from smoke_server import backend, runtime, server


def main():
    with tempfile.TemporaryDirectory(prefix="android-check-", dir=runtime) as temporary:
        directory = Path(temporary).resolve()
        assert directory.is_relative_to(runtime)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        with server(directory, port) as endpoint:
            (directory / "android-endpoint.txt").write_text(endpoint, encoding="utf-8")
            result = subprocess.run(
                [str(backend.parent / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")),
                 ":core:domain:test", ":core:network:test", ":core:data:testDebugUnitTest", ":app:testDebugUnitTest", "--console=plain", "--rerun-tasks"],
                cwd=backend.parent / "android", env={**os.environ, "CHARACTER_ANDROID_TEST_DIR": str(directory)},
            )
            if result.returncode:
                raise SystemExit(result.returncode)
    print("Android transport, Room, ViewModel and real local backend checks passed; temporary server/data cleaned.")


if __name__ == "__main__":
    main()
