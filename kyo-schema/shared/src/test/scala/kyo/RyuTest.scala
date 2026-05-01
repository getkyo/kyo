package kyo.internal

import kyo.*

/** Direct unit tests for Ryu algorithm (bypasses JsonWriter/JsonReader) */
class RyuTest extends Test:

    // Helper to convert Ryu output to string
    def ryuDouble(value: Double): String =
        val buf = new Array[Byte](32)
        val len = Ryu.RyuDouble.write(value, buf, 0, buf.length)
        new String(buf, 0, len, java.nio.charset.StandardCharsets.US_ASCII)
    end ryuDouble

    def ryuFloat(value: Float): String =
        val buf = new Array[Byte](32)
        val len = Ryu.RyuFloat.write(value, buf, 0, buf.length)
        new String(buf, 0, len, java.nio.charset.StandardCharsets.US_ASCII)
    end ryuFloat

    // Round-trip assertion helpers
    def assertDoubleRoundTrip(v: Double): Assertion =
        val str    = ryuDouble(v)
        val parsed = java.lang.Double.parseDouble(str)
        assert(parsed == v, s"Double round-trip failed: $v -> '$str' -> $parsed")
    end assertDoubleRoundTrip

    def assertFloatRoundTrip(v: Float): Assertion =
        val str    = ryuFloat(v)
        val parsed = java.lang.Float.parseFloat(str)
        assert(parsed == v, s"Float round-trip failed: $v -> '$str' -> $parsed")
    end assertFloatRoundTrip

    // --- RyuDouble basic tests ---

    "RyuDouble formats 0.0 correctly" in {
        assert(ryuDouble(0.0) == "0.0")
    }

    "RyuDouble formats -0.0 correctly" in {
        assert(ryuDouble(-0.0) == "-0.0")
    }

    "RyuDouble formats 1.0 correctly" in {
        assert(ryuDouble(1.0) == "1.0")
    }

    "RyuDouble formats -1.0 correctly" in {
        assert(ryuDouble(-1.0) == "-1.0")
    }

    "RyuDouble formats 3.14159 correctly" in {
        val result = ryuDouble(3.14159)
        assert(result == "3.14159", s"Got: $result")
    }

    "RyuDouble formats Double.MinValue correctly" in {
        val result = ryuDouble(Double.MinValue)
        val parsed = java.lang.Double.parseDouble(result)
        assert(parsed == Double.MinValue, s"Got: $result, parsed: $parsed")
    }

    "RyuDouble formats Double.MaxValue correctly" in {
        val result = ryuDouble(Double.MaxValue)
        val parsed = java.lang.Double.parseDouble(result)
        assert(parsed == Double.MaxValue, s"Got: $result, parsed: $parsed")
    }

    "RyuDouble formats Double.MinPositiveValue correctly" in {
        val result = ryuDouble(Double.MinPositiveValue)
        val parsed = java.lang.Double.parseDouble(result)
        assert(parsed == Double.MinPositiveValue, s"Got: $result, parsed: $parsed")
    }

    "RyuDouble formats scientific notation correctly" in {
        val result = ryuDouble(1.23e100)
        assert(result.contains("E"), s"Expected scientific notation, got: $result")
    }

    "RyuDouble formats small numbers correctly" in {
        val result = ryuDouble(0.001)
        assert(result == "0.001", s"Got: $result")
    }

    "RyuDouble round-trips through parsing" in {
        val values = Seq(0.0, 1.0, -1.0, 3.14159, 1e10, 1e-10, Double.MinValue, Double.MaxValue)
        values.foreach { v =>
            val str    = ryuDouble(v)
            val parsed = java.lang.Double.parseDouble(str)
            assert(parsed == v, s"$v -> '$str' -> $parsed")
        }
        succeed
    }

    // --- RyuFloat basic tests ---

    "RyuFloat formats 0.0f correctly" in {
        assert(ryuFloat(0.0f) == "0.0")
    }

    "RyuFloat formats -0.0f correctly" in {
        assert(ryuFloat(-0.0f) == "-0.0")
    }

    "RyuFloat formats 1.0f correctly" in {
        assert(ryuFloat(1.0f) == "1.0")
    }

    "RyuFloat formats 3.14f correctly" in {
        val result = ryuFloat(3.14f)
        assert(result == "3.14", s"Got: $result")
    }

    "RyuFloat formats Float.MinValue correctly" in {
        val result = ryuFloat(Float.MinValue)
        val parsed = java.lang.Float.parseFloat(result)
        assert(parsed == Float.MinValue, s"Got: $result, parsed: $parsed")
    }

    "RyuFloat formats Float.MaxValue correctly" in {
        val result = ryuFloat(Float.MaxValue)
        val parsed = java.lang.Float.parseFloat(result)
        assert(parsed == Float.MaxValue, s"Got: $result, parsed: $parsed")
    }

    "RyuFloat round-trips through parsing" in {
        val values = Seq(0.0f, 1.0f, -1.0f, 3.14f, 1e10f, 1e-10f, Float.MinValue, Float.MaxValue)
        values.foreach { v =>
            val str    = ryuFloat(v)
            val parsed = java.lang.Float.parseFloat(str)
            assert(parsed == v, s"$v -> '$str' -> $parsed")
        }
        succeed
    }

    // --- Shared utilities tests ---

    "digitCount counts digits correctly" in {
        assert(Ryu.digitCount(0L) == 1)
        assert(Ryu.digitCount(9L) == 1)
        assert(Ryu.digitCount(10L) == 2)
        assert(Ryu.digitCount(99L) == 2)
        assert(Ryu.digitCount(100L) == 3)
        assert(Ryu.digitCount(999999999999999999L) == 18)
    }

    // =========================================================================
    // 3a. Subnormal/denormal transition tests
    // =========================================================================

    "RyuDouble round-trips smallest normal double (2.2250738585072014E-308)" in {
        assertDoubleRoundTrip(2.2250738585072014e-308)
    }

    "RyuDouble round-trips near subnormal boundary (2.2250738585072E-308)" in {
        assertDoubleRoundTrip(2.2250738585072e-308)
    }

    "RyuDouble round-trips smallest subnormal double (5E-324)" in {
        assertDoubleRoundTrip(5e-324)
    }

    "RyuDouble round-trips adjacent subnormal (4.9406564584124654E-324)" in {
        assertDoubleRoundTrip(4.9406564584124654e-324)
    }

    "RyuDouble round-trips largest double (1.7976931348623157E308)" in {
        assertDoubleRoundTrip(1.7976931348623157e308)
    }

    "RyuDouble round-trips largest subnormal double (2.225073858507201E-308)" in {
        // Largest subnormal = smallest normal - 1 ULP
        val largestSubnormal = java.lang.Double.longBitsToDouble(0x000fffffffffffffL)
        assertDoubleRoundTrip(largestSubnormal)
    }

    "RyuFloat round-trips smallest normal float (1.1754944E-38)" in {
        assertFloatRoundTrip(1.1754944e-38f)
    }

    "RyuFloat round-trips smallest subnormal float (1.4E-45)" in {
        assertFloatRoundTrip(1.4e-45f)
    }

    "RyuFloat round-trips largest subnormal float (1.1754942E-38)" in {
        assertFloatRoundTrip(1.1754942e-38f)
    }

    "RyuDouble round-trips negative smallest normal double" in {
        assertDoubleRoundTrip(-2.2250738585072014e-308)
    }

    // =========================================================================
    // 3b. Exponent boundary transitions
    // =========================================================================

    "RyuDouble round-trips just below 1e-3 boundary (9.999999999999998E-4)" in {
        assertDoubleRoundTrip(9.999999999999998e-4)
    }

    "RyuDouble round-trips at 1e-3 boundary (0.001)" in {
        assertDoubleRoundTrip(0.001)
    }

    "RyuDouble round-trips just above 1e-3 boundary (0.0010000000000000002)" in {
        assertDoubleRoundTrip(0.0010000000000000002)
    }

    "RyuDouble round-trips just below 1e7 boundary (9999999.999999998)" in {
        assertDoubleRoundTrip(9999999.999999998)
    }

    "RyuDouble round-trips at 1e7 boundary" in {
        assertDoubleRoundTrip(1.0e7)
    }

    "RyuDouble round-trips just above 1e7 boundary (1.0000000000000002E7)" in {
        assertDoubleRoundTrip(1.0000000000000002e7)
    }

    "RyuFloat round-trips 1.0E-3f" in {
        assertFloatRoundTrip(1.0e-3f)
    }

    "RyuFloat round-trips 1.0E7f" in {
        assertFloatRoundTrip(1.0e7f)
    }

    // =========================================================================
    // 3c. Power-of-2 sequences
    // =========================================================================

    "RyuDouble round-trips positive powers of 2" in {
        var v = 1.0
        var i = 0
        while i <= 52 do
            assertDoubleRoundTrip(v)
            v *= 2.0
            i += 1
        end while
        succeed
    }

    "RyuDouble round-trips negative powers of 2" in {
        var v = 0.5
        var i = 0
        while i <= 52 do
            assertDoubleRoundTrip(v)
            v /= 2.0
            i += 1
        end while
        succeed
    }

    "RyuDouble round-trips 1 + epsilon (1.0000000000000002)" in {
        // 1.0 + 2^-52
        assertDoubleRoundTrip(1.0000000000000002)
    }

    "RyuDouble round-trips 2^53 (9007199254740992.0)" in {
        assertDoubleRoundTrip(9007199254740992.0)
    }

    "RyuDouble round-trips 2^-1074 (smallest positive double)" in {
        assertDoubleRoundTrip(java.lang.Double.longBitsToDouble(1L))
    }

    "RyuFloat round-trips positive powers of 2" in {
        var v = 1.0f
        var i = 0
        while i <= 23 do
            assertFloatRoundTrip(v)
            v *= 2.0f
            i += 1
        end while
        succeed
    }

    "RyuFloat round-trips negative powers of 2" in {
        var v = 0.5f
        var i = 0
        while i <= 23 do
            assertFloatRoundTrip(v)
            v /= 2.0f
            i += 1
        end while
        succeed
    }

    "RyuFloat round-trips 2^24 (16777216.0f)" in {
        assertFloatRoundTrip(16777216.0f)
    }

    "RyuFloat round-trips 2^-149 (smallest positive float)" in {
        assertFloatRoundTrip(java.lang.Float.intBitsToFloat(1))
    }

    "RyuDouble round-trips large powers of 2" in {
        assertDoubleRoundTrip(math.pow(2.0, 100))
        assertDoubleRoundTrip(math.pow(2.0, 200))
        assertDoubleRoundTrip(math.pow(2.0, 500))
        assertDoubleRoundTrip(math.pow(2.0, 1000))
        succeed
    }

    // =========================================================================
    // 3d. Power-of-5 corner cases
    // =========================================================================

    "RyuDouble round-trips 5.0E-1" in {
        assertDoubleRoundTrip(5.0e-1)
    }

    "RyuDouble round-trips 2.5E-1" in {
        assertDoubleRoundTrip(2.5e-1)
    }

    "RyuDouble round-trips 1.25E-1" in {
        assertDoubleRoundTrip(1.25e-1)
    }

    "RyuDouble round-trips 5.0E-15" in {
        assertDoubleRoundTrip(5.0e-15)
    }

    "RyuDouble round-trips 5.0E15" in {
        assertDoubleRoundTrip(5.0e15)
    }

    "RyuFloat round-trips 5.0E-7f" in {
        assertFloatRoundTrip(5.0e-7f)
    }

    "RyuFloat round-trips 5.0E7f" in {
        assertFloatRoundTrip(5.0e7f)
    }

    // =========================================================================
    // 3e. Trailing zero patterns
    // =========================================================================

    "RyuDouble round-trips 1000000.0" in {
        assertDoubleRoundTrip(1000000.0)
    }

    "RyuDouble round-trips 100.0" in {
        assertDoubleRoundTrip(100.0)
    }

    "RyuDouble round-trips 10.0" in {
        assertDoubleRoundTrip(10.0)
    }

    "RyuDouble round-trips 1.0E20" in {
        assertDoubleRoundTrip(1.0e20)
    }

    "RyuDouble round-trips 1.0E-20" in {
        assertDoubleRoundTrip(1.0e-20)
    }

    "RyuDouble round-trips 1.0E15" in {
        assertDoubleRoundTrip(1.0e15)
    }

    "RyuDouble round-trips 1.0E23" in {
        assertDoubleRoundTrip(1.0e23)
    }

    "RyuDouble round-trips 10000.0" in {
        assertDoubleRoundTrip(10000.0)
    }

    // =========================================================================
    // 3f. Rounding edge cases
    // =========================================================================

    "RyuDouble round-trips 2.109808898695963E16" in {
        assertDoubleRoundTrip(2.109808898695963e16)
    }

    "RyuDouble round-trips 1 + 1 ULP via bit manipulation" in {
        val v = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(1.0) + 1)
        assertDoubleRoundTrip(v)
    }

    "RyuDouble round-trips 1 - 1 ULP via bit manipulation" in {
        val v = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(1.0) - 1)
        assertDoubleRoundTrip(v)
    }

    "RyuDouble round-trips values at exact midpoints" in {
        // A value exactly between two representable doubles
        assertDoubleRoundTrip(6.631236871469758e+018)
        assertDoubleRoundTrip(4.5e+15)
        succeed
    }

    "RyuFloat round-trips 3.4028235E38 (Float.MaxValue)" in {
        assertFloatRoundTrip(3.4028235e38f)
    }

    "RyuFloat round-trips 1.0000001f (1 + float epsilon)" in {
        assertFloatRoundTrip(1.0000001f)
    }

    "RyuDouble round-trips 9007199254740994.0 (2^53 + 2)" in {
        assertDoubleRoundTrip(9007199254740994.0)
    }

    "RyuDouble round-trips 2.2204460492503131E-16 (machine epsilon)" in {
        assertDoubleRoundTrip(2.2204460492503131e-16)
    }

    // =========================================================================
    // 3g. Regression suite (values from ulfjack/ryu d2s_test.cc and f2s_test.cc)
    // =========================================================================

    "RyuDouble round-trips regression: 4.940656E-318" in {
        assertDoubleRoundTrip(4.940656e-318)
    }

    "RyuDouble round-trips regression: 1.18575755E-316" in {
        assertDoubleRoundTrip(1.18575755e-316)
    }

    "RyuDouble round-trips regression: 2.989102097996E-312" in {
        assertDoubleRoundTrip(2.989102097996e-312)
    }

    "RyuDouble round-trips regression: 9.0608011534336E15" in {
        assertDoubleRoundTrip(9.0608011534336e15)
    }

    "RyuDouble round-trips regression: 4.708356024711512E18" in {
        assertDoubleRoundTrip(4.708356024711512e18)
    }

    "RyuDouble round-trips regression: 9.409340012568248E18" in {
        assertDoubleRoundTrip(9.409340012568248e18)
    }

    "RyuDouble round-trips regression: 1.2345678901234567E20" in {
        assertDoubleRoundTrip(1.2345678901234567e20)
    }

    "RyuDouble round-trips regression: 4.4501477170144003E-308" in {
        assertDoubleRoundTrip(4.4501477170144003e-308)
    }

    "RyuDouble round-trips regression: 1.7800590868057611E-307" in {
        assertDoubleRoundTrip(1.7800590868057611e-307)
    }

    "RyuDouble round-trips regression: 1.2379400392853803E15" in {
        assertDoubleRoundTrip(1.2379400392853803e15)
    }

    "RyuDouble round-trips regression: 1.2345678901234567E-300" in {
        assertDoubleRoundTrip(1.2345678901234567e-300)
    }

    "RyuDouble round-trips regression: 1.8531501765868567E-21" in {
        assertDoubleRoundTrip(1.8531501765868567e-21)
    }

    "RyuDouble round-trips regression: 6.104543372E18" in {
        assertDoubleRoundTrip(6.104543372e18)
    }

    "RyuDouble round-trips regression: 2.2250738585072014E-308 (smallest normal)" in {
        assertDoubleRoundTrip(2.2250738585072014e-308)
    }

    "RyuDouble round-trips regression: 2.2250738585072011E-308" in {
        assertDoubleRoundTrip(2.2250738585072011e-308)
    }

    "RyuDouble round-trips regression: 5.0E-324" in {
        assertDoubleRoundTrip(5.0e-324)
    }

    "RyuDouble round-trips regression: 6.631236871469758E18" in {
        assertDoubleRoundTrip(6.631236871469758e18)
    }

    "RyuDouble round-trips regression: 3.0540412E15" in {
        assertDoubleRoundTrip(3.0540412e15)
    }

    "RyuDouble round-trips regression: 8.0E12" in {
        assertDoubleRoundTrip(8.0e12)
    }

    "RyuDouble round-trips regression: 4.3452007E7" in {
        assertDoubleRoundTrip(4.3452007e7)
    }

    // =========================================================================
    // 3g (float). Regression suite - float values from f2s_test.cc
    // =========================================================================

    "RyuFloat round-trips regression: 5.764607523034235E39 (outside float range)" in {
        // This is a double value from the ryu reference; test as double
        assertDoubleRoundTrip(5.764607523034235e39)
    }

    "RyuFloat round-trips regression: 2.4414062E-4f" in {
        assertFloatRoundTrip(2.4414062e-4f)
    }

    "RyuFloat round-trips regression: 3.3554432E7f" in {
        assertFloatRoundTrip(3.3554432e7f)
    }

    "RyuFloat round-trips regression: 1.18E-38f" in {
        assertFloatRoundTrip(1.18e-38f)
    }

    "RyuFloat round-trips regression: 2.8823261E17f" in {
        assertFloatRoundTrip(2.8823261e17f)
    }

    "RyuFloat round-trips regression: 7.0385309E-26f" in {
        assertFloatRoundTrip(7.0385309e-26f)
    }

    "RyuFloat round-trips regression: 9.2E-8f" in {
        assertFloatRoundTrip(9.2e-8f)
    }

    "RyuFloat round-trips regression: 6.7108864E7f" in {
        assertFloatRoundTrip(6.7108864e7f)
    }

    "RyuFloat round-trips regression: 1.0E-44f" in {
        assertFloatRoundTrip(1.0e-44f)
    }

    "RyuFloat round-trips regression: 2.816025E14f" in {
        assertFloatRoundTrip(2.816025e14f)
    }

    // =========================================================================
    // 3h. Float-specific edge cases
    // =========================================================================

    "RyuFloat round-trips boundary rounding even: 33554450.0f" in {
        assertFloatRoundTrip(33554450.0f)
    }

    "RyuFloat round-trips boundary rounding even: 8388609.0f" in {
        assertFloatRoundTrip(8388609.0f)
    }

    "RyuFloat round-trips 3.355445E7f" in {
        assertFloatRoundTrip(3.355445e7f)
    }

    "RyuFloat round-trips 9.0E9f" in {
        assertFloatRoundTrip(9.0e9f)
    }

    "RyuFloat round-trips near Float.MaxValue: 3.4028234E38f" in {
        assertFloatRoundTrip(3.4028234e38f)
    }

    "RyuFloat round-trips near smallest normal: 1.17549435E-38f" in {
        assertFloatRoundTrip(1.17549435e-38f)
    }

    "RyuFloat round-trips 8.999999E9f" in {
        assertFloatRoundTrip(8.999999e9f)
    }

    "RyuFloat round-trips 1.0E1f" in {
        assertFloatRoundTrip(1.0e1f)
    }

    "RyuFloat round-trips Float.MinPositiveValue" in {
        assertFloatRoundTrip(Float.MinPositiveValue)
    }

    "RyuFloat round-trips negative smallest subnormal" in {
        assertFloatRoundTrip(-Float.MinPositiveValue)
    }

    // =========================================================================
    // 3h (additional). NaN and Infinity
    // =========================================================================

    "RyuDouble round-trips NaN" in {
        val result = ryuDouble(Double.NaN)
        val parsed = java.lang.Double.parseDouble(result)
        assert(java.lang.Double.isNaN(parsed), s"Expected NaN, got: $result -> $parsed")
    }

    "RyuDouble round-trips PositiveInfinity" in {
        val result = ryuDouble(Double.PositiveInfinity)
        val parsed = java.lang.Double.parseDouble(result)
        assert(parsed == Double.PositiveInfinity, s"Expected +Inf, got: $result -> $parsed")
    }

    "RyuDouble formats PositiveInfinity as Infinity" in {
        val result = ryuDouble(Double.PositiveInfinity)
        assert(result == "Infinity", s"Expected 'Infinity', got: '$result'")
    }

    "RyuDouble round-trips NegativeInfinity" in {
        val result = ryuDouble(Double.NegativeInfinity)
        val parsed = java.lang.Double.parseDouble(result)
        assert(parsed == Double.NegativeInfinity, s"Expected -Inf, got: $result -> $parsed")
    }

    "RyuDouble formats NegativeInfinity as -Infinity" in {
        val result = ryuDouble(Double.NegativeInfinity)
        assert(result == "-Infinity", s"Expected '-Infinity', got: '$result'")
    }

    "RyuFloat round-trips NaN" in {
        val result = ryuFloat(Float.NaN)
        val parsed = java.lang.Float.parseFloat(result)
        assert(java.lang.Float.isNaN(parsed), s"Expected NaN, got: $result -> $parsed")
    }

    "RyuFloat round-trips PositiveInfinity" in {
        val result = ryuFloat(Float.PositiveInfinity)
        val parsed = java.lang.Float.parseFloat(result)
        assert(parsed == Float.PositiveInfinity, s"Expected +Inf, got: $result -> $parsed")
    }

    "RyuFloat formats PositiveInfinity as Infinity" in {
        val result = ryuFloat(Float.PositiveInfinity)
        assert(result == "Infinity", s"Expected 'Infinity', got: '$result'")
    }

    "RyuFloat round-trips NegativeInfinity" in {
        val result = ryuFloat(Float.NegativeInfinity)
        val parsed = java.lang.Float.parseFloat(result)
        assert(parsed == Float.NegativeInfinity, s"Expected -Inf, got: $result -> $parsed")
    }

    "RyuFloat formats NegativeInfinity as -Infinity" in {
        val result = ryuFloat(Float.NegativeInfinity)
        assert(result == "-Infinity", s"Expected '-Infinity', got: '$result'")
    }

end RyuTest
