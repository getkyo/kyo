package kyo.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kyo.*

/** [[Hmac.sha256]] against the JDK's `Mac.getInstance("HmacSHA256")`: keys from one byte to well past the 64-byte block, where RFC 2104
  * hashes the key first, and random messages. The empty key stands on the one-zero-byte key, which pads to the same block; `SecretKeySpec`
  * refuses zero bytes.
  */
class HmacJvmTest extends kyo.test.Test[Any]:

    private def jdk(key: Array[Byte], message: Array[Byte]): Array[Byte] =
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(if key.isEmpty then Array[Byte](0) else key, "HmacSHA256"))
        mac.doFinal(message)
    end jdk

    "matches the JVM MAC for every key length from 0 to 200 and 400 random key and message pairs, and verifies what the JDK computes" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        val keys   = (0 to 200) ++ Seq.fill(400)(random.nextInt(300))
        keys.foreach { keySize =>
            val key     = new Array[Byte](keySize)
            val message = new Array[Byte](random.nextInt(500))
            random.nextBytes(key)
            random.nextBytes(message)
            val expected = jdk(key, message)
            assert(Hmac.sha256Array(key, message).sameElements(expected), s"seed $seed, key $keySize bytes, message ${message.length}")
            assert(Hmac.verifySha256(Span.from(key), Span.from(message), Span.from(expected)))
            val flipped = expected.clone()
            flipped(random.nextInt(32)) = (flipped(0) ^ 1).toByte
            if !flipped.sameElements(expected) then assert(!Hmac.verifySha256(Span.from(key), Span.from(message), Span.from(flipped)))
        }
    }

end HmacJvmTest
