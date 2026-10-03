package kyo.crypto

import kyo.*

/** [[Sha256]] against Node's `createHash` (OpenSSL). */
class Sha256NodeTest extends kyo.test.Test[Any]:

    "matches Node at every size from 0 to 300 and on 200 random inputs, split at random points" in {
        assert(Sha256NodeTest.mismatches("sha256", Sha256.hashArrays) == Seq.empty)
    }

end Sha256NodeTest

object Sha256NodeTest:

    /** The inputs where `hash` over the input split at a random point differs from Node's `algorithm` over it whole, for every size from 0
      * to 300 and 200 random sizes up to 2100 bytes. Each names the seed, so a failure reproduces.
      */
    def mismatches(algorithm: String, hash: Chunk[Array[Byte]] => Array[Byte]): Seq[String] =
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        val sizes  = (0 to 300) ++ Seq.fill(200)(random.nextInt(2100))
        sizes.map { size =>
            val input = new Array[Byte](size)
            random.nextBytes(input)
            val cut = if input.isEmpty then 0 else random.nextInt(input.length)
            val ok  = hash(Chunk(input.take(cut), input.drop(cut))).sameElements(NodeOracle.hash(algorithm, input))
            (ok, s"$algorithm, seed $seed, $size bytes, cut $cut")
        }.collect { case (false, label) => label }
    end mismatches

end Sha256NodeTest
