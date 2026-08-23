#!/usr/bin/env python3
"""Static contract checks for the repo-local EVE Map Assistant plugin."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


EXPECTED_TOOLS = [
    "search_system",
    "get_system_info",
    "calculate_normal_route",
    "calculate_capital_route",
    "get_active_missions",
    "get_mission",
    "begin_mission",
    "focus_system",
    "show_normal_route",
    "show_capital_route",
    "remove_mission_route",
    "clear_mission_routes",
    "show_jump_range",
    "remove_jump_range",
    "clear_mission_jump_ranges",
    "add_mission_marker",
    "remove_mission_marker",
    "clear_mission_markers",
    "fit_mission",
    "clear_mission",
]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def extract_tool_contract(skill: str) -> list[str]:
    match = re.search(r"## Tool contract\s+```text\s+(.*?)\s+```", skill, re.DOTALL)
    require(match is not None, "SKILL.md is missing its tool contract block")
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


def extract_catalog_tools(catalog: str) -> list[str]:
    match = re.search(r"val names = listOf\((.*?)\n\s*\)", catalog, re.DOTALL)
    require(match is not None, "Could not locate McpToolCatalog.names")
    return re.findall(r'"([a-z][a-z0-9_]*)"', match.group(1))


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    plugin = root / "plugins" / "eve-map-assistant"
    skill_dir = plugin / "skills" / "eve-map-assistant"
    skill_path = skill_dir / "SKILL.md"
    openai_yaml_path = skill_dir / "agents" / "openai.yaml"
    manifest_path = plugin / ".codex-plugin" / "plugin.json"
    marketplace_path = root / ".agents" / "plugins" / "marketplace.json"
    cases_path = root / "qa" / "eve-map-assistant-cases.json"
    docs_path = root / "docs" / "ai-map-plugin.md"
    catalog_path = root / "mcp" / "src" / "main" / "kotlin" / "dev" / "evestaticmapplanner" / "mcp" / "McpToolCatalog.kt"

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    marketplace = json.loads(marketplace_path.read_text(encoding="utf-8"))
    cases = json.loads(cases_path.read_text(encoding="utf-8"))["cases"]
    skill = skill_path.read_text(encoding="utf-8")
    openai_yaml = openai_yaml_path.read_text(encoding="utf-8")
    docs = docs_path.read_text(encoding="utf-8")
    catalog = catalog_path.read_text(encoding="utf-8")

    require(manifest["name"] == "eve-map-assistant", "Unexpected plugin name")
    require(manifest["version"] == "0.1.0", "Plugin version must start at 0.1.0")
    require(manifest["skills"] == "./skills/", "Plugin must point to bundled skills")
    require("mcpServers" not in manifest, "Gate B plugin must not bundle a non-portable MCP command")
    require(not (plugin / ".mcp.json").exists(), "Gate B plugin must not contain .mcp.json")

    entries = [entry for entry in marketplace["plugins"] if entry["name"] == manifest["name"]]
    require(len(entries) == 1, "Marketplace must expose the plugin exactly once")
    require(entries[0]["source"]["path"] == "./plugins/eve-map-assistant", "Unexpected marketplace source path")
    require(entries[0]["policy"] == {"installation": "AVAILABLE", "authentication": "ON_INSTALL"}, "Unexpected marketplace policy")

    require('value: "eve-static-map"' in openai_yaml, "Skill must declare the separately registered MCP dependency")
    require("transport:" not in openai_yaml and "url:" not in openai_yaml, "Gate B dependency must not invent a transport or URL")
    require(extract_tool_contract(skill) == EXPECTED_TOOLS, "SKILL.md tool contract must contain exactly the fixed 20 tools")
    require(extract_catalog_tools(catalog) == EXPECTED_TOOLS, "MCP production catalog no longer matches the fixed 20 tools")

    artifact_text = "\n".join([
        skill,
        openai_yaml,
        docs,
        json.dumps(manifest),
        json.dumps(marketplace),
        json.dumps(cases),
    ])
    lowered = artifact_text.lower()
    for forbidden in [
        "session.key",
        "bearer token",
        "localhost:",
        "127.0.0.1:",
        "fc ping",
        "discord parser",
        "static.db",
    ]:
        require(forbidden not in lowered, f"Forbidden private detail or out-of-scope feature found: {forbidden}")
    require(
        re.search(r"(?i)[a-z]:[\\/]+users[\\/]+[^<%$\\/\s]+", artifact_text) is None,
        "Step 4 artifacts must not contain a concrete Windows user profile path",
    )
    require(re.search(r"\b30\d{6}\b", skill) is None, "SKILL.md must not hard-code EVE system IDs")
    require("only through the `eve-static-map` mcp tools" in skill.lower(), "SKILL.md must prohibit non-MCP map control")
    require(
        "never use powershell, cmd, bash, filesystem access, sqlite, curl, or arbitrary http as a fallback" in skill.lower(),
        "SKILL.md must explicitly prohibit shell, filesystem, database, and HTTP fallbacks",
    )
    require("saved markers" in skill.lower() and "ansiblex" in skill.lower(), "SKILL.md must protect user-owned state")

    all_tools = set(EXPECTED_TOOLS)
    case_names = {case["name"] for case in cases}
    require(case_names == {
        "query-only normal route",
        "visual normal mission",
        "visual capital mission",
        "disconnected safety",
        "saved marker protection",
    }, "Behavior contract cases are incomplete")
    for case in cases:
        required = set(case["requiredTools"])
        forbidden = set(case["forbiddenTools"])
        require(required <= all_tools and forbidden <= all_tools, f"{case['name']} declares an unknown tool")
        require(required.isdisjoint(forbidden), f"{case['name']} requires and forbids the same tool")

    require('"<actual install root>\\EVE Map MCP Bridge.exe"' in docs, "Docs must quote the direct path-with-spaces command")
    require("%LOCALAPPDATA%" in docs, "Docs must describe the portable install location")
    require("0.2.0" in docs, "Docs must retain the app prerequisite version")
    require("codex mcp get eve-static-map" in docs, "Docs must include the MCP registration check")
    require("codex plugin add eve-map-assistant@personal" in docs, "Docs must include the plugin install command")

    print("EVE Map Assistant contract validation passed (20 tools, Gate B, 5 behavior cases).")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, json.JSONDecodeError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        raise SystemExit(1)
