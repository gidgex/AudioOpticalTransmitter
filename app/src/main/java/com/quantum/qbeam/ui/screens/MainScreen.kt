package com.quantum.qbeam.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quantum.qbeam.optical.QrScannerView
import com.quantum.qbeam.ui.Channel
import com.quantum.qbeam.ui.QBeamViewModel
import com.quantum.qbeam.ui.SelfTestPhase
import com.quantum.qbeam.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: QBeamViewModel = viewModel()) {
    val channel by vm.channel.collectAsState()
    val status by vm.status.collectAsState()
    var sendMode by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Void)) {
        QuantumBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("QBeam", fontSize = 40.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace, color = QuantumCyan)
            Text("quantum data teleporter", color = Mist, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(16.dp))

            // SEND / RECEIVE toggle
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    .background(VoidLight).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ModeButton("◈ SEND", sendMode, Modifier.weight(1f)) {
                    sendMode = true; vm.stopReceive()
                }
                ModeButton("◇ RECEIVE", !sendMode, Modifier.weight(1f)) {
                    sendMode = false; vm.stopSend()
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("CHANNEL", color = ElectricViolet, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Channel.entries.forEach { ch ->
                    val enabled = ch != Channel.TORCH || vm.torchSupported
                    ChannelChip(ch, channel == ch, enabled, Modifier.weight(1f)) {
                        vm.channel.value = ch
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (sendMode) SendPanel(vm, channel) else ReceivePanel(vm, channel)

            Spacer(Modifier.height(20.dp))
            Surface(color = VoidLight, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Text(status, color = QuantumCyan, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier, onClick = onClick, shape = RoundedCornerShape(50),
        color = if (active) ElectricViolet else VoidLight
    ) {
        Text(text, modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center, color = if (active) Void else Mist,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChannelChip(
    ch: Channel,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border = when (ch) {
        Channel.PHONON -> EntangleMagenta
        Channel.PHOTON -> QuantumCyan
        Channel.TORCH -> PhotonYellow
    }
    val icon = when (ch) {
        Channel.PHONON -> "♪"
        Channel.PHOTON -> "▦"
        Channel.TORCH -> "✲"
    }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) border.copy(alpha = 0.18f) else VoidLight,
        contentColor = if (enabled) border else Mist.copy(alpha = 0.3f),
        border = if (selected) BorderStroke(1.dp, border) else null
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("$icon ${ch.label}", fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text(ch.tag, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                color = Mist.copy(alpha = if (enabled) 0.6f else 0.3f))
        }
    }
}

// ---------------- SEND ----------------
@Composable
private fun SendPanel(vm: QBeamViewModel, channel: Channel) {
    val tx by vm.tx.collectAsState()
    var text by remember { mutableStateOf("") }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.setUri(it) } }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; vm.setText(it) },
        label = { Text("Message to teleport") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = QuantumCyan, unfocusedBorderColor = ElectricViolet,
            focusedTextColor = Mist, unfocusedTextColor = Mist, cursorColor = QuantumCyan,
        )
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { pickFile.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
        Text("⊹ Attach image / file")
    }
    Spacer(Modifier.height(12.dp))

    val hint = when (channel) {
        Channel.PHONON -> "Plays the data as audio — keep phones close, volume up."
        Channel.PHOTON -> "Shows cycling QR codes — point the receiver's camera at this screen."
        Channel.TORCH -> "Blinks the flashlight — aim it at the receiver's light sensor."
    }
    Text(hint, color = Mist.copy(alpha = 0.7f), fontSize = 11.sp,
        fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(8.dp))

    if (!tx.sending) {
        Button(
            onClick = { vm.startSend() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
        ) {
            Text("▶  SEND", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                fontFamily = FontFamily.Monospace)
        }
    } else {
        Button(
            onClick = { vm.stopSend() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EntangleMagenta)
        ) {
            Text("■  STOP", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                fontFamily = FontFamily.Monospace)
        }
    }

    // Photon channel: cycling QR display
    if (channel == Channel.PHOTON && tx.qrFrames.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        var idx by remember(tx.qrFrames) { mutableStateOf(0) }
        LaunchedEffect(tx.qrFrames, tx.qrFps) {
            if (tx.qrFrames.isEmpty()) return@LaunchedEffect
            while (true) { delay(1000L / tx.qrFps); idx = (idx + 1) % tx.qrFrames.size }
        }
        Surface(color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(12.dp)) {
            Image(tx.qrFrames[idx].asImageBitmap(), contentDescription = "photon packet",
                modifier = Modifier.size(280.dp).padding(8.dp))
        }
        Text("packet ${idx + 1}/${tx.qrFrames.size}", color = QuantumCyan,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    } else if (tx.sending && tx.total > 0) {
        Spacer(Modifier.height(16.dp))
        val pct = if (tx.total == 0) 0 else (100 * tx.progress / tx.total)
        LinearProgressIndicator(
            progress = { tx.progress.toFloat() / tx.total },
            modifier = Modifier.fillMaxWidth(), color = QuantumCyan, trackColor = VoidLight
        )
        Text("$pct%  (${tx.progress}/${tx.total})", color = Mist,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    // Phonon self-test: play + record simultaneously and decode our own signal
    if (channel == Channel.PHONON) {
        val selfTest by vm.selfTest.collectAsState()
        val running = selfTest.phase == SelfTestPhase.RUNNING
        val micPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) vm.runSelfTest() }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = !running && !tx.sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "⏳ Testing speaker → mic…" else "🧪 Self-test (speaker → mic)")
        }
        if (selfTest.phase != SelfTestPhase.IDLE) {
            val c = when (selfTest.phase) {
                SelfTestPhase.PASS -> QuantumCyan
                SelfTestPhase.FAIL -> EntangleMagenta
                else -> Mist
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = VoidLight, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Text(selfTest.detail, color = c, fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ---------------- RECEIVE ----------------
@Composable
private fun ReceivePanel(vm: QBeamViewModel, channel: Channel) {
    val rx by vm.rx.collectAsState()

    val audioPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startReceiveAudio() }
    val camPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.beginPhotonReceive() }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { vm.saveReceived(it) } }

    if (!rx.receiving) {
        Button(
            onClick = {
                when (channel) {
                    Channel.PHONON -> audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                    Channel.PHOTON -> camPerm.launch(Manifest.permission.CAMERA)
                    Channel.TORCH -> vm.startReceiveLight() // light sensor, no permission
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = QuantumCyan,
                contentColor = Void)
        ) { Text("◉ START RECEIVER", fontWeight = FontWeight.Bold) }
    } else {
        Button(onClick = { vm.stopReceive() }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = EntangleMagenta)) {
            Text("■ STOP RECEIVER")
        }
    }

    // Photon: live camera scanner
    if (rx.receiving && channel == Channel.PHOTON) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            QrScannerView(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                onPayload = { vm.onQrPayload(it) }
            )
        }
    }
    // Torch: light-sensor receiver (no camera)
    if (rx.receiving && channel == Channel.TORCH) {
        Spacer(Modifier.height(12.dp))
        Surface(color = VoidLight, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text("☀ Light-sensor active — aim the sender's flashlight at this phone's " +
                "ambient-light sensor (near the front camera). Experimental & slow.",
                color = PhotonYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(12.dp))
        }
    }
    if (rx.receiving) {
        Spacer(Modifier.height(8.dp))
        Text("captured frames: ${rx.frames}", color = QuantumCyan,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    // Decoded result (the currently open message)
    rx.received?.let { msg ->
        Spacer(Modifier.height(16.dp))
        Surface(color = VoidLight, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("⤓ MESSAGE  ${msg.name}", color = QuantumCyan,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                when {
                    rx.receivedBitmap != null ->
                        Image(rx.receivedBitmap!!.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                    msg.type.name == "TEXT" ->
                        Text(String(msg.data), color = Mist)
                    else -> Text("${msg.data.size} bytes binary", color = Mist)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { saveFile.launch(msg.name) },
                    modifier = Modifier.fillMaxWidth()) { Text("⤓ Export to device") }
            }
        }
    }

    // Stored history — every received message is saved and can be re-opened
    val history by vm.history.collectAsState()
    Spacer(Modifier.height(20.dp))
    Text("RECEIVED  (${history.size})", color = ElectricViolet, fontSize = 12.sp,
        fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(8.dp))
    if (history.isEmpty()) {
        Text("Nothing received yet. Start the receiver and beam something from another phone.",
            color = Mist.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    } else {
        val fmt = remember { java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault()) }
        history.forEach { item ->
            Surface(
                onClick = { vm.openStored(item.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = VoidLight, shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val glyph = when (item.type.name) {
                        "TEXT" -> "✉"; "IMAGE" -> "🖼"; else -> "📄"
                    }
                    Text(glyph, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Mist, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp, maxLines = 1)
                        Text("${item.size} B · ${fmt.format(java.util.Date(item.timestamp))}",
                            color = Mist.copy(alpha = 0.6f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { vm.deleteStored(item.id) }) {
                        Text("✕", color = EntangleMagenta, fontSize = 16.sp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
