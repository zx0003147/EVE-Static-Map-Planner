package dev.evestaticmapplanner.ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSoundVoiceAudioPlayerDiagnosticTest {
    @Test
    @EnabledIfEnvironmentVariable(named = LOCAL_WAV_ENVIRONMENT_VARIABLE, matches = ".+")
    fun `plays a known-good local wav through the production audio player only`() {
        val path = Path.of(checkNotNull(System.getenv(LOCAL_WAV_ENVIRONMENT_VARIABLE)))
        assertTrue(Files.isRegularFile(path), "Diagnostic WAV does not exist: $path")
        val wav = Files.readAllBytes(path)
        val expectedPcm = decodeWavToPcm(wav)
        val finished = CountDownLatch(1)
        val completionCount = AtomicInteger()
        val snapshots = mutableListOf<AudioPlayerDebugSnapshot>()

        JavaSoundVoiceAudioPlayer(diagnosticSink = { snapshot ->
            synchronized(snapshots) { snapshots += snapshot }
            println(snapshot.toSafeLogMessage())
        }).use { player ->
            player.playLocalDiagnosticWav(path) {
                completionCount.incrementAndGet()
                finished.countDown()
            }
            assertTrue(
                finished.await(55, TimeUnit.SECONDS),
                "Java Sound Clip did not report playback completion within 55 seconds.",
            )
        }
        val final = synchronized(snapshots) { snapshots.last() }
        assertEquals("FINISHED", final.phase)
        assertEquals(1, completionCount.get())
        assertEquals(wav.size, final.wavByteLength)
        assertEquals(expectedPcm.bytes.size, final.pcmByteLength)
        assertEquals(1, final.clipPcmTransferCount)
        assertEquals(final.pcmByteLength, final.clipPcmTransferByteCount)
        assertEquals(1, final.clipOpenCount)
        assertEquals(1, final.clipStartCount)
        assertEquals(0, final.clipLoopCount)
        assertEquals(1, final.clipStopEventCount)
        assertEquals(1, final.clipCloseCount)
        assertEquals(0, final.activePlayerCount)
        assertEquals(0, final.activeClipCount)
    }

    private companion object {
        const val LOCAL_WAV_ENVIRONMENT_VARIABLE = "EVE_LOCAL_AUDIO_PLAYER_DIAGNOSTIC_WAV"
    }
}
