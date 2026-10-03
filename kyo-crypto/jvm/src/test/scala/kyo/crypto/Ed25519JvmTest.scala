package kyo.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kyo.*
import kyo.crypto.Ed25519.VerificationKey

/** [[Ed25519.verify]] against the JDK's own Ed25519: keys and signatures the JDK generates are accepted, their mutations rejected by both,
  * and the test-side signer's signatures pass the JDK, so the oracle the shared suites rely on is itself checked.
  */
class Ed25519JvmTest extends kyo.test.Test[Any]:

    import Ed25519Test.*

    private val random: SecureRandom =
        val r = SecureRandom.getInstance("SHA1PRNG")
        r.setSeed(0x25519L)
        r
    end random

    /** The SubjectPublicKeyInfo of RFC 8410 around 32 raw key bytes. */
    private val SpkiPrefix: Array[Byte] = bytes("302a300506032b6570032100")

    private def jdkVerify(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]): Boolean =
        val key      = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(SpkiPrefix ++ publicKey))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(message)
        try verifier.verify(signature)
        catch case _: java.security.SignatureException => false
    end jdkVerify

    "signatures the JDK produces verify, and their mutations fail in both verifiers" in {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        generator.initialize(255, random)
        (0 until 8).foreach { i =>
            val pair      = generator.generateKeyPair()
            val publicKey = pair.getPublic.getEncoded.takeRight(32)
            val key       = keyOf(VerificationKey.fromBytes(Span.from(publicKey)))
            Seq(0, 1, 31, 32, 33, 63, 64, 65, 127, 128, 129, 300).foreach { length =>
                val message = new Array[Byte](length)
                random.nextBytes(message)
                val signer = Signature.getInstance("Ed25519")
                signer.initSign(pair.getPrivate)
                signer.update(message)
                val signature = signer.sign()
                assert(signature.length == 64)
                assert(Ed25519.verify(key, Span.from(message), Span.from(signature)), s"key $i, length $length")
                assert(Ed25519Reference.verify(publicKey, message, signature))
                Seq(0, 7, 255, 256, 260, 511).foreach { bit =>
                    val mutated = flip(signature, bit)
                    assert(!jdkVerify(publicKey, message, mutated), s"key $i, length $length, bit $bit")
                    assert(!Ed25519.verify(key, Span.from(message), Span.from(mutated)), s"key $i, length $length, bit $bit")
                }
                if length > 0 then
                    val mutated = flip(message, length * 4)
                    assert(!jdkVerify(publicKey, mutated, signature))
                    assert(!Ed25519.verify(key, Span.from(mutated), Span.from(signature)))
                end if
            }
        }
    }

    "agrees with the JVM on 200 JDK signatures and on one random bit flipped in the message, the signature or the key" in {
        val seed      = new java.util.Random().nextLong()
        val pick      = new java.util.Random(seed)
        val generator = KeyPairGenerator.getInstance("Ed25519")
        generator.initialize(255, random)
        def jdkAccepts(pub: Array[Byte], m: Array[Byte], s: Array[Byte]): Boolean =
            try jdkVerify(pub, m, s)
            catch case _: java.security.GeneralSecurityException => false
        (0 until 200).foreach { i =>
            val pair      = generator.generateKeyPair()
            val publicKey = pair.getPublic.getEncoded.takeRight(32)
            val message   = new Array[Byte](pick.nextInt(300))
            pick.nextBytes(message)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.getPrivate)
            signer.update(message)
            val signature = signer.sign()
            val label     = s"seed $seed, case $i"
            assert(verifies(publicKey, message, signature), label)
            val flippedMessage = if message.isEmpty then Array[Byte](1) else flip(message, pick.nextInt(message.length * 8))
            Seq(
                "message"   -> (publicKey, flippedMessage, signature),
                "signature" -> (publicKey, message, flip(signature, pick.nextInt(512))),
                "key"       -> (flip(publicKey, pick.nextInt(256)), message, signature)
            ).foreach { case (what, (pub, m, s)) =>
                assert(verifies(pub, m, s) == jdkAccepts(pub, m, s), s"$label, flipped $what")
            }
        }
    }

    "the reference signer's signatures pass the JDK, and the JDK and this module agree on the RFC 8032 vectors" in {
        Seq(31L, 32L, 33L).foreach { seed =>
            val g = generated(seed, 12)
            g.messages.foreach { message =>
                val signature = Ed25519Reference.sign(g.secretKey, message)
                assert(jdkVerify(g.publicKey, message, signature))
                assert(verifies(g.publicKey, message, signature))
            }
        }
        rfcVectors.foreach { v =>
            assert(jdkVerify(v.publicKey, v.message, v.signature))
            assert(verifies(v.publicKey, v.message, v.signature))
            assert(!jdkVerify(v.publicKey, v.message, flip(v.signature, 100)))
            assert(!verifies(v.publicKey, v.message, flip(v.signature, 100)))
        }
    }

end Ed25519JvmTest
