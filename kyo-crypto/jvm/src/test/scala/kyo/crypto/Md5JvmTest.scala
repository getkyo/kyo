package kyo.crypto

import java.security.MessageDigest
import kyo.*

/** [[Md5]] against the JDK's `MessageDigest.getInstance("MD5")` on random inputs at every size up to a few blocks. */
class Md5JvmTest extends kyo.test.Test[Any]:

    "matches the JVM digest at every size from 0 to 300 and on 400 random inputs, split at random points" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        val sizes  = (0 to 300) ++ Seq.fill(400)(random.nextInt(2100))
        sizes.foreach { size =>
            val input = new Array[Byte](size)
            random.nextBytes(input)
            val cut = if input.isEmpty then 0 else random.nextInt(input.length)
            val jdk = MessageDigest.getInstance("MD5").digest(input)
            assert(Md5.hashArray(input).sameElements(jdk), s"seed $seed, $size bytes")
            assert(Md5.hash(Span.from(input.take(cut))).size == 16)
            assert(
                Md5.hashAll(Chunk(Span.from(input.take(cut)), Span.from(input.drop(cut)))).toArray.sameElements(jdk),
                s"seed $seed, cut $cut"
            )
        }
    }

end Md5JvmTest
