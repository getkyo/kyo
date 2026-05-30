package kyo

import kyo.internal.tasty.scala2.InflateHook

/** Tests for Phase 20a: Native InflateHook real implementation.
  *
  * The test is tagged jvmOnly because:
  *   - JS: InflateHook returns NotImplemented (Phase 20b-f will implement JS inflate).
  *   - Native: InflateHook now has a real implementation, but nativeOnly is not yet a tag in Test.scala. The shared test exercises the same
  *     code path; run `sbt 'project kyo-tastyNative' 'testOnly kyo.InflateHookTest'` to verify on Native.
  *
  * ZLIB envelope for "hello kyo" (9 bytes), pre-computed with java.util.zip.DeflaterOutputStream: 0x78 0x9c (ZLIB header, default
  * compression) + deflate bitstream + 4-byte Adler-32 checksum Total: 17 bytes.
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

    "InflateHook.inflate decompresses a known ZLIB envelope to the original bytes" taggedAs jvmOnly in run {
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

end InflateHookTest
