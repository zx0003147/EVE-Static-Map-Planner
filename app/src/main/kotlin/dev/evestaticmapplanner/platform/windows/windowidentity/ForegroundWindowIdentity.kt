package dev.evestaticmapplanner.platform.windows.windowidentity

import java.time.Instant
import java.util.Locale

internal enum class ForegroundWindowClassification {
    NON_EVE,
    EVE_LAUNCHER,
    EVE_GAME_UNKNOWN_CHARACTER,
    EVE_GAME_CHARACTER,
    UNKNOWN,
}

internal data class WindowSessionIdentity(
    val hwnd: Long,
    val processId: Long,
    val processStartTime: Instant,
)

internal data class ForegroundWindowSnapshot(
    val capturedAt: Instant,
    val hwnd: Long,
    val processId: Long?,
    val threadId: Long?,
    val title: String,
    val className: String,
    val processPath: String?,
    val processName: String?,
    val processStartTime: Instant?,
    val isOwnProcess: Boolean,
    val classification: ForegroundWindowClassification,
    val characterName: String?,
    val reason: String,
) {
    val sessionIdentity: WindowSessionIdentity?
        get() = if (processId != null && processStartTime != null) {
            WindowSessionIdentity(hwnd, processId, processStartTime)
        } else {
            null
        }
}

internal sealed interface CharacterTitleParseResult {
    data class Match(val characterName: String) : CharacterTitleParseResult
    data class NoMatch(val reason: String) : CharacterTitleParseResult
}

/**
 * Strict parser for the observed EVE top-level caption contract. The diagnostic prints the raw
 * caption as well so future client changes can be detected without introducing fuzzy matching.
 */
internal object CharacterTitleParser {
    private const val PREFIX = "EVE - "

    fun parse(rawTitle: String): CharacterTitleParseResult {
        if (rawTitle.isBlank()) return CharacterTitleParseResult.NoMatch("Window title is blank")
        if (rawTitle != rawTitle.trim()) {
            return CharacterTitleParseResult.NoMatch("Window title has unexpected leading or trailing whitespace")
        }
        if (!rawTitle.startsWith(PREFIX)) {
            return CharacterTitleParseResult.NoMatch("Window title does not match the exact 'EVE - Character Name' format")
        }
        val characterName = rawTitle.removePrefix(PREFIX)
        if (characterName.isBlank()) return CharacterTitleParseResult.NoMatch("Character name is blank")
        if (characterName != characterName.trim()) {
            return CharacterTitleParseResult.NoMatch("Character name has unexpected edge whitespace")
        }
        if (characterName.any(Char::isISOControl)) {
            return CharacterTitleParseResult.NoMatch("Character name contains control characters")
        }
        return CharacterTitleParseResult.Match(characterName)
    }
}

internal enum class EveExecutableKind {
    GAME,
    LAUNCHER,
    NON_EVE,
    UNKNOWN,
}

internal data class EveExecutableDecision(
    val kind: EveExecutableKind,
    val reason: String,
)

/** Exact executable names only; window-title text never promotes a process to an EVE process. */
internal object EveExecutableClassifier {
    private val gameExecutables = setOf("exefile.exe")
    private val launcherExecutables = setOf("eve-online.exe", "evelauncher.exe")

    fun classify(processPath: String?, processName: String?): EveExecutableDecision {
        val normalizedName = sequenceOf(processName, processPath?.fileName())
            .filterNotNull()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.lowercase(Locale.ROOT)
            ?: return EveExecutableDecision(EveExecutableKind.UNKNOWN, "Process executable is unavailable")

        return when (normalizedName) {
            in gameExecutables -> EveExecutableDecision(
                EveExecutableKind.GAME,
                "Executable name exactly matches the EVE game client candidate '$normalizedName'",
            )
            in launcherExecutables -> EveExecutableDecision(
                EveExecutableKind.LAUNCHER,
                "Executable name exactly matches the EVE Launcher candidate '$normalizedName'",
            )
            else -> EveExecutableDecision(
                EveExecutableKind.NON_EVE,
                "Executable name '$normalizedName' is not an accepted EVE process",
            )
        }
    }

    private fun String.fileName(): String = replace('/', '\\').substringAfterLast('\\')
}

internal data class ForegroundWindowMetadata(
    val hwnd: Long,
    val windowIsValid: Boolean,
    val processId: Long?,
    val threadId: Long?,
    val title: String,
    val className: String,
    val processPath: String?,
    val processName: String?,
    val processStartTime: Instant?,
)

internal data class ForegroundWindowIdentityDecision(
    val isOwnProcess: Boolean,
    val classification: ForegroundWindowClassification,
    val characterName: String?,
    val reason: String,
)

internal object ForegroundWindowClassifier {
    private const val VERIFIED_GAME_WINDOW_CLASS = "trinityWindow"

    fun classify(metadata: ForegroundWindowMetadata, ownProcessId: Long): ForegroundWindowIdentityDecision {
        if (!metadata.windowIsValid) {
            return ForegroundWindowIdentityDecision(
                isOwnProcess = false,
                classification = ForegroundWindowClassification.UNKNOWN,
                characterName = null,
                reason = "HWND was no longer valid when its metadata was read",
            )
        }
        val isOwnProcess = metadata.processId == ownProcessId
        if (isOwnProcess) {
            return ForegroundWindowIdentityDecision(
                isOwnProcess = true,
                classification = ForegroundWindowClassification.NON_EVE,
                characterName = null,
                reason = "Foreground window belongs to the Planner process",
            )
        }

        val executable = EveExecutableClassifier.classify(metadata.processPath, metadata.processName)
        return when (executable.kind) {
            EveExecutableKind.LAUNCHER -> ForegroundWindowIdentityDecision(
                isOwnProcess = false,
                classification = ForegroundWindowClassification.EVE_LAUNCHER,
                characterName = null,
                reason = executable.reason,
            )
            EveExecutableKind.GAME -> {
                if (!metadata.className.equals(VERIFIED_GAME_WINDOW_CLASS, ignoreCase = true)) {
                    ForegroundWindowIdentityDecision(
                        isOwnProcess = false,
                        classification = ForegroundWindowClassification.UNKNOWN,
                        characterName = null,
                        reason = "${executable.reason}; window class '${metadata.className}' is not the verified " +
                            "EVE game class '$VERIFIED_GAME_WINDOW_CLASS'",
                    )
                } else when (val title = CharacterTitleParser.parse(metadata.title)) {
                    is CharacterTitleParseResult.Match -> ForegroundWindowIdentityDecision(
                        isOwnProcess = false,
                        classification = ForegroundWindowClassification.EVE_GAME_CHARACTER,
                        characterName = title.characterName,
                        reason = "${executable.reason}; verified game window class and exact title format matched",
                    )
                    is CharacterTitleParseResult.NoMatch -> ForegroundWindowIdentityDecision(
                        isOwnProcess = false,
                        classification = ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER,
                        characterName = null,
                        reason = "${executable.reason}; verified game window class matched; ${title.reason}",
                    )
                }
            }
            EveExecutableKind.NON_EVE -> ForegroundWindowIdentityDecision(
                isOwnProcess = false,
                classification = ForegroundWindowClassification.NON_EVE,
                characterName = null,
                reason = executable.reason,
            )
            EveExecutableKind.UNKNOWN -> ForegroundWindowIdentityDecision(
                isOwnProcess = false,
                classification = ForegroundWindowClassification.UNKNOWN,
                characterName = null,
                reason = executable.reason,
            )
        }
    }
}

internal enum class ForegroundWindowEventSource {
    STARTUP_SYNC,
    WIN_EVENT_HOOK,
    WATCHDOG_RESYNC,
}

internal data class ForegroundWindowEvent(
    val hwnd: Long,
    val source: ForegroundWindowEventSource,
    val nativeEventTimeMillis: Long? = null,
)

internal interface ForegroundWindowMonitor : AutoCloseable {
    fun start(
        onForegroundChanged: (ForegroundWindowEvent) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    )
}
