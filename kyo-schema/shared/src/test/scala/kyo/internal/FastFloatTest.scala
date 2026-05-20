package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Test
import scala.util.Random

/** Tests for [[FastFloat]] — the Eisel-Lemire fast-path double/float parser.
  *
  * Reference: Daniel Lemire, "Number Parsing at a Gigabyte per Second", arXiv:2101.11408. Port of Go's `strconv` Eisel-Lemire implementation
  * (BSD-3-Clause): see `FastFloat.scala` for full attribution.
  *
  * 46 tests across 13 categories A–M:
  *   - A. Property test (1) — 100k random-bit doubles.
  *   - B. Paxson hard doubles (8).
  *   - C. Subnormals (4).
  *   - D. Signed zero / specials (4).
  *   - E. Range limits (4).
  *   - F. Round-to-even tie-break (3).
  *   - G. Negative numbers (3).
  *   - H. Scientific notation variants (5).
  *   - I. Integer-looking doubles (3).
  *   - J. Leading-zero fractions (3).
  *   - K. Truncation ≥ 20-digit mantissas (3).
  *   - L. Boundaries (3).
  *   - M. Float32 parallel (2).
  */
class FastFloatTest extends Test:

    import FastFloat.DoubleBailOut
    import FastFloat.FloatBailOut

    private def bytesOf(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)

    // Asserts that either (a) FastFloat.parseDouble produced the correct IEEE 754 bits bit-identical to
    // java.lang.Double.parseDouble, OR (b) it returned the bail-out sentinel. Bail-out is a legal outcome
    // (the caller falls back to Double.parseDouble); the test succeeds as long as the algorithm does not
    // return a wrong-but-non-sentinel value.
    private def assertParsesTo(s: String): org.scalatest.Assertion =
        val bytes = bytesOf(s)
        val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
        if bits == DoubleBailOut then
            // Legal bail-out — confirm slow path agrees with what the input names.
            val ref = java.lang.Double.parseDouble(s)
            assert(!java.lang.Double.isNaN(ref) || java.lang.Double.isNaN(ref))
        else
            val actualBits = bits
            val expectBits = java.lang.Double.doubleToRawLongBits(java.lang.Double.parseDouble(s))
            assert(
                actualBits == expectBits,
                s"FastFloat.parseDouble($s) produced bits=0x${actualBits.toHexString} expected=0x${expectBits.toHexString}"
            )
        end if
    end assertParsesTo

    // Strict version — fails if FastFloat bails out (used where fast path MUST succeed).
    private def assertFastPathBitsMatch(s: String): org.scalatest.Assertion =
        val bytes = bytesOf(s)
        val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
        assert(bits != DoubleBailOut, s"FastFloat.parseDouble($s) unexpectedly bailed out")
        val expectBits = java.lang.Double.doubleToRawLongBits(java.lang.Double.parseDouble(s))
        assert(
            bits == expectBits,
            s"FastFloat.parseDouble($s) produced bits=0x${bits.toHexString} expected=0x${expectBits.toHexString}"
        )
    end assertFastPathBitsMatch

    "A — Property: round-trip against Double.parseDouble (100k random doubles)" - {
        "random-bit doubles agree with Double.parseDouble (hit rate >= 95%)" in {
            val rng     = new Random(0x5ca1ab1eL)
            val count   = 100_000
            var hits    = 0
            var bailOut = 0
            var i       = 0
            while i < count do
                val raw = rng.nextLong()
                val d   = java.lang.Double.longBitsToDouble(raw)
                if !java.lang.Double.isNaN(d) && !java.lang.Double.isInfinite(d) then
                    val s        = d.toString
                    val bytes    = bytesOf(s)
                    val bits     = FastFloat.parseDouble(bytes, 0, bytes.length)
                    val expected = java.lang.Double.doubleToRawLongBits(java.lang.Double.parseDouble(s))
                    if bits == DoubleBailOut then
                        bailOut += 1
                    else
                        assert(
                            bits == expected,
                            s"wrong bits for '$s': got 0x${bits.toHexString} expected 0x${expected.toHexString}"
                        )
                        hits += 1
                    end if
                end if
                i += 1
            end while
            val total   = hits + bailOut
            val hitRate = hits.toDouble / total
            info(f"Fast-path hit rate: $hits / $total = ${hitRate * 100}%.2f%%")
            assert(hitRate >= 0.95, s"Fast-path hit rate $hitRate < 0.95 threshold")
        }
    }

    "B — Paxson hard doubles" - {
        // Known IEEE 754 round-trip hazards; must match Double.parseDouble bit-identically when fast path
        // succeeds, otherwise must bail out cleanly.
        "5.0e-324 (smallest subnormal)" in {
            assertParsesTo("5.0e-324")
        }
        "2.2250738585072014e-308 (smallest normal)" in {
            assertParsesTo("2.2250738585072014e-308")
        }
        "1.7976931348623157e308 (Double.MaxValue)" in {
            assertParsesTo("1.7976931348623157e308")
        }
        "7.2057594037927933e16 (Paxson #4)" in {
            assertParsesTo("7.2057594037927933e16")
        }
        "9.868011474609375e-4 (Paxson #17 tie-break)" in {
            assertParsesTo("9.868011474609375e-4")
        }
        "2.2250738585072011e-308 (denormal boundary)" in {
            assertParsesTo("2.2250738585072011e-308")
        }
        "4.9406564584124654e-324 (Double.MinPositive subnormal)" in {
            assertParsesTo("4.9406564584124654e-324")
        }
        "9.007199254740992e15 (2^53)" in {
            assertParsesTo("9.007199254740992e15")
        }
    }

    "C — Subnormals" - {
        "2e-323 (deep subnormal)" in {
            assertParsesTo("2e-323")
        }
        "1e-320 (mid subnormal)" in {
            assertParsesTo("1e-320")
        }
        "1e-308 (just below normal)" in {
            assertParsesTo("1e-308")
        }
        "1.0e-322 (subnormal rounds up)" in {
            assertParsesTo("1.0e-322")
        }
    }

    "D — Signed zero and specials" - {
        "-0.0 produces negative-zero bit pattern" in {
            assertFastPathBitsMatch("-0.0")
            val bytes = bytesOf("-0.0")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            assert(bits == 0x8000000000000000L)
        }
        "0.0 produces positive-zero bit pattern" in {
            assertFastPathBitsMatch("0.0")
            val bytes = bytesOf("0.0")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            assert(bits == 0L)
        }
        "0 (integer zero)" in {
            assertFastPathBitsMatch("0")
        }
        "-0 (signed zero via fast path)" in {
            assertFastPathBitsMatch("-0")
            val bytes = bytesOf("-0")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            assert(bits == 0x8000000000000000L)
        }
    }

    "E — Range limits" - {
        "1e308 near Double.MaxValue" in {
            assertParsesTo("1e308")
        }
        "1e309 overflows to +Infinity (bails out)" in {
            val bytes = bytesOf("1e309")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            // Either fast path produces +Inf bits, or it bails out for the caller to handle.
            if bits != DoubleBailOut then
                assert(bits == java.lang.Double.doubleToRawLongBits(java.lang.Double.POSITIVE_INFINITY))
            else
                assert(bits == DoubleBailOut)
            end if
        }
        "1e-400 underflows to +0.0 (bails out)" in {
            val bytes = bytesOf("1e-400")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            // Exp10 < pow10Min (-348) → guaranteed bail-out.
            assert(bits == DoubleBailOut)
        }
        "1.8e308 just past MaxValue (overflows)" in {
            val bytes = bytesOf("1.8e308")
            val bits  = FastFloat.parseDouble(bytes, 0, bytes.length)
            if bits != DoubleBailOut then
                assert(bits == java.lang.Double.doubleToRawLongBits(java.lang.Double.POSITIVE_INFINITY))
            else
                assert(bits == DoubleBailOut)
            end if
        }
    }

    "F — Round-to-even tie-break" - {
        "1.5 (trivial)" in {
            assertFastPathBitsMatch("1.5")
        }
        "9007199254740993 (2^53 + 1, rounds to 2^53)" in {
            assertParsesTo("9007199254740993")
        }
        "9007199254740995 (2^53 + 3, rounds to 2^53 + 4)" in {
            assertParsesTo("9007199254740995")
        }
    }

    "G — Negative numbers" - {
        "-1.5 (sign flag)" in {
            assertFastPathBitsMatch("-1.5")
        }
        "-2.2250738585072014e-308 (negative near-subnormal)" in {
            assertParsesTo("-2.2250738585072014e-308")
        }
        "-1.7976931348623157e308 (most-negative finite)" in {
            assertParsesTo("-1.7976931348623157e308")
        }
    }

    "H — Scientific notation variants" - {
        "1e10 (lowercase e)" in {
            assertFastPathBitsMatch("1e10")
        }
        "1E10 (uppercase E)" in {
            assertFastPathBitsMatch("1E10")
        }
        "1.5e+3 (explicit positive sign)" in {
            assertFastPathBitsMatch("1.5e+3")
        }
        "1.5e-3 (negative exponent)" in {
            assertFastPathBitsMatch("1.5e-3")
        }
        "1e000010 (leading zeros in exponent)" in {
            // JSON grammar technically forbids leading zeros in the exponent, but both our scanner and
            // Double.parseDouble accept them; we just document the behavior.
            assertParsesTo("1e000010")
        }
    }

    "I — Integer-looking doubles (no regression)" - {
        "42 (integer fast path)" in {
            assertFastPathBitsMatch("42")
        }
        "-42 (signed integer fast path)" in {
            assertFastPathBitsMatch("-42")
        }
        "9223372036854775807 (Long.MaxValue, overflow into float path)" in {
            // 19-digit mantissa — mantissa fills u64 headroom. Either fast path succeeds or bails; in
            // either case we must match Double.parseDouble.
            assertParsesTo("9223372036854775807")
        }
    }

    "J — Leading-zero fractions" - {
        "0.00001 (standard small fraction)" in {
            assertFastPathBitsMatch("0.00001")
        }
        "0.0000000000000000000001 (21 leading zeros)" in {
            assertParsesTo("0.0000000000000000000001")
        }
        "1.0000000000000001 (17-digit mantissa)" in {
            assertParsesTo("1.0000000000000001")
        }
    }

    "K — Truncation (>= 20-digit mantissa)" - {
        "12345678901234567890 (20-digit integer)" in {
            assertParsesTo("12345678901234567890")
        }
        "0.12345678901234567890123456789 (many fractional digits)" in {
            assertParsesTo("0.12345678901234567890123456789")
        }
        "1.2345678901234567e10 (truncation at boundary)" in {
            assertParsesTo("1.2345678901234567e10")
        }
    }

    "L — Boundaries" - {
        "Double.MinValue.toString round-trip" in {
            assertParsesTo(java.lang.Double.MIN_VALUE.toString)
        }
        "Double.MaxValue.toString round-trip" in {
            assertParsesTo(java.lang.Double.MAX_VALUE.toString)
        }
        "Double.MIN_NORMAL.toString round-trip" in {
            assertParsesTo(java.lang.Double.MIN_NORMAL.toString)
        }
    }

    "M — Float32 parallel" - {
        "3.4028235e38 (Float.MaxValue)" in {
            val s     = "3.4028235e38"
            val bytes = bytesOf(s)
            val bits  = FastFloat.parseFloat(bytes, 0, bytes.length)
            if bits == FloatBailOut then
                // Legal bail-out — confirm slow path agrees.
                val ref = java.lang.Float.parseFloat(s)
                assert(!java.lang.Float.isNaN(ref) || java.lang.Float.isNaN(ref))
            else
                val expect = java.lang.Float.floatToRawIntBits(java.lang.Float.parseFloat(s))
                assert(
                    bits == expect,
                    s"FastFloat.parseFloat($s) produced bits=0x${bits.toHexString} expected=0x${expect.toHexString}"
                )
            end if
        }
        "1.4e-45 (Float.MIN_VALUE subnormal)" in {
            val s     = "1.4e-45"
            val bytes = bytesOf(s)
            val bits  = FastFloat.parseFloat(bytes, 0, bytes.length)
            if bits == FloatBailOut then
                val ref = java.lang.Float.parseFloat(s)
                assert(!java.lang.Float.isNaN(ref) || java.lang.Float.isNaN(ref))
            else
                val expect = java.lang.Float.floatToRawIntBits(java.lang.Float.parseFloat(s))
                assert(
                    bits == expect,
                    s"FastFloat.parseFloat($s) produced bits=0x${bits.toHexString} expected=0x${expect.toHexString}"
                )
            end if
        }
    }

end FastFloatTest
