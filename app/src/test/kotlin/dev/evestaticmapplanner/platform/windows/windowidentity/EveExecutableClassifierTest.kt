package dev.evestaticmapplanner.platform.windows.windowidentity

import kotlin.test.Test
import kotlin.test.assertEquals

class EveExecutableClassifierTest {
    @Test
    fun `game executable is recognized exactly and case insensitively`() {
        assertEquals(
            EveExecutableKind.GAME,
            EveExecutableClassifier.classify("X:\\Fixture\\EVE\\bin64\\EXEFILE.EXE", null).kind,
        )
        assertEquals(
            EveExecutableKind.GAME,
            EveExecutableClassifier.classify(null, "exefile.exe").kind,
        )
    }

    @Test
    fun `current and legacy launcher candidate names are not classified as game`() {
        assertEquals(
            EveExecutableKind.LAUNCHER,
            EveExecutableClassifier.classify(null, "eve-online.exe").kind,
        )
        assertEquals(
            EveExecutableKind.LAUNCHER,
            EveExecutableClassifier.classify(null, "evelauncher.exe").kind,
        )
    }

    @Test
    fun `similar and unrelated executable names are not guessed`() {
        assertEquals(
            EveExecutableKind.NON_EVE,
            EveExecutableClassifier.classify(null, "my-exefile.exe").kind,
        )
        assertEquals(
            EveExecutableKind.NON_EVE,
            EveExecutableClassifier.classify(null, "chrome.exe").kind,
        )
        assertEquals(
            EveExecutableKind.UNKNOWN,
            EveExecutableClassifier.classify(null, null).kind,
        )
    }
}
