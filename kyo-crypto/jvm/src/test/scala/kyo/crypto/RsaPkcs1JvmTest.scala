package kyo.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import kyo.*

/** [[RsaPkcs1.verifySha256]] against the JDK's `Signature.getInstance("SHA256withRSA")`: the JDK signs random messages under fresh keys of
  * every size the verifier takes, and for each signature one random bit is flipped in the message, the signature or the modulus, where the
  * JDK and this module must give the same answer.
  */
class RsaPkcs1JvmTest extends kyo.test.Test[Any]:

    private def jdkVerifies(n: BigInt, e: BigInt, message: Array[Byte], signature: Array[Byte]): Boolean =
        try
            val key      = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n.bigInteger, e.bigInteger))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(signature)
        catch case _: java.security.GeneralSecurityException => false

    private def oursVerifies(n: BigInt, e: BigInt, message: Array[Byte], signature: Array[Byte]): Boolean =
        Rsa.VerificationKey(n, e) match
            case Result.Success(key) => RsaPkcs1.verifySha256(key, Span.from(message), Span.from(signature))
            case _                   => false

    private def flip(bytes: Array[Byte], random: java.util.Random): Array[Byte] =
        val copy = bytes.clone()
        val bit  = random.nextInt(copy.length * 8)
        copy(bit / 8) = (copy(bit / 8) ^ (1 << (bit % 8))).toByte
        copy
    end flip

    "agrees with the JVM on JDK signatures under 2048, 3072 and 4096-bit keys and on one flipped bit in the message, the signature or n" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        Seq(2048, 3072, 4096).foreach { bits =>
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(bits)
            val pair = generator.generateKeyPair()
            val pub  = pair.getPublic.asInstanceOf[RSAPublicKey]
            val n    = BigInt(pub.getModulus)
            val e    = BigInt(pub.getPublicExponent)
            (0 until 20).foreach { i =>
                val message = new Array[Byte](random.nextInt(300))
                random.nextBytes(message)
                val signer = Signature.getInstance("SHA256withRSA")
                signer.initSign(pair.getPrivate)
                signer.update(message)
                val signature = signer.sign()
                val label     = s"seed $seed, $bits bits, case $i"
                assert(oursVerifies(n, e, message, signature), label)
                val flippedMessage   = if message.isEmpty then Array[Byte](1) else flip(message, random)
                val flippedSignature = flip(signature, random)
                val flippedN         = BigInt(1, flip(n.toByteArray.dropWhile(_ == 0), random))
                Seq(
                    "message"   -> (n, flippedMessage, signature),
                    "signature" -> (n, message, flippedSignature),
                    "modulus"   -> (flippedN, message, signature)
                ).foreach { case (what, (modulus, m, s)) =>
                    assert(oursVerifies(modulus, e, m, s) == jdkVerifies(modulus, e, m, s), s"$label, flipped $what")
                    assert(!oursVerifies(modulus, e, m, s), s"$label, flipped $what")
                }
            }
        }
    }

end RsaPkcs1JvmTest
