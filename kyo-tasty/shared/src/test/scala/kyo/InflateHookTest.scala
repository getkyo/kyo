package kyo

import kyo.internal.tasty.scala2.InflateHook

/** Tests for Phase 20a/20f: InflateHook real implementation on all platforms.
  *
  * Phase 20a wired JVM and Native. Phase 20f wired JS via PortableInflate. All three platforms now run the full test suite (jvmOnly tag
  * removed).
  *
  * ZLIB envelope for "hello kyo" (9 bytes), pre-computed with java.util.zip.DeflaterOutputStream: 0x78 0x9c (ZLIB header, default
  * compression) + deflate bitstream + 4-byte Adler-32 checksum. Total: 17 bytes.
  */
class InflateHookTest extends Test:

    private val zlibCompressed: Array[Byte] = Array(
        0x78.toByte,
        0x9c.toByte,
        0xcb.toByte,
        0x48.toByte,
        0xcd.toByte,
        0xc9.toByte,
        0xc9.toByte,
        0x57.toByte,
        0xc8.toByte,
        0xae.toByte,
        0xcc.toByte,
        0x07.toByte,
        0x00.toByte,
        0x11.toByte,
        0xa2.toByte,
        0x03.toByte,
        0x88.toByte
    )

    private val expectedBytes: Array[Byte] =
        "hello kyo".getBytes(java.nio.charset.StandardCharsets.UTF_8)

    "InflateHook.inflate decompresses a known ZLIB envelope to the original bytes" in run {
        Abort.run[TastyError](InflateHook.inflate(zlibCompressed)).map:
            case Result.Success(bytes) =>
                assert(
                    bytes.sameElements(expectedBytes),
                    s"Expected ${expectedBytes.toSeq} but got ${bytes.toSeq}"
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "InflateHook.inflate returns a TastyError failure for a corrupted ZLIB header (bad CMF byte)" in run {
        // CMF byte 0x00: compression method bits = 0x0 (not deflate), triggers an error on all platforms.
        // JS/Native (PortableInflate) returns MalformedSection; JVM (InflaterInputStream) returns CorruptedFile.
        val badCmf: Array[Byte] = Array(
            0x00.toByte, // CMF: CM=0 (invalid), CINFO=0
            0x00.toByte, // FLG
            0x00.toByte,
            0x00.toByte,
            0x00.toByte,
            0x00.toByte // padding to reach min length
        )
        Abort.run[TastyError](InflateHook.inflate(badCmf)).map:
            case Result.Failure(_: TastyError) =>
                succeed
            case Result.Success(_) =>
                fail("Expected failure for corrupted ZLIB header, but got success")
            case Result.Panic(t) =>
                throw t
    }

end InflateHookTest
