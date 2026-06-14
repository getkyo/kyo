package kyo.ffi.internal

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kyo.ffi.FfiMalformedResult
import kyo.ffi.Test

/** Unit tests for [[Scratch.readCStringBounded]], the bounded replacement for `reinterpret(Long.MaxValue)` in generated returned-struct /
  * multi-value readers.
  */
class ScratchReadCStringBoundedTest extends Test:

    "readCStringBounded" - {

        "decodes a NUL-terminated UTF-8 string within the cap" in {
            val arena = Arena.ofConfined().nn
            try
                val seg     = arena.allocate(16).nn
                val payload = "hello".getBytes("UTF-8").nn
                var i       = 0
                while i < payload.length do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, payload(i))
                    i += 1
                seg.set(ValueLayout.JAVA_BYTE, payload.length.toLong, 0: Byte)
                val out = Scratch.readCStringBounded(seg, 0L, 64L * 1024L, "kyo.example.B", "m", "f")
                assert(out == "hello")
            finally arena.close()
            end try
        }

        "decodes an empty string (leading NUL) as \"\"" in {
            val arena = Arena.ofConfined().nn
            try
                val seg = arena.allocate(8).nn
                seg.set(ValueLayout.JAVA_BYTE, 0L, 0: Byte)
                val out = Scratch.readCStringBounded(seg, 0L, 64L * 1024L, "kyo.example.B", "m", "f")
                assert(out == "")
            finally arena.close()
            end try
        }

        "throws FfiMalformedResult when no NUL is found within the cap and names binding + method + field" in {
            val arena = Arena.ofConfined().nn
            try
                // 70 KiB of non-NUL bytes, exceeds the small cap we pass below.
                val size = 70L * 1024L
                val seg  = arena.allocate(size).nn
                var i    = 0L
                while i < size do
                    seg.set(ValueLayout.JAVA_BYTE, i, 0x41: Byte) // 'A'
                    i += 1L

                val thrown = intercept[FfiMalformedResult](
                    Scratch.readCStringBounded(
                        seg,
                        0L,
                        64L * 1024L,
                        "kyo.example.Users",
                        "lookup",
                        "name"
                    )
                )
                assert(thrown.getMessage.contains("String field 'name'"))
                assert(thrown.getMessage.contains("kyo.example.Users.lookup"))
                assert(thrown.getMessage.contains("65536 bytes"))
                assert(thrown.getMessage.contains("-Dkyo.ffi.stringFieldMaxBytes="))
            finally arena.close()
            end try
        }

        "honors the cap even when the backing segment is larger" in {
            val arena = Arena.ofConfined().nn
            try
                // A backing segment of 16 non-NUL bytes, but we pass cap = 4; expect failure because
                // NUL must be found within 4 bytes.
                val seg = arena.allocate(16).nn
                var i   = 0
                while i < 16 do
                    seg.set(ValueLayout.JAVA_BYTE, i.toLong, 0x41: Byte)
                    i += 1
                val thrown = intercept[FfiMalformedResult](
                    Scratch.readCStringBounded(seg, 0L, 4L, "kyo.example.B", "m", "f")
                )
                assert(thrown.getMessage.contains("4 bytes"))
            finally arena.close()
            end try
        }
    }

end ScratchReadCStringBoundedTest
