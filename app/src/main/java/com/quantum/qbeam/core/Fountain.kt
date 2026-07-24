package com.quantum.qbeam.core

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Luby-Transform (LT) **fountain code** for the photon (optical / QR) channel.
 *
 * Why a fountain here: QR frames are read out of order and many are missed (the camera only
 * sees whatever happens to be on screen when it looks). A fountain is *rateless* — the
 * sender emits an endless stream of encoded symbols, and the receiver can reconstruct the
 * original from *any* sufficiently large subset (≈ K·1.05–1.2 symbols), no matter which ones
 * arrive or in what order. No retransmission, no sequencing fuss.
 *
 * Each encoded symbol = XOR of a pseudo-random subset of the K source blocks. The subset is
 * derived deterministically from a per-symbol seed via a self-contained xorshift PRNG, so
 * the decoder reproduces it from the seed carried in the packet header.
 *
 * Packet wire layout (then Base64'd into a QR by QrCodec):
 *   magic 'Q','F' | version | msgId(2) | K(2) | blockSize(2) | totalLen(4) | seed(4)
 *                 | body(blockSize) | crc32(4)
 */
object Fountain {

    private const val MAGIC0 = 'Q'.code.toByte()
    private const val MAGIC1 = 'F'.code.toByte()
    private const val VERSION = 1
    private const val HEADER = 17 // magic2+ver1+msgId2+K2+blockSize2+totalLen4+seed4

    // --- deterministic PRNG (SplitMix64), identical on encoder and decoder ---
    // SplitMix64 is used precisely because it de-correlates structured/sequential seeds —
    // a plain xorshift leaks seed structure into the first outputs, which here biased the
    // degree distribution and starved the decoder of degree-1 droplets.
    private class Rng(seed: Long) {
        private var s = seed
        fun next(): Long {
            s += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15 golden gamma
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
        fun nextInt(bound: Int): Int = ((next() ushr 1) % bound).toInt()
        fun nextDouble(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun seedFor(msgId: Int, seed: Int): Long =
        (msgId.toLong() shl 32) xor (seed.toLong() and 0xFFFFFFFFL)

    /** Robust Soliton degree distribution -> CDF, sampled by the per-symbol RNG. */
    private fun degreeCdf(k: Int): DoubleArray {
        if (k <= 1) return doubleArrayOf(1.0)
        val c = 0.1; val delta = 0.05
        val r = c * Math.log(k / delta) * Math.sqrt(k.toDouble())
        val rho = DoubleArray(k + 1)
        rho[1] = 1.0 / k
        for (d in 2..k) rho[d] = 1.0 / (d.toDouble() * (d - 1))
        val tau = DoubleArray(k + 1)
        val pivot = Math.floor(k / r).toInt().coerceIn(1, k) // never exceed K
        for (d in 1 until pivot) tau[d] = r / (d.toDouble() * k)
        tau[pivot] = maxOf(0.0, r * Math.log(r / delta) / k)
        var beta = 0.0
        for (d in 1..k) beta += rho[d] + tau[d]
        val cdf = DoubleArray(k + 1)
        var acc = 0.0
        for (d in 1..k) { acc += (rho[d] + tau[d]) / beta; cdf[d] = acc }
        return cdf
    }

    private fun sampleDegree(cdf: DoubleArray, rng: Rng, k: Int): Int {
        val u = rng.nextDouble()
        for (d in 1 until cdf.size) if (u <= cdf[d]) return d
        return k
    }

    /** Distinct source-block indices selected for one encoded symbol. */
    private fun neighbors(msgId: Int, seed: Int, k: Int, cdf: DoubleArray): IntArray {
        val rng = Rng(seedFor(msgId, seed))
        val degree = sampleDegree(cdf, rng, k).coerceIn(1, k)
        val chosen = LinkedHashSet<Int>()
        while (chosen.size < degree) chosen.add(rng.nextInt(k))
        return chosen.toIntArray()
    }

    // ----------------------------- Encoder -----------------------------
    class Encoder(private val msgId: Int, payload: ByteArray, val blockSize: Int = 256) {
        val totalLen = payload.size
        val k: Int = maxOf(1, (payload.size + blockSize - 1) / blockSize)
        private val blocks: Array<ByteArray> = Array(k) { i ->
            val from = i * blockSize
            val b = ByteArray(blockSize)
            val len = minOf(blockSize, payload.size - from).coerceAtLeast(0)
            if (len > 0) System.arraycopy(payload, from, b, 0, len)
            b
        }
        private val cdf = degreeCdf(k)

        /** Produce the encoded symbol for index [seed] as wire bytes (ready for QrCodec). */
        fun symbol(seed: Int): ByteArray {
            val idxs = neighbors(msgId, seed, k, cdf)
            val body = ByteArray(blockSize)
            for (i in idxs) for (j in 0 until blockSize) body[j] = (body[j].toInt() xor blocks[i][j].toInt()).toByte()
            val buf = ByteBuffer.allocate(HEADER + blockSize)
            buf.put(MAGIC0); buf.put(MAGIC1); buf.put(VERSION.toByte())
            buf.putShort(msgId.toShort()); buf.putShort(k.toShort())
            buf.putShort(blockSize.toShort()); buf.putInt(totalLen); buf.putInt(seed)
            buf.put(body)
            val crc = CRC32().apply { update(buf.array()) }.value
            return ByteBuffer.allocate(buf.capacity() + 4).put(buf.array()).putInt(crc.toInt()).array()
        }

        /** A pool size that decodes with very high probability (rateless, but QR loops finitely). */
        fun recommendedPool(): Int = k + maxOf(12, k / 2)
    }

    // ----------------------------- Decoder -----------------------------
    /** Feed decoded QR payloads via [offer]; returns the full payload once recoverable. */
    class Decoder {
        private var msgId = -1
        private var k = -1
        private var blockSize = -1
        private var totalLen = -1
        private var cdf: DoubleArray = DoubleArray(0)
        private val solved = HashMap<Int, ByteArray>()
        private val seenSeeds = HashSet<Int>()
        private data class Pending(val neighbors: MutableSet<Int>, val value: ByteArray)
        private val pending = ArrayList<Pending>()

        val progress: Pair<Int, Int> get() = solved.size to k

        @Synchronized
        fun offer(packet: ByteArray): ByteArray? {
            val parsed = parse(packet) ?: return null
            val (pMsgId, pK, pBlock, pTotal, seed, body) = parsed
            if (msgId != pMsgId) reset(pMsgId, pK, pBlock, pTotal)
            if (!seenSeeds.add(seed)) return null // duplicate symbol

            val nbrs = neighbors(msgId, seed, k, cdf).toMutableSet()
            val value = body.copyOf()
            // reduce against already-solved blocks
            val it = nbrs.iterator()
            while (it.hasNext()) {
                val b = it.next()
                solved[b]?.let { sv -> xorInto(value, sv); it.remove() }
            }
            pending.add(Pending(nbrs, value))
            peel()
            return if (solved.size == k) assemble() else null
        }

        private fun peel() {
            var progress = true
            while (progress) {
                progress = false
                val ready = pending.firstOrNull { it.neighbors.size == 1 } ?: break
                val blockIdx = ready.neighbors.first()
                pending.remove(ready)
                if (solved.containsKey(blockIdx)) continue
                solved[blockIdx] = ready.value
                // propagate into every pending symbol that references this block
                for (p in pending) {
                    if (p.neighbors.remove(blockIdx)) xorInto(p.value, ready.value)
                }
                progress = true
            }
        }

        private fun assemble(): ByteArray {
            val out = ByteArray(k * blockSize)
            for (i in 0 until k) System.arraycopy(solved[i]!!, 0, out, i * blockSize, blockSize)
            return out.copyOf(totalLen)
        }

        private fun reset(id: Int, kk: Int, block: Int, total: Int) {
            msgId = id; k = kk; blockSize = block; totalLen = total
            cdf = degreeCdf(k)
            solved.clear(); seenSeeds.clear(); pending.clear()
        }

        private data class Parsed(
            val msgId: Int, val k: Int, val blockSize: Int,
            val totalLen: Int, val seed: Int, val body: ByteArray
        )

        private fun parse(packet: ByteArray): Parsed? {
            if (packet.size < HEADER + 4) return null
            if (packet[0] != MAGIC0 || packet[1] != MAGIC1) return null
            val bodyLen = packet.size - HEADER - 4
            if (bodyLen <= 0) return null
            // crc check
            val expected = ByteBuffer.wrap(packet, packet.size - 4, 4).int
            val actual = CRC32().apply { update(packet, 0, packet.size - 4) }.value.toInt()
            if (expected != actual) return null
            val buf = ByteBuffer.wrap(packet)
            buf.position(3)
            val mId = buf.short.toInt() and 0xFFFF
            val kk = buf.short.toInt() and 0xFFFF
            val block = buf.short.toInt() and 0xFFFF
            val total = buf.int
            val seed = buf.int
            if (block != bodyLen) return null
            val body = ByteArray(block).also { buf.get(it) }
            return Parsed(mId, kk, block, total, seed, body)
        }

        private fun xorInto(dst: ByteArray, src: ByteArray) {
            for (j in dst.indices) dst[j] = (dst[j].toInt() xor src[j].toInt()).toByte()
        }
    }
}
