package kyo

import java.util.UUID

class KeyCodecTest extends Test:

    "Int round-trip" in {
        val k       = KeyCodec[Int]
        val decoded = k.decode(k.encode(42))
        assert(decoded.isSuccess)
        assert(decoded.toMaybe.contains(42))
        succeed
    }

    "Long round-trip" in {
        val k       = KeyCodec[Long]
        val decoded = k.decode(k.encode(Long.MaxValue))
        assert(decoded.isSuccess)
        assert(decoded.toMaybe.contains(Long.MaxValue))
        succeed
    }

    "UUID round-trip" in {
        val u       = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val k       = KeyCodec[UUID]
        val decoded = k.decode(k.encode(u))
        assert(decoded.isSuccess)
        assert(decoded.toMaybe.exists(_.equals(u)))
        succeed
    }

    "String round-trip" in {
        val k = KeyCodec[String]
        assert(k.encode("hello") == "hello")
        val decoded = k.decode("hello")
        assert(decoded.isSuccess)
        assert(decoded.toMaybe.contains("hello"))
        succeed
    }

    "Int decode of non-numeric input fails" in {
        val result = KeyCodec[Int].decode("not-a-number")
        assert(result.isFailure)
        val msg = result.failure.map(_.getMessage()).getOrElse("")
        assert(msg.contains("not-a-number"), s"expected message to mention 'not-a-number', got: $msg")
        succeed
    }

end KeyCodecTest
