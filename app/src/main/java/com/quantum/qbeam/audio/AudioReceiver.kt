package com.quantum.qbeam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.quantum.qbeam.core.WavePacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Phonon receiver: records from the mic and demodulates interleaved-RS, M-FSK frames.
 *
 * Strategy:
 *  1. SEARCH: slide a symbol-sized window; when the sync tone dominates for several hops
 *     we have a preamble. Keep sliding until a data tone overtakes it — that transition
 *     marks the first data symbol boundary.
 *  2. LOCK: every frame is a FIXED number of symbols (symbolsPerFrame), so we read exactly
 *     that many, pick the strongest of the M tones per symbol to recover bits, pack them
 *     into the wire bytes, run Reed–Solomon correction, then CRC-check via WavePacket.
 *
 * NOTE: audio links are sensitive to volume, distance, and acoustics. The thresholds here
 * are reasonable starting points; expect to calibrate on-device.
 */
class AudioReceiver(private val cfg: AudioConfig = AudioConfig()) {

    private val n = cfg.symbolSamples
    private val fec = AudioFec(cfg)
    private val gSync = Goertzel(cfg.sampleRate, cfg.freqSync, n)
    private val toneG = cfg.toneFreqs.map { Goertzel(cfg.sampleRate, it, n) }

    // Per-symbol *decision* uses a shorter window centred in the symbol, so reverb / smear
    // at the symbol edges doesn't corrupt the tone estimate. ~2/3 of the symbol, centred.
    private val decLen = (n * 2) / 3
    private val decOffset = (n - decLen) / 2
    private val toneGdec = cfg.toneFreqs.map { Goertzel(cfg.sampleRate, it, decLen) }

    private val symbolsPerFrame = cfg.symbolsPerFrame
    private val hop = maxOf(1, n / 4)

    /**
     * Open an AudioRecord, preferring sources with the *least* DSP. Phone voice pipelines
     * apply AGC / noise suppression / echo cancellation that mangle FSK tones, so we try
     * UNPROCESSED, then VOICE_RECOGNITION (no AGC/AEC), then plain MIC.
     */
    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    private fun openRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, n * 16)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            val r = runCatching {
                AudioRecord(src, cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return AudioRecord(MediaRecorder.AudioSource.MIC, cfg.sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
    }

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    suspend fun receive(onFrame: (WavePacket.Frame) -> Unit) = withContext(Dispatchers.Default) {
        val record = openRecord()
        var buf = ShortArray(n * (cfg.preambleSymbols + symbolsPerFrame + 8))
        var size = 0
        val chunk = ShortArray(n * 4)
        try {
            record.startRecording()
            while (coroutineContext.isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                if (size + read > buf.size) {
                    val keep = buf.size - read
                    System.arraycopy(buf, size - keep, buf, 0, keep)
                    size = keep
                }
                System.arraycopy(chunk, 0, buf, size, read)
                size += read
                size = drain(buf, size, onFrame)
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    /** Pure DSP: demodulate a complete PCM buffer to frames (exposed for loopback testing). */
    internal fun decodeBuffer(samples: ShortArray): List<WavePacket.Frame> {
        val out = ArrayList<WavePacket.Frame>()
        drain(samples, samples.size) { out.add(it) }
        return out
    }

    /** Record raw mic audio for as long as [active] returns true (for the self-test). */
    @SuppressLint("MissingPermission")
    suspend fun captureRawWhile(active: () -> Boolean): ShortArray = withContext(Dispatchers.Default) {
        val record = openRecord()
        val chunks = ArrayList<ShortArray>()
        val chunk = ShortArray(n * 4)
        try {
            record.startRecording()
            while (active() && coroutineContext.isActive) {
                val r = record.read(chunk, 0, chunk.size)
                if (r > 0) chunks.add(chunk.copyOf(r))
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total); var o = 0
        for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
        out
    }

    /** Signal diagnostics for a captured buffer (peak level + best tone energy shares). */
    data class SignalStats(val peak: Double, val syncShare: Double, val toneShare: Double)

    fun analyze(samples: ShortArray): SignalStats {
        if (samples.isEmpty()) return SignalStats(0.0, 0.0, 0.0)
        var peak = 0
        for (s in samples) { val a = abs(s.toInt()); if (a > peak) peak = a }
        var bestSync = 0.0; var bestTone = 0.0
        var pos = 0
        while (pos + n <= samples.size) {
            val sync = gSync.magnitude(samples, pos)
            var dMax = 0.0; var dSum = 0.0
            for (g in toneG) { val v = g.magnitude(samples, pos); dSum += v; if (v > dMax) dMax = v }
            val total = sync + dSum + 1e-12
            if (total > floor) {
                if (sync / total > bestSync) bestSync = sync / total
                if (dMax / total > bestTone) bestTone = dMax / total
            }
            pos += hop
        }
        return SignalStats(peak / 32768.0, bestSync, bestTone)
    }

    // Volume-independent detection: decide by the *share* of energy in a tone, not its
    // absolute magnitude (which depends entirely on mic gain and distance). `floor` only
    // rejects true silence. `dominance` = how much of the total tone energy one tone must
    // hold to count as "present".
    private val floor = 5e-5
    private val dominance = 0.40

    /** Process as many complete frames as are buffered; return the new (compacted) size. */
    private fun drain(buf: ShortArray, size: Int, onFrame: (WavePacket.Frame) -> Unit): Int {
        var pos = 0
        while (pos + n <= size) {
            val sync = gSync.magnitude(buf, pos)
            var dataMax = 0.0; var dataSum = 0.0
            for (g in toneG) { val v = g.magnitude(buf, pos); dataSum += v; if (v > dataMax) dataMax = v }
            val total = sync + dataSum + 1e-12
            // in a preamble: sync tone dominates the spectrum
            if (total > floor && sync / total > dominance && sync > dataMax) {
                var p = pos
                while (p + n <= size) {
                    val s = gSync.magnitude(buf, p)
                    var dMax = 0.0; var dSum = 0.0
                    for (g in toneG) { val v = g.magnitude(buf, p); dSum += v; if (v > dMax) dMax = v }
                    val t = s + dSum + 1e-12
                    // first data symbol: a data tone now dominates instead of sync
                    if (t > floor && dMax / t > dominance && dMax > s) break
                    p += hop
                }
                if (p + n > size) return compact(buf, size, pos) // need more samples
                // Symbol-timing recovery: lock the exact symbol phase before decoding.
                val aligned = refineStart(buf, p, size)
                val frame = tryReadFrame(buf, aligned, size)
                if (frame != null) {
                    onFrame(frame.first)
                    pos = frame.second
                    continue
                } else {
                    // false preamble or corrupt frame; nudge past it and keep scanning
                    pos = p + n
                    continue
                }
            }
            pos += hop
        }
        return compact(buf, size, maxOf(0, size - n))
    }

    /**
     * Symbol-timing recovery. The coarse detector only finds the data start to within a
     * quarter-symbol; here we search ±half a symbol around it and pick the phase that
     * maximises the summed dominant-tone energy over the first several symbols — i.e. the
     * alignment where each decision window sits cleanly inside one symbol. This is what was
     * missing: real (reverberant) audio needs sub-symbol timing that synthetic audio doesn't.
     */
    private fun refineStart(buf: ShortArray, coarse: Int, size: Int): Int {
        val half = n / 2
        val step = maxOf(1, n / 16)
        val k = minOf(24, symbolsPerFrame)
        var bestStart = coarse
        var bestScore = -1.0
        var s = maxOf(0, coarse - half)
        val sEnd = coarse + half
        while (s <= sEnd) {
            if (s + (k - 1) * n + decOffset + decLen > size) break
            var score = 0.0
            for (j in 0 until k) {
                val off = s + j * n + decOffset
                var m = 0.0
                for (g in toneGdec) { val v = g.magnitude(buf, off); if (v > m) m = v }
                score += m
            }
            if (score > bestScore) { bestScore = score; bestStart = s }
            s += step
        }
        return bestStart
    }

    // A symbol is flagged as an erasure when the winning tone barely beats the runner-up;
    // such low-confidence symbols are the most likely to be wrong.
    private val eraseMargin = 0.25

    /** Read exactly symbolsPerFrame symbols, recover + RS-correct the frame. */
    private fun tryReadFrame(buf: ShortArray, start: Int, size: Int): Pair<WavePacket.Frame, Int>? {
        if (start + symbolsPerFrame * n > size) return null // need more samples
        val bps = cfg.bitsPerSymbol
        val bits = IntArray(symbolsPerFrame * bps)
        val symbolErase = BooleanArray(symbolsPerFrame)
        for (sIdx in 0 until symbolsPerFrame) {
            val off = start + sIdx * n + decOffset // centred decision window
            var bestI = 0; var bestM = -1.0; var secondM = -1.0
            for (k in toneGdec.indices) {
                val m = toneGdec[k].magnitude(buf, off)
                if (m > bestM) { secondM = bestM; bestM = m; bestI = k }
                else if (m > secondM) secondM = m
            }
            symbolErase[sIdx] = bestM <= 0.0 || (bestM - secondM) < eraseMargin * bestM
            for (b in 0 until bps) bits[sIdx * bps + b] = (bestI shr (bps - 1 - b)) and 1
        }
        val wire = ByteArray(cfg.codewordTotal)
        val byteErase = BooleanArray(cfg.codewordTotal)
        for (i in wire.indices) {
            var v = 0
            for (b in 0 until 8) {
                val bitIdx = i * 8 + b
                v = (v shl 1) or bits[bitIdx]
                if (symbolErase[bitIdx / bps]) byteErase[i] = true
            }
            wire[i] = v.toByte()
        }
        val frameBytes = fec.decode(wire, byteErase) ?: return null
        val frame = WavePacket.parseFrame(frameBytes) ?: return null
        return frame to (start + symbolsPerFrame * n)
    }

    private fun compact(buf: ShortArray, size: Int, from: Int): Int {
        if (from <= 0) return size
        val keep = size - from
        if (keep > 0) System.arraycopy(buf, from, buf, 0, keep)
        return keep
    }
}
