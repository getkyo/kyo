package kyo.internal

import kyo.*

class Base64sTest extends kyo.test.Test[Any]:

    def enc(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)

    "decodeExact returns the text-implied decoded length" - {
        "empty input" in {
            assert(Base64s.decodeExact("").toSeq == Seq.empty[Byte])
        }
        "one byte (two padding chars)" in {
            assert(Base64s.decodeExact(enc(Array[Byte](1))).toSeq == Seq[Byte](1))
        }
        "two bytes (one padding char)" in {
            assert(Base64s.decodeExact(enc(Array[Byte](1, 2))).toSeq == Seq[Byte](1, 2))
        }
        "three bytes (no padding)" in {
            assert(Base64s.decodeExact(enc(Array[Byte](1, 2, 3))).toSeq == Seq[Byte](1, 2, 3))
        }
        "the full signed-byte range round-trips" in {
            val bytes = Array.tabulate(256)(i => (i - 128).toByte)
            assert(Base64s.decodeExact(enc(bytes)).toSeq == bytes.toSeq)
        }
    }

    "decodeExact throws IllegalArgumentException on non-alphabet input" in {
        val result = scala.util.Try(Base64s.decodeExact("!!!!"))
        assert(result.isFailure)
        assert(result.failed.get.isInstanceOf[IllegalArgumentException])
    }

end Base64sTest
