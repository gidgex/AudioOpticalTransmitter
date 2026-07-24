# QBeam — Quantum Data Teleporter

A native Android app that sends **text, images, and files** between phones over the two
physical channels a stock phone actually exposes to software:

- **Phonon channel** — *audio*. A BFSK modem turns data into tones played through the
  speaker and demodulated from the microphone.
- **Photon channel** — *optical*. Data is rendered as a cycling sequence of QR codes on
  the screen and read back through the other phone's camera (plus a slow flashlight/OOK mode).

No second device or server is required to try it: point one phone's camera at another
phone's screen, or play audio from one into the mic of another.

---

## ⚠️ About using the phone's antenna (cellular / Wi-Fi / Bluetooth)

**You cannot.** Android exposes *no API* to emit arbitrary RF on the cellular, Wi-Fi, or
Bluetooth radios — that hardware is regulatory-locked (FCC/ETSI) and driven only by signed
baseband firmware. There is no software-defined-radio path on a stock handset.

The one radio with a developer API is **NFC**, but it is range-limited to ~4 cm and only
moves structured NDEF records — not arbitrary modulated signals. It is therefore *not*
implemented as a transmission channel here (it would carry only tiny payloads at touch range).

So the genuinely usable physical layers are **sound** and **light**, which is exactly what
QBeam uses — and fits the quantum theme (*phonons* and *photons*).

---

## How it works

### Wire format (`core/WavePacket.kt`)
Every channel transmits the same self-describing, CRC32-checked frames:

```
magic 'QB' | version | msgId | totalFrames | frameIndex | payloadLen | payload | crc32
```

A message is split into frames. **Frame 0** is a *manifest* (data type, filename, MIME,
total length); frames 1..N carry the data chunks. The `Reassembler` collects frames by
`msgId` and rebuilds the original `Message` once every frame has arrived. Because frames are
self-describing and CRC-checked, the same stream works on audio, QR, or torch.

### Phonon channel (`audio/`)
- **Modulation: M-FSK** (`AudioTransmitter`/`AudioReceiver`). `AudioConfig.toneFreqs` is one
  tone per symbol value, so each symbol carries log2(M) bits. Default 4 tones = **4-FSK**
  (2 bits/symbol), doubling throughput vs. the original binary FSK. Tones are aligned to
  Goertzel bins; the receiver picks the strongest of the M tones per symbol.
- **FEC: interleaved Reed–Solomon** (`core/ReedSolomon.kt` + `core/Interleaver.kt`). Each
  frame is padded and split into `rsBlocks` RS blocks (default 2 × RS(32,24), each correcting
  4 byte-errors), and the codewords are byte-interleaved so a noise *burst* is scattered
  across blocks. Net: ~8 scattered byte-errors or an 8-byte contiguous burst is repaired
  before the CRC32 is even checked. (`AudioFec` in `audio/AudioModem.kt` ties it together.)
- **Verified in `:app:testDebugUnitTest`** — RS correction, interleaving, full frame
  round-trip, and burst-error recovery all have passing JUnit tests.
- Defaults: 44.1 kHz, 100 baud (≈200 bit/s raw), sync 1500 Hz, tones 1800–2700 Hz.

### Photon channel (`optical/` + `core/Fountain.kt`)
- **Fountain-coded** (Luby Transform). The sender turns the message into an *endless* stream
  of XOR "droplets" (`Fountain.Encoder`) rendered as cycling QR codes. The receiver
  (`Fountain.Decoder`, fed by `QrScannerView` → ML Kit) reconstructs the original from **any**
  ≈K·1.1 droplets it happens to catch — order-independent and loss-tolerant, so missed
  frames simply don't matter and there's no retransmission. A robust-soliton degree
  distribution + SplitMix64 PRNG (seed carried per droplet) drives a belief-propagation
  (peeling) decoder. This is the **high-bandwidth, most reliable** channel.
- **Verified in unit tests**: decodes from a shuffled subset in ≤2·K droplets across trials.
- **Torch** (`TorchTransmitter`): flashlight on-off keying for very small payloads.
- **Light-sensor receiver** (`LightReceiver`): pairs with the torch — samples the ambient
  light sensor, adaptively thresholds, finds the OOK preamble, and reassembles frames. No
  permission required. ⚠️ Ambient-light sensors are slow/smoothed (a few Hz), so the torch
  runs at ~250 ms/bit and payloads must be tiny — this is a proof-of-concept, not the
  practical optical link (QR is).

### Quantum theming (`ui/`)
Dark "void" palette, monospace, animated orbiting-electron background, channels named for
phonons/photons, payloads called *wave packets*, the send action is **BEAM**.

---

## Build & run

This is a standard Gradle Android project.

1. Open the folder in **Android Studio** (Hedgehog or newer). It will auto-provision the
   Gradle version named in `gradle/wrapper/gradle-wrapper.properties` and sync.
2. `local.properties` is pre-filled with the detected SDK path on this machine.
3. Run on **two physical devices** (camera/mic/speaker aren't on emulators):
   - Pick a **CHANNEL**, hit **SEND** on one phone and **START RECEIVER** on the other.
   - **Photon:** aim the receiver's camera at the sender's cycling QR screen.
   - **Phonon:** put the phones close, turn up volume, keep it quiet.

Minimum SDK 24, target/compile SDK 34, Kotlin + Jetpack Compose.

### Tuning notes
- The **photon (QR)** path is the dependable one and works out of the box.
- The **audio modem** is the part most sensitive to real-world conditions. Expect to tune
  `AudioConfig` (`symbolSamples` for baud, the `syncThreshold` in `AudioReceiver`, and
  whether to use near-ultrasonic frequencies) on your specific devices.

## Roadmap ideas
- ~~Forward error correction on the audio frames~~ ✅ interleaved Reed–Solomon (`ReedSolomon`).
- ~~Light-sensor receiver to pair with the torch transmitter~~ ✅ added (`LightReceiver`).
- ~~Reed–Solomon (interleaved) for burst-error resilience on audio~~ ✅ done.
- ~~Fountain codes for the QR sequence so order/loss don't matter~~ ✅ LT codes (`Fountain`).
- ~~Multi-tone (M-FSK) audio for higher throughput~~ ✅ 4-FSK default, 8-FSK preset.
- ~~Erasure-aware RS using tone-magnitude confidence~~ ✅ done (`AudioFec` + `ReedSolomon`).
- Soft-decision (LLR) demodulation for the audio channel.
- On-screen QR FPS / camera exposure auto-tuning.
