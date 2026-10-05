import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from character_app.config import BACKEND_ROOT

SCRIPT = BACKEND_ROOT.parent / "scripts" / "check_secrets.py"
spec = importlib.util.spec_from_file_location("check_secrets", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class GitGuardTests(unittest.TestCase):
    def setUp(self):
        runtime = (BACKEND_ROOT / ".runtime").resolve()
        runtime.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix="git-guard-test-", dir=runtime)
        self.directory = Path(temp.name).resolve()
        assert self.directory.is_relative_to(runtime)
        self.addCleanup(temp.cleanup)
        self.git("init", "-q")
        self.git("config", "user.name", "Guard Test")
        self.git("config", "user.email", "test@example.invalid")
        # Isolate disposable fixtures from any user's global hooks configuration.
        self.git("config", "core.hooksPath", str(self.directory / "unused-hooks"))

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.directory, stderr=subprocess.DEVNULL).decode().strip()

    def check(self, *args, stdin=None):
        return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.directory, text=True, input=stdin,
                              capture_output=True, timeout=20)

    def test_force_added_private_files_are_blocked_but_examples_are_allowed(self):
        shutil.copyfile(BACKEND_ROOT.parent / ".gitignore", self.directory / ".gitignore")
        for name in ["model.local.json", ".env.local", "trace.log"]:
            (self.directory / name).write_text("private data", encoding="utf-8")
            self.git("check-ignore", name)
            self.git("add", "-f", name)
        self.assertEqual(self.check("--staged").returncode, 1)
        self.git("rm", "--cached", "model.local.json", ".env.local", "trace.log")
        (self.directory / "model.example.json").write_text('{"api_key": ""}', encoding="utf-8")
        self.git("add", "model.example.json")
        self.assertEqual(self.check("--staged").returncode, 0)

    def test_scans_index_instead_of_unstaged_cleanup_and_hides_value(self):
        key = "sk-" + "notarealcredential" * 3
        path = self.directory / "accidental.txt"
        path.write_text(key, encoding="utf-8")
        self.git("add", path.name)
        path.write_text("removed from working copy only", encoding="utf-8")
        result = self.check("--staged")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(key, result.stdout + result.stderr)
        self.git("add", path.name)
        self.assertEqual(self.check("--staged").returncode, 0)

    def test_pre_push_catches_secret_deleted_from_latest_commit(self):
        key = "sk-" + "notarealcredential" * 3
        path = self.directory / "accidental.txt"
        path.write_text(key, encoding="utf-8")
        self.git("add", path.name)
        self.git("commit", "-qm", "temporary unsafe fixture")
        path.write_text("clean head", encoding="utf-8")
        self.git("add", path.name)
        self.git("commit", "-qm", "clean current fixture")
        self.assertEqual(self.check("--staged").returncode, 0)
        oid = self.git("rev-parse", "HEAD")
        result = self.check("--pre-push", stdin=f"refs/heads/main {oid} refs/heads/main {'0' * 40}\n")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(key, result.stdout + result.stderr)

    def test_generic_keys_and_private_key_material_are_detected(self):
        value = b"random-" + b"credential-value"
        self.assertTrue(guard.inspect_content(b'api_key = "' + value + b'"'))
        self.assertTrue(guard.inspect_content(b"-----BEGIN " + b"PRIVATE KEY-----"))
        self.assertEqual(guard.inspect_content(b'api_key = ""'), [])
