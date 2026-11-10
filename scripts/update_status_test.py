"""Updater status polling must survive transient file publication on Windows."""
import sys
import unittest
from unittest.mock import Mock

sys.dont_write_bytecode = True
from update_status import terminal_status


class UpdateStatusTest(unittest.TestCase):
    def test_retries_missing_locked_and_nonterminal_status(self):
        path = Mock()
        path.read_text.side_effect = [FileNotFoundError(), PermissionError(), "state=READY", "", "state=SUCCESS"]
        self.assertEqual(["", "", "", "", "state=SUCCESS"], [terminal_status(path) for _ in range(5)])

    def test_failure_is_terminal_and_unexpected_io_errors_are_not_hidden(self):
        path = Mock()
        path.read_text.return_value = "state=FAILED\nmessage=update failed"
        self.assertEqual(path.read_text.return_value, terminal_status(path))
        path.read_text.side_effect = OSError("disk failure")
        with self.assertRaises(OSError):
            terminal_status(path)
