package kyo.crypto

import kyo.*
import scala.scalajs.js as sjs

/** [[Ed25519.verify]] against Node's Ed25519 (OpenSSL): Node's own signatures verify, and with one random bit flipped in the message, the
  * signature or the key both verifiers answer alike.
  */
class Ed25519NodeTest extends kyo.test.Test[Any]:

    import NodeOracle.*

    private def nodeVerifies(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]): Boolean =
        accepts {
            val jwk = sjs.Dynamic.literal(kty = "OKP", crv = "Ed25519", x = base64Url(publicKey))
            val key = crypto.createPublicKey(sjs.Dynamic.literal(key = jwk, format = "jwk"))
            crypto.verify(null, buffer(message), key, buffer(signature)).asInstanceOf[Boolean]
        }

    private def flip(bytes: Array[Byte], bit: Int): Array[Byte] =
        val copy = bytes.clone()
        copy(bit / 8) = (copy(bit / 8) ^ (1 << (bit % 8))).toByte
        copy
    end flip

    "agrees with Node on 100 of its signatures and on one random bit flipped in the message, the signature or the key" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        (0 until 100).foreach { i =>
            val pair      = crypto.generateKeyPairSync("ed25519")
            val publicKey = base64UrlBytes(pair.publicKey.`export`(sjs.Dynamic.literal(format = "jwk")).x.asInstanceOf[String])
            val message   = new Array[Byte](random.nextInt(300))
            random.nextBytes(message)
            val signature = bytes(crypto.sign(null, buffer(message), pair.privateKey))
            val label     = s"seed $seed, case $i"
            assert(Ed25519Test.verifies(publicKey, message, signature), label)
            val flippedMessage = if message.isEmpty then Array[Byte](1) else flip(message, random.nextInt(message.length * 8))
            Seq(
                "message"   -> (publicKey, flippedMessage, signature),
                "signature" -> (publicKey, message, flip(signature, random.nextInt(512))),
                "key"       -> (flip(publicKey, random.nextInt(256)), message, signature)
            ).foreach { case (what, (pub, m, s)) =>
                assert(Ed25519Test.verifies(pub, m, s) == nodeVerifies(pub, m, s), s"$label, flipped $what")
            }
        }
    }

end Ed25519NodeTest
