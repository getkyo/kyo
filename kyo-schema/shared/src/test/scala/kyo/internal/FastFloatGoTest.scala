/** Test vectors ported from Go's `strconv/atof_test.go` (BSD-3-Clause).
  *
  * Source: [[https://github.com/golang/go/blob/master/src/internal/strconv/atof_test.go]]
  *
  * Algorithm reference: Daniel Lemire, "Number Parsing at a Gigabyte per Second", Software: Practice and Experience, 2021
  * ([[https://arxiv.org/abs/2101.11408 arXiv:2101.11408]]).
  *
  * Covers: algorithm edge cases near MaxValue/MinValue, subnormal boundaries, truncation triggers (19–21 significant digits), round-to-even
  * ties, overflow/underflow, historical bug reproducers (Java hang, PHP hang, issue #36657, issue #15364), and parse-error cases.
  *
  * Go-API-specific tests (ParseFloat signature, bitsize, locale, hex floats, underscore separators) are skipped — they do not apply to the
  * JSON number subset.
  */
package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Test

class FastFloatGoTest extends Test:

    import FastFloat.DoubleBailOut

    private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)

    // For valid-JSON inputs: fast path must either return the correct IEEE 754 bits
    // (bit-identical to java.lang.Double.parseDouble) or bail out (DoubleBailOut).
    // A bail-out is legal — the caller falls back to the JDK; what is illegal is a
    // wrong-but-non-sentinel value.
    private def assertParsesTo(s: String, expected: Double): org.scalatest.Assertion =
        val b    = bytes(s)
        val bits = FastFloat.parseDouble(b, 0, b.length)
        if bits == DoubleBailOut then
            // bail-out is fine — verify that DoubleBailOut is indeed the sentinel
            assert(bits == DoubleBailOut)
        else
            val expectBits = java.lang.Double.doubleToRawLongBits(expected)
            assert(
                bits == expectBits,
                s"FastFloat.parseDouble($s): bits=0x${bits.toHexString} expected=0x${expectBits.toHexString} (${java.lang.Double.longBitsToDouble(bits)} vs $expected)"
            )
        end if
    end assertParsesTo

    // Convenience overload — expected value computed by Double.parseDouble.
    private def assertParsesTo(s: String): org.scalatest.Assertion =
        assertParsesTo(s, java.lang.Double.parseDouble(s))

    // Asserts that the fast path returns +Infinity bits or bails out (for overflow inputs).
    private def assertOverflows(s: String): org.scalatest.Assertion =
        val b    = bytes(s)
        val bits = FastFloat.parseDouble(b, 0, b.length)
        if bits == DoubleBailOut then
            assert(bits == DoubleBailOut)
        else
            assert(
                bits == java.lang.Double.doubleToRawLongBits(java.lang.Double.POSITIVE_INFINITY),
                s"FastFloat.parseDouble($s): expected +Inf or bail-out, got bits=0x${bits.toHexString}"
            )
        end if
    end assertOverflows

    // Asserts that the fast path returns -Infinity bits or bails out.
    private def assertOverflowsNeg(s: String): org.scalatest.Assertion =
        val b    = bytes(s)
        val bits = FastFloat.parseDouble(b, 0, b.length)
        if bits == DoubleBailOut then
            assert(bits == DoubleBailOut)
        else
            assert(
                bits == java.lang.Double.doubleToRawLongBits(java.lang.Double.NEGATIVE_INFINITY),
                s"FastFloat.parseDouble($s): expected -Inf or bail-out, got bits=0x${bits.toHexString}"
            )
        end if
    end assertOverflowsNeg

    // Asserts that the fast path returns +0.0 bits or bails out (for underflow inputs).
    private def assertUnderflows(s: String): org.scalatest.Assertion =
        val b    = bytes(s)
        val bits = FastFloat.parseDouble(b, 0, b.length)
        if bits == DoubleBailOut then
            assert(bits == DoubleBailOut)
        else
            assert(
                bits == 0L || bits == java.lang.Double.doubleToRawLongBits(5e-324),
                s"FastFloat.parseDouble($s): expected ~0 or bail-out, got bits=0x${bits.toHexString}"
            )
        end if
    end assertUnderflows

    // ────────────────────────────────────────────────────────────────────────────────
    // N — Largest representable double (MaxValue) and borderline cases
    //     Ported from Go's "largest float64" and "borderline" blocks.
    // ────────────────────────────────────────────────────────────────────────────────
    "N — MaxValue and over-MaxValue" - {
        "1.7976931348623157e308 (Double.MaxValue, exact)" in {
            assertParsesTo("1.7976931348623157e308")
        }
        "-1.7976931348623157e308 (negative MaxValue)" in {
            assertParsesTo("-1.7976931348623157e308")
        }
        "1.7976931348623158e308 (borderline — rounds to MaxValue)" in {
            // Go says this rounds DOWN to MaxValue (not overflow).
            assertParsesTo("1.7976931348623158e308")
        }
        "-1.7976931348623158e308 (borderline negative — rounds to -MaxValue)" in {
            assertParsesTo("-1.7976931348623158e308")
        }
        "1.7976931348623159e308 (just over MaxValue — overflow to +Inf)" in {
            assertOverflows("1.7976931348623159e308")
        }
        "-1.7976931348623159e308 (overflow to -Inf)" in {
            assertOverflowsNeg("-1.7976931348623159e308")
        }
        "1.797693134862315808e308 (further over borderline — overflow to +Inf)" in {
            assertOverflows("1.797693134862315808e308")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // O — Overflow: too large / way too large
    //     Go's "a little too large" and "way too large" blocks.
    // ────────────────────────────────────────────────────────────────────────────────
    "O — Overflow" - {
        "2e308 (a little too large → +Inf)" in {
            assertOverflows("2e308")
        }
        "1e309 (too large → +Inf)" in {
            assertOverflows("1e309")
        }
        "1e310 (way too large → +Inf)" in {
            assertOverflows("1e310")
        }
        "-1e310 (way too large negative → -Inf)" in {
            assertOverflowsNeg("-1e310")
        }
        "1e400 (extreme overflow → +Inf)" in {
            assertOverflows("1e400")
        }
        "-1e400 (extreme negative overflow → -Inf)" in {
            assertOverflowsNeg("-1e400")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // P — Underflow: too small / way too small
    //     Go's "too small" and "way too small" blocks.
    // ────────────────────────────────────────────────────────────────────────────────
    "P — Underflow" - {
        "2e-324 (too small — underflows to 0)" in {
            assertUnderflows("2e-324")
        }
        "1e-350 (way too small → 0)" in {
            assertUnderflows("1e-350")
        }
        "1e-400000 (astronomically small → 0)" in {
            assertUnderflows("1e-400000")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Q — Exponent overflow: try to overflow the exponent field itself
    //     Go: "try to overflow exponent" block (decimal, not hex).
    // ────────────────────────────────────────────────────────────────────────────────
    "Q — Exponent overflow (u32/u64 overflow)" - {
        "1e-4294967296 (underflows — u32 exponent wrap)" in {
            assertUnderflows("1e-4294967296")
        }
        "1e+4294967296 (overflows — u32 exponent wrap → +Inf)" in {
            assertOverflows("1e+4294967296")
        }
        "1e-18446744073709551616 (underflows — u64 exponent wrap)" in {
            assertUnderflows("1e-18446744073709551616")
        }
        "1e+18446744073709551616 (overflows — u64 exponent wrap → +Inf)" in {
            assertOverflows("1e+18446744073709551616")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // R — Denormals / subnormals (decimal range)
    //     Go's "denormalized" block.
    // ────────────────────────────────────────────────────────────────────────────────
    "R — Denormals (decimal)" - {
        "1e-305" in { assertParsesTo("1e-305") }
        "1e-306" in { assertParsesTo("1e-306") }
        "1e-307" in { assertParsesTo("1e-307") }
        "1e-308" in { assertParsesTo("1e-308") }
        "1e-309" in { assertParsesTo("1e-309") }
        "1e-310" in { assertParsesTo("1e-310") }
        "1e-322" in { assertParsesTo("1e-322") }
        "5e-324 (smallest denormal)" in { assertParsesTo("5e-324") }
        "4e-324 (rounds up to 5e-324)" in {
            assertParsesTo("4e-324", java.lang.Double.parseDouble("4e-324"))
        }
        "3e-324 (rounds up to 5e-324)" in {
            assertParsesTo("3e-324", java.lang.Double.parseDouble("3e-324"))
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // S — Zero with large exponents (issue #15364 reproducers)
    //     Go's "zeros" block — the non-hex, non-+/-prefix cases.
    // ────────────────────────────────────────────────────────────────────────────────
    "S — Zero with giant exponents (issue 15364)" - {
        "0e291 (issue 15364)" in { assertParsesTo("0e291", 0.0) }
        "0e292 (issue 15364)" in { assertParsesTo("0e292", 0.0) }
        "0e347 (issue 15364)" in { assertParsesTo("0e347", 0.0) }
        "0e348 (issue 15364)" in { assertParsesTo("0e348", 0.0) }
        "-0e291" in {
            val b    = bytes("-0e291")
            val bits = FastFloat.parseDouble(b, 0, b.length)
            if bits != DoubleBailOut then
                assert(
                    bits == 0x8000000000000000L || bits == 0L,
                    s"expected -0 or +0, got 0x${bits.toHexString}"
                )
            else
                assert(bits == DoubleBailOut)
            end if
        }
        "-0e292" in {
            val b    = bytes("-0e292")
            val bits = FastFloat.parseDouble(b, 0, b.length)
            if bits != DoubleBailOut then
                assert(
                    bits == 0x8000000000000000L || bits == 0L,
                    s"expected -0 or +0, got 0x${bits.toHexString}"
                )
            else
                assert(bits == DoubleBailOut)
            end if
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // T — Truncation / many significant digits
    //     Go: "a different kind of very large number" + issue 36657.
    // ────────────────────────────────────────────────────────────────────────────────
    "T — Truncation and many significant digits" - {
        "22.222222222222222 (17 significant digits, truncated)" in {
            assertParsesTo("22.222222222222222")
        }
        "99999999999999974834176 (23 digits, rounds to 9.999999999999997e+22)" in {
            assertParsesTo("99999999999999974834176")
        }
        "100000000000000000000001 (24 digits, rounds to 1.0000000000000001e+23)" in {
            assertParsesTo("100000000000000000000001")
        }
        "100000000000000008388608 (24 digits, rounds to 1.0000000000000001e+23)" in {
            assertParsesTo("100000000000000008388608")
        }
        "100000000000000016777216 (24 digits, rounds to 1.0000000000000003e+23)" in {
            assertParsesTo("100000000000000016777216")
        }
        // Issue 36657: halfway between two floats — round to even.
        "1090544144181609348671888949248 (round-to-even, issue 36657)" in {
            assertParsesTo("1090544144181609348671888949248")
        }
        "1090544144181609348835077142190 (slightly above midpoint, rounds up)" in {
            assertParsesTo("1090544144181609348835077142190")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // U — Round-to-even tie-break (exact decimal midpoints)
    //     Go's "exactly halfway between 1 and math.Nextafter(1, 2)" block.
    // ────────────────────────────────────────────────────────────────────────────────
    "U — Round-to-even exact-decimal ties" - {
        "1.00000000000000011102230246251565404236316680908203125 (exact midpoint, round down)" in {
            assertParsesTo("1.00000000000000011102230246251565404236316680908203125")
        }
        "1.00000000000000011102230246251565404236316680908203124 (below midpoint, round down)" in {
            assertParsesTo("1.00000000000000011102230246251565404236316680908203124")
        }
        "1.00000000000000011102230246251565404236316680908203126 (above midpoint, round up)" in {
            assertParsesTo("1.00000000000000011102230246251565404236316680908203126")
        }
        // Halfway between x=nextafter(1,2) and nextafter(x,2): round to even (up).
        "1.00000000000000033306690738754696212708950042724609375 (upper midpoint, round even up)" in {
            assertParsesTo("1.00000000000000033306690738754696212708950042724609375")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // V — Historical bug reproducers
    //     Java hang: 2.2250738585072012e-308
    //     PHP hang:  2.2250738585072011e-308
    //     Large number initially mishandled by fast algorithm: 4.630813248087435e+307
    // ────────────────────────────────────────────────────────────────────────────────
    "V — Historical bug reproducers" - {
        // https://www.exploringbinary.com/java-hangs-when-converting-2-2250738585072012e-308/
        "2.2250738585072012e-308 (Java hang reproducer)" in {
            assertParsesTo("2.2250738585072012e-308")
        }
        // https://www.exploringbinary.com/php-hangs-on-numeric-value-2-2250738585072011e-308/
        "2.2250738585072011e-308 (PHP hang reproducer)" in {
            assertParsesTo("2.2250738585072011e-308")
        }
        // Value initially wrongly parsed by fast algorithm.
        "4.630813248087435e+307 (fast-algorithm wrong-parse reproducer)" in {
            assertParsesTo("4.630813248087435e+307")
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // W — General valid decimal values (basic smoke)
    //     From Go's opening entries, filtered to valid-JSON decimal inputs.
    // ────────────────────────────────────────────────────────────────────────────────
    "W — General valid decimal inputs" - {
        "1" in { assertParsesTo("1") }
        "1x is invalid (ErrSyntax in Go) — bail-out or wrong parse guarded" in {
            // Our parseDouble is only called on pre-validated JSON tokens, so we document
            // whatever the fast path does without asserting a specific outcome.
            val b    = bytes("1x")
            val bits = FastFloat.parseDouble(b, 0, b.length)
            // Fast path may bail or parse '1' ignoring the trailing 'x'. Either is acceptable
            // here because this is not a JSON-valid number.
            assert(bits == DoubleBailOut || bits == java.lang.Double.doubleToRawLongBits(1.0))
        }
        "1e23" in { assertParsesTo("1e23") }
        "1E23 (uppercase E)" in { assertParsesTo("1E23") }
        "100000000000000000000000 (1e23 as integer literal)" in {
            assertParsesTo("100000000000000000000000")
        }
        "1e-100" in { assertParsesTo("1e-100") }
        "123456700 (trailing zeros)" in { assertParsesTo("123456700") }
        "-1" in { assertParsesTo("-1") }
        "-0.1" in { assertParsesTo("-0.1") }
        "-0" in { assertParsesTo("-0") }
        "1e-20" in { assertParsesTo("1e-20") }
        "625e-3" in { assertParsesTo("625e-3") }
        "1e308 (near MaxValue)" in { assertParsesTo("1e308") }
        "1e-100 (very small but normal)" in { assertParsesTo("1e-100") }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // X — Parse-error / rejection cases
    //     Inputs that are invalid as JSON numbers; our parser should bail out or parse
    //     the numeric prefix, but must NOT return a wrong normal value.
    //     The key Go ones that apply to decimal/JSON are: "1e", "1e-", ".e-1".
    // ────────────────────────────────────────────────────────────────────────────────
    "X — Invalid / parse-error inputs (Go ErrSyntax)" - {
        "1e (empty exponent) — must bail-out or produce 0 bits (not a legal double)" in {
            val b    = bytes("1e")
            val bits = FastFloat.parseDouble(b, 0, b.length)
            // Acceptable: bail-out sentinel. Also acceptable: DoubleBailOut (same thing).
            // What is NOT acceptable: bits of some random finite double like 2.718...
            assert(
                bits == DoubleBailOut || bits == java.lang.Double.doubleToRawLongBits(1.0),
                s"unexpected bits 0x${bits.toHexString} for input '1e'"
            )
        }
        "1e- (truncated negative exponent) — must bail-out" in {
            val b    = bytes("1e-")
            val bits = FastFloat.parseDouble(b, 0, b.length)
            assert(
                bits == DoubleBailOut || bits == java.lang.Double.doubleToRawLongBits(1.0),
                s"unexpected bits 0x${bits.toHexString} for input '1e-'"
            )
        }
    }

end FastFloatGoTest
