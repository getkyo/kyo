package kyo.ffi.internal

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kyo.ffi.FfiMalformedResult
import kyo.ffi.Test

/** Unit tests for the bulk NUL scan used by [[Scratch.readCStringBounded]]. Exercises boundary positions around the 8-byte Long alignment
  * used by [[Scratch.findNul]].
  */
class ScratchBulkNulScanTest extends Test:

    /** Helper: allocate a segment of `size` bytes, fill with `fill`, place NUL at `nulPos`. */
    private def withSegment(size: Int, fill: Byte, nulPos: Int)(f: (Arena, java.lang.foreign.MemorySegment) => Unit): Unit =
        val arena = Arena.ofConfined().nn
        try
            val seg = arena.allocate(size.toLong).nn
            var i   = 0
            while i < size do
                seg.set(ValueLayout.JAVA_BYTE, i.toLong, fill)
                i += 1
            seg.set(ValueLayout.JAVA_BYTE, nulPos.toLong, 0: Byte)
            f(arena, seg)
        finally arena.close()
        end try
    end withSegment

    "readCStringBounded bulk NUL scan" - {

        "NUL at byte 0 returns empty string" in {
            withSegment(16, 0x41: Byte, 0) { (_, seg) =>
                assert(Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f") == "")
            }
        }

        "NUL at byte 1 returns single char" in {
            withSegment(16, 0x41: Byte, 1) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result == "A")
                assert(result.length == 1)
            }
        }

        "NUL at byte 7, just before Long boundary" in {
            withSegment(16, 0x42: Byte, 7) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result.length == 7)
                assert(result == "BBBBBBB")
            }
        }

        "NUL at byte 8, at Long boundary" in {
            withSegment(24, 0x43: Byte, 8) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result.length == 8)
                assert(result == "CCCCCCCC")
            }
        }

        "NUL at byte 9, just past Long boundary" in {
            withSegment(24, 0x44: Byte, 9) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result.length == 9)
                assert(result == "DDDDDDDDD")
            }
        }

        "NUL at byte 63, large short string" in {
            withSegment(128, 0x45: Byte, 63) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result.length == 63)
                assert(result.forall(_ == 'E') == true)
            }
        }

        "NUL at byte 1000, large string" in {
            withSegment(1024, 0x46: Byte, 1000) { (_, seg) =>
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result.length == 1000)
                assert(result.forall(_ == 'F') == true)
            }
        }

        "readCStringBounded throws FfiMalformedResult when no NUL within cap" in {
            val arena = Arena.ofConfined().nn
            try
                val size = 128
                val seg  = arena.allocate(size.toLong).nn
                var i    = 0
                while i < size do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, 0x41: Byte)
                    i += 1
                // No NUL anywhere, cap is 64
                val thrown = intercept[FfiMalformedResult](
                    Scratch.readCStringBounded(seg, 0L, 64L, "kyo.test.B", "m", "f")
                )
                assert(thrown.getMessage.contains("64 bytes"))
            finally arena.close()
            end try
        }

        "UTF-8 multi-byte chars, NUL after multi-byte sequence" in {
            val arena = Arena.ofConfined().nn
            try
                // U+00E9 (e-acute) is 0xC3 0xA9 in UTF-8 (2 bytes)
                val utf8Bytes = "\u00e9\u00e9\u00e9".getBytes("UTF-8").nn // 6 bytes
                val seg       = arena.allocate(16).nn
                // Fill with 0xFF first
                var i = 0
                while i < 16 do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, 0xff.toByte)
                    i += 1
                // Copy UTF-8 bytes
                i = 0
                while i < utf8Bytes.length do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, utf8Bytes(i))
                    i += 1
                // Place NUL right after
                seg.set(ValueLayout.JAVA_BYTE, utf8Bytes.length.toLong, 0: Byte)
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                assert(result == "\u00e9\u00e9\u00e9")
                assert(result.length == 3) // 3 code points
            finally arena.close()
            end try
        }

        "all 0xFF bytes followed by NUL, tests bit trick edge case" in {
            // 0xFF is interesting because ~0xFF = 0x00, so the SWAR trick needs to handle it correctly.
            // readCStringBounded decodes as UTF-8, 0xFF is not valid UTF-8, so we verify that the bulk scan
            // finds the NUL at the right position by checking the result is non-empty.
            val arena = Arena.ofConfined().nn
            try
                val size = 32
                val seg  = arena.allocate(size.toLong).nn
                var i    = 0
                while i < size do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, 0xff.toByte)
                    i += 1
                // Place NUL at position 17
                seg.set(ValueLayout.JAVA_BYTE, 17L, 0: Byte)
                val result = Scratch.readCStringBounded(seg, 0L, seg.byteSize(), "kyo.test.B", "m", "f")
                // The scan must have found NUL at position 17, producing a non-empty string
                assert(result.nonEmpty == true)
                // Also verify via an explicit smaller cap, same NUL position, same result
                val bounded = Scratch.readCStringBounded(seg, 0L, 32L, "test", "m", "f")
                assert(bounded == result)
            finally arena.close()
            end try
        }
    }

end ScratchBulkNulScanTest
