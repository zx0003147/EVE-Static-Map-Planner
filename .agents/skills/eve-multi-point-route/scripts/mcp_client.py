"""Minimal synchronous STDIO MCP client for the EVE map graph snapshot."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any


MCP_PROTOCOL_VERSION = "2025-03-26"
MCP_COMMAND_ENV = "EVE_MAP_MCP_COMMAND"
PROCESS_EXIT_GRACE_SECONDS = 0.25


class McpClientError(Exception):
    """A stable, user-facing MCP failure."""

    def __init__(self, code: str, message: str, exit_code: int = 3) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.exit_code = exit_code


def _existing_command(value: str, source: str) -> str:
    candidate = Path(os.path.expandvars(value)).expanduser()
    if candidate.is_file():
        return str(candidate.resolve())

    resolved = shutil.which(value)
    if resolved:
        return str(Path(resolved).resolve())

    raise McpClientError(
        "MCP_STARTUP_FAILURE",
        f"The EVE Map MCP command from {source} does not exist: {value}",
    )


def _read_locator(locator: Path) -> str:
    try:
        document = json.loads(locator.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise McpClientError(
            "MCP_STARTUP_FAILURE",
            f"Could not read the EVE Map MCP locator {locator}: {error}",
        ) from error

    if not isinstance(document, dict):
        raise McpClientError("MCP_STARTUP_FAILURE", f"MCP locator is not a JSON object: {locator}")
    if document.get("schemaVersion") != 1:
        raise McpClientError("MCP_STARTUP_FAILURE", f"Unsupported MCP locator schema version: {locator}")
    if document.get("transport") != "stdio":
        raise McpClientError("MCP_STARTUP_FAILURE", f"MCP locator transport is not stdio: {locator}")

    command = document.get("command")
    if not isinstance(command, str) or not command.strip():
        raise McpClientError("MCP_STARTUP_FAILURE", f"MCP locator has no valid command: {locator}")
    return _existing_command(command, f"locator {locator}")


def resolve_mcp_command(explicit_command: str | None = None, explicit_locator: str | None = None) -> str:
    """Resolve the launcher using overrides, the app locator, then PATH."""

    if explicit_command:
        return _existing_command(explicit_command, "--mcp-command")

    environment_command = os.environ.get(MCP_COMMAND_ENV)
    if environment_command:
        return _existing_command(environment_command, MCP_COMMAND_ENV)

    locator: Path | None = None
    if explicit_locator:
        locator = Path(os.path.expandvars(explicit_locator)).expanduser()
        if not locator.is_file():
            raise McpClientError(
                "MCP_STARTUP_FAILURE",
                f"The EVE Map MCP locator from --mcp-locator does not exist: {locator}",
            )
    else:
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            default_locator = Path(local_app_data) / "EVE Static Map Planner" / "integration" / "mcp.json"
            if default_locator.is_file():
                locator = default_locator

    if locator is not None:
        return _read_locator(locator)

    path_command = shutil.which("eve-map-mcp.exe") or shutil.which("eve-map-mcp")
    if path_command:
        return str(Path(path_command).resolve())

    raise McpClientError(
        "MCP_STARTUP_FAILURE",
        "Could not find eve-map-mcp. Start the packaged map to publish its locator, "
        "or use --mcp-command / EVE_MAP_MCP_COMMAND.",
    )


def extract_tool_payload(tool_result: Any) -> dict[str, Any]:
    """Extract and validate structuredContent from one tools/call result."""

    if not isinstance(tool_result, dict):
        raise McpClientError("MALFORMED_GRAPH_RESPONSE", "MCP tools/call result is not an object.", 4)

    if tool_result.get("isError") is True:
        structured = tool_result.get("structuredContent")
        message = "get_normal_route_graph returned an MCP tool error."
        if isinstance(structured, dict):
            error = structured.get("error")
            if isinstance(error, dict) and isinstance(error.get("message"), str):
                message = error["message"]
        raise McpClientError("MCP_GRAPH_CALL_FAILURE", message)

    structured = tool_result.get("structuredContent")
    if not isinstance(structured, dict):
        raise McpClientError(
            "MALFORMED_GRAPH_RESPONSE",
            "get_normal_route_graph did not return object-valued structuredContent.",
            4,
        )
    return structured


class StdioMcpClient:
    """Small sequential JSON-RPC client for one spawned MCP process."""

    def __init__(self, command: str) -> None:
        self.command = command
        self._process: subprocess.Popen[str] | None = None
        self._stderr_file: Any = None
        self._next_id = 1

    def __enter__(self) -> "StdioMcpClient":
        self.start()
        return self

    def __exit__(self, _type: Any, _value: Any, _traceback: Any) -> None:
        self.close()

    def start(self) -> None:
        self._stderr_file = tempfile.TemporaryFile(mode="w+b")
        try:
            self._process = subprocess.Popen(
                [self.command],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=self._stderr_file,
                text=True,
                encoding="utf-8",
                bufsize=1,
            )
        except OSError as error:
            self._stderr_file.close()
            self._stderr_file = None
            raise McpClientError(
                "MCP_STARTUP_FAILURE",
                f"Could not start EVE Map MCP command {self.command}: {error}",
            ) from error

    def initialize(self) -> None:
        try:
            result = self._request(
                "initialize",
                {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "eve-multi-point-route", "version": "1.0"},
                },
            )
            if not isinstance(result.get("protocolVersion"), str):
                raise McpClientError("MCP_INITIALIZE_FAILURE", "MCP initialize response has no protocolVersion.")
            self._notify("notifications/initialized", {})
        except McpClientError as error:
            if error.code == "MCP_INITIALIZE_FAILURE":
                raise
            raise McpClientError("MCP_INITIALIZE_FAILURE", error.message) from error

    def call_graph(self, use_ansiblex: bool) -> dict[str, Any]:
        try:
            result = self._request(
                "tools/call",
                {"name": "get_normal_route_graph", "arguments": {"useAnsiblex": use_ansiblex}},
            )
        except McpClientError as error:
            if error.code in {"MCP_GRAPH_CALL_FAILURE", "MALFORMED_GRAPH_RESPONSE"}:
                raise
            raise McpClientError("MCP_GRAPH_CALL_FAILURE", error.message) from error
        return extract_tool_payload(result)

    def _request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        return self._read_result(request_id)

    def _notify(self, method: str, params: dict[str, Any]) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def _send(self, message: dict[str, Any]) -> None:
        process = self._require_process()
        if process.stdin is None:
            raise McpClientError("MCP_STARTUP_FAILURE", "EVE Map MCP stdin is unavailable.")
        try:
            process.stdin.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
            process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise McpClientError("MCP_STARTUP_FAILURE", f"Could not write to EVE Map MCP: {error}") from error

    def _read_result(self, request_id: int) -> dict[str, Any]:
        process = self._require_process()
        if process.stdout is None:
            raise McpClientError("MCP_STARTUP_FAILURE", "EVE Map MCP stdout is unavailable.")

        while True:
            line = process.stdout.readline()
            if line == "":
                return_code = process.poll()
                if return_code is None:
                    try:
                        return_code = process.wait(timeout=PROCESS_EXIT_GRACE_SECONDS)
                    except subprocess.TimeoutExpired:
                        pass
                details = self._completed_stderr_tail()
                suffix = f" Stderr: {details}" if details else ""
                if return_code is None:
                    raise McpClientError(
                        "MCP_STARTUP_FAILURE",
                        "EVE Map MCP closed stdout before replying while the process is still running.",
                    )
                raise McpClientError(
                    "MCP_STARTUP_FAILURE",
                    f"EVE Map MCP exited before replying (exit code {return_code}).{suffix}",
                )
            if not line.strip():
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError as error:
                raise McpClientError("MALFORMED_GRAPH_RESPONSE", f"MCP emitted invalid JSON: {error}", 4) from error
            if not isinstance(message, dict):
                raise McpClientError("MALFORMED_GRAPH_RESPONSE", "MCP emitted a non-object JSON-RPC message.", 4)

            if "id" not in message:
                continue
            if message.get("id") != request_id:
                raise McpClientError(
                    "MALFORMED_GRAPH_RESPONSE",
                    f"MCP response id {message.get('id')!r} did not match request id {request_id}.",
                    4,
                )
            if "error" in message:
                raise McpClientError("MCP_GRAPH_CALL_FAILURE", f"MCP JSON-RPC error: {message['error']}")
            result = message.get("result")
            if not isinstance(result, dict):
                raise McpClientError("MALFORMED_GRAPH_RESPONSE", "MCP response result is not an object.", 4)
            return result

    def _require_process(self) -> subprocess.Popen[str]:
        if self._process is None:
            raise McpClientError("MCP_STARTUP_FAILURE", "EVE Map MCP process has not been started.")
        return self._process

    def _completed_stderr_tail(self) -> str:
        process = self._process
        if process is None or process.poll() is None or self._stderr_file is None:
            return ""
        try:
            self._stderr_file.seek(0, os.SEEK_END)
            size = self._stderr_file.tell()
            self._stderr_file.seek(max(0, size - 4096))
            return self._stderr_file.read().decode("utf-8", errors="replace").strip()
        except OSError:
            return ""

    def close(self) -> None:
        process = self._process
        if process is not None:
            if process.stdin is not None and not process.stdin.closed:
                try:
                    process.stdin.close()
                except OSError:
                    pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            if process.stdout is not None:
                process.stdout.close()
        if self._stderr_file is not None:
            self._stderr_file.close()
        self._process = None
        self._stderr_file = None


def fetch_normal_route_graph(
    use_ansiblex: bool,
    explicit_command: str | None = None,
    explicit_locator: str | None = None,
) -> dict[str, Any]:
    command = resolve_mcp_command(explicit_command, explicit_locator)
    with StdioMcpClient(command) as client:
        client.initialize()
        return client.call_graph(use_ansiblex)
