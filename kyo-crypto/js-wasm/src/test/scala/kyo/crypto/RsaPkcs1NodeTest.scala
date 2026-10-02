package kyo.crypto

import kyo.*
import scala.scalajs.js as sjs

/** [[RsaPkcs1.verifySha256]] against Node (OpenSSL): Node signs random messages, one random bit is flipped in the message, the signature
  * or the modulus, and both verifiers must answer alike.
  */
class RsaPkcs1NodeTest extends kyo.test.Test[Any]:

    import NodeOracle.*

    private def keyPair(bits: Int): sjs.Dynamic =
        crypto.generateKeyPairSync("rsa", sjs.Dynamic.literal(modulusLength = bits, publicExponent = 65537))

    private def jwkNumbers(publicKey: sjs.Dynamic): (BigInt, BigInt) =
        val jwk = publicKey.`export`(sjs.Dynamic.literal(format = "jwk"))
        (BigInt(1, base64UrlBytes(jwk.n.asInstanceOf[String])), BigInt(1, base64UrlBytes(jwk.e.asInstanceOf[String])))

    private def nodeVerifies(n: BigInt, e: BigInt, message: Array[Byte], signature: Array[Byte]): Boolean =
        accepts {
            val jwk = sjs.Dynamic.literal(kty = "RSA", n = base64Url(n.toByteArray.dropWhile(_ == 0)), e = base64Url(e.toByteArray))
            val key = crypto.createPublicKey(sjs.Dynamic.literal(key = jwk, format = "jwk"))
            crypto.verify("sha256", buffer(message), key, buffer(signature)).asInstanceOf[Boolean]
        }

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

    "RS256 agrees with Node on its signatures under 2048 and 3072-bit keys and on one flipped bit in the message, signature or n" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        Seq(2048, 3072).foreach { bits =>
            val pair   = keyPair(bits)
            val (n, e) = jwkNumbers(pair.publicKey)
            (0 until 16).foreach { i =>
                val message = new Array[Byte](random.nextInt(300))
                random.nextBytes(message)
                val signature = bytes(crypto.sign("sha256", buffer(message), pair.privateKey))
                val label     = s"seed $seed, $bits bits, case $i"
                assert(oursVerifies(n, e, message, signature), label)
                val flippedMessage = if message.isEmpty then Array[Byte](1) else flip(message, random)
                Seq(
                    "message"   -> (n, flippedMessage, signature),
                    "signature" -> (n, message, flip(signature, random)),
                    "modulus"   -> (BigInt(1, flip(n.toByteArray.dropWhile(_ == 0), random)), message, signature)
                ).foreach { case (what, (modulus, m, s)) =>
                    assert(oursVerifies(modulus, e, m, s) == nodeVerifies(modulus, e, m, s), s"$label, flipped $what")
                }
            }
        }
    }

end RsaPkcs1NodeTest
