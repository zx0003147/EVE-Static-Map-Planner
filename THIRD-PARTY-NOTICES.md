# Third-Party Notices

This document identifies third-party material distributed with EVE Static Map Planner. It is not a license for the project's original source. Each component remains under its own terms. Exact license texts that must accompany the distribution are under `legal/`; the bundled Temurin runtime also retains its complete per-module `legal/` tree and `release` metadata.

## Apache License 2.0 family

The following distributed families are licensed under Apache License 2.0. A complete copy is at `legal/apache-2.0/LICENSE.txt`.

- Kotlin runtime 2.3.x and JetBrains annotations 23.0.0
- kotlinx.coroutines 1.10.2/1.11.0, kotlinx.serialization 1.10.0/1.11.0, kotlinx immutable collections 0.5.0, kotlinx-io 0.9.1, kotlinx-datetime 0.7.1, and atomicfu 0.23.2
- JetBrains Compose Multiplatform 1.10.0, Material3 1.9.0-beta03, JetBrains Compose/AndroidX support artifacts, JetBrains Runtime API 1.5.0, and JSpecify 1.0.0
- Skiko 0.9.37.3. Its upstream NOTICE is reproduced at `legal/skiko/NOTICE.txt`.
- Xerial SQLite JDBC 3.53.1.0
- Apache Commons CSV 1.14.1, Commons IO 2.20.0, and Commons Codec 1.19.0
- kotlin-logging 8.0.4

The resolved artifacts and version-specific upstream NOTICE entries were inspected. Skiko's NOTICE attributes code adapted from the Android Open Source Project; Apache Commons NOTICE files contain Apache Software Foundation copyright/attribution only.

## Skiko and Skia native runtime

The Windows application contains `skiko-awt-runtime-windows-x64:0.9.37.3`. The exact Skiko tag is `v0.9.37.3` (commit `ecc1b2e7976a2bb06344e0007f05150ccd02c591`) and selects skia-pack `m138-80d088a-1` (commit `9481c9b3b8e740d240c7f300cf3a0398abbdb052`), built from Skia revision `80d088a`.

Skiko is Apache-2.0. Skia is BSD-3-Clause; its complete notice is at `legal/skia/LICENSE.txt`. The exact Windows native build statically uses Skia's configured copies of Expat, FreeType, HarfBuzz, ICU, libjpeg-turbo, libpng, libwebp, and zlib. These projects use permissive MIT/BSD-style, ICU, libpng, FreeType, and zlib terms. Their license/notice texts, version pins, and authoritative source locations are under `legal/skia/`.

## SQLite

Xerial SQLite JDBC 3.53.1.0 is Apache-2.0 and embeds native SQLite libraries. Legacy Zentus portions retain the BSD-2-Clause notice reproduced at `legal/sqlite-jdbc/LICENSE.zentus.txt`. SQLite itself is dedicated to the public domain; see <https://www.sqlite.org/copyright.html>.

## Model Context Protocol Kotlin SDK

The MCP distribution includes `io.modelcontextprotocol:kotlin-sdk-core-jvm:0.14.0` and `kotlin-sdk-server-jvm:0.14.0`.

The exact `0.14.0` tag records a licensing transition: new and relicensed contributions are Apache-2.0, unrelicensed contributions remain MIT, and project documentation excluding specifications is CC-BY-4.0. The exact tag's complete LICENSE is reproduced verbatim at `legal/mcp-kotlin-sdk/LICENSE.txt`; it governs instead of relying only on the artifact POM's MIT declaration.

## SLF4J

The MCP runtime includes SLF4J API and NOP binding 2.0.17 under the MIT License. The complete copyright and permission notice is at `legal/slf4j/LICENSE.txt`.

## Eclipse Temurin / OpenJDK runtime

The Windows application image contains a jlink runtime built from Eclipse Temurin 25.0.4+7. The runtime is OpenJDK under GPL-2.0 with the Classpath Exception and the OpenJDK Assembly Exception, together with module-specific third-party terms. The release process preserves the runtime's `legal/` tree and `release` metadata, and ships the exact Temurin distribution notice at `legal/temurin/NOTICE.txt`; those files are authoritative for the exact runtime modules shipped.

The tracked WiX template override `app/src/main/jpackage/windows/main.wxs` is a modified copy of:

- repository: <https://github.com/adoptium/jdk25u>
- tag: `jdk-25.0.4+7`
- source path: `src/jdk.jpackage/windows/classes/jdk/jpackage/internal/resources/main.wxs`
- source commit recorded by the exact Temurin build: `a2ce02c38bc9`
- original template SHA-256: `932C805A5B28BF73844540BDF239F3CAAB943F01DBBAEE0160973A00B6B05120`

That upstream file contains no Classpath Exception designation. The modified template is therefore treated separately under GPL-2.0-only. The complete upstream OpenJDK license file is at `legal/openjdk-jpackage/OPENJDK-LICENSE.txt`, and the file-local comment prominently describes project modifications. This treatment applies only to that adapted file; it does not license the project's original source.

Corresponding Source for the adapted template is available at the pinned repository/tag above. Source information for the bundled runtime is retained in its `release`, packaged Temurin notice, and module legal files.

## CCP / EVE Online material and services

CCP/EVE materials are not open-source dependencies and are not licensed by this document. The separate `NOTICE.md` contains the required proprietary/trademark notice and identifies the CCP-derived Keepstar geometry. Runtime-downloaded SDE data, public ESI responses, and Image Server content remain under the current CCP Developer License Agreement and service terms. No SDE archive, generated `static.db`, ESI response cache, or alliance logo is bundled in the Core installer.

The application is unofficial and is not affiliated with or endorsed by CCP hf. The distributor must have accepted and must comply with the then-current CCP Developer License Agreement before sharing a binary.

## Build-only components

Gradle 9.2.1 and its wrapper, Kotlin/Compose build plugins, WiX Toolset 4.0.6, and test frameworks are build/test tools and are not bundled as application runtime libraries. Their source and binary distributions remain under their own licenses.
