package com.quantum.qbeam.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quantum.qbeam.audio.AudioConfig
import com.quantum.qbeam.audio.AudioReceiver
import com.quantum.qbeam.audio.AudioTransmitter
import com.quantum.qbeam.optical.LightReceiver
import com.quantum.qbeam.optical.QrCodec
import com.quantum.qbeam.optical.TorchTransmitter
import com.quantum.qbeam.core.Fountain
import com.quantum.qbeam.core.MessageStore
import com.quantum.qbeam.core.StoredMessage
import com.quantum.qbeam.core.WavePacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class Channel(val label: String, val tag: String, val sub: String) {
    PHONON("Phonon", "audio", "audio · speaker ⇄ mic"),
    PHOTON("Photon", "QR·cam", "optical · screen ⇄ camera"),
    TORCH("Torch", "light", "optical · flashlight"),
}

data class TxState(
    val sending: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val qrFrames: List<Bitmap> = emptyList(),
    val qrFps: Int = 5,
)

data class RxState(
    val receiving: Boolean = false,
    val received: WavePacket.Message? = null,
    val receivedBitmap: Bitmap? = null,
    val frames: Int = 0,
)

enum class SelfTestPhase { IDLE, RUNNING, PASS, FAIL }
data class SelfTestState(
    val phase: SelfTestPhase = SelfTestPhase.IDLE,
    val detail: String = "",
)

class QBeamViewModel(app: Application) : AndroidViewModel(app) {

    companion object { const val TORCH_BIT_MS = 250L }

    private val audioCfg = AudioConfig()
    private val audioTx = AudioTransmitter(audioCfg)
    private val audioRx = AudioReceiver(audioCfg)
    private val torchTx = TorchTransmitter(app)
    private val lightRx = LightReceiver(app, bitMillis = TORCH_BIT_MS)
    private val store = MessageStore(app)

    val channel = MutableStateFlow(Channel.PHOTON)
    val status = MutableStateFlow("Idle")

    /** All received messages persisted on this device, newest first. */
    val history = MutableStateFlow<List<StoredMessage>>(emptyList())

    init { history.value = store.list() }

    private val _tx = MutableStateFlow(TxState())
    val tx: StateFlow<TxState> = _tx.asStateFlow()

    private val _rx = MutableStateFlow(RxState())
    val rx: StateFlow<RxState> = _rx.asStateFlow()

    val torchSupported: Boolean get() = torchTx.isSupported
    val lightSupported: Boolean get() = lightRx.isAvailable

    private var txJob: Job? = null
    private var rxJob: Job? = null
    private var nextMsgId = (System.currentTimeMillis() and 0xFFFF).toInt()

    // -------- compose a message --------
    private var pending: WavePacket.Message? = null

    fun setText(text: String) {
        pending = if (text.isBlank()) null else
            WavePacket.Message(WavePacket.DataType.TEXT, "message.txt", "text/plain",
                text.toByteArray(Charsets.UTF_8))
        status.value = pending?.let { "Ready: text (${it.data.size} B)" } ?: "Idle"
    }

    fun setUri(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        var name = "file.bin"
        cr.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { ins ->
            val bos = ByteArrayOutputStream(); ins.copyTo(bos); bos.toByteArray()
        } ?: return
        val type = when {
            mime.startsWith("image/") -> WavePacket.DataType.IMAGE
            mime.startsWith("text/") -> WavePacket.DataType.TEXT
            else -> WavePacket.DataType.FILE
        }
        pending = WavePacket.Message(type, name, mime, bytes)
        status.value = "Ready: $name (${bytes.size} B)"
    }

    // -------- transmit --------
    fun startSend() {
        val msg = pending ?: run { status.value = "Nothing to send"; return }
        val id = nextMsgId++ and 0xFFFF
        txJob?.cancel()
        when (channel.value) {
            Channel.PHONON -> sendAudio(msg, id)
            Channel.PHOTON -> sendQr(msg, id)
            Channel.TORCH -> sendTorch(msg, id)
        }
    }

    private fun sendAudio(msg: WavePacket.Message, id: Int) {
        // Audio carries up to (paddedFrameLen - header - crc) data bytes per frame.
        val frames = WavePacket.encode(msg, id, chunkSize = 96)
        _tx.value = TxState(sending = true, total = frames.size)
        status.value = "Beaming ${frames.size} wave packets over phonon channel…"
        txJob = viewModelScope.launch {
            audioTx.transmit(frames) { cur, tot ->
                _tx.value = _tx.value.copy(progress = cur, total = tot)
            }
            _tx.value = _tx.value.copy(sending = false)
            status.value = "Phonon transmission complete"
        }
    }

    private fun sendQr(msg: WavePacket.Message, id: Int) {
        status.value = "Encoding photon fountain…"
        txJob = viewModelScope.launch {
            val payload = WavePacket.packMessage(msg)
            val enc = Fountain.Encoder(id, payload, blockSize = 256)
            val pool = enc.recommendedPool()
            val bmps = (0 until pool).map { QrCodec.encode(enc.symbol(it), 720) }
            _tx.value = TxState(sending = true, total = bmps.size, qrFrames = bmps)
            status.value = "Cycling ${bmps.size} fountain droplets (K=${enc.k}) — point receiver here"
        }
    }

    private fun sendTorch(msg: WavePacket.Message, id: Int) {
        if (!torchTx.isSupported) { status.value = "No flashlight available"; return }
        val frames = WavePacket.encode(msg, id, chunkSize = 32)
        val units = torchTx.unitsFor(frames)
        val secs = units * TORCH_BIT_MS / 1000
        _tx.value = TxState(sending = true, total = units, progress = 0)
        status.value = "Blinking ${frames.size} packets over torch — ~${secs}s at this speed (very slow channel)…"
        txJob = viewModelScope.launch {
            torchTx.transmit(frames, bitMillis = TORCH_BIT_MS) { done, total ->
                _tx.value = _tx.value.copy(progress = done, total = total)
            }
            _tx.value = _tx.value.copy(sending = false)
            status.value = "Torch transmission complete"
        }
    }

    fun stopSend() {
        audioTx.cancel(); torchTx.cancel(); txJob?.cancel()
        _tx.value = TxState()
        status.value = "Transmission stopped"
    }

    // -------- on-device phonon self-test (speaker -> mic loopback) --------
    private val _selfTest = MutableStateFlow(SelfTestState())
    val selfTest: StateFlow<SelfTestState> = _selfTest.asStateFlow()
    private var selfTestJob: Job? = null

    /**
     * Plays a single known frame several times while recording from the mic, and passes if
     * *any* copy decodes (CRC-valid). Repeating a single frame — rather than requiring a whole
     * multi-frame message in one pass — makes the test tolerant of the odd dropped frame,
     * which is what you'd actually rely on in practice.
     */
    fun runSelfTest() {
        if (_selfTest.value.phase == SelfTestPhase.RUNNING) return
        selfTestJob?.cancel()
        val payload = "QBEAM-SELFTEST".toByteArray()
        val id = nextMsgId++ and 0xFFFF
        val repeats = 3
        val frame = WavePacket.serializeFrame(id, total = 1, index = 0, payload)
        val frames = List(repeats) { frame }
        _selfTest.value = SelfTestState(SelfTestPhase.RUNNING, "Listening + beaming $repeats frames…")
        selfTestJob = viewModelScope.launch {
            // Record raw mic audio for the whole test, then analyse it offline so we can
            // report *why* it failed (no signal vs. distorted signal vs. timing).
            var capturing = true
            val capture = async(Dispatchers.Default) { audioRx.captureRawWhile { capturing } }
            delay(400) // recorder warm-up
            audioTx.transmit(frames) { cur, tot ->
                _selfTest.value = _selfTest.value.copy(detail = "Beaming $cur/$tot…")
            }
            delay(900)
            capturing = false
            val raw = capture.await()

            val stats = audioRx.analyze(raw)
            val decoded = audioRx.decodeBuffer(raw).count { it.payload.contentEquals(payload) }
            val lvl = (stats.peak * 100).toInt()
            val sync = (stats.syncShare * 100).toInt()
            val tone = (stats.toneShare * 100).toInt()
            val nums = "mic level ${lvl}% · sync tone ${sync}% · data tone ${tone}% · frames $decoded"

            _selfTest.value = when {
                decoded > 0 -> SelfTestState(SelfTestPhase.PASS,
                    "PASS — decoded $decoded frame(s) from your own speaker→mic. The modem works here.\n($nums)")
                stats.peak < 0.01 -> SelfTestState(SelfTestPhase.FAIL,
                    "FAIL — the mic isn't hearing the speaker ($nums). Set media volume to max; many phones " +
                    "fire the speaker away from the mic — try cupping a hand to bounce sound back, or play near a wall.")
                stats.syncShare < 0.30 -> SelfTestState(SelfTestPhase.FAIL,
                    "FAIL — sound is getting in but too distorted/quiet to lock ($nums). Raise volume, kill " +
                    "background noise, and keep the phone still.")
                else -> SelfTestState(SelfTestPhase.FAIL,
                    "FAIL — tones are arriving clearly but not decoding ($nums). This is a tuning issue — " +
                    "send me these numbers and I'll adjust the modem.")
            }
        }
    }

    fun clearSelfTest() {
        selfTestJob?.cancel()
        audioTx.cancel()
        _selfTest.value = SelfTestState()
    }

    // -------- receive --------
    private val reassembler = WavePacket.Reassembler()
    private var fountain = Fountain.Decoder()

    fun startReceiveAudio() {
        rxJob?.cancel()
        _rx.value = RxState(receiving = true)
        status.value = "Listening on phonon channel…"
        rxJob = viewModelScope.launch {
            audioRx.receive { frame ->
                onFrame(frame)
            }
        }
    }

    /** For the photon channel, the scanner composable pushes decoded QR payloads here. */
    fun onQrPayload(payload: String) {
        if (!_rx.value.receiving) return
        val bytes = QrCodec.payloadToFrame(payload) ?: return
        val done = fountain.offer(bytes)
        if (done != null) {
            deliverMessage(WavePacket.unpackMessage(done))
        } else {
            val (got, total) = fountain.progress
            _rx.value = _rx.value.copy(frames = got)
            status.value = "Caught droplets: $got/${if (total < 0) "?" else total} blocks"
        }
    }

    fun beginPhotonReceive() {
        fountain = Fountain.Decoder() // fresh decoder per session
        _rx.value = RxState(receiving = true)
        status.value = "Watching for photon fountain…"
    }

    /** Light-sensor receive, paired with the torch (flashlight OOK) transmitter. */
    fun startReceiveLight() {
        if (!lightRx.isAvailable) { status.value = "No ambient light sensor"; return }
        _rx.value = RxState(receiving = true)
        status.value = "Sampling photons via light sensor (point flashlight here)…"
        lightRx.start { frame -> onFrame(frame) }
    }

    private fun onFrame(frame: WavePacket.Frame) {
        _rx.value = _rx.value.copy(frames = _rx.value.frames + 1)
        val msg = reassembler.offer(frame) ?: run {
            status.value = "Captured frame ${frame.index + 1}/${frame.total}"
            return
        }
        deliverMessage(msg)
    }

    private fun deliverMessage(msg: WavePacket.Message) {
        _rx.value = _rx.value.copy(received = msg, receivedBitmap = bitmapFor(msg))
        store.save(msg)                 // persist for later browsing
        history.value = store.list()
        status.value = "Decoded & saved: ${msg.name} (${msg.data.size} B)"
    }

    private fun bitmapFor(msg: WavePacket.Message): android.graphics.Bitmap? =
        if (msg.type == WavePacket.DataType.IMAGE)
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(msg.data, 0, msg.data.size)
            }.getOrNull() else null

    /** Re-open a previously received, stored message for viewing. */
    fun openStored(id: Long) {
        val msg = store.load(id) ?: run { status.value = "Message not found"; return }
        _rx.value = _rx.value.copy(received = msg, receivedBitmap = bitmapFor(msg))
        status.value = "Opened: ${msg.name} (${msg.data.size} B)"
    }

    fun deleteStored(id: Long) {
        store.delete(id)
        history.value = store.list()
        status.value = "Deleted message"
    }

    fun stopReceive() {
        rxJob?.cancel()
        lightRx.stop()
        _rx.value = _rx.value.copy(receiving = false)
        status.value = "Receiver off"
    }

    fun saveReceived(uri: Uri) {
        val msg = _rx.value.received ?: return
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(msg.data)
        }
        status.value = "Saved ${msg.name}"
    }

    override fun onCleared() {
        stopSend(); stopReceive(); selfTestJob?.cancel()
        super.onCleared()
    }
}
