"""Regression checks for cleanup, including a failed check process and locked files."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
import test_workspace


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="voxelbrew-cleanup-test-"))
        self.addCleanup(test_workspace.cleanup, self.root)

    def test_keeps_evidence_and_removes_runtime_copies(self):
        work = self.root / "work"
        (work / "installation").mkdir(parents=True)
        (work / "installation/runtime.jar").write_bytes(b"runtime")
        (work / "commands.json").write_text("[]")
        evidence = self.root / "evidence"
        test_workspace.finish_workspace(work, evidence, ("installation",))
        self.assertFalse(work.exists())
        self.assertEqual("[]", (evidence / "commands.json").read_text())
        self.assertFalse((evidence / "installation").exists())

    def test_cleanup_failure_logs_error_without_raising(self):
        with patch("test_workspace.shutil.rmtree", side_effect=PermissionError("locked")):
            with self.assertLogs(test_workspace.LOG, level="ERROR") as logs:
                test_workspace.cleanup(self.root)
        self.assertIn(str(self.root), logs.output[0])

    def test_per_file_failure_is_logged_without_raising(self):
        def denied(path, onerror):
            onerror(os.unlink, str(path / "locked"), (PermissionError, PermissionError("locked"), None))
        with patch("test_workspace.shutil.rmtree", side_effect=denied):
            with self.assertLogs(test_workspace.LOG, level="ERROR"):
                test_workspace.cleanup(self.root)

    def test_failing_check_still_cleans_and_preserves_exit_code(self):
        script = ("from test_workspace import temporary_workspace; "
                  "import pathlib,sys; "
                  "p=temporary_workspace(pathlib.Path(sys.argv[1]), 'child '); "
                  "(p/'data').write_text('test'); raise RuntimeError('original failure')")
        result = subprocess.run([sys.executable, "-B", "-c", script, str(self.root)],
                                cwd=Path(__file__).parent, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("original failure", result.stderr)
        self.assertEqual([], list(self.root.iterdir()))


if __name__ == "__main__":
    unittest.main()
