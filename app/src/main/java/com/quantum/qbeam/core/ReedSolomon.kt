package com.quantum.qbeam.core

/**
 * Reed–Solomon error correction over GF(256) — the outer code for the phonon (audio)
 * channel. It works on *bytes*, so a single corrupted byte (or a short burst that lands
 * inside one byte) costs only one of the code's correction budget. With `nsym` parity
 * bytes it corrects up to `nsym / 2` byte errors anywhere in the block.
 *
 * This is a faithful port of the well-known "Reed–Solomon for coders" algorithm
 * (GF generator 2, primitive polynomial 0x11D, fcr = 0), errors-only (no erasures).
 *
 * Combined with byte interleaving across sub-blocks (see [Interleaver]) this gives the
 * audio link real burst-error resilience: a noise spike that wipes several consecutive
 * channel symbols is scattered across multiple RS blocks, each losing only a byte or two.
 */
object ReedSolomon {

    private const val PRIM = 0x11D
    private const val FIELD_CHARAC = 255
    private val expT = IntArray(512)
    private val logT = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expT[i] = x
            logT[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIM
        }
        for (i in 255 until 512) expT[i] = expT[i - 255]
    }

    // ---- GF(256) scalar ops ----
    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else expT[logT[a] + logT[b]]

    private fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero" }
        if (a == 0) return 0
        return expT[(logT[a] + FIELD_CHARAC - logT[b]) % FIELD_CHARAC]
    }

    private fun inverse(a: Int): Int = expT[FIELD_CHARAC - logT[a]]

    private fun pow(x: Int, power: Int): Int {
        var p = (logT[x] * power) % FIELD_CHARAC
        if (p < 0) p += FIELD_CHARAC
        return expT[p]
    }

    // ---- GF polynomial ops (index 0 = leading/highest-degree coefficient) ----
    private fun polyScale(p: IntArray, s: Int) = IntArray(p.size) { mul(p[it], s) }

    private fun polyAdd(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(maxOf(p.size, q.size))
        for (i in p.indices) r[i + r.size - p.size] = p[i]
        for (i in q.indices) r[i + r.size - q.size] = r[i + r.size - q.size] xor q[i]
        return r
    }

    private fun polyMul(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(p.size + q.size - 1)
        for (j in q.indices) for (i in p.indices) r[i + j] = r[i + j] xor mul(p[i], q[j])
        return r
    }

    private fun polyEval(p: IntArray, x: Int): Int {
        var y = p[0]
        for (i in 1 until p.size) y = mul(y, x) xor p[i]
        return y
    }

    private fun polyDivRemainder(dividend: IntArray, divisor: IntArray): IntArray {
        val out = dividend.copyOf()
        for (i in 0 until dividend.size - (divisor.size - 1)) {
            val coef = out[i]
            if (coef != 0) for (j in 1 until divisor.size)
                if (divisor[j] != 0) out[i + j] = out[i + j] xor mul(divisor[j], coef)
        }
        val sep = out.size - (divisor.size - 1)
        return out.copyOfRange(sep, out.size)
    }

    private fun reversed(a: IntArray) = IntArray(a.size) { a[a.size - 1 - it] }

    private fun generatorPoly(nsym: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until nsym) g = polyMul(g, intArrayOf(1, pow(2, i)))
        return g
    }

    private class RsException : Exception()

    // ---- public API ----

    /** @return data followed by [nsym] parity bytes (length = data.size + nsym). */
    fun encode(data: ByteArray, nsym: Int): ByteArray {
        val gen = generatorPoly(nsym)
        val out = IntArray(data.size + nsym)
        for (i in data.indices) out[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val coef = out[i]
            if (coef != 0) for (j in 1 until gen.size)
                out[i + j] = out[i + j] xor mul(gen[j], coef)
        }
        for (i in data.indices) out[i] = data[i].toInt() and 0xFF
        return ByteArray(out.size) { out[it].toByte() }
    }

    /**
     * @param erasePos positions (0-based, in the codeword) known/suspected to be corrupt.
     *   Erasures are twice as cheap as unknown errors: this corrects any `e` errors and `r`
     *   erasures as long as `2e + r <= nsym`. Pass an empty array for plain errors-only.
     * @return the corrected data portion (size = code.size - nsym), or null if uncorrectable.
     */
    fun decode(code: ByteArray, nsym: Int, erasePos: IntArray = IntArray(0)): ByteArray? = try {
        if (erasePos.size > nsym) throw RsException()
        val msg = IntArray(code.size) { code[it].toInt() and 0xFF }
        for (p in erasePos) if (p in msg.indices) msg[p] = 0
        val synd = calcSyndromes(msg, nsym)
        val corrected = if (synd.max() == 0) msg else {
            val fsynd = forneySyndromes(synd, erasePos, msg.size)
            val errLoc = findErrorLocator(fsynd, nsym, erasePos.size)
            val errPos = findErrors(reversed(errLoc), msg.size)
            val allPos = erasePos + errPos
            val fixed = correctErrata(msg, synd, allPos)
            if (calcSyndromes(fixed, nsym).max() != 0) throw RsException()
            fixed
        }
        ByteArray(corrected.size - nsym) { corrected[it].toByte() }
    } catch (_: Exception) { null }

    /** Forney syndromes: fold the known erasure locations out of the syndrome polynomial. */
    private fun forneySyndromes(synd: IntArray, erasePos: IntArray, nmess: Int): IntArray {
        val fsynd = synd.copyOfRange(1, synd.size) // drop leading 0
        for (p in erasePos) {
            val x = pow(2, nmess - 1 - p)
            for (j in 0 until fsynd.size - 1) fsynd[j] = mul(fsynd[j], x) xor fsynd[j + 1]
        }
        return fsynd
    }

    private fun calcSyndromes(msg: IntArray, nsym: Int): IntArray {
        val synd = IntArray(nsym + 1) // synd[0] stays 0
        for (i in 0 until nsym) synd[i + 1] = polyEval(msg, pow(2, i))
        return synd
    }

    private fun findErrorLocator(synd: IntArray, nsym: Int, eraseCount: Int = 0): IntArray {
        var errLoc = intArrayOf(1)
        var oldLoc = intArrayOf(1)
        for (i in 0 until nsym - eraseCount) {
            var delta = synd[i]
            for (j in 1 until errLoc.size) delta = delta xor mul(errLoc[errLoc.size - 1 - j], synd[i - j])
            oldLoc = oldLoc + intArrayOf(0)
            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val newLoc = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, inverse(delta))
                    errLoc = newLoc
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta))
            }
        }
        var start = 0
        while (start < errLoc.size && errLoc[start] == 0) start++
        errLoc = errLoc.copyOfRange(start, errLoc.size)
        val errs = errLoc.size - 1 // unknown (non-erasure) errors
        if (errs * 2 + eraseCount > nsym) throw RsException()
        return errLoc
    }

    private fun findErrors(errLoc: IntArray, nmess: Int): IntArray {
        val errs = errLoc.size - 1
        val pos = ArrayList<Int>()
        for (i in 0 until nmess) if (polyEval(errLoc, pow(2, i)) == 0) pos.add(nmess - 1 - i)
        if (pos.size != errs) throw RsException()
        return pos.toIntArray()
    }

    private fun errataLocator(ePos: IntArray): IntArray {
        var eLoc = intArrayOf(1)
        for (i in ePos) eLoc = polyMul(eLoc, polyAdd(intArrayOf(1), intArrayOf(pow(2, i), 0)))
        return eLoc
    }

    private fun errorEvaluator(synd: IntArray, errLoc: IntArray, nsym: Int): IntArray {
        val divisor = IntArray(nsym + 2).also { it[0] = 1 } // x^(nsym+1)
        return polyDivRemainder(polyMul(synd, errLoc), divisor)
    }

    private fun correctErrata(msg: IntArray, synd: IntArray, errPos: IntArray): IntArray {
        val coefPos = IntArray(errPos.size) { msg.size - 1 - errPos[it] }
        val errLoc = errataLocator(coefPos)
        val errEval = reversed(errorEvaluator(reversed(synd), errLoc, errLoc.size - 1))
        val X = IntArray(coefPos.size) { pow(2, -(FIELD_CHARAC - coefPos[it])) }
        val E = IntArray(msg.size)
        for (i in X.indices) {
            val xiInv = inverse(X[i])
            var errLocPrime = 1
            for (j in X.indices) if (j != i) errLocPrime = mul(errLocPrime, 1 xor mul(xiInv, X[j]))
            var y = polyEval(reversed(errEval), xiInv)
            y = mul(pow(X[i], 1), y)
            if (errLocPrime == 0) throw RsException()
            E[errPos[i]] = div(y, errLocPrime)
        }
        return polyAdd(msg, E)
    }

    private fun IntArray.max(): Int { var m = 0; for (v in this) if (v > m) m = v; return m }
}

/**
 * Block-transpose byte interleaver. Given [blocks] RS codewords stored concatenated
 * (block-major: block 0's bytes, then block 1's, …), it emits them column-first so that
 * consecutive *wire* bytes belong to different RS blocks. A burst of B consecutive
 * corrupted wire bytes is then spread as ⌈B/blocks⌉ errors per block — multiplying the
 * effective burst tolerance by [blocks]. [data].size must be a multiple of [blocks].
 */
object Interleaver {
    fun interleave(data: ByteArray, blocks: Int): ByteArray {
        if (blocks <= 1) return data
        val clen = data.size / blocks
        val out = ByteArray(data.size)
        for (b in 0 until blocks) for (i in 0 until clen) out[i * blocks + b] = data[b * clen + i]
        return out
    }

    fun deinterleave(data: ByteArray, blocks: Int): ByteArray {
        if (blocks <= 1) return data
        val clen = data.size / blocks
        val out = ByteArray(data.size)
        for (b in 0 until blocks) for (i in 0 until clen) out[b * clen + i] = data[i * blocks + b]
        return out
    }
}
