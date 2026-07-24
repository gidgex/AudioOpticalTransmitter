package com.quantum.qbeam.optical

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.quantum.qbeam.core.WavePacket

/**
 * Experimental photon receiver that pairs with [TorchTransmitter]: it watches the ambient
 * **light sensor** and recovers the on-off-keyed (OOK) bitstream from the other phone's
 * flashlight.
 *
 * ⚠️ Hard physical limit: ambient-light sensors report slowly and are heavily smoothed
 * (often only ~5–50 Hz, sometimes less). That caps the usable bit rate to a few bits/sec,
 * so the torch must use a *large* [bitMillis] (≈250 ms) and payloads must be tiny. This is
 * a proof-of-concept channel; the QR (camera) path is the practical optical link.
 *
 * Algorithm: adaptive threshold on buffered (time, lux) samples → binarize → find an
 * alternating preamble at ~bitMillis spacing → sample each data bit at its centre by
 * nearest-sample lookup → assemble bytes → CRC-checked WavePacket.parseFrame.
 */
class LightReceiver(
    context: Context,
    private val bitMillis: Long = 250,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    val isAvailable: Boolean get() = lightSensor != null

    private data class Sample(val t: Long, val lux: Float)
    private val samples = ArrayDeque<Sample>()
    private val maxFrameBytes = WavePacket.HEADER_LEN + 16 + WavePacket.CRC_LEN
    private val windowMs get() = (maxFrameBytes * 8 + 32) * bitMillis

    private var onFrame: ((WavePacket.Frame) -> Unit)? = null
    private var consumedUntil = 0L

    /** Latest contrast (max-min lux), exposed for a UI signal meter. */
    @Volatile var contrast: Float = 0f; private set

    fun start(onFrame: (WavePacket.Frame) -> Unit) {
        this.onFrame = onFrame
        consumedUntil = 0L
        synchronized(samples) { samples.clear() }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        onFrame = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        synchronized(samples) {
            samples.addLast(Sample(now, event.values[0]))
            while (samples.isNotEmpty() && now - samples.first().t > windowMs) samples.removeFirst()
        }
        decode()
    }

    private fun decode() {
        val snap = synchronized(samples) { samples.toList() }
        if (snap.size < 16) return

        val t = LongArray(snap.size) { snap[it].t }
        val v = FloatArray(snap.size) { snap[it].lux }
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (x in v) { if (x < min) min = x; if (x > max) max = x }
        contrast = max - min
        if (contrast < 3f) return // no discernible modulation
        val threshold = (min + max) / 2f

        // binarized edges (transitions), timestamped at the mid-point between samples
        val edges = ArrayList<Long>()
        var prev = v[0] > threshold
        for (i in 1 until snap.size) {
            val lvl = v[i] > threshold
            if (lvl != prev) { edges.add((t[i] + t[i - 1]) / 2); prev = lvl }
        }
        if (edges.size < 7) return

        fun levelAt(time: Long): Int? {
            if (time > t[t.size - 1] || time < t[0]) return null
            var best = 0; var bestD = Long.MAX_VALUE
            for (i in t.indices) {
                val d = kotlin.math.abs(t[i] - time)
                if (d < bestD) { bestD = d; best = i }
            }
            return if (v[best] > threshold) 1 else 0
        }

        val tol = bitMillis / 2
        // look for a preamble: >=6 consecutive edge intervals near bitMillis
        var run = 0
        for (e in 1 until edges.size) {
            val gap = edges[e] - edges[e - 1]
            if (gap in (bitMillis - tol)..(bitMillis + tol)) {
                run++
                if (run >= 6) {
                    val t0 = edges[e]               // transition into the low start-marker
                    if (t0 <= consumedUntil) { continue }
                    val dataStart = t0 + bitMillis  // data begins after the 1-bit low marker

                    fun readByte(k: Int): Int? {
                        var value = 0
                        for (b in 0 until 8) {
                            val centre = dataStart + (k * 8 + b) * bitMillis + bitMillis / 2
                            val bit = levelAt(centre) ?: return null
                            value = (value shl 1) or bit
                        }
                        return value and 0xFF
                    }

                    val header = ByteArray(WavePacket.HEADER_LEN)
                    var ok = true
                    for (i in header.indices) {
                        val byte = readByte(i); if (byte == null) { ok = false; break }
                        header[i] = byte.toByte()
                    }
                    if (ok && header[0] == WavePacket.MAGIC0 && header[1] == WavePacket.MAGIC1) {
                        val payloadLen = ((header[9].toInt() and 0xFF) shl 8) or
                            (header[10].toInt() and 0xFF)
                        if (payloadLen <= 16) {
                            val frameLen = WavePacket.HEADER_LEN + payloadLen + WavePacket.CRC_LEN
                            val full = ByteArray(frameLen)
                            System.arraycopy(header, 0, full, 0, header.size)
                            var done = true
                            for (i in WavePacket.HEADER_LEN until frameLen) {
                                val byte = readByte(i); if (byte == null) { done = false; break }
                                full[i] = byte.toByte()
                            }
                            if (done) {
                                val frame = WavePacket.parseFrame(full)
                                if (frame != null) {
                                    consumedUntil = dataStart + frameLen.toLong() * 8 * bitMillis
                                    onFrame?.invoke(frame)
                                    return
                                }
                            }
                        }
                    }
                    run = 0 // false preamble; keep scanning
                }
            } else run = 0
        }
    }
}
