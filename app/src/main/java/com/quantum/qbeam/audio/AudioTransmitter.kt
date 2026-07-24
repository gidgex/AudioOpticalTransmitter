package com.quantum.qbeam.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Phonon transmitter: turns frame bytes into interleaved-RS, M-FSK audio and plays it.
 *
 * On-air structure of one frame:
 *   [preamble: N symbols of sync tone] [data: each symbol -> one of M tones] [tail silence]
 * Each symbol encodes `bitsPerSymbol` bits; tones come from AudioConfig.toneFreqs.
 */
class AudioTransmitter(private val cfg: AudioConfig = AudioConfig()) {

    private val fec = AudioFec(cfg)

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /** Render + play all frames sequentially. Suspends until finished or cancelled. */
    suspend fun transmit(frames: List<ByteArray>, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.Default) {
            cancelled = false
            frames.forEachIndexed { i, frame ->
                if (cancelled) return@withContext
                playBlocking(renderFrame(frame))
                onProgress(i + 1, frames.size)
            }
        }

    /** Pure DSP: render one frame to PCM (exposed for loopback testing). */
    internal fun renderFrame(frame: ByteArray): ShortArray {
        val s = cfg.symbolSamples
        val wire = fec.encode(frame)                 // codewordTotal bytes
        val bps = cfg.bitsPerSymbol
        val nSymbols = cfg.symbolsPerFrame
        // expand wire bytes to a bit list (MSB first), zero-padded to a whole # of symbols
        val bits = IntArray(nSymbols * bps)          // trailing pad bits stay 0
        var bi = 0
        for (byte in wire) {
            val v = byte.toInt() and 0xFF
            for (b in 7 downTo 0) bits[bi++] = (v shr b) and 1
        }
        val out = ShortArray((cfg.preambleSymbols + nSymbols) * s + s)
        var pos = 0
        repeat(cfg.preambleSymbols) { pos = writeTone(out, pos, cfg.freqSync) }
        for (sIdx in 0 until nSymbols) {
            var toneIdx = 0
            for (b in 0 until bps) toneIdx = (toneIdx shl 1) or bits[sIdx * bps + b]
            pos = writeTone(out, pos, cfg.toneFreqs[toneIdx])
        }
        return out // trailing symbol stays silence
    }

    /** Write one symbol of a sine tone with short raised-cosine ramps to avoid clicks. */
    private fun writeTone(out: ShortArray, start: Int, freq: Double): Int {
        val n = cfg.symbolSamples
        val ramp = min(32, n / 8)
        for (i in 0 until n) {
            val env = when {
                i < ramp -> 0.5 * (1 - cos(PI * i / ramp))
                i >= n - ramp -> 0.5 * (1 - cos(PI * (n - i) / ramp))
                else -> 1.0
            }
            val sample = sin(2.0 * PI * freq * (start + i) / cfg.sampleRate) * env * 0.9
            out[start + i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return start + n
    }

    private fun playBlocking(pcm: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            cfg.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, cfg.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, pcm.size * 2), AudioTrack.MODE_STATIC
        )
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = (pcm.size * 1000L / cfg.sampleRate) + 50
            val deadline = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < deadline && !cancelled) Thread.sleep(10)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
