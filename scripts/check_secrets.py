"""Scan Git objects, never print matched credential values or source lines."""
import argparse
from fnmatch import fnmatch
from pathlib import PurePosixPath
import re
import subprocess
import sys

PATTERNS = {
    "API key format": re.compile(rb"\bsk-[A-Za-z0-9_-]{20,}"),
    "GitHub credential": re.compile(rb"\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})"),
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "literal credential assignment": re.compile(rb'''(?i)["']?(?:[A-Z_]*API_KEY|[A-Z_]*ACCESS_TOKEN|[A-Z_]*SECRET_KEY)["']?\s*[:=]\s*["']([^"'\r\n]{12,})["']'''),
}


def git(*args):
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def private_path(path):
    parts = PurePosixPath(path.lower()).parts
    if any(p in {".runtime", ".venv", "__pycache__", "secrets", "credentials", "logs"} for p in parts):
        return True
    name = parts[-1]
    if name == ".env.example":
        return False
    return any(fnmatch(name, p) for p in (
        ".env", ".env.*", "model.local.json", "model.local.*.json", "*.log", "*.log.*",
        "*.pem", "*.p12", "*.pfx", "*.key", "*.jks", "*.keystore", "keystore.properties",
        "development-users.json", "service.lock", "character.sqlite3*"))


def inspect_content(content):
    reasons = []
    for reason, pattern in PATTERNS.items():
        for match in pattern.finditer(content):
            # Only explicit angle-bracket placeholders are allowed; examples should use empty values.
            if reason == "literal credential assignment" and re.fullmatch(rb"<[A-Z_ -]+>", match[1]):
                continue
            reasons.append(reason)
            break
    return reasons


def index_entries():
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if record:
            info, path = record.split(b"\t", 1)
            mode, oid, stage = info.split()
            if stage != b"0":
                raise ValueError("Unmerged index")
            if mode != b"160000":
                yield oid.decode(), path.decode("utf-8", errors="replace")


def revision_entries(revision):
    for commit in git("rev-list", revision).splitlines():
        for record in git("ls-tree", "-r", "-z", commit.decode()).split(b"\0"):
            if record:
                info, path = record.split(b"\t", 1)
                _, kind, oid = info.split()
                if kind == b"blob":
                    yield oid.decode(), path.decode("utf-8", errors="replace")


def scan(entries):
    seen = set()
    checked_blobs = {}
    problems = []
    for oid, path in entries:
        if (oid, path) in seen:
            continue
        seen.add((oid, path))
        if private_path(path):
            problems.append((path, "private/runtime file"))
        if oid not in checked_blobs:
            checked_blobs[oid] = inspect_content(git("cat-file", "blob", oid))
        problems.extend((path, reason) for reason in checked_blobs[oid])
    for path, reason in sorted(set(problems)):
        print(f"Blocked {path!r}: {reason}. Matched value hidden.", file=sys.stderr)
    if problems:
        print("Remove private files or credentials from the staged change/history before continuing.", file=sys.stderr)
        return 1
    print(f"Secret check passed ({len(seen)} Git file versions).")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--staged", action="store_true")
    modes.add_argument("--history", metavar="REVISION")
    modes.add_argument("--pre-push", action="store_true")
    args = parser.parse_args()
    if args.staged:
        return scan(index_entries())
    if args.history:
        # Resolve revision before passing it to rev-list to prevent option interpretation.
        oid = git("rev-parse", "--verify", "--end-of-options", args.history + "^{commit}").decode().strip()
        return scan(revision_entries(oid))
    refs = []
    for line in sys.stdin:
        fields = line.split()
        if len(fields) != 4 or not re.fullmatch(r"[0-9a-f]{40,64}", fields[1]):
            raise ValueError("Invalid push input")
        if set(fields[1]) != {"0"}:
            refs.append(fields[1])
    # Checking all reachable history also catches a key introduced and deleted before a push.
    return scan(entry for ref in refs for entry in revision_entries(ref))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (subprocess.CalledProcessError, OSError, ValueError):
        print("Secret check could not complete; operation blocked. No file contents were printed.", file=sys.stderr)
        sys.exit(2)
