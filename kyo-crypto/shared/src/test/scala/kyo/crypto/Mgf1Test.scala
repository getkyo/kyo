package kyo.crypto

import kyo.*

class Mgf1Test extends kyo.test.Test[Any]:

    private def mask(seed: Array[Byte], length: Int): Array[Byte] = Mgf1.sha1(Span.from(seed), length) match
        case Result.Success(bytes) => bytes.toArray
        case other                 => throw new IllegalStateException(s"expected a mask, got $other")

    private def maskArray(seed: Array[Byte], length: Int): Array[Byte] = Mgf1.sha1Array(seed, length) match
        case Result.Success(bytes) => bytes
        case other                 => throw new IllegalStateException(s"expected a mask, got $other")

    "MGF1-SHA-1 known answers" in {
        // SHA-1(00 00 00 00 || 00 00 00 00) = 05fe405753166f125559e7c9ac558654f107c7e9, verified with Python's hashlib.
        assert(Hex.encodeArray(mask(Array[Byte](0, 0, 0, 0), 20)) == "05fe405753166f125559e7c9ac558654f107c7e9")
        // SHA-1(aa || 00 00 00 00), first 4 bytes = f667b659.
        assert(Hex.encodeArray(mask(Array[Byte](0xaa.toByte), 4)) == "f667b659")
        assert(mask(Array.emptyByteArray, 0).isEmpty)
    }

    "the array tier answers as the Span row" in {
        val seed = Array[Byte](0, 0, 0, 0)
        assert(maskArray(seed, 20).sameElements(mask(seed, 20)))
        assert(maskArray(seed, 45).sameElements(mask(seed, 45)))
        assert(maskArray(seed, 0).isEmpty)
    }

    "the mask is the concatenation of SHA-1(seed || counter) for counters from 0, cut to the length asked for" in {
        val seeds =
            Seq(Array.emptyByteArray, Array[Byte](1), Array.tabulate[Byte](20)(i => (i * 13).toByte), Array.tabulate[Byte](33)(_.toByte))
        val lengths = Seq(0, 1, 19, 20, 21, 40, 41, 100, 235)
        for
            seed   <- seeds
            length <- lengths
        do
            val blocks   = (length + 19) / 20
            val expected = (0 until blocks).flatMap { counter =>
                val c = Array[Byte]((counter >>> 24).toByte, (counter >>> 16).toByte, (counter >>> 8).toByte, counter.toByte)
                Sha1.hashArray(seed ++ c).toSeq
            }.take(length)
            val actual = mask(seed, length)
            assert(actual.length == length)
            assert(actual.toSeq == expected)
        end for
    }

    "leaves the seed unchanged" in {
        val seed = Array.tabulate[Byte](20)(_.toByte)
        val copy = seed.clone()
        assert(mask(seed, 50).length == 50)
        assert(seed.sameElements(copy))
    }

    "a negative length is refused with the length offered" in {
        assert(Mgf1.sha1(Span.from(Array[Byte](1)), -1).failure == Maybe(Mgf1.Failure.Length(-1)))
        assert(Mgf1.sha1Array(Array[Byte](1), -5).failure == Maybe(Mgf1.Failure.Length(-5)))
    }

end Mgf1Test
