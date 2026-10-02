package kyo.internal.crypto

/** Arithmetic modulo `p = 2^255 - 19` on sixteen limbs of 16 bits held in `Double`, TweetNaCl's `gf` (tweetnacl-js, the Cure53-audited
  * port, whose `gf` is a `Float64Array(16)`), written function for function after it: `car25519`, `sel25519`, `pack25519`,
  * `unpack25519`, `A`, `Z`, `M`, `S`, `inv25519`, `pow2523`, `neq25519` and `par25519` are [[carry]], [[select]], [[pack]], [[unpack]],
  * [[add]], [[sub]], [[mul]], [[square]], [[invert]], [[pow2523]], [[isEqual]] and [[parity]]. An element is `f0 .. f15` with value
  * `sum fi 2^(16 i)` modulo `p`; limbs are integers, may be negative, and are redundant: two arrays with different limbs can hold the same
  * value, so equality and parity go through [[pack]], the canonical 32 bytes, never through the limbs.
  *
  * Every limb is an integer held exactly in a binary64, and every intermediate is an integer below `2^53` in magnitude, so the
  * computation is the integer computation on all four platforms (JavaScript numbers are binary64; the JVM has been strict since 17; Scala
  * Native and Wasm compute binary64 without extended precision on x86-64 and arm64). The bounds relied on, derived for these functions
  * and the point formulas of [[kyo.crypto.Ed25519]], with `r = 2^16 + 38`:
  *
  *   - [[mul]] and [[square]] take limbs of magnitude at most `2^21`. Each product is at most `2^42`, a column sum of 16 products at
  *     most `2^46`, and after the fold `o(k) = t(k) + 38 t(k + 16)` at most `571 * 2^42 < 2^51.2`. Their output is reduced: limbs 1 to
  *     15 in `[0, 2^16)` and limb 0 in `[-38, 2^16 + 38)`, so every limb is at most `r` in magnitude.
  *   - [[add]] and [[sub]] of inputs of magnitude at most `i r` and `j r` give at most `(i + j) r`, no carry.
  *   - [[carry]] on a value with every limb below `2^52` in magnitude is exact: `v / 65536` and `Math.floor` of it are exact for these
  *     magnitudes, and `c * 65536` and the differences stay integers below `2^52`.
  *   - [[pack]] takes limbs of magnitude at most `2^21`: three carries bring every limb into `[0, 2^16)`, then the two conditional
  *     subtractions of `p` (`2p = 2^256 - 38`, so a value in `[2p, 2^256)` needs both) leave the value in `[0, p)`, which covers the
  *     19 values in `[p, 2^255)` and the values a negative limb 0 produces.
  *
  * The doubling of [[kyo.crypto.Ed25519]] reaches `4 r` at a multiplication input, the addition `3 r`, and `32 r = 2^21 + 1216`, so the
  * precondition holds with a factor of 8 to spare. `Math.floor` is the carry's division, never truncation, because limbs are signed;
  * there is no `%` and no conversion to `Long`.
  *
  * Note: nothing here is constant time. [[select]] is TweetNaCl's `sel25519` because [[pack]] is written after `pack25519`, not to hide
  * the branch: the verifier handles public values only.
  */
private[kyo] object Ed25519Field:

    type Fe = Array[Double]

    inline def fe(): Fe = new Array[Double](16)

    /** The temporaries the functions that need any take from their caller, so nothing is shared between calls. */
    final class Scratch:
        val t: Array[Double]     = new Array[Double](31)
        val m: Fe                = fe()
        val u: Fe                = fe()
        val c: Fe                = fe()
        val packed: Array[Byte]  = new Array[Byte](32)
        val packed2: Array[Byte] = new Array[Byte](32)
    end Scratch

    /** `d = -121665 / 121666`, TweetNaCl's `D`. */
    val D: Fe = Array(
        0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203
    )
        .map(_.toDouble)

    /** `2 d`, TweetNaCl's `D2`. */
    val D2: Fe = Array(
        0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406
    )
        .map(_.toDouble)

    /** The base point's `x`, TweetNaCl's `X`. */
    val BaseX: Fe = Array(
        0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169
    )
        .map(_.toDouble)

    /** The base point's `y = 4 / 5`, TweetNaCl's `Y`. */
    val BaseY: Fe = Array(
        0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666
    )
        .map(_.toDouble)

    /** `sqrt(-1) = 2^((p - 1) / 4)`, TweetNaCl's `I`. */
    val SqrtMinusOne: Fe = Array(
        0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83
    )
        .map(_.toDouble)

    val Zero: Fe = fe()

    val One: Fe =
        val o = fe()
        o(0) = 1
        o
    end One

    def copy(o: Fe, a: Fe): Unit =
        var i = 0
        while i < 16 do
            o(i) = a(i)
            i += 1
        end while
    end copy

    /** `car25519`: one carry pass, limb 15's carry folded into limb 0 as `38 c`. Each limb below `2^52` in magnitude on entry. */
    def carry(o: Fe): Unit =
        var c = 1.0
        var i = 0
        while i < 16 do
            val v = o(i) + c + 65535
            c = Math.floor(v / 65536)
            o(i) = v - c * 65536
            i += 1
        end while
        o(0) += c - 1 + 37 * (c - 1)
    end carry

    /** `sel25519`: swaps `p` and `q` when `b` is 1, leaves them when `b` is 0. Limbs must fit an `Int`. */
    def select(p: Fe, q: Fe, b: Int): Unit =
        val c = -b
        var i = 0
        while i < 16 do
            val pi = p(i).toInt
            val qi = q(i).toInt
            val t  = c & (pi ^ qi)
            p(i) = (pi ^ t).toDouble
            q(i) = (qi ^ t).toDouble
            i += 1
        end while
    end select

    /** `pack25519`: the canonical 32 little-endian bytes of the value in `[0, p)`. */
    def pack(o: Array[Byte], n: Fe, s: Scratch): Unit =
        val m = s.m
        val t = s.u
        copy(t, n)
        carry(t)
        carry(t)
        carry(t)
        var j = 0
        while j < 2 do
            m(0) = t(0) - 0xffed
            var i = 1
            while i < 15 do
                m(i) = t(i) - 0xffff - ((m(i - 1).toInt >> 16) & 1)
                m(i - 1) = (m(i - 1).toInt & 0xffff).toDouble
                i += 1
            end while
            m(15) = t(15) - 0x7fff - ((m(14).toInt >> 16) & 1)
            val b = (m(15).toInt >> 16) & 1
            m(14) = (m(14).toInt & 0xffff).toDouble
            select(t, m, 1 - b)
            j += 1
        end while
        var i = 0
        while i < 16 do
            val ti = t(i).toInt
            o(2 * i) = (ti & 0xff).toByte
            o(2 * i + 1) = (ti >> 8).toByte
            i += 1
        end while
    end pack

    /** `unpack25519`: the 32 little-endian bytes at `offset`, bit 255 dropped, as limbs in `[0, 2^16)`. */
    def unpack(o: Fe, n: Array[Byte], offset: Int): Unit =
        var i = 0
        while i < 16 do
            o(i) = ((n(offset + 2 * i) & 0xff) + ((n(offset + 2 * i + 1) & 0xff) << 8)).toDouble
            i += 1
        end while
        o(15) = (o(15).toInt & 0x7fff).toDouble
    end unpack

    /** `A`. */
    def add(o: Fe, a: Fe, b: Fe): Unit =
        var i = 0
        while i < 16 do
            o(i) = a(i) + b(i)
            i += 1
        end while
    end add

    /** `Z`. */
    def sub(o: Fe, a: Fe, b: Fe): Unit =
        var i = 0
        while i < 16 do
            o(i) = a(i) - b(i)
            i += 1
        end while
    end sub

    /** `M`: the 31 column sums, the fold by 38, two carries. `o` may be `a` or `b`. */
    def mul(o: Fe, a: Fe, b: Fe, s: Scratch): Unit =
        val t = s.t
        var i = 0
        while i < 31 do
            t(i) = 0
            i += 1
        end while
        i = 0
        while i < 16 do
            val ai = a(i)
            var j  = 0
            while j < 16 do
                t(i + j) += ai * b(j)
                j += 1
            end while
            i += 1
        end while
        i = 0
        while i < 15 do
            t(i) += 38 * t(i + 16)
            i += 1
        end while
        i = 0
        while i < 16 do
            o(i) = t(i)
            i += 1
        end while
        carry(o)
        carry(o)
    end mul

    /** `S`. */
    def square(o: Fe, a: Fe, s: Scratch): Unit = mul(o, a, a, s)

    /** `inv25519`: `i^(p - 2)` by the fixed square-and-multiply chain. `o` may be `i`. */
    def invert(o: Fe, i: Fe, s: Scratch): Unit =
        val c = s.c
        copy(c, i)
        var a = 253
        while a >= 0 do
            square(c, c, s)
            if a != 2 && a != 4 then mul(c, c, i, s)
            a -= 1
        end while
        copy(o, c)
    end invert

    /** `pow2523`: `i^((p - 5) / 8)`, the exponent of the square root. `o` may be `i`. */
    def pow2523(o: Fe, i: Fe, s: Scratch): Unit =
        val c = s.c
        copy(c, i)
        var a = 250
        while a >= 0 do
            square(c, c, s)
            if a != 1 then mul(c, c, i, s)
            a -= 1
        end while
        copy(o, c)
    end pow2523

    /** `neq25519` negated: whether `a` and `b` hold the same value, decided on the canonical bytes. */
    def isEqual(a: Fe, b: Fe, s: Scratch): Boolean =
        pack(s.packed, a, s)
        pack(s.packed2, b, s)
        java.util.Arrays.equals(s.packed, s.packed2)
    end isEqual

    /** `par25519`: the low bit of the canonical value. */
    def parity(a: Fe, s: Scratch): Int =
        pack(s.packed, a, s)
        s.packed(0) & 1
    end parity

    /** Whether the canonical value is 0. */
    def isZero(a: Fe, s: Scratch): Boolean =
        pack(s.packed, a, s)
        var i   = 0
        var acc = 0
        while i < 32 do
            acc |= s.packed(i)
            i += 1
        end while
        acc == 0
    end isZero

end Ed25519Field
