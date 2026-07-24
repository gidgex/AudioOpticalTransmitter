package com.quantum.qbeam.optical

import android.content.Context
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Low-bandwidth photon transmitter using the LED flashlight as on-off keying (OOK).
 *
 * The torch can only be toggled at a modest rate (tens of Hz at best, hardware-dependent),
 * so this is best for short payloads. A camera/light-sensor receiver would sample the
 * brightness; here we expose just the transmit side as a demonstration channel.
 *
 * Bit timing: each bit occupies [bitMillis]. Framing mirrors the audio preamble idea —
 * a run of toggles to let a receiver lock on, then the raw bits MSB-first.
 */
class TorchTransmitter(context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    val isSupported: Boolean get() = cameraId != null

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    private fun set(on: Boolean) {
        val id = cameraId ?: return
        runCatching { cameraManager.setTorchMode(id, on) }
    }

    /** Total bit-slots (for progress + duration estimate): 8 preamble + 1 marker + bits + 1 gap. */
    fun unitsFor(frames: List<ByteArray>): Int = frames.sumOf { 10 + it.size * 8 }

    /**
     * @param onProgress called with (slotsDone, slotsTotal) after every bit-slot so the UI
     *   can show smooth progress — the flashlight is slow, so per-frame updates feel frozen.
     */
    suspend fun transmit(
        frames: List<ByteArray>,
        bitMillis: Long = 60,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.Default) {
        cancelled = false
        val total = unitsFor(frames)
        var done = 0
        suspend fun tick(ms: Long) { delay(ms); done++; onProgress(done, total) }
        try {
            for (frame in frames) {
                if (cancelled) break
                repeat(8) { set(it % 2 == 0); tick(bitMillis); if (cancelled) return@withContext } // preamble
                set(false); tick(bitMillis)                                                          // start marker
                for (b in frame) {
                    var mask = 0x80
                    while (mask != 0) {
                        if (cancelled) return@withContext
                        set((b.toInt() and mask) != 0)
                        tick(bitMillis)
                        mask = mask shr 1
                    }
                }
                set(false); tick(bitMillis * 4) // inter-frame gap
            }
        } finally {
            set(false)
        }
    }
}
