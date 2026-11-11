package kyo.internal.signing

/** Montgomery arithmetic modulo one odd modulus at a fixed width of 32-bit limbs, little-endian in `Int` arrays, with source-level
  * control flow independent of the operands: every loop runs over all [[size]] limbs, subtraction or addition is kept by a mask, and no
  * array is indexed by a secret. The moduli are an RSA key's modulus and primes, whose widths are public and whose values are not.
  * No guarantee is made about the code emitted by a JIT, JavaScript engine or LLVM.
  *
  * It is in `kyo.internal.signing`, outside `kyo.internal.crypto`, so kyo-crypto's `Montgomery` and `Exponentiation`, which branch on
  * their operands and run only over public values, are out of its reach and it out of theirs.
  *
  * A value in Montgomery form is `x * R mod n`, with `R = 2^(32 * size)`. Every operation takes operands of `size` limbs below the
  * modulus, unless it says otherwise, and writes `size` limbs below the modulus into `out`, which may be one of its operands.
  */
final private[kyo] class ConstantTimeMontgomery private (
    val size: Int,
    val modulus: Array[Int],
    n0inv: Long,
    rSquared: Array[Int]
):
    import ConstantTimeMontgomery.*

    /** `a * b * R^-1 mod n`: for `a` and `b` in Montgomery form, their product's. */
    def multiply(a: Array[Int], b: Array[Int], out: Array[Int]): Unit =
        val product = new Array[Int](2 * size + 1)
        var i       = 0
        while i < size do
            val ai    = a(i) & Mask
            var carry = 0L
            var j     = 0
            while j < size do
                val sum = (product(i + j) & Mask) + ai * (b(j) & Mask) + carry
                product(i + j) = sum.toInt
                carry = sum >>> 32
                j += 1
            end while
            product(i + size) = carry.toInt
            i += 1
        end while
        reduce(product, out)
    end multiply

    /** `a - b mod n`, in Montgomery form or not, since the form is linear. */
    def subtract(a: Array[Int], b: Array[Int], out: Array[Int]): Unit =
        var borrow = 0L
        var j      = 0
        while j < size do
            val difference = (a(j) & Mask) - (b(j) & Mask) - borrow
            out(j) = difference.toInt
            borrow = difference >>> 63
            j += 1
        end while
        // all ones when the difference borrowed, so the modulus is added back
        val add   = (-borrow).toInt
        var carry = 0L
        j = 0
        while j < size do
            val sum = (out(j) & Mask) + (modulus(j) & add & Mask) + carry
            out(j) = sum.toInt
            carry = sum >>> 32
            j += 1
        end while
    end subtract

    /** `value * R mod n`, the Montgomery form of `value`, for a `value` of up to `2 * size` limbs below `n * R`: a number of the modulus's
      * width, or of twice the width of a prime that divides it.
      */
    def toMontgomery(value: Array[Int], out: Array[Int]): Unit =
        val t = new Array[Int](2 * size + 1)
        java.lang.System.arraycopy(value, 0, t, 0, value.length)
        reduce(t, out)
        // value * R^-1, then value, then value * R
        multiply(out, rSquared, out)
        multiply(out, rSquared, out)
    end toMontgomery

    /** `value * R^-1 mod n`: for `value` in Montgomery form, the number it stands for. */
    def fromMontgomery(value: Array[Int], out: Array[Int]): Unit =
        val t = new Array[Int](2 * size + 1)
        java.lang.System.arraycopy(value, 0, t, 0, size)
        reduce(t, out)
    end fromMontgomery

    /** `base ^ exponent` for a `base` in Montgomery form, into `out` in Montgomery form. The exponent is read in windows of [[Window]] bits
      * from its top, over every limb whatever its bits, and each window's power is selected by reading every entry of the table under a
      * mask. `step` sees every operation in order: production passes a no-op, which inlines to nothing, and a test records the sequence to
      * show it is the same for every exponent of one length.
      */
    inline def power(base: Array[Int], exponent: Array[Int], out: Array[Int])(inline step: ConstantTimeMontgomery.Step => Unit): Unit =
        val entries = 1 << Window
        val table   = new Array[Array[Int]](entries)
        table(0) = new Array[Int](size)
        fromMontgomery(rSquared, table(0))
        table(1) = base.clone()
        var entry = 2
        while entry < entries do
            table(entry) = new Array[Int](size)
            multiply(table(entry - 1), base, table(entry))
            step(Step.Multiply)
            entry += 1
        end while
        val accumulator = table(0).clone()
        val selected    = new Array[Int](size)
        var window      = exponent.length * 32 / Window - 1
        while window >= 0 do
            var square = 0
            while square < Window do
                multiply(accumulator, accumulator, accumulator)
                step(Step.Square)
                square += 1
            end while
            select(table, windowOf(exponent, window), selected)
            step(Step.Select(entries))
            multiply(accumulator, selected, accumulator)
            step(Step.Multiply)
            window -= 1
        end while
        java.lang.System.arraycopy(accumulator, 0, out, 0, size)
    end power

    /** REDC: `t * R^-1 mod n` into `out`, for a `t` of `2 * size + 1` limbs below `n * R`, which it overwrites. */
    private def reduce(t: Array[Int], out: Array[Int]): Unit =
        var i = 0
        while i < size do
            val m     = ((t(i) & Mask) * n0inv) & Mask
            var carry = 0L
            var j     = 0
            while j < size do
                val sum = (t(i + j) & Mask) + m * (modulus(j) & Mask) + carry
                t(i + j) = sum.toInt
                carry = sum >>> 32
                j += 1
            end while
            var k = i + size
            while k <= 2 * size do
                val sum = (t(k) & Mask) + carry
                t(k) = sum.toInt
                carry = sum >>> 32
                k += 1
            end while
            i += 1
        end while
        // t / R, the limbs from `size`, is below 2n: n is subtracted, and the difference kept unless the subtraction borrowed
        var borrow = 0L
        var j      = 0
        while j < size do
            val difference = (t(size + j) & Mask) - (modulus(j) & Mask) - borrow
            out(j) = difference.toInt
            borrow = difference >>> 63
            j += 1
        end while
        val keep = (((t(2 * size) & Mask) - borrow) >> 63).toInt
        j = 0
        while j < size do
            out(j) = (t(size + j) & keep) | (out(j) & ~keep)
            j += 1
        end while
    end reduce

    /** The table entry at `index` into `out`, every entry read and all but one masked to zero. */
    private def select(table: Array[Array[Int]], index: Int, out: Array[Int]): Unit =
        java.util.Arrays.fill(out, 0)
        var entry = 0
        while entry < table.length do
            // all ones when entry is index, else zero: (entry ^ index) - 1 is negative only for 0
            val mask = ((entry ^ index) - 1) >> 31
            var j    = 0
            while j < size do
                out(j) |= table(entry)(j) & mask
                j += 1
            end while
            entry += 1
        end while
    end select

end ConstantTimeMontgomery

private[kyo] object ConstantTimeMontgomery:

    /** The bits of the exponent each step of [[ConstantTimeMontgomery.power]] reads. It divides 32, so a window never spans two limbs. */
    val Window: Int = 4

    private val Mask = 0xffffffffL

    /** The arithmetic modulo `modulus`, which is odd. Its constants are computed with `BigInt`, which is not constant time: this runs once
      * per key, when the key is read.
      */
    def init(modulus: BigInt): ConstantTimeMontgomery =
        val size   = (modulus.bitLength + 31) / 32
        val radix  = BigInt(1) << 32
        val n0inv  = (radix - (modulus mod radix).modInverse(radix)).toLong & Mask
        val square = (BigInt(1) << (64 * size)) mod modulus
        new ConstantTimeMontgomery(size, limbs(modulus, size), n0inv, limbs(square, size))
    end init

    /** `value`, below `2^(32 * size)`, as `size` little-endian limbs. */
    def limbs(value: BigInt, size: Int): Array[Int] =
        val out = new Array[Int](size)
        var i   = 0
        while i < size do
            out(i) = (value >> (32 * i)).toInt
            i += 1
        end while
        out
    end limbs

    def value(limbs: Array[Int]): BigInt =
        limbs.reverseIterator.foldLeft(BigInt(0))((value, limb) => (value << 32) | BigInt(limb & Mask))

    private def windowOf(exponent: Array[Int], window: Int): Int =
        val bit = window * Window
        (exponent(bit >>> 5) >>> (bit & 31)) & ((1 << Window) - 1)

    /** What [[ConstantTimeMontgomery.power]] does at each point of its loop. */
    enum Step derives CanEqual:
        case Square
        case Multiply
        case Select(entries: Int)
    end Step

end ConstantTimeMontgomery
