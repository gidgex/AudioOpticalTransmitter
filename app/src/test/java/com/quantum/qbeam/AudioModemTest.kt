package com.quantum.qbeam

import com.quantum.qbeam.audio.AudioConfig
import com.quantum.qbeam.audio.AudioReceiver
import com.quantum.qbeam.audio.AudioTransmitter
import com.quantum.qbeam.core.WavePacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

/**
 * Full transmit -> demodulate loopback for the phonon (audio) channel, run entirely in the
 * JVM. This renders the *actual PCM the speaker would play* and feeds it straight into the
 * real demodulator, proving the modem round-trips (and that the FEC holds under noise).
 */
class AudioModemTest {

    private fun silence(n: Int) = ShortArray(n)

    /** Concatenate leading silence + each rendered frame + an inter-frame gap. */
    private fun buildPcm(cfg: AudioConfig, frames: List<ByteArray>, noise: Double, seed: Long): ShortArray {
        val tx = AudioTransmitter(cfg)
        val gap = cfg.symbolSamples * 6
        val parts = ArrayList<ShortArray>()
        parts.add(silence(gap))
        for (f in frames) { parts.add(tx.renderFrame(f)); parts.add(silence(gap)) }
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        if (noise > 0.0) {
            val rng = Random(seed)
            for (i in out.indices) {
                val n = (rng.nextDouble() * 2 - 1) * noise * Short.MAX_VALUE
                out[i] = (out[i] + n).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    private fun roundTrip(cfg: AudioConfig, msg: WavePacket.Message, noise: Double): WavePacket.Message? {
        val frames = WavePacket.encode(msg, msgId = 99, chunkSize = 32)
        val pcm = buildPcm(cfg, frames, noise, seed = 1)
        val rx = AudioReceiver(cfg)
        val reasm = WavePacket.Reassembler()
        var result: WavePacket.Message? = null
        for (f in rx.decodeBuffer(pcm)) reasm.offer(f)?.let { result = it }
        return result
    }

    @Test fun loopback_clean_4fsk() {
        val cfg = AudioConfig() // default 4-FSK
        val msg = WavePacket.Message(
            WavePacket.DataType.TEXT, "m.txt", "text/plain",
            "Hello QBeam — phonon channel works!".toByteArray()
        )
        val out = roundTrip(cfg, msg, noise = 0.0)
        assertNotNull("clean loopback produced no message", out)
        assertEquals(msg.name, out!!.name)
        assertArrayEquals(msg.data, out.data)
    }

    @Test fun loopback_recovers_under_noise() {
        val cfg = AudioConfig()
        val msg = WavePacket.Message(
            WavePacket.DataType.TEXT, "n.txt", "text/plain",
            "noisy entanglement test".toByteArray()
        )
        // additive white noise at 8% of full scale — FEC should still recover the payload
        val out = roundTrip(cfg, msg, noise = 0.08)
        assertNotNull("noisy loopback produced no message", out)
        assertArrayEquals(msg.data, out!!.data)
    }

    @Test fun loopback_with_reverb_offset_and_noise() {
        // Mirrors the real-device case: tones arrive clearly but with a non-symbol-aligned
        // start, room reverb (a delayed echo) and noise. Timing recovery must lock the phase.
        val cfg = AudioConfig()
        val msg = WavePacket.Message(
            WavePacket.DataType.TEXT, "r.txt", "text/plain",
            "reverberant timing-recovery test".toByteArray()
        )
        val frames = WavePacket.encode(msg, msgId = 99, chunkSize = 32)
        var pcm = buildPcm(cfg, frames, noise = 0.03, seed = 5)

        // 1) shift by a non-symbol-aligned offset (the coarse detector won't be on a boundary)
        val offset = 137
        pcm = ShortArray(pcm.size + offset).also { System.arraycopy(pcm, 0, it, offset, pcm.size) }

        // 2) add reverb: y[i] = x[i] + 0.25*x[i-200]
        val delay = 200; val g = 0.25
        val rev = pcm.copyOf()
        for (i in delay until pcm.size) {
            rev[i] = (pcm[i] + g * pcm[i - delay]).toInt().coerceIn(-32768, 32767).toShort()
        }

        val rx = AudioReceiver(cfg)
        val reasm = WavePacket.Reassembler()
        var out: WavePacket.Message? = null
        for (f in rx.decodeBuffer(rev)) reasm.offer(f)?.let { out = it }
        assertNotNull("reverb/offset loopback produced no message", out)
        assertArrayEquals(msg.data, out!!.data)
    }

    @Test fun loopback_distinct_payloads_differ_on_air() {
        // Sanity check that different text really does produce different audio (it does;
        // it's just hard to hear at 100 baud). Compare the rendered data symbols.
        val cfg = AudioConfig()
        val tx = AudioTransmitter(cfg)
        val a = tx.renderFrame(WavePacket.encode(
            WavePacket.Message(WavePacket.DataType.TEXT, "a", "text/plain", "AAAA".toByteArray()),
            1, 32)[1])
        val b = tx.renderFrame(WavePacket.encode(
            WavePacket.Message(WavePacket.DataType.TEXT, "b", "text/plain", "ZZZZ".toByteArray()),
            1, 32)[1])
        var differing = 0
        for (i in a.indices) if (a[i] != b[i]) differing++
        org.junit.Assert.assertTrue("payloads should modulate differently", differing > 0)
    }
}
