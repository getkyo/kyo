package kyo.crypto

import kyo.*
import scala.scalajs.js as sjs

/** [[RsaOaep.encryptSha1]] against Node (OpenSSL): this module encrypts under a Node key pair and Node's `privateDecrypt` with OAEP-SHA-1
  * recovers the plaintext.
  */
class RsaOaepNodeTest extends kyo.test.Test[Any]:

    import NodeOracle.*

    "OAEP-SHA-1 ciphertexts decrypt under Node's privateDecrypt, from empty to the longest plaintext the key takes" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        val pair   = crypto.generateKeyPairSync("rsa", sjs.Dynamic.literal(modulusLength = 2048, publicExponent = 65537))
        val spki   = bytes(pair.publicKey.`export`(sjs.Dynamic.literal(`type` = "spki", format = "der")))
        val key    = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromSpki(Span.from(spki)))
        (Seq(0, 1, 214) ++ Seq.fill(20)(random.nextInt(215))).foreach { size =>
            val plaintext = new Array[Byte](size)
            val oaepSeed  = new Array[Byte](20)
            random.nextBytes(plaintext)
            random.nextBytes(oaepSeed)
            val ciphertext = RsaOaepTest.ciphertextOf(RsaOaep.encryptSha1(key, Span.from(plaintext), Span.from(oaepSeed)))
            val options    = sjs.Dynamic.literal(
                key = pair.privateKey,
                padding = crypto.constants.RSA_PKCS1_OAEP_PADDING,
                oaepHash = "sha1"
            )
            val decrypted = bytes(crypto.privateDecrypt(options, buffer(ciphertext.toArray)))
            assert(decrypted.sameElements(plaintext), s"seed $seed, $size bytes")
        }
    }

end RsaOaepNodeTest
