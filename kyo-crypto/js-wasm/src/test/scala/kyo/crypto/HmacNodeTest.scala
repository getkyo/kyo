package kyo.crypto

import kyo.*

/** [[Hmac]] against Node's `createHmac`: the MAC for keys from empty to past the 64-byte block. */
class HmacNodeTest extends kyo.test.Test[Any]:

    "HMAC-SHA-256 matches Node for every key length from 0 to 200 and 200 random key and message pairs" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        ((0 to 200) ++ Seq.fill(200)(random.nextInt(300))).foreach { keySize =>
            val key     = new Array[Byte](keySize)
            val message = new Array[Byte](random.nextInt(500))
            random.nextBytes(key)
            random.nextBytes(message)
            val expected = NodeOracle.hmacSha256(key, message)
            assert(Hmac.sha256Array(key, message).sameElements(expected), s"seed $seed, key $keySize bytes, message ${message.length}")
            assert(Hmac.verifySha256(Span.from(key), Span.from(message), Span.from(expected)))
        }
    }

end HmacNodeTest
