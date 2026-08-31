# Windows x64 Portable ZIP distribution

EVE Static Map Planner 1.1.0 is distributed for Windows x64 only as:

```text
EVE-Static-Map-Planner-1.1.0-Windows-x64.zip
```

The previous MSI release path is retired. The release does not require WiX, Windows Installer, a system JDK, registry
installation state, shortcuts, or an uninstall entry.

## Portable application image

The archive contains exactly one top-level directory:

```text
EVE Static Map Planner\
├─ EVE Static Map Planner.exe
├─ EVE Map MCP Bridge.exe
├─ eve-map-mcp.exe
├─ app\
│  ├─ app-1.1.0-<hash>.jar
│  ├─ feature-api-2.0.0-<hash>.jar
│  ├─ mcp\
│  └─ packaged resources and runtime dependencies
└─ runtime\
   ├─ bin\
   └─ lib\modules
```

The image is the verified Compose/jpackage application image with the MCP launchers integrated before archiving.
Keep the whole directory together; copying only the GUI EXE is unsupported.

## Program files and user data

The extracted directory is immutable program content and can live under any user-writable location, including paths
with spaces. It contains no mutable user database, preferences, Feature Pack, OAuth credential, control discovery
file, or log.

Mutable data always uses:

```text
%LOCALAPPDATA%\EVE Static Map Planner\
├─ data\static.db
├─ data\user.db
├─ settings.properties
├─ feature-packs\
│  ├─ esi.pack\pack.jar
│  └─ sovereignty.pack\pack.jar
├─ feature-pack-storage\
├─ integration\mcp.json
├─ control\
└─ logs\
```

The managed `static.db` is user data downloaded and built through Static Data Setup; it is not a bundled SDE or an
installer resource. A missing LocalAppData root is created on first launch. Feature Pack discovery may create its
subdirectories lazily.

Moving or copying the program directory continues to use the same LocalAppData root. This is an installer-free
Portable ZIP, not a fully self-contained data-on-USB mode.

## Building

Use JDK 25 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat --no-daemon --console=plain clean build
.\gradlew.bat --no-daemon --console=plain :app:verifyPortableZip
```

`:app:packagePortableZip` depends on the verified integrated app-image and MCP runtime analysis. The ZIP task uses
reproducible file order and omits source timestamps. `:app:verifyPortableZip` then:

1. audits the standard filename and single root directory;
2. verifies the GUI and two MCP launchers;
3. verifies `runtime/lib/modules`, the main JAR, Feature API Host JAR, Skiko runtime, and complete MCP JAR set;
4. rejects source, repository, QA, credential, mutable user-data, and external Pack files;
5. extracts to a build path containing spaces and audits the extracted image again;
6. writes SHA-256, content audit, and release manifest files.

Outputs are placed under:

```text
build\release\
├─ EVE-Static-Map-Planner-1.1.0-Windows-x64.zip
├─ EVE-Static-Map-Planner-1.1.0-Windows-x64.zip.sha256
├─ portable-audit-1.1.0.txt
└─ release-manifest-1.1.0.txt
```

No third-party archive dependency is used.

## Runtime and native access

The ZIP includes a jlink runtime and does not resolve Java from `PATH`, `JAVA_HOME`, or `JDK_HOME`.
The GUI launcher retains `--enable-native-access=ALL-UNNAMED` for JNA/DPAPI and Skiko. DPAPI credentials remain
bound to the current Windows user and computer; do not copy `refresh-token.dpapi` between computers.

## Feature Packs

Feature Packs remain separate release artifacts and are never placed in the main ZIP. Install them at:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\esi.pack\pack.jar
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

Feature API runtime 2 / artifact 2.0.0 is frozen. ESI Pack 1.0.0 and Sovereignty Pack 0.2.0 keep their independent
PackStorage, state, and lifecycle behavior.

## MCP portability

Codex users should use EVE Map Assistant 0.5.0, which connects to the fixed localhost HTTP endpoint hosted by the
running Map at `http://127.0.0.1:27892/mcp`. It requires no PATH entry, absolute executable path, or manual MCP
registration. Moving the complete Portable directory does not change the Plugin configuration: restart the Map and
open a new Codex task.

The application image also includes compatibility launcher `EVE Map MCP Bridge.exe` and stable launcher
`eve-map-mcp.exe` for supported STDIO integrations. Each packaged GUI startup publishes or updates the discovery
locator at:

```text
%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json
```

A locator-aware STDIO client can read that file during session initialization or reconnect, validate schema 1, and
start its absolute `command` directly. After moving the Portable directory, start the map once to update the same
locator. The locator is generated at runtime and is not included in the ZIP.

Legacy/manual MCP client configuration remains tied to an absolute launcher path. For example:

Register an external MCP client with an absolute launcher path, for example:

```powershell
codex mcp add eve-static-map -- "D:\Portable Apps\EVE Static Map Planner\eve-map-mcp.exe"
```

If the directory is moved, deleted, or replaced by a new-version directory, update that manual registration. The map
does not edit any external AI-client configuration. See `mcp-discovery.md` for the locator-aware plugin contract.

## Update and removal

To update:

1. close EVE Static Map Planner and any MCP bridge processes;
2. extract the new ZIP to a new directory;
3. launch and validate the new copy;
4. start the map so its HTTP host is available and its STDIO locator reflects the new directory; update only a
   legacy/manual STDIO registration whose absolute path changed;
5. delete the old program directory.

Do not overwrite a directory while its application or MCP process is running. LocalAppData is retained.

To remove the program, delete the extracted directory. This does not remove LocalAppData. For complete data removal,
Disconnect the ESI Pack first if desired, then manually delete `%LOCALAPPDATA%\EVE Static Map Planner`.

## Release acceptance

Automated acceptance covers clean build, ZIP audit, extraction with spaces, launch from an unrelated working
directory, no-system-Java launch, first-run AppData creation, program-image immutability, user-data preservation,
whole-directory movement, external Feature Packs, DPAPI regression, and the exact 30-tool MCP catalog.

Final human product QA is limited to:

1. extract the final ZIP;
2. double-click the GUI EXE;
3. confirm Feature Packs;
4. Connect EVE;
5. confirm Location;
6. Send Draft to EVE;
7. restart and confirm restoration;
8. Disconnect.

Passing automation means **READY FOR PORTABLE PRODUCT MANUAL QA**, not ready for publication.
