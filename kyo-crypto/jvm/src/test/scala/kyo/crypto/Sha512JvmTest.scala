package kyo.crypto

import java.security.MessageDigest
import kyo.*

class Sha512JvmTest extends kyo.test.Test[Any]:

    private def generated(size: Int, salt: Int): Array[Byte] =
        Array.tabulate(size)(index => ((index * 73 + salt * 41 + size * 19) & 0xff).toByte)

    "hashing" - {
        "matches the JVM digest for deterministic inputs at block boundaries" in {
            val sizes = Seq(0, 1, 2, 7, 8, 15, 16, 63, 64, 65, 111, 112, 113, 127, 128, 129, 239, 240, 241, 255, 256, 257)
            sizes.foreach { size =>
                val input    = generated(size, salt = 11)
                val expected = MessageDigest.getInstance("SHA-512").digest(input)
                assert(Sha512.hashArray(input).sameElements(expected))
            }
        }

        "matches the JVM digest for deterministic multi-block inputs" in {
            val sizes = Seq(511, 512, 513, 1023, 1024, 1025, 8191, 65537)
            sizes.foreach { size =>
                val input    = generated(size, salt = 29)
                val expected = MessageDigest.getInstance("SHA-512").digest(input)
                assert(Sha512.hashArray(input).sameElements(expected))
            }
        }

        "matches the JVM digest when deterministic input is split across chunks" in {
            val input  = generated(1025, salt = 47)
            val chunks = Chunk(input.take(1), input.slice(1, 112), input.slice(112, 128), input.slice(128, 700), input.drop(700))
            val digest = MessageDigest.getInstance("SHA-512")
            chunks.foreach(digest.update)
            assert(Sha512.hashArrays(chunks).sameElements(digest.digest()))
        }

        "matches the JVM digest on 400 random inputs of random sizes, split at random points" in {
            val seed   = new java.util.Random().nextLong()
            val random = new java.util.Random(seed)
            (0 until 400).foreach { i =>
                val input = new Array[Byte](random.nextInt(2100))
                random.nextBytes(input)
                val cut = if input.isEmpty then 0 else random.nextInt(input.length)
                val jdk = MessageDigest.getInstance("SHA-512").digest(input)
                assert(Sha512.hashArray(input).sameElements(jdk), s"seed $seed, case $i, ${input.length} bytes")
                assert(Sha512.hashArrays(Chunk(input.take(cut), input.drop(cut))).sameElements(jdk), s"seed $seed, case $i, cut $cut")
            }
        }
    }
end Sha512JvmTest
