package dev.evestaticmapplanner.platform.windows.windowidentity

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundWindowClassifierTest {
    @Test
    fun `EVE game executable and exact title identify a character`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(processPath = "X:\\Fixture\\EVE\\bin64\\exefile.exe", title = "EVE - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.EVE_GAME_CHARACTER, result.classification)
        assertEquals("Character A", result.characterName)
        assertFalse(result.isOwnProcess)
    }

    @Test
    fun `EVE game with blank or unexpected title remains game with unknown character`() {
        val blank = ForegroundWindowClassifier.classify(
            metadata(processName = "exefile.exe", title = ""),
            ownProcessId = OWN_PID,
        )
        val unexpected = ForegroundWindowClassifier.classify(
            metadata(processName = "exefile.exe", title = "EVE Online - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER, blank.classification)
        assertEquals(ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER, unexpected.classification)
        assertNull(blank.characterName)
        assertNull(unexpected.characterName)
    }

    @Test
    fun `launcher is distinguished from game client`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(processName = "eve-online.exe", title = "EVE Online"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.EVE_LAUNCHER, result.classification)
        assertNull(result.characterName)
    }

    @Test
    fun `title text alone never turns another executable into EVE`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(processName = "chrome.exe", title = "EVE - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.NON_EVE, result.classification)
        assertNull(result.characterName)
    }

    @Test
    fun `game executable with the historical unverified window class is not promoted to EVE game`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(
                processName = "exefile.exe",
                className = "triuiScreen",
                title = "EVE - Character A",
            ),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.UNKNOWN, result.classification)
        assertNull(result.characterName)
    }

    @Test
    fun `own process is filtered before title or executable classification`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(processId = OWN_PID, processName = "exefile.exe", title = "EVE - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.NON_EVE, result.classification)
        assertTrue(result.isOwnProcess)
        assertNull(result.characterName)
    }

    @Test
    fun `missing executable metadata is unknown`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(processPath = null, processName = null, title = "EVE - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.UNKNOWN, result.classification)
        assertNull(result.characterName)
    }

    @Test
    fun `invalid HWND is unknown even when stale metadata resembles EVE`() {
        val result = ForegroundWindowClassifier.classify(
            metadata(windowIsValid = false, processName = "exefile.exe", title = "EVE - Character A"),
            ownProcessId = OWN_PID,
        )

        assertEquals(ForegroundWindowClassification.UNKNOWN, result.classification)
        assertNull(result.characterName)
    }

    @Test
    fun `session identity includes handle pid and process start time`() {
        val start = Instant.parse("2026-09-06T01:02:03Z")
        val snapshot = ForegroundWindowSnapshot(
            capturedAt = Instant.EPOCH,
            hwnd = 0x1234,
            processId = 42,
            threadId = 7,
            title = "EVE - Character A",
            className = "trinityWindow",
            processPath = "X:\\Fixture\\EVE\\bin64\\exefile.exe",
            processName = "exefile.exe",
            processStartTime = start,
            isOwnProcess = false,
            classification = ForegroundWindowClassification.EVE_GAME_CHARACTER,
            characterName = "Character A",
            reason = "test",
        )

        assertEquals(WindowSessionIdentity(0x1234, 42, start), snapshot.sessionIdentity)
        assertNull(snapshot.copy(processStartTime = null).sessionIdentity)
    }

    private fun metadata(
        hwnd: Long = 0x1234,
        windowIsValid: Boolean = true,
        processId: Long = 42,
        threadId: Long = 7,
        title: String = "",
        className: String = "trinityWindow",
        processPath: String? = null,
        processName: String? = null,
        processStartTime: Instant? = Instant.parse("2026-09-06T01:02:03Z"),
    ) = ForegroundWindowMetadata(
        hwnd = hwnd,
        windowIsValid = windowIsValid,
        processId = processId,
        threadId = threadId,
        title = title,
        className = className,
        processPath = processPath,
        processName = processName,
        processStartTime = processStartTime,
    )

    private companion object {
        const val OWN_PID = 99L
    }
}
