package kyo.internal.mysql.auth

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kyo.*

/** [[RsaOaep.encrypt]] against the JDK's own OAEP: a fresh key pair, a ciphertext from this module, the plaintext back from
  * `Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")`.
  */
class RsaOaepJvmTest extends kyo.Test:

    private def pemOf(spki: Array[Byte]): String =
        val body = java.util.Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(spki)
        s"-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"

    private def roundTrip(bits: Int, plaintexts: Seq[Array[Byte]])(using kyo.test.AssertScope) =
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        val pem  = pemOf(pair.getPublic.getEncoded)
        Kyo.foreach(plaintexts) { plaintext =>
            RsaOaep.encrypt(pem, Span.from(plaintext), SecureRandom.live).map { ciphertext =>
                val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
                cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate)
                val decrypted = cipher.doFinal(ciphertext.toArray)
                assert(ciphertext.size == bits / 8)
                assert(decrypted.sameElements(plaintext), s"${plaintext.length} bytes did not round-trip through a $bits-bit key")
            }
        }.map(_ => succeed)
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
        roundTrip(3072, Seq("hello".getBytes(StandardCharsets.UTF_8))).map { _ =>
            roundTrip(4096, Seq(Array.tabulate[Byte](100)(i => (255 - i).toByte)))
        }
    }

end RsaOaepJvmTest
