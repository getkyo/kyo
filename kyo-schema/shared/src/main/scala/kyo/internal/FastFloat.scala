package kyo.internal

import scala.annotation.tailrec

/** Fast double/float parsing via the Eisel-Lemire algorithm.
  *
  * Algorithm: Daniel Lemire, "Number Parsing at a Gigabyte per Second", Software: Practice and Experience, 2021
  * ([[https://arxiv.org/abs/2101.11408 arXiv:2101.11408]]).
  *
  * Scala port derived from the reference implementation in Go's `strconv` package
  * ([[https://github.com/golang/go/blob/master/src/internal/strconv/atofeisel.go atofeisel.go]] and
  * [[https://github.com/golang/go/blob/master/src/internal/strconv/atof.go atof.go]], BSD-3-Clause). The original C++ reference is Daniel
  * Lemire's fast_double_parser; a detailed exposition of the algorithm appears at
  * [[https://nigeltao.github.io/blog/2020/eisel-lemire.html Nigel Tao's blog]].
  *
  * Structure and branch-level semantics mirror the Go version; any divergence is documented inline.
  *
  * All entry points return IEEE 754 bit patterns. Callers decode via `java.lang.Double.longBitsToDouble` /
  * `java.lang.Float.intBitsToFloat`. `Long.MinValue` / `Int.MinValue` are used as bail-out sentinels — these patterns are not valid results
  * of the fast path (bail-out is checked before decoding).
  */
private[internal] object FastFloat:

    // IEEE 754 binary64 / binary32 constants (mirror Go's floatinfo constants).
    // Note the sign: Go's `floatInfo.bias` fields are NEGATIVE (float64Bias = -1023, float32Bias = -127).
    // The formula `exp2 + 63 - float64Bias` in Go therefore reduces to `exp2 + 63 + 1023`. Mirroring Go
    // verbatim requires carrying the sign through.
    private inline val Float64Bias     = -1023
    private inline val Float32Bias     = -127
    private inline val Float64MantBits = 52
    private inline val Float32MantBits = 23

    // Bail-out sentinels. These bit patterns cannot be produced by the fast path:
    //   - `0x7ff8000000000000L` is a canonical quiet-NaN bit pattern. The fast path produces only
    //     finite, non-NaN doubles (its final range check rejects `retExp2 >= 0x7FF` which covers both
    //     Infinity and NaN), so this value is unambiguously a bail-out sentinel and cannot collide
    //     with legitimate results — including `-0.0`, which the `man == 0` short-circuit emits as
    //     `0x8000000000000000L`.
    //   - Same reasoning for the float32 canonical qNaN pattern `0x7fc00000`.
    inline val DoubleBailOut = 0x7ff8000000000000L
    inline val FloatBailOut  = 0x7fc00000

    // Mirrors Go's `mulLog2_10` in `src/internal/strconv/math.go`.
    // Returns floor(x * log(10)/log(2)) for -500 <= x <= 500.
    private inline def mulLog2_10(x: Int): Int = (x * 108853) >> 15

    /** Returns the top 64 bits of the unsigned 128-bit product `a * b`.
      *
      * Pure-Scala implementation — portable across JVM / Scala.js / Scala Native without requiring JDK 18+'s `Math.unsignedMultiplyHigh`.
      * Uses 32-bit half splits and Long arithmetic. This is the same identity used by `java.lang.Math.unsignedMultiplyHigh` in the JDK.
      *
      * Divergence from Go: Go uses `math/bits.Mul64` which compiles to a single hardware instruction. The portable Scala version expands to
      * ~5 Long multiplications. On JVM the JIT may not recognize and collapse this; Phase 4 can profile and swap to
      * `Math.unsignedMultiplyHigh` if needed.
      */
    private inline def unsignedMultiplyHigh(a: Long, b: Long): Long =
        val aLo = a & 0xffffffffL
        val aHi = a >>> 32
        val bLo = b & 0xffffffffL
        val bHi = b >>> 32
        val ll  = aLo * bLo
        val hl  = aHi * bLo
        val lh  = aLo * bHi
        val hh  = aHi * bHi
        // Carry from ll -> (hl + lh) addition in the middle 64-bit lane.
        val mid = (ll >>> 32) + (hl & 0xffffffffL) + (lh & 0xffffffffL)
        hh + (hl >>> 32) + (lh >>> 32) + (mid >>> 32)
    end unsignedMultiplyHigh

    /** Eisel-Lemire 64-bit fast-path: converts `(-1)^neg * man * 10^exp10` to a correctly-rounded IEEE 754 double.
      *
      * Mirrors `eiselLemire64` in Go's `src/internal/strconv/atofeisel.go`. Returns the raw IEEE 754 bits on success, or [[DoubleBailOut]]
      * (`Long.MinValue`) when the fast path cannot conclusively round and the caller must fall back to a slow, exact-arithmetic path.
      *
      * Preconditions: `man` is an unsigned 64-bit value fitting in ≤ 19 decimal digits.
      */
    def eiselLemire64(man: Long, exp10: Int, neg: Boolean): Long =
        // Exp10 Range.
        if man == 0L then
            // Zero — always representable. Apply sign bit directly.
            return if neg then 0x8000000000000000L else 0L
        if exp10 < FastFloatPow10Table.pow10Min || exp10 > FastFloatPow10Table.pow10Max then
            return DoubleBailOut
        val tabIdx = exp10 - FastFloatPow10Table.pow10Min
        val powHi  = FastFloatPow10Table.pow10Hi(tabIdx)
        val powLo  = FastFloatPow10Table.pow10Lo(tabIdx)
        val exp2   = 1 + mulLog2_10(exp10)

        // Normalization. Shift `man` so its top bit is set.
        val clz        = java.lang.Long.numberOfLeadingZeros(man)
        var mant: Long = man << clz
        // retExp2 is conceptually uint64; we hold it in a Long and do unsigned comparisons at the end.
        var retExp2: Long = (exp2 + 63 - Float64Bias).toLong - clz.toLong

        // Multiplication.
        var xHi: Long = unsignedMultiplyHigh(mant, powHi)
        var xLo: Long = mant * powHi

        // Wider Approximation.
        // `xLo + man < man` is the unsigned carry check — in signed Long arithmetic we compare via
        // java.lang.Long.compareUnsigned.
        if (xHi & 0x1ffL) == 0x1ffL && java.lang.Long.compareUnsigned(xLo + mant, mant) < 0 then
            val yHi      = unsignedMultiplyHigh(mant, powLo)
            val yLo      = mant * powLo
            val mergedLo = xLo + yHi
            var mergedHi = xHi
            if java.lang.Long.compareUnsigned(mergedLo, xLo) < 0 then mergedHi = mergedHi + 1L
            if (mergedHi & 0x1ffL) == 0x1ffL && mergedLo + 1L == 0L &&
                java.lang.Long.compareUnsigned(yLo + mant, mant) < 0
            then
                return DoubleBailOut
            end if
            xHi = mergedHi
            xLo = mergedLo
        end if

        // Shifting to 54 Bits.
        val msb         = xHi >>> 63
        var retMantissa = xHi >>> (msb.toInt + 9)
        retExp2 = retExp2 - (1L ^ msb)

        // Half-way Ambiguity.
        if xLo == 0L && (xHi & 0x1ffL) == 0L && (retMantissa & 3L) == 1L then
            return DoubleBailOut

        // From 54 to 53 Bits.
        retMantissa = retMantissa + (retMantissa & 1L)
        retMantissa = retMantissa >>> 1
        if (retMantissa >>> 53) > 0L then
            retMantissa = retMantissa >>> 1
            retExp2 = retExp2 + 1L

        // Final range check. Go uses unsigned `retExp2-1 >= 0x7FF-1`. We mirror via compareUnsigned.
        if java.lang.Long.compareUnsigned(retExp2 - 1L, 0x7ffL - 1L) >= 0 then
            return DoubleBailOut

        var retBits = (retExp2 << Float64MantBits) | (retMantissa & ((1L << Float64MantBits) - 1L))
        if neg then retBits = retBits | 0x8000000000000000L
        retBits
    end eiselLemire64

    /** Eisel-Lemire 32-bit fast-path: converts `(-1)^neg * man * 10^exp10` to a correctly-rounded IEEE 754 float.
      *
      * Mirrors `eiselLemire32` in Go's `src/internal/strconv/atofeisel.go`. Same algorithm as the 64-bit variant but with float32
      * constants:
      *   - bias 127 (vs. 1023)
      *   - 23 mantissa bits (vs. 52)
      *   - shift by `msb + 38` (vs. `msb + 9`) to produce 25 bits
      *   - half-way / wider-approximation mask `0x3fffffffffL` (vs. `0x1ff`)
      *   - final range check `retExp2-1 >= 0xff-1` (vs. `0x7ff-1`)
      *
      * The 128-bit power-of-10 table is shared with the 64-bit path: Go's `eiselLemire32` calls the exact same `pow10(e)` function. Returns
      * raw IEEE 754 float32 bits as an `Int`, or [[FloatBailOut]] (`Int.MinValue`) on bail-out.
      */
    def eiselLemire32(man: Long, exp10: Int, neg: Boolean): Int =
        // Exp10 Range.
        if man == 0L then
            return if neg then 0x80000000 else 0
        if exp10 < FastFloatPow10Table.pow10Min || exp10 > FastFloatPow10Table.pow10Max then
            return FloatBailOut
        val tabIdx = exp10 - FastFloatPow10Table.pow10Min
        val powHi  = FastFloatPow10Table.pow10Hi(tabIdx)
        val powLo  = FastFloatPow10Table.pow10Lo(tabIdx)
        val exp2   = 1 + mulLog2_10(exp10)

        // Normalization.
        val clz           = java.lang.Long.numberOfLeadingZeros(man)
        var mant: Long    = man << clz
        var retExp2: Long = (exp2 + 63 - Float32Bias).toLong - clz.toLong

        // Multiplication.
        var xHi: Long = unsignedMultiplyHigh(mant, powHi)
        var xLo: Long = mant * powHi

        // Wider Approximation — float32 mask is the low 38 bits (0x3fffffffffL).
        if (xHi & 0x3fffffffffL) == 0x3fffffffffL && java.lang.Long.compareUnsigned(xLo + mant, mant) < 0 then
            val yHi      = unsignedMultiplyHigh(mant, powLo)
            val yLo      = mant * powLo
            val mergedLo = xLo + yHi
            var mergedHi = xHi
            if java.lang.Long.compareUnsigned(mergedLo, xLo) < 0 then mergedHi = mergedHi + 1L
            if (mergedHi & 0x3fffffffffL) == 0x3fffffffffL && mergedLo + 1L == 0L &&
                java.lang.Long.compareUnsigned(yLo + mant, mant) < 0
            then
                return FloatBailOut
            end if
            xHi = mergedHi
            xLo = mergedLo
        end if

        // Shifting to 25 Bits — shift is `msb + 38` for float32.
        val msb         = xHi >>> 63
        var retMantissa = xHi >>> (msb.toInt + 38)
        retExp2 = retExp2 - (1L ^ msb)

        // Half-way Ambiguity.
        if xLo == 0L && (xHi & 0x3fffffffffL) == 0L && (retMantissa & 3L) == 1L then
            return FloatBailOut

        // From 25 to 24 Bits.
        retMantissa = retMantissa + (retMantissa & 1L)
        retMantissa = retMantissa >>> 1
        if (retMantissa >>> 24) > 0L then
            retMantissa = retMantissa >>> 1
            retExp2 = retExp2 + 1L

        // Final range check: retExp2 in [1, 0xFE].
        if java.lang.Long.compareUnsigned(retExp2 - 1L, 0xffL - 1L) >= 0 then
            return FloatBailOut

        var retBits = (retExp2 << Float32MantBits) | (retMantissa & ((1L << Float32MantBits) - 1L))
        if neg then retBits = retBits | 0x80000000L
        retBits.toInt
    end eiselLemire32

    /** Advances past a JSON-style number starting at `start`. Mirrors the loop in `JsonReader.readNumber`. Returns the end index
      * (exclusive). The scan is permissive — it accepts any sequence of digits/`.eE+-` bytes and leaves detailed validation to the caller.
      * Does NOT accept quoted special values (`"NaN"`, `"Infinity"`) — those are handled upstream in `JsonReader.double`.
      */
    def scanNumberEnd(input: Array[Byte], start: Int, limit: Int): Int =
        @tailrec def loop(p: Int): Int =
            if p >= limit then p
            else
                val b = input(p)
                if (b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-' then
                    loop(p + 1)
                else p
        // Accept an optional leading '-' (a leading '+' is not valid JSON; we tolerate it in the permissive
        // loop because the caller re-validates on the fallback path).
        val p0 = if start < limit && input(start) == '-' then start + 1 else start
        loop(p0)
    end scanNumberEnd

    // Internal result of `readFloat64` / `readFloat32`-style scan. Packed so callers can distinguish parse
    // failure from "parsed but requires truncation retry" from "parsed cleanly".
    private inline val ScanOk        = 0
    private inline val ScanTruncated = 1
    private inline val ScanFailed    = 2

    /** Parses the number bytes in `input[start, end)` into `(mantissa, exp10, neg, status)`. Mirrors Go's `readFloat` in
      * `src/internal/strconv/atof.go`, specialized to JSON grammar (no hex, no underscores).
      *
      * `mantissaOut` / `exp10Out` / `negOut` are single-element `Array` out-parameters to avoid allocating a tuple per call. `Status` is
      * the return value: [[ScanOk]], [[ScanTruncated]], or [[ScanFailed]].
      */
    private def readFloat(
        input: Array[Byte],
        start: Int,
        end: Int,
        mantissaOut: Array[Long],
        exp10Out: Array[Int],
        negOut: Array[Boolean]
    ): Int =
        val MaxMantDigits = 19 // 10^19 fits in uint64 (just barely — max mantissa is 9_999_999_999_999_999_999L < 2^64).
        var i             = start
        var neg           = false
        var mantissa      = 0L
        var sawDot        = false
        var sawDigits     = false
        var nd            = 0
        var ndMant        = 0
        var dp            = 0
        var truncated     = false

        // Optional sign. JSON disallows leading '+', but we accept it here — the caller's scanner already
        // bounded the region, and the fallback path re-validates on failure.
        if i < end then
            val c = input(i)
            if c == '+' then i += 1
            else if c == '-' then
                neg = true
                i += 1
            end if
        end if

        // Mantissa digits.
        var done = false
        while !done && i < end do
            val c = input(i)
            if c == '.' then
                if sawDot then done = true
                else
                    sawDot = true
                    dp = nd
                    i += 1
            else if c >= '0' && c <= '9' then
                sawDigits = true
                if c == '0' && nd == 0 then
                    // Leading zero — advance the decimal-point offset.
                    dp -= 1
                    i += 1
                else
                    nd += 1
                    if ndMant < MaxMantDigits then
                        mantissa = mantissa * 10L + (c - '0').toLong
                        ndMant += 1
                    else if c != '0' then
                        truncated = true
                    end if
                    i += 1
                end if
            else
                done = true
            end if
        end while

        if !sawDigits then
            return ScanFailed

        if !sawDot then dp = nd

        // Optional exponent.
        if i < end then
            val c = input(i)
            if c == 'e' || c == 'E' then
                i += 1
                if i >= end then return ScanFailed
                var eSign = 1
                val sc    = input(i)
                if sc == '+' then i += 1
                else if sc == '-' then
                    eSign = -1
                    i += 1
                end if
                if i >= end || input(i) < '0' || input(i) > '9' then
                    return ScanFailed
                var e = 0
                while i < end && input(i) >= '0' && input(i) <= '9' do
                    if e < 10000 then
                        e = e * 10 + (input(i) - '0')
                    i += 1
                end while
                dp += e * eSign
            end if
        end if

        if i != end then
            // Unconsumed trailing bytes — the caller's scanner should never include these, but guard anyway.
            return ScanFailed

        // Final exponent is `dp - ndMant` when mantissa ≠ 0; zero mantissa still implies a zero result.
        val exp10 =
            if mantissa != 0L then dp - ndMant
            else 0

        mantissaOut(0) = mantissa
        exp10Out(0) = exp10
        negOut(0) = neg
        if truncated then ScanTruncated else ScanOk
    end readFloat

    // Exact powers of 10 representable as double / float without loss. Mirrors Go's `float64pow10` and
    // `float32pow10` arrays in `src/internal/strconv/atof.go`.
    private val float64Pow10: Array[Double] = Array(
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
        1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
        1e20, 1e21, 1e22
    )

    private val float32Pow10: Array[Float] = Array(
        1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f
    )

    // Exact double-precision fast path. Mirrors Go's `atof64exact`. Returns the IEEE 754 bits of
    // `(-1)^neg * mantissa * 10^exp` if the computation can be done exactly in `double` arithmetic, or
    // [[DoubleBailOut]] otherwise. The three accepted cases (per the paper's §2 and Go's comment):
    //   - value is an exact integer (exp == 0, mantissa fits in 53 bits)
    //   - value is an exact integer × exact power of 10 (exp in [1, 37])
    //   - value is an exact integer ÷ exact power of 10 (exp in [-22, -1])
    private def atof64Exact(mantissa: Long, exp10: Int, neg: Boolean): Long =
        // Mantissa must fit in 53 bits (no truncation) — mirrors Go's `mantissa>>float64info.mantbits != 0` check.
        if (mantissa >>> Float64MantBits) != 0L then return DoubleBailOut
        var f: Double = mantissa.toDouble
        if neg then f = -f
        if exp10 == 0 then
            java.lang.Double.doubleToRawLongBits(f)
        else if exp10 > 0 && exp10 <= 15 + 22 then
            // Exact integers are <= 10^15; exact powers of ten are <= 10^22.
            // If exponent is big but digit count isn't, shift some zeros into the integer part.
            var e = exp10
            if e > 22 then
                f = f * float64Pow10(e - 22)
                e = 22
            if f > 1e15 || f < -1e15 then DoubleBailOut
            else java.lang.Double.doubleToRawLongBits(f * float64Pow10(e))
        else if exp10 < 0 && exp10 >= -22 then
            java.lang.Double.doubleToRawLongBits(f / float64Pow10(-exp10))
        else
            DoubleBailOut
        end if
    end atof64Exact

    // Exact float32 fast path. Mirrors Go's `atof32exact`.
    private def atof32Exact(mantissa: Long, exp10: Int, neg: Boolean): Int =
        if (mantissa >>> Float32MantBits) != 0L then return FloatBailOut
        var f: Float = mantissa.toFloat
        if neg then f = -f
        if exp10 == 0 then
            java.lang.Float.floatToRawIntBits(f)
        else if exp10 > 0 && exp10 <= 7 + 10 then
            // Exact integers are <= 10^7; exact powers of ten are <= 10^10.
            var e = exp10
            if e > 10 then
                f = f * float32Pow10(e - 10)
                e = 10
            if f > 1e7f || f < -1e7f then FloatBailOut
            else java.lang.Float.floatToRawIntBits(f * float32Pow10(e))
        else if exp10 < 0 && exp10 >= -10 then
            java.lang.Float.floatToRawIntBits(f / float32Pow10(-exp10))
        else
            FloatBailOut
        end if
    end atof32Exact

    /** Parses the ASCII bytes `input[start, end)` as a decimal number and returns the IEEE 754 double bits. Returns [[DoubleBailOut]]
      * (`Long.MinValue`) on any bail-out — parse failure, out-of-range exponent, half-way ambiguity, or truncation-retry disagreement.
      * Callers must then fall back to a slow-path parser (e.g. `java.lang.Double.parseDouble`) to preserve correctness.
      *
      * Mirrors Go's `atof64` flow in `src/internal/strconv/atof.go`: scan → `atof64exact` (integer × / ÷ power-of-10 if everything fits
      * exactly in `double` arithmetic) → Eisel-Lemire → (if truncated) confirm by re-running on `mantissa + 1`.
      */
    def parseDouble(input: Array[Byte], start: Int, end: Int): Long =
        val mantOut = new Array[Long](1)
        val expOut  = new Array[Int](1)
        val negOut  = new Array[Boolean](1)
        val status  = readFloat(input, start, end, mantOut, expOut, negOut)
        if status == ScanFailed then return DoubleBailOut
        val mantissa  = mantOut(0)
        val exp10     = expOut(0)
        val neg       = negOut(0)
        val truncated = status == ScanTruncated

        // Try exact-arithmetic path first (only when mantissa was NOT truncated — a truncated mantissa
        // can't be the "exact integer × 10^k" form).
        if !truncated then
            val exactBits = atof64Exact(mantissa, exp10, neg)
            if exactBits != DoubleBailOut then return exactBits

        val bits = eiselLemire64(mantissa, exp10, neg)
        if bits == DoubleBailOut then DoubleBailOut
        else if !truncated then bits
        else
            // Mirrors Go's `atof64` truncation retry: if the mantissa was truncated at 19 digits, re-run on
            // `mantissa + 1`. If both bounds produce the same double, the truncated bits were safe and we
            // can commit the result. Otherwise bail out.
            val bitsUp = eiselLemire64(mantissa + 1L, exp10, neg)
            if bitsUp == bits then bits else DoubleBailOut
        end if
    end parseDouble

    /** Parses the ASCII bytes `input[start, end)` as a decimal number and returns the IEEE 754 float bits. Analogous to [[parseDouble]] —
      * returns [[FloatBailOut]] (`Int.MinValue`) on any bail-out.
      */
    def parseFloat(input: Array[Byte], start: Int, end: Int): Int =
        val mantOut = new Array[Long](1)
        val expOut  = new Array[Int](1)
        val negOut  = new Array[Boolean](1)
        val status  = readFloat(input, start, end, mantOut, expOut, negOut)
        if status == ScanFailed then return FloatBailOut
        val mantissa  = mantOut(0)
        val exp10     = expOut(0)
        val neg       = negOut(0)
        val truncated = status == ScanTruncated

        if !truncated then
            val exactBits = atof32Exact(mantissa, exp10, neg)
            if exactBits != FloatBailOut then return exactBits

        val bits = eiselLemire32(mantissa, exp10, neg)
        if bits == FloatBailOut then FloatBailOut
        else if !truncated then bits
        else
            val bitsUp = eiselLemire32(mantissa + 1L, exp10, neg)
            if bitsUp == bits then bits else FloatBailOut
        end if
    end parseFloat

end FastFloat
