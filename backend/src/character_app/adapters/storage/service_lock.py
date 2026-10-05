from pathlib import Path
import os


class ServiceLock:
    """One local executor owns recovery; the OS releases this lock after a crash."""

    def __init__(self, directory: Path):
        self.directory = directory

    def __enter__(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        self.file = (self.directory / "service.lock").open("a+b")
        if self.file.tell() == 0:
            self.file.write(b"\0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError("Another service already owns this data directory; use one worker.") from None
        return self

    def __exit__(self, *args):
        self.file.close()
