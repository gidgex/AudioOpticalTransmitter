package com.quantum.qbeam

import com.quantum.qbeam.audio.AudioConfig
import com.quantum.qbeam.audio.AudioFec
import com.quantum.qbeam.core.Fountain
import com.quantum.qbeam.core.Interleaver
import com.quantum.qbeam.core.ReedSolomon
import com.quantum.qbeam.core.WavePacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests that actually exercise the error-correction math (run with
 * `:app:testDebugUnitTest`). These verify RS correction, interleaving, and the full
 * audio FEC frame round-trip including burst-error recovery.
 */
class FecTest {

    @Test fun rs_clean_roundtrip() {
        val data = ByteArray(24) { (it * 7 + 3).toByte() }
        val code = ReedSolomon.encode(data, 8)
        assertEquals(32, code.size)
        assertArrayEquals(data, ReedSolomon.decode(code, 8))
    }

    @Test fun rs_corrects_up_to_t_errors() {
        val rng = Random(42)
        val nsym = 8 // corrects 4 byte errors
        repeat(200) {
            val data = ByteArray(24) { rng.nextInt(256).toByte() }
            val code = ReedSolomon.encode(data, nsym)
            // corrupt 4 distinct positions
            val positions = (code.indices).shuffled(rng).take(4)
            for (p in positions) code[p] = (code[p].toInt() xor (rng.nextInt(255) + 1)).toByte()
            assertArrayEquals("trial $it", data, ReedSolomon.decode(code, nsym))
        }
    }

    @Test fun rs_rejects_when_overwhelmed() {
        val rng = Random(7)
        val nsym = 8
        val data = ByteArray(24) { rng.nextInt(256).toByte() }
        val code = ReedSolomon.encode(data, nsym)
        // 8 errors >> t=4: should fail to decode (return null) or not equal original
        for (p in (code.indices).shuffled(rng).take(8)) code[p] = (code[p] + 1).toByte()
        val decoded = ReedSolomon.decode(code, nsym)
        assertTrue(decoded == null || !decoded.contentEquals(data))
    }

    @Test fun interleaver_roundtrip() {
        val data = ByteArray(64) { it.toByte() }
        val w = Interleaver.interleave(data, 2)
        assertArrayEquals(data, Interleaver.deinterleave(w, 2))
        // verify columns really alternate blocks
        assertEquals(data[0], w[0])
        assertEquals(data[32], w[1])
    }

    @Test fun audioFec_frame_roundtrip_clean() {
        val cfg = AudioConfig()
        val fec = AudioFec(cfg)
        val frames = WavePacket.encode(
            WavePacket.Message(WavePacket.DataType.TEXT, "m.txt", "text/plain",
                "Entangled hello!".toByteArray()),
            msgId = 1234, chunkSize = 32
        )
        for (f in frames) {
            val wire = fec.encode(f)
            assertEquals(cfg.codewordTotal, wire.size)
            val recovered = fec.decode(wire)!!
            assertNotNull_(recovered)
            val parsed = WavePacket.parseFrame(recovered)!!
            assertEquals(f.size <= cfg.paddedFrameLen, true)
            assertTrue(parsed.total >= 1)
        }
    }

    @Test fun audioFec_survives_burst_error() {
        val cfg = AudioConfig() // 2 blocks, interleaved => burst tolerance
        val fec = AudioFec(cfg)
        val frame = WavePacket.serializeFrame(7, 2, 0, "quantum".toByteArray())
        val wire = fec.encode(frame)
        // wipe out 6 consecutive wire bytes (a burst). Interleaving spreads them to
        // 3 per RS block, within each block's t=4 correction budget.
        for (i in 10 until 16) wire[i] = (wire[i].toInt() xor 0xAA).toByte()
        val recovered = fec.decode(wire)
        assertTrue("burst should be corrected", recovered != null)
        val parsed = WavePacket.parseFrame(recovered!!)
        assertTrue(parsed != null)
    }

    private fun assertNotNull_(x: Any?) { assertTrue(x != null) }

    // ---------------- erasure-aware Reed–Solomon ----------------

    @Test fun rs_corrects_full_erasures() {
        val rng = Random(99)
        val nsym = 8 // with all-erasures we can fix up to 8
        repeat(100) {
            val data = ByteArray(24) { rng.nextInt(256).toByte() }
            val code = ReedSolomon.encode(data, nsym)
            val pos = code.indices.shuffled(rng).take(8).sorted()
            for (p in pos) code[p] = (code[p].toInt() xor (rng.nextInt(255) + 1)).toByte()
            val decoded = ReedSolomon.decode(code, nsym, pos.toIntArray())
            assertArrayEquals("erasure trial $it", data, decoded)
        }
    }

    @Test fun rs_corrects_mixed_errors_and_erasures() {
        // 2 unknown errors + 4 erasures => 2*2 + 4 = 8 == nsym, must still decode
        val rng = Random(123)
        val nsym = 8
        repeat(100) {
            val data = ByteArray(24) { rng.nextInt(256).toByte() }
            val code = ReedSolomon.encode(data, nsym)
            val corrupt = code.indices.shuffled(rng).take(6)
            for (p in corrupt) code[p] = (code[p].toInt() xor (rng.nextInt(255) + 1)).toByte()
            val erasures = corrupt.take(4).toIntArray() // declare 4 of the 6 as erasures
            val decoded = ReedSolomon.decode(code, nsym, erasures)
            assertArrayEquals("mixed trial $it", data, decoded)
        }
    }

    // ---------------- fountain code ----------------

    @Test fun fountain_decodes_from_arbitrary_subset() {
        val rng = Random(2024)
        val trials = 20
        var totalFed = 0; var totalK = 0
        repeat(trials) {
            val payload = ByteArray(4000) { rng.nextInt(256).toByte() }
            val enc = Fountain.Encoder(msgId = it, payload = payload, blockSize = 256)
            // Generous pool, shuffled to simulate out-of-order arrival and loss.
            val pool = (0 until enc.k * 6).map { seed -> enc.symbol(seed) }.shuffled(rng)
            val dec = Fountain.Decoder()
            var recovered: ByteArray? = null
            var fed = 0
            for (sym in pool) { recovered = dec.offer(sym); fed++; if (recovered != null) break }
            // correctness: every trial reconstructs the payload from a shuffled subset
            assertArrayEquals("trial $it fed=$fed/${pool.size} k=${enc.k}", payload, recovered)
            totalFed += fed; totalK += enc.k
        }
        // rateless efficiency on AVERAGE (per-trial variance is large at this small K)
        val overhead = totalFed.toDouble() / totalK
        assertTrue("avg droplets/K = $overhead", overhead <= 2.0)
    }

    @Test fun fountain_carries_a_whole_message() {
        val msg = WavePacket.Message(
            WavePacket.DataType.TEXT, "note.txt", "text/plain",
            ("Spooky action at a distance. ".repeat(50)).toByteArray()
        )
        val payload = WavePacket.packMessage(msg)
        val enc = Fountain.Encoder(7, payload, 256)
        val dec = Fountain.Decoder()
        var out: ByteArray? = null
        var seed = 0
        while (out == null && seed < enc.k * 5) { out = dec.offer(enc.symbol(seed)); seed++ }
        val recoveredMsg = WavePacket.unpackMessage(out!!)
        assertEquals(msg.name, recoveredMsg.name)
        assertArrayEquals(msg.data, recoveredMsg.data)
    }

    // ---------------- 8-FSK symbol mapping ----------------

    @Test fun eightFsk_symbol_mapping_roundtrip() {
        val cfg = AudioConfig.eightFsk()
        assertEquals(3, cfg.bitsPerSymbol)
        // symbolsPerFrame must be ceil(codewordTotal*8 / bitsPerSymbol) (last symbol padded)
        val totalBits = cfg.codewordTotal * 8
        assertTrue(cfg.symbolsPerFrame * cfg.bitsPerSymbol >= totalBits)
        assertTrue((cfg.symbolsPerFrame - 1) * cfg.bitsPerSymbol < totalBits)

        // replicate the TX bit->symbol packing and RX symbol->bit unpacking, verify bytes
        val rng = Random(55)
        val wire = ByteArray(cfg.codewordTotal) { rng.nextInt(256).toByte() }
        val bps = cfg.bitsPerSymbol
        val bits = IntArray(cfg.symbolsPerFrame * bps)
        var bi = 0
        for (byte in wire) { val v = byte.toInt() and 0xFF; for (b in 7 downTo 0) bits[bi++] = (v shr b) and 1 }
        val symbols = IntArray(cfg.symbolsPerFrame) { s ->
            var t = 0; for (b in 0 until bps) t = (t shl 1) or bits[s * bps + b]; t
        }
        val rxBits = IntArray(cfg.symbolsPerFrame * bps)
        for (s in symbols.indices) for (b in 0 until bps) rxBits[s * bps + b] = (symbols[s] shr (bps - 1 - b)) and 1
        val rxWire = ByteArray(cfg.codewordTotal) { i ->
            var v = 0; for (b in 0 until 8) v = (v shl 1) or rxBits[i * 8 + b]; v.toByte()
        }
        assertArrayEquals(wire, rxWire)
        for (s in symbols) assertTrue(s in 0..7) // 8-FSK uses tones 0..7
    }
}
