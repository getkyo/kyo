package kyo.internal

import scala.annotation.tailrec

/** Ryu algorithm for fast floating-point to string conversion.
  *
  * Ported from https://github.com/ulfjack/ryu by Ulf Adams. Provides the shortest decimal representation of Double and Float values that
  * round-trips correctly.
  */
private[internal] object Ryu:

    // Lookup table for 2-digit pairs: "00", "01", ..., "99"
    private[internal] val DigitPairs: Array[Byte] =
        val table = new Array[Byte](200)
        @tailrec def loop(i: Int): Unit =
            if i < 100 then
                table(i * 2) = ('0' + i / 10).toByte
                table(i * 2 + 1) = ('0' + i % 10).toByte
                loop(i + 1)
        loop(0)
        table
    end DigitPairs

    // Count decimal digits in a non-negative long using comparison chain (faster than log10).
    private[internal] def digitCount(v: Long): Int =
        if v < 10L then 1
        else if v < 100L then 2
        else if v < 1000L then 3
        else if v < 10000L then 4
        else if v < 100000L then 5
        else if v < 1000000L then 6
        else if v < 10000000L then 7
        else if v < 100000000L then 8
        else if v < 1000000000L then 9
        else if v < 10000000000L then 10
        else if v < 100000000000L then 11
        else if v < 1000000000000L then 12
        else if v < 10000000000000L then 13
        else if v < 100000000000000L then 14
        else if v < 1000000000000000L then 15
        else if v < 10000000000000000L then 16
        else if v < 100000000000000000L then 17
        else if v < 1000000000000000000L then 18
        else 19

    // Write a positive int (up to 3 digits, used for exponents) to buf, returning new pos.
    private[internal] def writePositiveInt(value: Int, buf: Array[Byte], pos: Int): Int =
        if value < 10 then
            buf(pos) = ('0' + value).toByte
            pos + 1
        else if value < 100 then
            buf(pos) = ('0' + value / 10).toByte
            buf(pos + 1) = ('0' + value % 10).toByte
            pos + 2
        else
            buf(pos) = ('0' + value / 100).toByte
            buf(pos + 1) = ('0' + (value / 10) % 10).toByte
            buf(pos + 2) = ('0' + value        % 10).toByte
            pos + 3

    // ---- Ryu algorithm for Double ----
    // Writes the shortest decimal representation of a double directly to a byte buffer.
    object RyuDouble:
        private val DoubleMantissaBits = 52
        private val DoubleBias         = 1023

        // Max output length: sign + 17 digits + decimal point + 'E' + sign + 3 digit exp = 24
        private val MaxOutputLen = 24

        // Returns new pos, or negative value (-requiredPos) if buffer too small
        def write(value: Double, buf: Array[Byte], startPos: Int, bufLen: Int): Int =
            if bufLen - startPos < MaxOutputLen then -(startPos + MaxOutputLen)
            else writeImpl(value, buf, startPos)

        private def writeImpl(value: Double, buf: Array[Byte], startPos: Int): Int =
            val bits         = java.lang.Double.doubleToRawLongBits(value)
            val sign         = ((bits >>> 63) & 1).toInt
            val ieeeExponent = ((bits >>> DoubleMantissaBits) & 0x7ffL).toInt
            val ieeeMantissa = bits & ((1L << DoubleMantissaBits) - 1)

            val pos = if sign != 0 then
                buf(startPos) = '-'
                startPos + 1
            else
                startPos

            if ieeeExponent == 0x7ff then
                if ieeeMantissa != 0 then
                    // NaN — sign is ignored per Java's Double.toString
                    buf(startPos) = 'N'
                    buf(startPos + 1) = 'a'
                    buf(startPos + 2) = 'N'
                    startPos + 3
                else
                    // Infinity
                    buf(pos) = 'I'
                    buf(pos + 1) = 'n'
                    buf(pos + 2) = 'f'
                    buf(pos + 3) = 'i'
                    buf(pos + 4) = 'n'
                    buf(pos + 5) = 'i'
                    buf(pos + 6) = 't'
                    buf(pos + 7) = 'y'
                    pos + 8
            else if ieeeExponent == 0 && ieeeMantissa == 0 then
                // Zero
                buf(pos) = '0'
                buf(pos + 1) = '.'
                buf(pos + 2) = '0'
                pos + 3
            else
                // Step 1: Decode the floating point number and convert to decimal.
                val (e2, m2) =
                    if ieeeExponent == 0 then
                        // Subnormal
                        (1 - DoubleBias - DoubleMantissaBits, ieeeMantissa)
                    else
                        (ieeeExponent - DoubleBias - DoubleMantissaBits, ieeeMantissa | (1L << DoubleMantissaBits))

                val even         = (m2 & 1) == 0
                val acceptBounds = even

                // Step 2: Determine the interval of valid decimal representations.
                val mv      = 4 * m2
                val mmShift = if ieeeMantissa != 0 || ieeeExponent <= 1 then 1 else 0
                // mp = mv + 2, mm = mv - 1 - mmShift

                // Step 3: Convert to a decimal power base.
                val (e10, initVr, initVp, initVm, vrIsTrailingZeros0, vmIsTrailingZeros0) =
                    if e2 >= 0 then
                        val q   = math.max(0, ((e2 * 78913L) >> 18).toInt - 1)
                        val k   = RyuTables.DOUBLE_POW5_INV_BITCOUNT + pow5bits(q) - 1
                        val i   = -e2 + q + k + 2 // +2 compensates for mv = 4*m2
                        val vr0 = mulShiftAll(m2, RyuTables.DOUBLE_POW5_INV_SPLIT(q), i, mmShift)
                        val vp0 = mulShiftAllHigh(m2, RyuTables.DOUBLE_POW5_INV_SPLIT(q), i, mmShift)
                        val vm0 = mulShiftAllLow(m2, RyuTables.DOUBLE_POW5_INV_SPLIT(q), i, mmShift)
                        if q <= 21 then
                            if mv % 5 == 0 then
                                (q, vr0, vp0, vm0, multipleOfPowerOf5(mv, q), false)
                            else if acceptBounds then
                                (q, vr0, vp0, vm0, false, multipleOfPowerOf5(mv - 1 - mmShift, q))
                            else
                                val adjVp = if multipleOfPowerOf5(mv + 2, q) then vp0 - 1 else vp0
                                (q, vr0, adjVp, vm0, false, false)
                        else
                            (q, vr0, vp0, vm0, false, false)
                        end if
                    else
                        val q   = math.max(0, ((-e2 * 732923L) >> 20).toInt - 1)
                        val i   = -e2 - q
                        val k   = pow5bits(i) - RyuTables.DOUBLE_POW5_BITCOUNT
                        val j   = q - k + 2 // +2 compensates for mv = 4*m2
                        val vr0 = mulShiftAll(m2, RyuTables.DOUBLE_POW5_SPLIT(i), j, mmShift)
                        val vp0 = mulShiftAllHigh(m2, RyuTables.DOUBLE_POW5_SPLIT(i), j, mmShift)
                        val vm0 = mulShiftAllLow(m2, RyuTables.DOUBLE_POW5_SPLIT(i), j, mmShift)
                        if q <= 1 then
                            if acceptBounds then
                                (q + e2, vr0, vp0, vm0, true, mmShift == 1)
                            else
                                (q + e2, vr0, vp0 - 1, vm0, true, false)
                        else if q < 63 then
                            (q + e2, vr0, vp0, vm0, (mv & ((1L << (q - 1)) - 1)) == 0, false)
                        else
                            (q + e2, vr0, vp0, vm0, false, false)
                        end if
                    end if
                end val

                // Step 4: Find the shortest decimal representation.
                val (removed, output) =
                    if vmIsTrailingZeros0 || vrIsTrailingZeros0 then
                        // General case: figure out whether we should round up.
                        // First loop: remove digits until vp / 10 <= vm / 10
                        @tailrec def loop1(
                            vr: Long,
                            vp: Long,
                            vm: Long,
                            vmTZ: Boolean,
                            vrTZ: Boolean,
                            lastRemoved: Int,
                            removed: Int
                        ): (Long, Long, Long, Boolean, Boolean, Int, Int) =
                            if vp / 10 > vm / 10 then
                                loop1(
                                    vr / 10,
                                    vp / 10,
                                    vm / 10,
                                    vmTZ && (vm % 10 == 0),
                                    vrTZ && (lastRemoved == 0),
                                    (vr % 10).toInt,
                                    removed + 1
                                )
                            else
                                (vr, vp, vm, vmTZ, vrTZ, lastRemoved, removed)
                        val (vr1, vp1, vm1, vmTZ1, vrTZ1, lastRemoved1, removed1) =
                            loop1(initVr, initVp, initVm, vmIsTrailingZeros0, vrIsTrailingZeros0, 0, 0)

                        // Second loop: if vmIsTrailingZeros, keep removing trailing zeros
                        @tailrec def loop2(
                            vr: Long,
                            vp: Long,
                            vm: Long,
                            vrTZ: Boolean,
                            lastRemoved: Int,
                            removed: Int
                        ): (Long, Long, Long, Boolean, Boolean, Int, Int) =
                            if vm % 10 == 0 then
                                loop2(
                                    vr / 10,
                                    vp / 10,
                                    vm / 10,
                                    vrTZ && (lastRemoved == 0),
                                    (vr % 10).toInt,
                                    removed + 1
                                )
                            else
                                (vr, vp, vm, vrTZ, vmTZ1, lastRemoved, removed)

                        val (vr2, _vp2, vm2Final, vrTZ2, vmTZ2, lastRemoved2, removed2) =
                            if vmTZ1 then loop2(vr1, vp1, vm1, vrTZ1, lastRemoved1, removed1)
                            else (vr1, vp1, vm1, vrTZ1, vmTZ1, lastRemoved1, removed1)

                        val lastDigit =
                            if vrTZ2 && lastRemoved2 == 5 && vr2 % 2 == 0 then 4
                            else lastRemoved2
                        val out = vr2 + (if (vr2 == vm2Final && (!acceptBounds || !vmTZ2)) || lastDigit >= 5 then 1L else 0L)
                        (removed2, out)
                    else
                        // Specialized for the common case (~99.3%).
                        val vpDiv100 = initVp / 100
                        val vmDiv100 = initVm / 100
                        val (vr3, vp3, vm3, roundUp3, removed3) =
                            if vpDiv100 > vmDiv100 then
                                val vrDiv100 = initVr / 100
                                val vrMod100 = (initVr - 100 * vrDiv100).toInt
                                (vrDiv100, vpDiv100, vmDiv100, vrMod100 >= 50, 2)
                            else
                                (initVr, initVp, initVm, false, 0)
                        // Now remove digits one at a time
                        @tailrec def loop3(
                            vr: Long,
                            vp: Long,
                            vm: Long,
                            roundUp: Boolean,
                            removed: Int
                        ): (Int, Long) =
                            if vp / 10 > vm / 10 then
                                loop3(vr / 10, vp / 10, vm / 10, (vr % 10) >= 5, removed + 1)
                            else
                                val out = vr + (if vr == vm || roundUp then 1L else 0L)
                                (removed, out)
                        loop3(vr3, vp3, vm3, roundUp3, removed3)
                    end if
                end val

                val olength = Ryu.digitCount(output)
                val exp     = e10 + removed + olength - 1

                // Step 5: Format the output to match Double.toString behavior.
                // Java's Double.toString uses: plain notation for exp in [-3, 6], scientific otherwise
                // (but always at least one fraction digit, e.g. "1.0", "1.0E7")
                formatDouble(output, olength, exp, pos, buf)
            end if
        end writeImpl

        // Format double output to match Java's Double.toString exactly
        private def formatDouble(output: Long, olength: Int, exp: Int, startPos: Int, buf: Array[Byte]): Int =
            if exp >= 0 && exp < olength - 1 && exp <= 6 then
                // Decimal point in the middle: e.g., 3.14, 12.345
                val wholeDigits = exp + 1
                writeDigits(output, olength, buf, startPos)
                // Shift fractional part right to make room for '.'
                System.arraycopy(buf, startPos + wholeDigits, buf, startPos + wholeDigits + 1, olength - wholeDigits)
                buf(startPos + wholeDigits) = '.'
                startPos + olength + 1
            else if exp >= olength - 1 && exp <= 6 then
                // Integer with trailing zeros: e.g., 100.0, 1000.0
                writeDigits(output, olength, buf, startPos)
                val trailingZeros = exp - olength + 1
                @tailrec def fillZeros(pos: Int, remaining: Int): Int =
                    if remaining > 0 then
                        buf(pos) = '0'
                        fillZeros(pos + 1, remaining - 1)
                    else
                        pos
                val posAfterZeros = fillZeros(startPos + olength, trailingZeros)
                buf(posAfterZeros) = '.'
                buf(posAfterZeros + 1) = '0'
                posAfterZeros + 2
            else if exp >= -3 && exp < 0 then
                // Small number: 0.001 to 0.999...
                buf(startPos) = '0'
                buf(startPos + 1) = '.'
                val leadingZeros = -(exp + 1)
                @tailrec def fillZeros(pos: Int, remaining: Int): Int =
                    if remaining > 0 then
                        buf(pos) = '0'
                        fillZeros(pos + 1, remaining - 1)
                    else
                        pos
                val posAfterZeros = fillZeros(startPos + 2, leadingZeros)
                writeDigits(output, olength, buf, posAfterZeros)
                posAfterZeros + olength
            else
                // Scientific notation: e.g., 1.0E7, 1.23E-15
                writeDigits(output, olength, buf, startPos)
                val posAfterDigits = if olength > 1 then
                    // Shift to make room for '.'
                    System.arraycopy(buf, startPos + 1, buf, startPos + 2, olength - 1)
                    buf(startPos + 1) = '.'
                    startPos + olength + 1
                else
                    buf(startPos + 1) = '.'
                    buf(startPos + 2) = '0'
                    startPos + 3
                buf(posAfterDigits) = 'E'
                val posAfterE = posAfterDigits + 1
                if exp < 0 then
                    buf(posAfterE) = '-'
                    Ryu.writePositiveInt(-exp, buf, posAfterE + 1)
                else
                    Ryu.writePositiveInt(exp, buf, posAfterE)
                end if
            end if
        end formatDouble

        // Write digits of a long to buffer (most-significant first)
        private def writeDigits(output: Long, olength: Int, buf: Array[Byte], pos: Int): Unit =
            val dp = Ryu.DigitPairs
            @tailrec def loop(i: Int, v: Long): Unit =
                if i >= 1 then
                    val q     = (v % 100).toInt
                    val nextV = v / 100
                    buf(pos + i) = dp(q * 2 + 1)
                    buf(pos + i - 1) = dp(q * 2)
                    loop(i - 2, nextV)
                else if i == 0 then
                    buf(pos) = ('0' + (v % 10).toInt).toByte
            loop(olength - 1, output)
        end writeDigits

        private def pow5bits(e: Int): Int =
            ((e * 1217359L) >> 19).toInt + 1

        private def multipleOfPowerOf5(value: Long, p: Int): Boolean =
            pow5Factor(value) >= p

        private def pow5Factor(value: Long): Int =
            @tailrec def loop(v: Long, count: Int): Int =
                if v > 0 then
                    if v % 5 != 0 then count
                    else loop(v / 5, count + 1)
                else
                    0
            loop(value, 0)
        end pow5Factor

        // Multiply m by the 128-bit value in mul, shift right by j, for center value
        private def mulShiftAll(m: Long, mul: Array[Long], j: Int, mmShift: Int): Long =
            mulShift(4 * m, mul, j)

        // High value: 4*m + 2
        private def mulShiftAllHigh(m: Long, mul: Array[Long], j: Int, mmShift: Int): Long =
            mulShift(4 * m + 2, mul, j)

        // Low value: 4*m - 1 - mmShift
        private def mulShiftAllLow(m: Long, mul: Array[Long], j: Int, mmShift: Int): Long =
            mulShift(4 * m - 1 - mmShift, mul, j)

        private def mulShift(m: Long, mul: Array[Long], j: Int): Long =
            // m * (mul(0) << 64 + mul(1)) >> j
            // m*mul(0) produces (high0, low0) — shifted left by 64 in the full product
            // m*mul(1) produces (high1, low1) — low1 is discarded (below bit 0)
            // Full 192-bit product:
            //   bits 191-128: high0 + carry
            //   bits 127-64:  low0 + high1 (carry goes up)
            //   bits 63-0:    low1 (not needed)
            val high0 = unsignedMulHigh(m, mul(0))
            val low0  = m * mul(0) // unsigned low bits of m*mul(0)
            val high1 = unsignedMulHigh(m, mul(1))
            val sum   = low0 + high1
            // carry detection: if sum < low0 (unsigned), carry happened
            val carry  = if java.lang.Long.compareUnsigned(sum, low0) < 0 then 1L else 0L
            val result = high0 + carry
            shiftRight128(sum, result, j - 64)
        end mulShift

        // Compute high 64 bits of unsigned 64x64 -> 128-bit multiplication
        private[internal] def unsignedMulHigh(a: Long, b: Long): Long =
            // Split into 32-bit halves for cross-platform compatibility
            val aHi        = a >>> 32
            val aLo        = a & 0xffffffffL
            val bHi        = b >>> 32
            val bLo        = b & 0xffffffffL
            val c0         = aLo * bLo
            val c1a        = aHi * bLo
            val c1b        = aLo * bHi
            val c2         = aHi * bHi
            val mid        = c1a + (c0 >>> 32)
            val carry1     = if java.lang.Long.compareUnsigned(mid, c1a) < 0 then 1L << 32 else 0L
            val midPlusC1b = mid + c1b
            val carry2     = if java.lang.Long.compareUnsigned(midPlusC1b, mid) < 0 then 1L << 32 else 0L
            c2 + (midPlusC1b >>> 32) + carry1 + carry2
        end unsignedMulHigh

        // Extract bits from 128-bit value (hi, lo) after right-shifting by `shift`
        private[internal] def shiftRight128(lo: Long, hi: Long, shift: Int): Long =
            (hi << (64 - shift)) | (lo >>> shift)

    end RyuDouble

    // ---- Ryu algorithm for Float ----
    object RyuFloat:
        private val FloatMantissaBits = 23
        private val FloatBias         = 127

        private val MaxOutputLen = 16

        def write(value: Float, buf: Array[Byte], startPos: Int, bufLen: Int): Int =
            if bufLen - startPos < MaxOutputLen then -(startPos + MaxOutputLen)
            else writeImpl(value, buf, startPos)

        private def writeImpl(value: Float, buf: Array[Byte], startPos: Int): Int =
            val bits         = java.lang.Float.floatToRawIntBits(value)
            val sign         = (bits >>> 31) & 1
            val ieeeExponent = (bits >>> FloatMantissaBits) & 0xff
            val ieeeMantissa = bits & ((1 << FloatMantissaBits) - 1)

            val pos = if sign != 0 then
                buf(startPos) = '-'
                startPos + 1
            else
                startPos

            if ieeeExponent == 0xff then
                if ieeeMantissa != 0 then
                    // NaN — sign is ignored per Java's Float.toString
                    buf(startPos) = 'N'
                    buf(startPos + 1) = 'a'
                    buf(startPos + 2) = 'N'
                    startPos + 3
                else
                    // Infinity
                    buf(pos) = 'I'
                    buf(pos + 1) = 'n'
                    buf(pos + 2) = 'f'
                    buf(pos + 3) = 'i'
                    buf(pos + 4) = 'n'
                    buf(pos + 5) = 'i'
                    buf(pos + 6) = 't'
                    buf(pos + 7) = 'y'
                    pos + 8
            else if ieeeExponent == 0 && ieeeMantissa == 0 then
                buf(pos) = '0'
                buf(pos + 1) = '.'
                buf(pos + 2) = '0'
                pos + 3
            else
                val (e2, m2) =
                    if ieeeExponent == 0 then
                        (1 - FloatBias - FloatMantissaBits, ieeeMantissa)
                    else
                        (ieeeExponent - FloatBias - FloatMantissaBits, ieeeMantissa | (1 << FloatMantissaBits))

                val even         = (m2 & 1) == 0
                val acceptBounds = even

                val mv      = 4 * m2
                val mmShift = if ieeeMantissa != 0 || ieeeExponent <= 1 then 1 else 0

                val (e10, initVr, initVp, initVm, vrIsTrailingZeros0, vmIsTrailingZeros0) =
                    if e2 >= 0 then
                        val q   = math.max(0, ((e2 * 78913L) >> 18).toInt - 1)
                        val k   = RyuTables.FLOAT_POW5_INV_BITCOUNT + pow5bits(q) - 1
                        val i   = -e2 + q + k
                        val vr0 = mulPow5InvDivPow2(mv, q, i)
                        val vp0 = mulPow5InvDivPow2(mv + 2, q, i)
                        val vm0 = mulPow5InvDivPow2(mv - 1 - mmShift, q, i)
                        if q <= 9 then
                            if mv % 5 == 0 then
                                (q, vr0, vp0, vm0, multipleOfPowerOf5(mv, q), false)
                            else if acceptBounds then
                                (q, vr0, vp0, vm0, false, multipleOfPowerOf5(mv - 1 - mmShift, q))
                            else
                                val adjVp = if multipleOfPowerOf5(mv + 2, q) then vp0 - 1 else vp0
                                (q, vr0, adjVp, vm0, false, false)
                        else
                            (q, vr0, vp0, vm0, false, false)
                        end if
                    else
                        val q   = math.max(0, ((-e2 * 732923L) >> 20).toInt - 1)
                        val i   = -e2 - q
                        val k   = pow5bits(i) - RyuTables.FLOAT_POW5_BITCOUNT
                        val j   = q - k
                        val vr0 = mulPow5DivPow2(mv, i, j)
                        val vp0 = mulPow5DivPow2(mv + 2, i, j)
                        val vm0 = mulPow5DivPow2(mv - 1 - mmShift, i, j)
                        if q <= 1 then
                            if acceptBounds then
                                (q + e2, vr0, vp0, vm0, true, mmShift == 1)
                            else
                                (q + e2, vr0, vp0 - 1, vm0, true, false)
                        else if q < 31 then
                            (q + e2, vr0, vp0, vm0, (mv & ((1 << (q - 1)) - 1)) == 0, false)
                        else
                            (q + e2, vr0, vp0, vm0, false, false)
                        end if
                    end if
                end val

                // Step 4: find shortest representation
                val (removed, output) =
                    if vmIsTrailingZeros0 || vrIsTrailingZeros0 then
                        @tailrec def loop1(
                            vr: Int,
                            vp: Int,
                            vm: Int,
                            vmTZ: Boolean,
                            vrTZ: Boolean,
                            lastRemoved: Int,
                            removed: Int
                        ): (Int, Int, Int, Boolean, Boolean, Int, Int) =
                            if vp / 10 > vm / 10 then
                                loop1(
                                    vr / 10,
                                    vp / 10,
                                    vm / 10,
                                    vmTZ && (vm % 10 == 0),
                                    vrTZ && (lastRemoved == 0),
                                    vr % 10,
                                    removed + 1
                                )
                            else
                                (vr, vp, vm, vmTZ, vrTZ, lastRemoved, removed)
                        val (vr1, vp1, vm1, vmTZ1, vrTZ1, lastRemoved1, removed1) =
                            loop1(initVr, initVp, initVm, vmIsTrailingZeros0, vrIsTrailingZeros0, 0, 0)

                        @tailrec def loop2(
                            vr: Int,
                            vp: Int,
                            vm: Int,
                            vrTZ: Boolean,
                            lastRemoved: Int,
                            removed: Int
                        ): (Int, Int, Int, Boolean, Boolean, Int, Int) =
                            if vm % 10 == 0 then
                                loop2(
                                    vr / 10,
                                    vp / 10,
                                    vm / 10,
                                    vrTZ && (lastRemoved == 0),
                                    vr % 10,
                                    removed + 1
                                )
                            else
                                (vr, vp, vm, vrTZ, vmTZ1, lastRemoved, removed)

                        val (vr2, _vp2, vm2Final, vrTZ2, vmTZ2, lastRemoved2, removed2) =
                            if vmTZ1 then loop2(vr1, vp1, vm1, vrTZ1, lastRemoved1, removed1)
                            else (vr1, vp1, vm1, vrTZ1, vmTZ1, lastRemoved1, removed1)

                        val lastDigit =
                            if vrTZ2 && lastRemoved2 == 5 && vr2 % 2 == 0 then 4
                            else lastRemoved2
                        val out = vr2 + (if (vr2 == vm2Final && (!acceptBounds || !vmTZ2)) || lastDigit >= 5 then 1 else 0)
                        (removed2, out)
                    else
                        val vpDiv100 = initVp / 100
                        val vmDiv100 = initVm / 100
                        val (vr3, vp3, vm3, roundUp3, removed3) =
                            if vpDiv100 > vmDiv100 then
                                val vrDiv100 = initVr / 100
                                val vrMod100 = initVr - 100 * vrDiv100
                                (vrDiv100, vpDiv100, vmDiv100, vrMod100 >= 50, 2)
                            else
                                (initVr, initVp, initVm, false, 0)
                        @tailrec def loop3(
                            vr: Int,
                            vp: Int,
                            vm: Int,
                            roundUp: Boolean,
                            removed: Int
                        ): (Int, Int) =
                            if vp / 10 > vm / 10 then
                                loop3(vr / 10, vp / 10, vm / 10, (vr % 10) >= 5, removed + 1)
                            else
                                val out = vr + (if vr == vm || roundUp then 1 else 0)
                                (removed, out)
                        loop3(vr3, vp3, vm3, roundUp3, removed3)
                    end if
                end val

                val olength = digitCountInt(output)
                val exp     = e10 + removed + olength - 1

                // Format to match Float.toString: plain for exp in [-3, 6], scientific otherwise
                formatFloat(output, olength, exp, pos, buf)
            end if
        end writeImpl

        private def formatFloat(output: Int, olength: Int, exp: Int, startPos: Int, buf: Array[Byte]): Int =
            if exp >= 0 && exp < olength - 1 && exp <= 6 then
                val wholeDigits = exp + 1
                writeDigitsInt(output, olength, buf, startPos)
                System.arraycopy(buf, startPos + wholeDigits, buf, startPos + wholeDigits + 1, olength - wholeDigits)
                buf(startPos + wholeDigits) = '.'
                startPos + olength + 1
            else if exp >= olength - 1 && exp <= 6 then
                writeDigitsInt(output, olength, buf, startPos)
                val trailingZeros = exp - olength + 1
                @tailrec def fillZeros(pos: Int, remaining: Int): Int =
                    if remaining > 0 then
                        buf(pos) = '0'
                        fillZeros(pos + 1, remaining - 1)
                    else
                        pos
                val posAfterZeros = fillZeros(startPos + olength, trailingZeros)
                buf(posAfterZeros) = '.'
                buf(posAfterZeros + 1) = '0'
                posAfterZeros + 2
            else if exp >= -3 && exp < 0 then
                buf(startPos) = '0'
                buf(startPos + 1) = '.'
                val leadingZeros = -(exp + 1)
                @tailrec def fillZeros(pos: Int, remaining: Int): Int =
                    if remaining > 0 then
                        buf(pos) = '0'
                        fillZeros(pos + 1, remaining - 1)
                    else
                        pos
                val posAfterZeros = fillZeros(startPos + 2, leadingZeros)
                writeDigitsInt(output, olength, buf, posAfterZeros)
                posAfterZeros + olength
            else
                writeDigitsInt(output, olength, buf, startPos)
                val posAfterDigits = if olength > 1 then
                    System.arraycopy(buf, startPos + 1, buf, startPos + 2, olength - 1)
                    buf(startPos + 1) = '.'
                    startPos + olength + 1
                else
                    buf(startPos + 1) = '.'
                    buf(startPos + 2) = '0'
                    startPos + 3
                buf(posAfterDigits) = 'E'
                val posAfterE = posAfterDigits + 1
                if exp < 0 then
                    buf(posAfterE) = '-'
                    Ryu.writePositiveInt(-exp, buf, posAfterE + 1)
                else
                    Ryu.writePositiveInt(exp, buf, posAfterE)
                end if
            end if
        end formatFloat

        private def writeDigitsInt(output: Int, olength: Int, buf: Array[Byte], pos: Int): Unit =
            val dp = Ryu.DigitPairs
            @tailrec def loop(i: Int, v: Int): Unit =
                if i >= 1 then
                    val q     = v % 100
                    val nextV = v / 100
                    buf(pos + i) = dp(q * 2 + 1)
                    buf(pos + i - 1) = dp(q * 2)
                    loop(i - 2, nextV)
                else if i == 0 then
                    buf(pos) = ('0' + v % 10).toByte
            loop(olength - 1, output)
        end writeDigitsInt

        private def digitCountInt(v: Int): Int =
            if v < 10 then 1
            else if v < 100 then 2
            else if v < 1000 then 3
            else if v < 10000 then 4
            else if v < 100000 then 5
            else if v < 1000000 then 6
            else if v < 10000000 then 7
            else if v < 100000000 then 8
            else if v < 1000000000 then 9
            else 10

        private def pow5bits(e: Int): Int =
            ((e * 1217359L) >> 19).toInt + 1

        private def multipleOfPowerOf5(value: Int, p: Int): Boolean =
            pow5Factor(value) >= p

        private def pow5Factor(value: Int): Int =
            @tailrec def loop(v: Int, count: Int): Int =
                if v > 0 then
                    if v % 5 != 0 then count
                    else loop(v / 5, count + 1)
                else
                    0
            loop(value, 0)
        end pow5Factor

        // Uses 128-bit arithmetic to avoid overflow for large exponents.
        // j is the computed shift from the caller; +2 compensates for mv = 4*m2.
        private def mulPow5InvDivPow2(m: Int, q: Int, j: Int): Int =
            val pow5  = RyuTables.FLOAT_POW5_INV_SPLIT(q)
            val mLong = m.toLong & 0xffffffffL // ensure unsigned extension
            val low   = mLong * pow5
            val high  = RyuDouble.unsignedMulHigh(mLong, pow5)
            val shift = j + 2                  // use caller's computed shift, +2 for 4*m factor
            RyuDouble.shiftRight128(low, high, shift).toInt
        end mulPow5InvDivPow2

        // Uses 128-bit arithmetic to avoid overflow for large exponents.
        // j is the computed shift from the caller; +2 compensates for mv = 4*m2.
        private def mulPow5DivPow2(m: Int, i: Int, j: Int): Int =
            val pow5  = RyuTables.FLOAT_POW5_SPLIT(i)
            val mLong = m.toLong & 0xffffffffL // ensure unsigned extension
            val low   = mLong * pow5
            val high  = RyuDouble.unsignedMulHigh(mLong, pow5)
            val shift = j + 2                  // use caller's computed shift, +2 for 4*m factor
            RyuDouble.shiftRight128(low, high, shift).toInt
        end mulPow5DivPow2

    end RyuFloat

end Ryu
