package kyo

import kyo.internal.*

class XXHashExtensionsTest extends kyo.test.Test[Any]:

    private val Seed32 = 0x9e3779b1
    private val Seed64 = 0x000000009e3779b1L

    "hash32" - {
        "Span overloads match core array hashing" in {
            val data = bytes(96)
            val span = Span.fromUnsafe(data)

            assert(XXHash.hash32(span) == XXHash.hash32(data))
            assert(XXHash.hash32(span, Seed32) == XXHash.hash32(data, Seed32))
            assert(XXHash.hash32(span, 5, 37, Seed32) == XXHash.hash32(data, 5, 37, Seed32))
        }

    }

    "hash64" - {
        "Span overloads match core array hashing" in {
            val data = bytes(128)
            val span = Span.fromUnsafe(data)

            assert(XXHash.hash64(span) == XXHash.hash64(data))
            assert(XXHash.hash64(span, Seed64) == XXHash.hash64(data, Seed64))
            assert(XXHash.hash64(span, 11, 53, Seed64) == XXHash.hash64(data, 11, 53, Seed64))
        }
    }

    private def bytes(size: Int): Array[Byte] =
        val data = new Array[Byte](size)
        var i    = 0
        while i < data.length do
            data(i) = (i * 31 + 17).toByte
            i += 1
        data
    end bytes

end XXHashExtensionsTest
