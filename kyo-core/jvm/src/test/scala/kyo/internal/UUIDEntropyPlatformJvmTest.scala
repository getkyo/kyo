package kyo.internal

import java.security.SecureRandom
import kyo.*

class UUIDEntropyPlatformJvmTest extends kyo.test.Test[Any]:

    final private class RecordingSecureRandom(bytes: Array[Byte]) extends SecureRandom:
        var calls      = 0
        var lastLength = -1

        override def nextBytes(target: Array[Byte]): Unit =
            calls += 1
            lastLength = target.length
            java.lang.System.arraycopy(bytes, 0, target, 0, target.length)
        end nextBytes
    end RecordingSecureRandom

    "JVM secure entropy adapter" - {
        "live adapter returns exactly 16 bytes" in {
            UUIDEntropyPlatform.live.next16.map: bytes =>
                assert(bytes.size == 16)
        }

        "fills all 16 bytes through SecureRandom.nextBytes" in {
            val expected = Array.tabulate[Byte](16)(i => (i * 13 + 7).toByte)
            val source   = new RecordingSecureRandom(expected)
            val adapter  = UUIDEntropyPlatform.fromSecureRandom(source)

            adapter.next16.map: actual =>
                assert(actual.is(Span.from(expected)))
                assert(source.calls == 1)
                assert(source.lastLength == 16)
        }

        "surfaces SecureRandom failures as Sync panics" in {
            val failure = new RuntimeException("SecureRandom failed")
            val source = new SecureRandom:
                override def nextBytes(target: Array[Byte]): Unit = throw failure
            val adapter = UUIDEntropyPlatform.fromSecureRandom(source)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) => assert(actual eq failure)
                    case other                => fail(s"expected SecureRandom panic, got $other")
        }
    }

end UUIDEntropyPlatformJvmTest
