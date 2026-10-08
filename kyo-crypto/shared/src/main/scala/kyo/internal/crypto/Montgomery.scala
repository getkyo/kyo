package kyo.internal.crypto

/** Modular exponentiation for an odd modulus by Montgomery multiplication: the coarsely integrated operand scanning form (CIOS) of Koc,
  * Acar and Kaliski, "Analyzing and Comparing Montgomery Multiplication Algorithms" (1996), over 32-bit limbs held little-endian in `Int`
  * arrays, each product and carry in a `Long`.
  *
  * It exists for the platforms whose `java.math.BigInteger` reduces by long division: on Scala Native an 8192-bit `modPow` with
  * `e = 65537` takes about 108 ms against 2.6 ms on the JVM. Here the only division is the one reduction that enters the Montgomery
  * domain; every product inside the loop is reduced by multiplication and a conditional subtraction.
  *
  * Visible only inside `kyo.internal.crypto`, so its one caller is [[Exponentiation]], which takes the exponent from a public key type.
  *
  * A limb product plus the two addends it is accumulated with is at most `2^64 - 1`, so it fits a `Long` read as unsigned, and every
  * carry is taken with `>>> 32`. The running value of a product stays below `2n`, so its extra limb is 0 or 1.
  *
  * Note: nothing here is constant time (the exponent's bits choose the products, and the comparison returns at the first differing
  * limb); it serves RSA's public operation, whose operands are public.
  */
private[crypto] object Montgomery:

    private inline val Mask = 0xffffffffL

    /** `base ^ exponent mod modulus` for an odd positive `modulus` and `0 <= base < modulus`. */
    def modPow(base: BigInt, exponent: BigInt, modulus: BigInt): BigInt =
        if exponent.signum == 0 then BigInt(1).mod(modulus)
        else
            val size    = (modulus.bitLength + 31) / 32
            val n       = limbs(modulus, size)
            val n0inv   = negatedInverse(n(0))
            val baseM   = limbs((base << (32 * size)).mod(modulus), size)
            val scratch = new Array[Int](size + 2)
            var x       = baseM.clone()
            var spare   = new Array[Int](size)
            var bit     = exponent.bitLength - 2
            while bit >= 0 do
                multiply(x, x, n, n0inv, scratch, spare)
                var swap = x
                x = spare
                spare = swap
                if exponent.testBit(bit) then
                    multiply(x, baseM, n, n0inv, scratch, spare)
                    swap = x
                    x = spare
                    spare = swap
                end if
                bit -= 1
            end while
            val one = new Array[Int](size)
            one(0) = 1
            multiply(x, one, n, n0inv, scratch, spare)
            value(spare)
        end if
    end modPow

    /** `-n0^-1 mod 2^32` for an odd limb `n0`, by Newton iteration: `n0` is its own inverse modulo 8, and each step doubles the correct
      * low bits, so four steps reach 48.
      */
    private def negatedInverse(n0: Int): Long =
        val n   = n0 & Mask
        var inv = n
        var i   = 0
        while i < 4 do
            inv = inv * (2 - n * inv)
            i += 1
        (-inv) & Mask
    end negatedInverse

    /** `a * b * 2^(-32 size) mod n` into `out`, for `a` and `b` below `n`; `t` is a scratch of `size + 2` limbs. `out` may not alias `a`,
      * `b` or `n`.
      */
    private def multiply(a: Array[Int], b: Array[Int], n: Array[Int], n0inv: Long, t: Array[Int], out: Array[Int]): Unit =
        val size = n.length
        java.util.Arrays.fill(t, 0)
        var i = 0
        while i < size do
            // t += a * b(i)
            val bi    = b(i) & Mask
            var carry = 0L
            var j     = 0
            while j < size do
                val p = (t(j) & Mask) + (a(j) & Mask) * bi + carry
                t(j) = p.toInt
                carry = p >>> 32
                j += 1
            end while
            var p = (t(size) & Mask) + carry
            t(size) = p.toInt
            t(size + 1) = (p >>> 32).toInt
            // t = (t + m * n) / 2^32, with m chosen so the low limb cancels
            val m = ((t(0) & Mask) * n0inv) & Mask
            p = (t(0) & Mask) + m * (n(0) & Mask)
            carry = p >>> 32
            j = 1
            while j < size do
                p = (t(j) & Mask) + m * (n(j) & Mask) + carry
                t(j - 1) = p.toInt
                carry = p >>> 32
                j += 1
            end while
            p = (t(size) & Mask) + carry
            t(size - 1) = p.toInt
            t(size) = ((t(size + 1) & Mask) + (p >>> 32)).toInt
            i += 1
        end while
        if t(size) != 0 || !below(t, n) then
            var borrow = 0L
            var j      = 0
            while j < size do
                val d = (t(j) & Mask) - (n(j) & Mask) - borrow
                out(j) = d.toInt
                borrow = d >>> 63
                j += 1
            end while
        else java.lang.System.arraycopy(t, 0, out, 0, size)
        end if
    end multiply

    /** Whether the low `n.length` limbs of `t` are below `n`. */
    private def below(t: Array[Int], n: Array[Int]): Boolean =
        var j = n.length - 1
        while j >= 0 do
            val tj = t(j) & Mask
            val nj = n(j) & Mask
            if tj < nj then return true
            if tj > nj then return false
            j -= 1
        end while
        false
    end below

    /** The non-negative `v`, below `2^(32 size)`, as `size` little-endian limbs. */
    private def limbs(v: BigInt, size: Int): Array[Int] =
        val bytes = v.toByteArray
        val out   = new Array[Int](size)
        var i     = 0
        while i < bytes.length && i < 4 * size do
            out(i >> 2) |= (bytes(bytes.length - 1 - i) & 0xff) << ((i & 3) * 8)
            i += 1
        end while
        out
    end limbs

    /** The little-endian limbs read as a non-negative number. */
    private def value(limbs: Array[Int]): BigInt =
        val bytes = new Array[Byte](4 * limbs.length + 1)
        var i     = 0
        while i < 4 * limbs.length do
            bytes(bytes.length - 1 - i) = (limbs(i >> 2) >>> ((i & 3) * 8)).toByte
            i += 1
        end while
        BigInt(bytes)
    end value

end Montgomery
