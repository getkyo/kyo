package kyo

import kyo.internal.XXHash
import org.scalatest.freespec.AnyFreeSpec

class XXHashTest extends AnyFreeSpec {

    private val Seed32 = XXHashTestVectors.Seed32

    "XXHash.hash32" - {
        "matches all official sanity vectors" in {
            val data   = sanityBuffer(XXHashTestVectors.MaxLength + 1)
            var length = 0
            while (length <= XXHashTestVectors.MaxLength) {
                assert(
                    XXHash.hash32(data, 0, length, 0) == XXHashTestVectors.xxh32(length, seeded = false),
                    s"XXH32 length=$length seed=0"
                )
                assert(
                    XXHash.hash32(data, 0, length, XXHashTestVectors.Seed32) ==
                        XXHashTestVectors.xxh32(length, seeded = true),
                    s"XXH32 length=$length seed=${XXHashTestVectors.Seed32}"
                )
                length += 1
            }
        }

        "hashes sliced arrays" in {
            val data   = Array[Byte](99, 88, 1, 2, 3, 4, 5, 77)
            val whole  = Array[Byte](1, 2, 3, 4, 5)
            val sliced = XXHash.hash32(data, 2, 5, Seed32)
            assert(sliced == XXHash.hash32(whole, 0, whole.length, Seed32))
        }

        "hashes strings as XXH32 of the JLS string hash" in {
            val values = Seq("", "ascii", "caf\u00e9", "\u6f22\u5b57", "emoji \ud83d\ude80", "\ud800", "\udc00")
            values.foreach { value =>
                assert(XXHash.hash32(value) == XXHash.hashInt(value.hashCode))
            }
        }

        "string hashes are pinned cross-platform constants" in {
            assert(XXHash.hash32("") == 148298089)
            assert(XXHash.hash32("ascii") == 1274324121)
            assert(XXHash.hash32("caf\u00e9") == 310371487)
            assert(XXHash.hash32("\u6f22\u5b57") == -1761639557)
            assert(XXHash.hash32("emoji \ud83d\ude80") == 687346460)
        }
    }

    "XXHash.hash64" - {
        "matches all official sanity vectors" in {
            val data   = sanityBuffer(XXHashTestVectors.MaxLength + 1)
            var length = 0
            while (length <= XXHashTestVectors.MaxLength) {
                assert(
                    XXHash.hash64(data, 0, length, 0L) == XXHashTestVectors.xxh64(length, seeded = false),
                    s"XXH64 length=$length seed=0"
                )
                assert(
                    XXHash.hash64(data, 0, length, XXHashTestVectors.Seed64) ==
                        XXHashTestVectors.xxh64(length, seeded = true),
                    s"XXH64 length=$length seed=${XXHashTestVectors.Seed64}"
                )
                length += 1
            }
        }
    }

    "XXHash.hashInt" - {
        "matches XXH32 of the same little-endian bytes" in {
            val values = Seq(Int.MinValue, -1, 0, 1, 0x12345678, Int.MaxValue)
            values.foreach { value =>
                val bytes = Array[Byte](
                    value.toByte,
                    (value >>> 8).toByte,
                    (value >>> 16).toByte,
                    (value >>> 24).toByte
                )
                assert(XXHash.hashInt(value) == XXHash.hash32(bytes))
            }
        }
    }

    private def sanityBuffer(size: Int): Array[Byte] = {
        val bytes = new Array[Byte](size)
        val prime = unsigned("9E3779B185EBCA8D")
        var gen   = 0x9e3779b1L
        var i     = 0
        while (i < bytes.length) {
            bytes(i) = (gen >>> 56).toByte
            gen *= prime
            i += 1
        }
        bytes
    }

    private def unsigned(hex: String): Long =
        java.lang.Long.parseUnsignedLong(hex, 16)
}
