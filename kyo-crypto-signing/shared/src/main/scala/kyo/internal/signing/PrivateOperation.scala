package kyo.internal.signing

import kyo.*

/** RSASP1 of RFC 8017 section 5.2.1 over a two-prime key, by the Chinese remainder theorem, in [[ConstantTimeMontgomery]]'s arithmetic.
  *
  * The input is blinded before either prime sees it, so the exponentiations by the secret exponents run on a value independent of the
  * input: with a blinding of two values r and u, the input is multiplied by r^e, and the result by r^-1. The inverse is the one step that
  * is not constant time, `BigInt.modInverse`, so it is taken of r * u, a value independent of r, and r^-1 is u * (r * u)^-1.
  */
private[kyo] object PrivateOperation:

    /** A private key as the operation runs it: the arithmetic modulo the modulus, which the blinding runs in, and modulo each prime; the
      * public exponent, the CRT exponents and q as limbs at their arithmetic's width; and the CRT coefficient in p's Montgomery form.
      */
    final class Key private (
        val modulus: ConstantTimeMontgomery,
        val exponent: Array[Int],
        val p: ConstantTimeMontgomery,
        val q: ConstantTimeMontgomery,
        val dP: Array[Int],
        val dQ: Array[Int],
        val qInv: Array[Int],
        val qLimbs: Array[Int]
    )

    object Key:
        /** The key of numbers that passed the checks of `RsaSigning`'s readers, so `p` and `q` are odd, of one width, and `qInv` below p. */
        def init(modulus: BigInt, exponent: BigInt, p: BigInt, q: BigInt, dP: BigInt, dQ: BigInt, qInv: BigInt): Key =
            val primeP  = ConstantTimeMontgomery.init(p)
            val primeQ  = ConstantTimeMontgomery.init(q)
            val inverse = new Array[Int](primeP.size)
            primeP.toMontgomery(ConstantTimeMontgomery.limbs(qInv, primeP.size), inverse)
            new Key(
                ConstantTimeMontgomery.init(modulus),
                ConstantTimeMontgomery.limbs(exponent, (exponent.bitLength + 31) / 32),
                primeP,
                primeQ,
                ConstantTimeMontgomery.limbs(dP, primeP.size),
                ConstantTimeMontgomery.limbs(dQ, primeQ.size),
                inverse,
                ConstantTimeMontgomery.limbs(q, primeQ.size)
            )
        end init
    end Key

    /** `input ^ d mod n` as `input.length` big-endian bytes, the modulus's length, for an `input` below the modulus. `blinding` is r then u,
      * each half of it, as `RsaSigning.SigningKey.blindingSizeInBytes` sizes them. Absent when r * u has no inverse modulo n.
      */
    def apply(key: Key, input: Array[Byte], blinding: Array[Byte]): Maybe[Array[Byte]] =
        val n     = key.modulus
        val width = blinding.length / 2
        val r     = new Array[Int](n.size)
        val u     = new Array[Int](n.size)
        n.toMontgomery(limbs(blinding, 0, width, 2 * n.size), r)
        n.toMontgomery(limbs(blinding, width, width, 2 * n.size), u)
        val product = new Array[Int](n.size)
        n.multiply(r, u, product)
        n.fromMontgomery(product, product)
        val modulus = ConstantTimeMontgomery.value(n.modulus)
        val ru      = ConstantTimeMontgomery.value(product)
        if ru.gcd(modulus) != BigInt(1) then Absent
        else
            val rInverse = new Array[Int](n.size)
            n.toMontgomery(ConstantTimeMontgomery.limbs(ru.modInverse(modulus), n.size), rInverse)
            n.multiply(u, rInverse, rInverse)

            val blinded = new Array[Int](n.size)
            n.power(r, key.exponent, blinded)(_ => ())
            val message = new Array[Int](n.size)
            n.toMontgomery(limbs(input, 0, input.length, n.size), message)
            n.multiply(message, blinded, blinded)
            n.fromMontgomery(blinded, blinded)

            // the halves modulo p and q, raised to dP and dQ
            val p  = key.p
            val q  = key.q
            val mp = new Array[Int](p.size)
            p.toMontgomery(blinded, mp)
            p.power(mp, key.dP, mp)(_ => ())
            val mq = new Array[Int](q.size)
            q.toMontgomery(blinded, mq)
            q.power(mq, key.dQ, mq)(_ => ())
            q.fromMontgomery(mq, mq)

            // Garner: h = qInv * (mp - mq) mod p, then mq + h * q, the signature still blinded
            val h = new Array[Int](p.size)
            p.toMontgomery(mq, h)
            p.subtract(mp, h, h)
            p.multiply(h, key.qInv, h)
            p.fromMontgomery(h, h)
            val combined = new Array[Int](n.size)
            n.toMontgomery(recombine(h, key.qLimbs, mq), combined)

            n.multiply(combined, rInverse, combined)
            n.fromMontgomery(combined, combined)
            Present(bytes(combined, input.length))
        end if
    end apply

    /** The big-endian `bytes` from `from`, `length` of them, as `size` little-endian limbs, every byte read whatever its value. */
    private def limbs(bytes: Array[Byte], from: Int, length: Int, size: Int): Array[Int] =
        val out = new Array[Int](size)
        var i   = 0
        while i < length do
            out(i >>> 2) |= (bytes(from + length - 1 - i) & 0xff) << (8 * (i & 3))
            i += 1
        end while
        out
    end limbs

    /** `limbs` as `length` big-endian bytes. */
    private def bytes(limbs: Array[Int], length: Int): Array[Byte] =
        val out = new Array[Byte](length)
        var i   = 0
        while i < length do
            out(length - 1 - i) = (limbs(i >>> 2) >>> (8 * (i & 3))).toByte
            i += 1
        end while
        out
    end bytes

    /** `low + h * q` over limbs, at the width of both factors and a carry limb: below `p * q`, so it fits the modulus's width. */
    private def recombine(h: Array[Int], q: Array[Int], low: Array[Int]): Array[Int] =
        val out = new Array[Int](h.length + q.length + 1)
        java.lang.System.arraycopy(low, 0, out, 0, low.length)
        var i = 0
        while i < h.length do
            val hi    = Integer.toUnsignedLong(h(i))
            var carry = 0L
            var j     = 0
            while j < q.length do
                val sum = Integer.toUnsignedLong(out(i + j)) + hi * Integer.toUnsignedLong(q(j)) + carry
                out(i + j) = sum.toInt
                carry = sum >>> 32
                j += 1
            end while
            var k = i + q.length
            while k < out.length do
                val sum = Integer.toUnsignedLong(out(k)) + carry
                out(k) = sum.toInt
                carry = sum >>> 32
                k += 1
            end while
            i += 1
        end while
        out
    end recombine

end PrivateOperation
