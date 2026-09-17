# EVE Static Map Planner 1.13.0

This release adds the complete Embedded AI Assistant V1 to the Windows Desktop application. Embedded AI is
Desktop-only; the Web and self-hosted server products do not gain AI features in this release.

## Highlights

### Built-in AI Assistant

- Adds an in-app, multi-turn AI chat experience with Markdown, multiple chat sessions, New Chat, and Rename Chat.
- Opens quickly from the Aura avatar and keeps the conversation beside the Planner map.
- Supports OpenRouter, OpenAI, Anthropic / Claude, DeepSeek, Google Gemini, and generic OpenAI-compatible providers.
- Lets users bring their own provider API key; OpenRouter is optional.
- Generic OpenAI-compatible configuration supports a custom Base URL, API key, and model.
- Test Connection validates provider connectivity, the selected model, and tool-calling support before normal use.

### Planner-aware Assistance

- Searches systems, retrieves system and marker information, and calculates Normal, Capital, and optimized
  multi-point routes.
- Can focus systems and show route, Capital route, Jump Range, Mission Marker, and Fit results on the map.
- Works with temporary Wormholes, Missions, Saved Markers, Views, and EVE Navigation actions.
- Supports precise Mission edits, including removing or clearing routes, ranges, and markers.
- Includes 33 bounded native Planner tools. The full 8,490-system normal-route graph remains available only through
  the external MCP integration; Embedded AI uses bounded route calculation and optimization tools instead.

### Safety and Credentials

- Protects provider API keys with Windows DPAPI and keeps credentials isolated by provider.
- Separates read-only, temporary UI, persistent write, destructive write, and external actions.
- Requires confirmation for protected actions, rejects prompt-injection attempts to bypass confirmation, and safely
  deduplicates repeated protected requests.

### Desktop Experience

- Adds an AI Features area for the Embedded Assistant and the existing external MCP integration.
- Adds the final Aura shortcut, chat-sidebar controls, and compact 2D/3D toggle polish.
- Keeps the established external MCP integration and its 34-tool catalog unchanged.

## Compatibility

- Embedded AI is available only in the Windows Desktop application.
- Model capabilities vary by provider and model. Use Test Connection to verify tool calling for the selected model.
- Existing Planner data remains under LocalAppData and is preserved when moving to the new Portable release.
- The Portable package includes its own runtime and does not require a system JDK.

## Download

- Windows x64 Portable ZIP, including the Desktop application and MCP launchers.
