package kyo.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import kyo.*

/** [[RsaOaep.encryptSha1]] against the JDK's own OAEP, `Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")`: the plaintext back
  * from the JDK for ciphertexts under fresh keys and seeds, and the JDK's own ciphertext byte for byte when it is handed the same seed.
  */
class RsaOaepJvmTest extends kyo.test.Test[Any]:

    private val random = new java.security.SecureRandom()

    private def seed(): Span[Byte] =
        val bytes = new Array[Byte](20)
        random.nextBytes(bytes)
        Span.from(bytes)
    end seed

    private def roundTrip(bits: Int, plaintexts: Seq[Array[Byte]])(using kyo.test.AssertScope): Unit =
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        val key  = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromSpki(Span.from(pair.getPublic.getEncoded)))
        plaintexts.foreach { plaintext =>
            val ciphertext = RsaOaepTest.ciphertextOf(RsaOaep.encryptSha1(key, Span.from(plaintext), seed()))
            val cipher     = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate)
            val decrypted = cipher.doFinal(ciphertext.toArray)
            assert(ciphertext.size == bits / 8)
            assert(decrypted.sameElements(plaintext), s"${plaintext.length} bytes did not round-trip through a $bits-bit key")
        }
    end roundTrip

    "the JDK decrypts a 2048-bit ciphertext to the plaintext, from empty to the longest the key takes" in {
        val longest = 256 - 2 * 20 - 2
        roundTrip(
            2048,
            Seq(
                Array.emptyByteArray,
                "hello".getBytes(StandardCharsets.UTF_8),
                "p4ssw0rd with a trailing NUL, as the MySQL scramble sends it\u0000".getBytes(StandardCharsets.UTF_8),
                Array.tabulate[Byte](longest)(i => (i * 13 + 7).toByte)
            )
        )
    }

    "the JDK decrypts a 3072-bit and a 4096-bit ciphertext" in {
        roundTrip(3072, Seq("hello".getBytes(StandardCharsets.UTF_8)))
        roundTrip(4096, Seq(Array.tabulate[Byte](100)(i => (255 - i).toByte)))
    }

    "under the pinned key and the pinned seed, the JDK's ciphertext is byte for byte this module's" in {
        val body    = RsaSpkiTest.testPubPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
        val spki    = java.util.Base64.getMimeDecoder.decode(body)
        val jdkKey  = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki))
        val key     = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromSpki(Span.from(spki)))
        val encrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        Seq(Array.emptyByteArray, "hello".getBytes(StandardCharsets.UTF_8), Array.tabulate[Byte](214)(i => (i * 3).toByte)).foreach {
            plaintext =>
                encrypt.init(Cipher.ENCRYPT_MODE, jdkKey, new RsaOaepJvmTest.FixedSeed(RsaOaepTest.seed42.toArray))
                val fromJdk = encrypt.doFinal(plaintext)
                val ours    = RsaOaepTest.ciphertextOf(RsaOaep.encryptSha1(key, Span.from(plaintext), RsaOaepTest.seed42))
                assert(ours.toArray.sameElements(fromJdk), s"${plaintext.length} bytes")
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, random)
        val pair    = generator.generateKeyPair()
        val fresh   = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromSpki(Span.from(pair.getPublic.getEncoded)))
        val seed    = this.seed()
        val message = "the same bytes".getBytes(StandardCharsets.UTF_8)
        encrypt.init(Cipher.ENCRYPT_MODE, pair.getPublic, new RsaOaepJvmTest.FixedSeed(seed.toArray))
        assert(RsaOaepTest.ciphertextOf(RsaOaep.encryptSha1(
            fresh,
            Span.from(message),
            seed
        )).toArray.sameElements(encrypt.doFinal(message)))
    }

end RsaOaepJvmTest

object RsaOaepJvmTest:

    /** A `SecureRandom` whose every draw is `seed`, so the JDK's OAEP masks with the seed this module was given. */
    final class FixedSeed(seed: Array[Byte]) extends java.security.SecureRandom:
        override def nextBytes(bytes: Array[Byte]): Unit =
            require(bytes.length == seed.length, s"the JDK asked for ${bytes.length} bytes, the seed has ${seed.length}")
            java.lang.System.arraycopy(seed, 0, bytes, 0, seed.length)
        end nextBytes
    end FixedSeed

end RsaOaepJvmTest
