# EVE Static Map Planner v1.14.0

This release expands the Embedded AI Assistant with optional web search and configurable voice interaction, adds
English and Simplified Chinese localization, and introduces a global Windows Push-to-Talk shortcut.

## Highlights

### English and Simplified Chinese localization

- Adds runtime switching between English and Simplified Chinese across the Desktop interface.
- Localizes map and route controls, Preferences, AI and voice settings, the AI Assistant, Saved Markers, Shared Map,
  Wormholes, Ansiblex, Mini-map, and static-data management.
- Adds official EVE Simplified Chinese Region names while keeping Solar System names canonical.

### Voice and Push-to-Talk

- Adds configurable speech-to-text and text-to-speech for the Embedded AI Assistant.
- Adds Alibaba Cloud STT and TTS through the Workspace endpoint, including
  `qwen-audio-3.0-asr-flash` and `qwen-audio-3.0-tts-flash`.
- Adds a configurable global Windows Push-to-Talk shortcut that works while Planner is in the background whenever
  the AI Assistant is open.
- Reuses the existing Auto Send behavior: transcripts can either fill the composer or send immediately.
- Pressing Push-to-Talk immediately interrupts current Assistant speech and prevents the interrupted streaming reply
  from resuming.

### Assistant web search

- Adds optional Brave web search to the Embedded AI Assistant.
- Keeps the web-search API key protected with the existing Windows credential-storage flow.

### Voice reliability

- Improves long-response TTS chunking and Markdown-to-speech normalization.
- Corrects Alibaba ASR request handling and signed audio URL processing.
- Improves streaming WAV/PCM handling and Java Sound playback reliability.
- Prevents repeated or overlapping speech chunks while preserving ordered playback.

### Static data and compatibility

- Updates the static database to schema v2 for multilingual Region data.
- Automatically rebuilds an existing managed schema-v1 database to schema v2 with rollback-safe activation.
- Leaves explicitly supplied external old-schema databases unchanged and reports that a compatible database is
  required.

## Upgrade notes

- Existing preferences remain compatible and are migrated to settings version 8 when saved.
- Push-to-Talk has no default shortcut. Configure it under **AI Features → Voice I/O → Voice Input**.
- Global Push-to-Talk is Windows-only and may depend on Windows privilege level or fullscreen behavior.
- Existing managed static databases are rebuilt automatically when schema v2 is required. External database files
  are never overwritten automatically.
- Embedded AI voice features are Desktop-only. The Portable package includes its own runtime and does not require a
  system JDK.

## Download

- Windows x64 Portable ZIP, including the Desktop application and MCP launchers.
