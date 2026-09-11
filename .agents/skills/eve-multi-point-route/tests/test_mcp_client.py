from __future__ import annotations

import io
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

from mcp_client import McpClientError, StdioMcpClient  # noqa: E402


class _RunningProcessWithClosedStdout:
    stdout = io.StringIO("")

    def poll(self) -> None:
        return None

    def wait(self, timeout: float) -> None:
        raise subprocess.TimeoutExpired("eve-map-mcp", timeout)


class McpEofDiagnosisTests(unittest.TestCase):
    def test_closed_stdout_is_not_reported_as_process_exit_when_poll_is_none(self) -> None:
        client = StdioMcpClient("eve-map-mcp")
        client._process = _RunningProcessWithClosedStdout()  # type: ignore[assignment]

        with self.assertRaises(McpClientError) as failure:
            client._read_result(1)

        self.assertIn("closed stdout", failure.exception.message)
        self.assertIn("still running", failure.exception.message)
        self.assertNotIn("exited", failure.exception.message)


if __name__ == "__main__":
    unittest.main()
