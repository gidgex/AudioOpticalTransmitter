package com.quantum.qbeam.audio

import com.quantum.qbeam.core.Interleaver
import com.quantum.qbeam.core.ReedSolomon
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Shared parameters for the phonon (audio) channel.
 *
 * Modulation: **M-FSK**. `toneFreqs` is one tone per symbol value, so each symbol carries
 * log2(toneFreqs.size) bits. The default 4 tones => 2 bits/symbol (4-FSK), doubling the
 * throughput of the original binary FSK at the same baud. Frequencies land on Goertzel
 * bins (bin spacing = sampleRate / symbolSamples) for clean detection.
 *
 * FEC: each frame is padded to `rsBlocks * rsBlockData` bytes, split into `rsBlocks`
 * Reed–Solomon blocks (each RS(rsBlockData+rsBlockParity)), then the codewords are byte-
 * interleaved so bursts spread across blocks. Defaults: 2 blocks × RS(32,24) correcting
 * 4 byte-errors each ⇒ up to ~8 scattered byte-errors, or an 8-byte+ contiguous burst.
 *
 * Defaults: 44.1 kHz, 441 samples/symbol (100 baud → 200 bit/s raw with 4-FSK),
 * sync = 1500 Hz, tones = 1800/2100/2400/2700 Hz.
 */
data class AudioConfig(
    val sampleRate: Int = 44_100,
    val symbolSamples: Int = 441,
    val freqSync: Double = 1500.0,
    val toneFreqs: List<Double> = listOf(1800.0, 2100.0, 2400.0, 2700.0),
    val preambleSymbols: Int = 32,
    val rsBlocks: Int = 2,
    val rsBlockData: Int = 56,   // bytes of data per RS block
    val rsBlockParity: Int = 14, // parity per block -> corrects 7 byte-errors (~10%)
) {
    val bitsPerSymbol: Int get() = Integer.numberOfTrailingZeros(toneFreqs.size) // log2(M)
    val blockCodeword: Int get() = rsBlockData + rsBlockParity
    val paddedFrameLen: Int get() = rsBlocks * rsBlockData
    val codewordTotal: Int get() = rsBlocks * blockCodeword
    val bitsTotal: Int get() = codewordTotal * 8
    // ceil so any M (incl. 8/16-FSK whose bps doesn't divide the bit count) works; the
    // final partial symbol is zero-padded on TX and ignored on RX.
    val symbolsPerFrame: Int get() = (bitsTotal + bitsPerSymbol - 1) / bitsPerSymbol
    val baud: Double get() = sampleRate.toDouble() / symbolSamples

    companion object {
        /** 8-FSK preset: 3 bits/symbol, ~1.5× the throughput of the 4-FSK default. */
        fun eightFsk() = AudioConfig(
            toneFreqs = listOf(
                1800.0, 2100.0, 2400.0, 2700.0, 3000.0, 3300.0, 3600.0, 3900.0
            )
        )
    }
}

/**
 * FEC codec for the phonon channel: frame bytes <-> interleaved RS codeword ("wire") bytes.
 * Both the transmitter and receiver go through here so the two stay in lock-step.
 */
class AudioFec(private val cfg: AudioConfig) {

    /** Frame (<= paddedFrameLen) -> interleaved codeword bytes of length codewordTotal. */
    fun encode(frame: ByteArray): ByteArray {
        require(frame.size <= cfg.paddedFrameLen)
        val padded = frame.copyOf(cfg.paddedFrameLen)
        val cw = ByteArray(cfg.codewordTotal)
        for (b in 0 until cfg.rsBlocks) {
            val block = padded.copyOfRange(b * cfg.rsBlockData, (b + 1) * cfg.rsBlockData)
            val enc = ReedSolomon.encode(block, cfg.rsBlockParity)
            System.arraycopy(enc, 0, cw, b * cfg.blockCodeword, cfg.blockCodeword)
        }
        return Interleaver.interleave(cw, cfg.rsBlocks)
    }

    /**
     * Wire bytes -> recovered padded frame (paddedFrameLen), or null if uncorrectable.
     * @param eraseFlags optional per-wire-byte "low confidence" markers from the demodulator.
     *   Bytes flagged here are passed to RS as *erasures* (twice as cheap to correct as
     *   unknown errors). If a block ends up with more erasures than its parity budget, that
     *   block falls back to errors-only decoding.
     */
    fun decode(wire: ByteArray, eraseFlags: BooleanArray? = null): ByteArray? {
        if (wire.size != cfg.codewordTotal) return null
        val cw = Interleaver.deinterleave(wire, cfg.rsBlocks)
        // Deinterleave the erasure flags the same way (via a 0/1 byte array).
        val eraseCw: BooleanArray? = eraseFlags?.let {
            val asBytes = ByteArray(it.size) { i -> if (it[i]) 1 else 0 }
            Interleaver.deinterleave(asBytes, cfg.rsBlocks).map { v -> v.toInt() != 0 }.toBooleanArray()
        }
        val data = ByteArray(cfg.paddedFrameLen)
        for (b in 0 until cfg.rsBlocks) {
            val from = b * cfg.blockCodeword
            val blockCw = cw.copyOfRange(from, from + cfg.blockCodeword)
            val erase = eraseCw?.let { flags ->
                val pos = ArrayList<Int>()
                for (i in 0 until cfg.blockCodeword) if (flags[from + i]) pos.add(i)
                if (pos.size > cfg.rsBlockParity) IntArray(0) else pos.toIntArray()
            } ?: IntArray(0)
            val dec = ReedSolomon.decode(blockCw, cfg.rsBlockParity, erase) ?: return null
            System.arraycopy(dec, 0, data, b * cfg.rsBlockData, cfg.rsBlockData)
        }
        return data
    }
}

/** Goertzel single-frequency power estimator over a fixed-length block. */
class Goertzel(sampleRate: Int, targetFreq: Double, private val n: Int) {
    private val coeff: Double
    init {
        val k = Math.round(n * targetFreq / sampleRate).toDouble()
        val omega = 2.0 * Math.PI * k / n
        coeff = 2.0 * cos(omega)
    }

    /** Normalized magnitude over samples[off, off+n). */
    fun magnitude(samples: ShortArray, off: Int): Double {
        var s0: Double; var s1 = 0.0; var s2 = 0.0
        val end = off + n
        var i = off
        while (i < end) {
            s0 = coeff * s1 - s2 + (samples[i] / 32768.0)
            s2 = s1; s1 = s0; i++
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return sqrt(if (power < 0) 0.0 else power) / n
    }
}
