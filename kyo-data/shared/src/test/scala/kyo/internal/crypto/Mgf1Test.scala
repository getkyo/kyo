package kyo.internal.crypto

class Mgf1Test extends kyo.test.Test[Any]:

    "MGF1-SHA-1 known answers" in {
        // SHA-1(00 00 00 00 || 00 00 00 00) = 05fe405753166f125559e7c9ac558654f107c7e9, verified with Python's hashlib.
        assert(Hex.encode(Mgf1.sha1(Array[Byte](0, 0, 0, 0), 20)) == "05fe405753166f125559e7c9ac558654f107c7e9")
        // SHA-1(aa || 00 00 00 00), first 4 bytes = f667b659.
        assert(Hex.encode(Mgf1.sha1(Array[Byte](0xaa.toByte), 4)) == "f667b659")
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
                Sha1.hash(seed ++ c).toSeq
            }.take(length)
            val mask = Mgf1.sha1(seed, length)
            assert(mask.length == length)
            assert(mask.toSeq == expected)
        end for
    }

    "leaves the seed unchanged" in {
        val seed = Array.tabulate[Byte](20)(_.toByte)
        val copy = seed.clone()
        assert(Mgf1.sha1(seed, 50).length == 50)
        assert(seed.sameElements(copy))
    }

end Mgf1Test
