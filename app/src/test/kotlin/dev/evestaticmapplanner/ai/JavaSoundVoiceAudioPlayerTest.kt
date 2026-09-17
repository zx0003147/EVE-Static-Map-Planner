package dev.evestaticmapplanner.ai

import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JavaSoundVoiceAudioPlayerTest {
    @Test
    fun `oversized streaming wav header is decoded to the actual finite pcm payload once`() {
        val pcm = ByteArray(32_000) { index -> (index % 127).toByte() }
        val wav = pcmWav(pcm, declaredDataSize = 0x7fffff9b)

        val decoded = decodeWavToPcm(wav)

        assertContentEquals(pcm, decoded.bytes)
        assertEquals(1_073_741_773, decoded.sourceFrameLength)
        assertEquals(1, decoded.sourceStreamOpenCount)
        assertEquals(0, decoded.sourceStreamResetCount)
        assertTrue(decoded.readCallCount > 0)
        assertEquals(0x7fffffbf, decoded.riffDeclaredSize)
        assertEquals(0x7fffff9b, decoded.dataDeclaredSize)
    }

    @Test
    fun `clip receives one exact pcm transfer and completes one playback instance`() {
        val pcm = ByteArray(48_000) { index -> ((index * 31) % 255).toByte() }
        val wav = pcmWav(pcm, declaredDataSize = 0x7fffff9b)
        val clip = FakeClip()
        val snapshots = mutableListOf<AudioPlayerDebugSnapshot>()
        var finished = 0
        val player = JavaSoundVoiceAudioPlayer(
            clipFactory = JavaSoundClipFactory { clip },
            diagnosticSink = snapshots::add,
        )

        player.play(
            wav,
            VoiceAudioPlaybackContext("known-good:play-1"),
        ) { finished++ }

        assertEquals(1, clip.openCount)
        assertEquals(1, clip.startCount)
        assertContentEquals(pcm, clip.openedPcm)
        assertEquals(1, snapshots.last().activePlayerCount)
        assertEquals(1, snapshots.last().activeClipCount)
        clip.completeNaturally()

        val final = snapshots.last()
        assertEquals("FINISHED", final.phase)
        assertEquals(1, finished)
        assertEquals(1, final.clipPcmTransferCount)
        assertEquals(pcm.size, final.pcmByteLength)
        assertEquals(pcm.size, final.clipPcmTransferByteCount)
        assertEquals(sha256(pcm), final.pcmSha256)
        assertEquals(1, final.clipOpenCount)
        assertEquals(1, final.clipStartCount)
        assertEquals(0, final.clipLoopCount)
        assertEquals(0, final.clipStopCallCount)
        assertEquals(1, final.clipStopEventCount)
        assertEquals(1, final.clipCloseCount)
        assertEquals(0, final.activePlayerCount)
        assertEquals(0, final.activeClipCount)
        assertEquals(final.clipFrameLength, final.clipFramePosition)
        assertEquals(1, snapshots.map(AudioPlayerDebugSnapshot::playerInstanceId).distinct().size)
        assertEquals(1, snapshots.map(AudioPlayerDebugSnapshot::clipInstanceId).distinct().size)
        assertNotEquals(final.wavSha256, final.pcmSha256)
        player.close()
    }

    @Test
    fun `explicit stop closes one clip without rewinding or completing playback`() {
        val clip = FakeClip()
        val snapshots = mutableListOf<AudioPlayerDebugSnapshot>()
        var finished = false
        val player = JavaSoundVoiceAudioPlayer(
            clipFactory = JavaSoundClipFactory { clip },
            diagnosticSink = snapshots::add,
        )

        player.play(pcmWav(ByteArray(320))) { finished = true }
        player.stop()

        assertFalse(finished)
        assertEquals(1, clip.stopCount)
        assertEquals(1, clip.closeCount)
        assertEquals("STOPPED", snapshots.last().phase)
        assertEquals(1, snapshots.last().clipStopCallCount)
        assertEquals(0, snapshots.last().activePlayerCount)
        assertEquals(0, snapshots.last().activeClipCount)
    }

    private class FakeClip : JavaSoundClipHandle {
        override var frameLength: Int = 0
        override var framePosition: Int = 0
        var openCount = 0
        var startCount = 0
        var stopCount = 0
        var closeCount = 0
        var openedPcm = byteArrayOf()
        private var stopListener: (() -> Unit)? = null

        override fun onStop(listener: () -> Unit) {
            stopListener = listener
        }

        override fun open(format: AudioFormat, pcm: ByteArray, offset: Int, length: Int) {
            openCount++
            openedPcm = pcm.copyOfRange(offset, offset + length)
            frameLength = length / format.frameSize
        }

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
            stopListener?.invoke()
        }

        override fun close() {
            closeCount++
        }

        fun completeNaturally() {
            framePosition = frameLength
            stopListener?.invoke()
        }
    }

    private companion object {
        fun pcmWav(pcm: ByteArray, declaredDataSize: Int = pcm.size): ByteArray = ByteArray(44 + pcm.size).also { wav ->
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
            putIntLe(wav, 4, declaredDataSize + 36)
            "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
            putIntLe(wav, 16, 16)
            putShortLe(wav, 20, 1)
            putShortLe(wav, 22, 1)
            putIntLe(wav, 24, 24_000)
            putIntLe(wav, 28, 48_000)
            putShortLe(wav, 32, 2)
            putShortLe(wav, 34, 16)
            "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
            putIntLe(wav, 40, declaredDataSize)
            pcm.copyInto(wav, 44)
        }

        fun putIntLe(target: ByteArray, offset: Int, value: Int) {
            repeat(4) { byte -> target[offset + byte] = (value ushr (byte * 8)).toByte() }
        }

        fun putShortLe(target: ByteArray, offset: Int, value: Int) {
            repeat(2) { byte -> target[offset + byte] = (value ushr (byte * 8)).toByte() }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
