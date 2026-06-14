package kyo.ffi.it

import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Cross-platform libc spec exercising `strlen`, `strcmp` (String parameters), `memcmp` / `memcpy` (Buffer[Byte] parameters), and the `abs`
  * / `labs` primitive-arithmetic path.
  *
  * Lives under `shared/src/test/` so it runs on JVM, Native, and JS.
  *
  * `SystemLibraryInit.force()` (run from `ItTestBase` at suite instantiation) ensures the JS-side `KYO_FFI_*_PATH` env vars are set before
  * the first `Ffi.load`. It is a no-op on JVM and Native.
  *
  * Table-driven density, each row invokes a binding method at least once so every row crosses the FFI boundary.
  */
class LibCTest extends ItTestBase:

    "strlen" - {
        "empty string returns 0" in {
            val libc = Ffi.load[LibCBindings]
            assert(libc.strlen("") == 0L)
        }

        "ascii string returns byte count" in {
            val libc = Ffi.load[LibCBindings]
            assert(libc.strlen("hello") == 5L)
        }

        "multi-byte UTF-8 string returns byte count (not codepoint count)" in {
            // "héllo", the 'é' is 0xC3 0xA9 in UTF-8 (2 bytes), so the
            // byte count is 6, not 5. strlen is byte-level, not codepoint-level.
            val libc = Ffi.load[LibCBindings]
            assert(libc.strlen("héllo") == 6L)
        }

        // Table-driven strlen rows: every row calls strlen exactly once,
        // comparing against the explicit UTF-8 byte length (pre-computed via
        // String.getBytes("UTF-8").length in a REPL to avoid runtime coupling).
        "table-driven: varied ASCII + UTF-8 inputs" in {
            val libc = Ffi.load[LibCBindings]
            // Each expected is the UTF-8 byte length, strlen counts bytes.
            val cases: Seq[(String, Long)] = Seq(
                "a"                          -> 1L,
                "ab"                         -> 2L,
                "abcdefghij"                 -> 10L,
                "café"                       -> 5L, // c(1)+a(1)+f(1)+é(2)=5
                "\t\n"                       -> 2L,
                "\u00A0"                     -> 2L, // NBSP = 0xC2 0xA0
                "πr^2"                       -> 5L, // π=2 bytes + "r^2"=3 → 5
                "   "                        -> 3L, // three spaces
                "  leading+trailing  "       -> 20L,
                "0123456789"                 -> 10L,
                "line one\nline two"         -> 17L,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" -> 26L,
                "abcdefghijklmnopqrstuvwxyz" -> 26L,
                "!@#$%^&*()"                 -> 10L,
                "1" * 100                    -> 100L,
                "x" * 1000                   -> 1000L
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libc.strlen(input) == expected)
            }
            last
        }
    }

    "strcmp" - {
        "equal strings return 0" in {
            val libc = Ffi.load[LibCBindings]
            assert(libc.strcmp("abc", "abc") == 0)
        }

        "'a' < 'b' returns a negative value" in {
            val libc = Ffi.load[LibCBindings]
            assert(libc.strcmp("a", "b") < 0)
        }

        // Table-driven rows, each calls strcmp and asserts the sign only. C's
        // strcmp is not spec'd to return specifically -1/+1, only sign matters,
        // which is the portable invariant.
        "table-driven: equal pairs return 0" in {
            val libc = Ffi.load[LibCBindings]
            val pairs = Seq(
                ("", ""),
                ("x", "x"),
                ("hello", "hello"),
                ("ABCDE", "ABCDE"),
                ("0123456789", "0123456789"),
                ("a" * 50, "a" * 50)
            )
            var last: Unit = succeed
            pairs.foreach { case (a, b) =>
                last = assert(libc.strcmp(a, b) == 0)
            }
            last
        }

        "table-driven: lexicographic ordering rows" in {
            val libc = Ffi.load[LibCBindings]
            // lesser < greater, every row asserts sign < 0.
            val lessPairs = Seq(
                ("a", "b"),
                ("aa", "ab"),
                ("abc", "abd"),
                ("A", "a"),      // 'A' (0x41) < 'a' (0x61)
                ("", "a"),       // empty is a prefix → strictly less
                ("abc", "abcd"), // shorter prefix is less
                ("1", "2"),
                ("abc", "abz")
            )
            var last: Unit = succeed
            lessPairs.foreach { case (lo, hi) =>
                last = assert(libc.strcmp(lo, hi) < 0)
            }
            last
        }

        "table-driven: symmetric reversed pairs return positive" in {
            val libc = Ffi.load[LibCBindings]
            val greaterPairs = Seq(
                ("b", "a"),
                ("ab", "aa"),
                ("abd", "abc"),
                ("a", "A"),
                ("a", ""),
                ("abcd", "abc")
            )
            var last: Unit = succeed
            greaterPairs.foreach { case (hi, lo) =>
                last = assert(libc.strcmp(hi, lo) > 0)
            }
            last
        }
    }

    "memcmp" - {
        "matching buffers return 0" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](4) { a =>
                Buffer.use[Byte, Unit](4) { b =>
                    val bytes = Array[Byte](1, 2, 3, 4)
                    var i     = 0
                    while i < 4 do
                        a.set(i, bytes(i))
                        b.set(i, bytes(i))
                        i += 1
                    end while
                    assert(libc.memcmp(a, b, 4L) == 0)
                }
            }
        }

        "differing buffers return non-zero at the first differing byte" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](4) { a =>
                Buffer.use[Byte, Unit](4) { b =>
                    // a = [1,2,3,4], b = [1,2,9,4], first diff at index 2 (3 < 9)
                    a.set(0, 1.toByte); a.set(1, 2.toByte); a.set(2, 3.toByte); a.set(3, 4.toByte)
                    b.set(0, 1.toByte); b.set(1, 2.toByte); b.set(2, 9.toByte); b.set(3, 4.toByte)
                    assert(libc.memcmp(a, b, 4L) < 0)
                }
            }
        }

        // Table-driven memcmp rows across varied buffer lengths + patterns.
        "table-driven: identical contents across varied lengths" in {
            val libc              = Ffi.load[LibCBindings]
            val lengths: Seq[Int] = Seq(1, 2, 8, 16, 32)
            var last: Unit        = succeed
            lengths.foreach { len =>
                Buffer.use[Byte, Unit](len) { a =>
                    Buffer.use[Byte, Unit](len) { b =>
                        var i = 0
                        while i < len do
                            a.set(i, (i * 3).toByte)
                            b.set(i, (i * 3).toByte)
                            i += 1
                        end while
                        val r = assert(libc.memcmp(a, b, len.toLong) == 0)
                        last = r
                        r
                    }
                }
            }
            last
        }

        "table-driven: reverse-ordered differing buffers are positive" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](4) { a =>
                Buffer.use[Byte, Unit](4) { b =>
                    // symmetric of the earlier "first diff" row, with a > b
                    a.set(0, 1.toByte); a.set(1, 2.toByte); a.set(2, 9.toByte); a.set(3, 4.toByte)
                    b.set(0, 1.toByte); b.set(1, 2.toByte); b.set(2, 3.toByte); b.set(3, 4.toByte)
                    assert(libc.memcmp(a, b, 4L) > 0)
                }
            }
        }

        "table-driven: zero-length compare ignores content" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](4) { a =>
                Buffer.use[Byte, Unit](4) { b =>
                    // different content but n = 0 ⇒ memcmp returns 0 per spec
                    a.set(0, 1.toByte); a.set(1, 2.toByte); a.set(2, 3.toByte); a.set(3, 4.toByte)
                    b.set(0, 9.toByte); b.set(1, 9.toByte); b.set(2, 9.toByte); b.set(3, 9.toByte)
                    assert(libc.memcmp(a, b, 0L) == 0)
                }
            }
        }
    }

    "abs" - {
        "abs(-5) is 5" in {
            val libc = Ffi.load[LibCBindings]
            assert(libc.abs(-5) == 5)
        }

        "abs(Int.MaxValue) is Int.MaxValue (stable, no overflow)" in {
            // abs(Int.MinValue) IS UB in C, so we test MaxValue which is well-defined.
            val libc = Ffi.load[LibCBindings]
            assert(libc.abs(Int.MaxValue) == Int.MaxValue)
        }

        // Table-driven abs rows. `Int.MinValue` is UB so we skip it.
        "table-driven: varied signed inputs" in {
            val libc = Ffi.load[LibCBindings]
            val cases: Seq[(Int, Int)] = Seq(
                0             -> 0,
                1             -> 1,
                -1            -> 1,
                2             -> 2,
                -2            -> 2,
                100           -> 100,
                -100          -> 100,
                1000          -> 1000,
                -1000         -> 1000,
                Int.MaxValue  -> Int.MaxValue,
                -Int.MaxValue -> Int.MaxValue,
                12345         -> 12345,
                -12345        -> 12345
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libc.abs(input) == expected)
            }
            last
        }

        "invariant: abs(x) == abs(-x) for a range of inputs" in {
            val libc       = Ffi.load[LibCBindings]
            var last: Unit = succeed
            val xs         = Seq(0, 1, 7, 42, 999, 123456)
            xs.foreach { x =>
                // each row crosses the FFI boundary twice: abs(x) and abs(-x).
                last = assert(libc.abs(x) == libc.abs(-x))
            }
            last
        }
    }

    "labs" - {
        "labs(Long.MinValue + 1) is Long.MaxValue" in {
            // labs(Long.MinValue) is UB. Long.MinValue + 1 is well-defined and
            // equals Long.MaxValue after abs.
            val libc = Ffi.load[LibCBindings]
            assert(libc.labs(Long.MinValue + 1L) == Long.MaxValue)
        }

        "labs(Long.MaxValue) is Long.MaxValue" in {
            // D8: Long.MaxValue is positive, labs must return it unchanged.
            val libc = Ffi.load[LibCBindings]
            assert(libc.labs(Long.MaxValue) == Long.MaxValue)
        }

        "labs(Long.MinValue) does not crash" in {
            // D8: labs(Long.MinValue) is implementation-defined in C (no representable positive
            // counterpart in two's complement). We only assert it does not throw an exception.
            // The actual return value is platform-defined and should not be checked.
            val libc = Ffi.load[LibCBindings]
            val _    = libc.labs(Long.MinValue)
            succeed
        }

        // Table-driven labs rows.
        "table-driven: varied signed long inputs" in {
            val libc = Ffi.load[LibCBindings]
            val cases: Seq[(Long, Long)] = Seq(
                0L                   -> 0L,
                1L                   -> 1L,
                -1L                  -> 1L,
                1234567890L          -> 1234567890L,
                -1234567890L         -> 1234567890L,
                Int.MaxValue.toLong  -> Int.MaxValue.toLong,
                -Int.MaxValue.toLong -> Int.MaxValue.toLong,
                Long.MaxValue        -> Long.MaxValue,
                Long.MinValue + 1L   -> Long.MaxValue
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libc.labs(input) == expected)
            }
            last
        }

        "invariant: labs(x) == labs(-x) for a range of long inputs" in {
            val libc       = Ffi.load[LibCBindings]
            var last: Unit = succeed
            val xs         = Seq(0L, 1L, 42L, 1_000_000L, 9_876_543_210L)
            xs.foreach { x =>
                last = assert(libc.labs(x) == libc.labs(-x))
            }
            last
        }
    }

    // G5: large string marshalling, exercises the Scratch spill path end-to-end.
    // A ~1 MB string forces the string-to-C marshaller to spill past any small
    // inline buffer, confirming that large allocations are handled correctly.
    "strlen on a ~1 MB string" - {
        "byte count matches expected length" in {
            val libc  = Ffi.load[LibCBindings]
            val big   = "a" * 1_000_000
            val bytes = big.getBytes("UTF-8").length.toLong
            assert(libc.strlen(big) == bytes)
        }
    }

    "memcpy" - {
        "copies bytes correctly" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](5) { dst =>
                Buffer.use[Byte, Unit](5) { src =>
                    var i = 0
                    while i < 5 do
                        src.set(i, (i + 10).toByte)
                        dst.set(i, 0.toByte)
                        i += 1
                    end while
                    libc.memcpy(dst, src, 5L)
                    // Final expression yields an Assertion, `shouldBe` on the last
                    // element is what Buffer.use returns.
                    var lastAssertion: Unit = succeed
                    i = 0
                    while i < 5 do
                        lastAssertion = assert(dst.get(i) == (i + 10).toByte)
                        i += 1
                    end while
                    lastAssertion
                }
            }
        }

        // memcpy across several buffer lengths. Each row calls memcpy +
        // then verifies all copied bytes with memcmp (double-FFI-boundary crossing).
        "table-driven: varied-length memcpy verified via memcmp" in {
            val libc              = Ffi.load[LibCBindings]
            val lengths: Seq[Int] = Seq(1, 4, 16, 64)
            var last: Unit        = succeed
            lengths.foreach { len =>
                Buffer.use[Byte, Unit](len) { dst =>
                    Buffer.use[Byte, Unit](len) { src =>
                        var i = 0
                        while i < len do
                            src.set(i, ((i * 7 + 1) & 0xff).toByte)
                            dst.set(i, 0.toByte)
                            i += 1
                        end while
                        libc.memcpy(dst, src, len.toLong)
                        val r = assert(libc.memcmp(dst, src, len.toLong) == 0)
                        last = r
                        r
                    }
                }
            }
            last
        }

        "zero-length memcpy is a no-op" in {
            val libc = Ffi.load[LibCBindings]
            Buffer.use[Byte, Unit](4) { dst =>
                Buffer.use[Byte, Unit](4) { src =>
                    src.set(0, 9.toByte); src.set(1, 9.toByte); src.set(2, 9.toByte); src.set(3, 9.toByte)
                    dst.set(0, 1.toByte); dst.set(1, 2.toByte); dst.set(2, 3.toByte); dst.set(3, 4.toByte)
                    libc.memcpy(dst, src, 0L)
                    // dst unchanged, verify via memcmp against a third sentinel buffer.
                    Buffer.use[Byte, Unit](4) { expected =>
                        expected.set(0, 1.toByte); expected.set(1, 2.toByte); expected.set(2, 3.toByte); expected.set(3, 4.toByte)
                        assert(libc.memcmp(dst, expected, 4L) == 0)
                    }
                }
            }
        }
    }
end LibCTest
