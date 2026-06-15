package kyo.ffi.it

import kyo.ffi.Ffi

/** Borrowed-return spec.
  *
  * Exercises `def kyoItMallocChunk(n: Long): Borrowed[Buffer[Byte]]`, the C side mallocs + fills + leaks a 16-byte region with 0xAB; the
  * Scala side unwraps the Borrowed wrapper, obtaining a borrowed `Buffer[Byte]`, and reads the sentinel byte at a known index.
  *
  * The intentional leak in `kyo_it_malloc_chunk` is the right call for this test: we are validating borrowed-buffer semantics (wrap + size
  * + read), not memory management. The test process's lifetime bounds the leak.
  */
class ItBorrowedTest extends ItTestBase:

    "kyoItMallocChunk" - {
        "returns a borrowed Buffer[Byte] filled with 0xAB at every byte" in {
            val b      = Ffi.load[ItBorrowedBindings]
            val n      = 16L
            val buf    = b.kyoItMallocChunk(n).value
            val sample = buf.get(5)
            // 0xAB as a Scala Byte is -85 (sign-extended).
            assert(sample == 0xab.toByte)
        }

        "size reflects the element count from the inferred size parameter" in {
            val b   = Ffi.load[ItBorrowedBindings]
            val n   = 16L
            val buf = b.kyoItMallocChunk(n).value
            assert(buf.size == n.toInt)
        }

        "every byte in the returned buffer is 0xAB" in {
            val b   = Ffi.load[ItBorrowedBindings]
            val n   = 16L
            val buf = b.kyoItMallocChunk(n).value
            var i   = 0
            while i < n.toInt do
                assert(buf.get(i) == 0xab.toByte)
                i += 1
            end while
            succeed
        }

        "close() is a no-op, the borrowed buffer leaves the underlying C memory intact" in {
            val b   = Ffi.load[ItBorrowedBindings]
            val n   = 16L
            val buf = b.kyoItMallocChunk(n).value
            // close() must be allowed and must not throw.
            buf.close()
            succeed
        }
    }

    // --- Borrowed String edge cases ---

    "kyoItBorrowedString (non-empty)" - {
        "returns the expected static ASCII string" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            assert(b.kyoItBorrowedString().value == "hello from C")
        }

        "repeated calls return the same value (String is copied, not aliased)" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            val a = b.kyoItBorrowedString().value
            val c = b.kyoItBorrowedString().value
            assert(a == c)
        }
    }

    "kyoItBorrowedStringNull" - {
        "returns null for a C NULL char* return" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            assert(b.kyoItBorrowedStringNull().value == null)
        }
    }

    "kyoItBorrowedStringEmpty" - {
        "returns an empty string for a C empty-string return" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            assert(b.kyoItBorrowedStringEmpty().value == "")
        }

        "result has length zero" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            assert(b.kyoItBorrowedStringEmpty().value.length == 0)
        }
    }

    "kyoItBorrowedStringUtf8" - {
        "returns the expected multi-byte UTF-8 string" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            // The C function returns: é (U+00E9) + 世 (U+4E16) + 😀 (U+1F600)
            assert(b.kyoItBorrowedStringUtf8().value == "\u00e9\u4e16\ud83d\ude00")
        }

        "string has the correct character count" in {
            val b = Ffi.load[ItBorrowedStringBindings]
            val s = b.kyoItBorrowedStringUtf8().value
            // 3 Unicode code points, but U+1F600 is a supplementary character
            // represented as a surrogate pair in UTF-16, so String.length is 4.
            assert(s.length == 4)
            assert(s.codePointCount(0, s.length) == 3)
        }
    }
end ItBorrowedTest
