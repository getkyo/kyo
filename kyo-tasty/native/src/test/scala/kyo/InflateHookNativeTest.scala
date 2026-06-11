package kyo

import kyo.internal.tasty.scala2.InflateHook

/** Native-specific test for InflateHook.inflate. Verifies the Native implementation using the same hardcoded ZLIB envelope for "hello kyo".
  */
class InflateHookNativeTest extends kyo.test.Test[Any]:

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

    "InflateHook.inflate (Native) decompresses a known ZLIB envelope to the original bytes" in {
        Abort.run[TastyError](InflateHook.inflate(zlibCompressed)).map {
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
    }

end InflateHookNativeTest
