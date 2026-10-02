package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.crypto.Rsa.BoundsFailure
import kyo.crypto.Rsa.Component
import kyo.crypto.Rsa.KeyFailure
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.TestVectorsJson.Json

class RsaTest extends kyo.test.Test[Any]:

    import RsaTest.*

    "RFC 7515 appendix A.2's published JWK builds a 2048-bit key with e = 65537" in {
        val key = rfc7515.key
        assert(key.modulus.bitLength == 2048)
        assert(key.exponent == BigInt(65537))
        assert(key.sizeInBytes == 256)
        assert(Rsa.verificationKeyFromJwk(jwk(key.modulus), jwk(key.exponent)) == Result.succeed(key))
        assert(jwk(key.modulus) == rfc7515.n)
    }

    "public operation" - {
        "RFC 7515's signature recovers the EMSA-PKCS1-v1_5 block: 00 01, 202 bytes of ff, 00, the SHA-256 DigestInfo" in {
            Rsa.publicOperation(rfc7515.key, rfc7515.signature) match
                case Present(block) =>
                    assert(block.length == 256)
                    assert(block(0) == 0 && block(1) == 1)
                    assert(block.slice(2, 204).forall(_ == 0xff.toByte))
                    assert(block(204) == 0)
                    assert(block.drop(224).sameElements(Sha256.hashArray(rfc7515.signingInput)))
                case Absent => fail("the signature is below the modulus and of its length")
        }

        "an input that is not the key's length is Absent" in {
            assert(Rsa.publicOperation(rfc7515.key, rfc7515.signature.take(255)) == Absent)
            assert(Rsa.publicOperation(rfc7515.key, rfc7515.signature :+ 0.toByte) == Absent)
            assert(Rsa.publicOperation(rfc7515.key, Array.emptyByteArray) == Absent)
        }

        "an input whose value is not below the modulus is Absent" in {
            val key = rfc7515.key
            assert(Rsa.publicOperation(key, magnitude(key.modulus)) == Absent)
            assert(Rsa.publicOperation(key, Array.fill[Byte](256)(0xff.toByte)) == Absent)
            assert(Rsa.publicOperation(key, magnitude(key.modulus - 1)).map(_.length) == Present(256))
        }

        "0 and 1 are fixed points, as the key's length in bytes" in {
            val key  = rfc7515.key
            val zero = new Array[Byte](256)
            val one  = new Array[Byte](256)
            one(255) = 1
            assert(Rsa.publicOperation(key, zero).map(_.toSeq) == Present(zero.toSeq))
            assert(Rsa.publicOperation(key, one).map(_.toSeq) == Present(one.toSeq))
        }

        "leaves the input unchanged" in {
            val input = rfc7515.signature.clone()
            discard(Rsa.publicOperation(rfc7515.key, input))
            assert(input.sameElements(rfc7515.signature))
        }
    }

    "size bounds" - {
        "a 2048-bit modulus, the minimum, is accepted" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "AQAB").map(_.modulus.bitLength) == Result.succeed(2048))
        }

        "a 2047-bit modulus is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2047)), "AQAB") == boundsFailure(BoundsFailure.ModulusTooSmall(2047, 2048)))
        }

        "an empty modulus is rejected as zero bits" in {
            assert(Rsa.verificationKeyFromJwk("", "AQAB") == boundsFailure(BoundsFailure.ModulusTooSmall(0, 2048)))
        }

        "an 8192-bit modulus, the maximum, is accepted" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(8192)), "AQAB").map(_.modulus.bitLength) == Result.succeed(8192))
        }

        "an 8193-bit modulus is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(8193)), "AQAB") == boundsFailure(BoundsFailure.ModulusTooLarge(8193, 8192)))
        }

        "a 64-bit exponent, the maximum, is accepted" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(oddOfBits(64))).map(_.exponent.bitLength) == Result.succeed(64))
        }

        "a 65-bit exponent is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(oddOfBits(65))) ==
                boundsFailure(BoundsFailure.ExponentTooLarge(65, 64)))
        }
    }

    "parity and range" - {
        "an even modulus is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048) + 1), "AQAB") == boundsFailure(BoundsFailure.ModulusEven))
        }

        "e = 3 is accepted" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(BigInt(3))).map(_.exponent) == Result.succeed(BigInt(3)))
        }

        "e = 1, e = 2 and an empty exponent are rejected as below 3" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(BigInt(1))) ==
                boundsFailure(BoundsFailure.ExponentTooSmall(BigInt(1))))
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(BigInt(2))) ==
                boundsFailure(BoundsFailure.ExponentTooSmall(BigInt(2))))
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "") == boundsFailure(BoundsFailure.ExponentTooSmall(BigInt(0))))
        }

        "an even exponent is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), jwk(BigInt(65536))) ==
                boundsFailure(BoundsFailure.ExponentEven(BigInt(65536))))
        }

        "the direct constructor applies the same checks" in {
            assert(Rsa.VerificationKey(oddOfBits(2047), BigInt(65537)) == boundsFailure(BoundsFailure.ModulusTooSmall(2047, 2048)))
            assert(Rsa.VerificationKey(BigInt(-1), BigInt(65537)) == boundsFailure(BoundsFailure.ModulusTooSmall(0, 2048)))
            assert(Rsa.VerificationKey(oddOfBits(2048), BigInt(-3)) == boundsFailure(BoundsFailure.ExponentTooSmall(BigInt(-3))))
            assert(Rsa.VerificationKey(oddOfBits(2048), BigInt(65537)).map(_.exponent) == Result.succeed(BigInt(65537)))
        }

        "two keys are equal exactly when their modulus and exponent are" in {
            val a = keyOf(Rsa.VerificationKey(oddOfBits(2048), BigInt(65537)))
            val b = keyOf(Rsa.VerificationKey(oddOfBits(2048), BigInt(65537)))
            val c = keyOf(Rsa.VerificationKey(oddOfBits(2048), BigInt(3)))
            assert(a == b)
            assert(a.hashCode == b.hashCode)
            assert(a != c)
            assert(a.toString == s"VerificationKey(${a.modulus}, 65537)")
        }
    }

    "JWK encoding" - {
        "a leading zero octet in n is rejected" in {
            val padded = Base64.encodeUrl(Span.from(0.toByte +: magnitude(oddOfBits(2048))))
            assert(Rsa.verificationKeyFromJwk(padded, "AQAB") == Result.fail(KeyFailure.LeadingZero(Component.Modulus)))
        }

        "a leading zero octet in e is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "AAEAAQ") == Result.fail(KeyFailure.LeadingZero(Component.Exponent)))
        }

        "base64url with padding is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "AQAB==") == Result.fail(KeyFailure.NotBase64Url(Component.Exponent)))
            assert(Rsa.verificationKeyFromJwk(rfc7515.n + "=", "AQAB") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
        }

        "the standard alphabet's + and / are rejected" in {
            val n = rfc7515.n
            assert(n.contains('-') && n.contains('_'))
            assert(Rsa.verificationKeyFromJwk(n.replace('-', '+'), "AQAB") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
            assert(Rsa.verificationKeyFromJwk(n.replace('_', '/'), "AQAB") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
        }

        "a length of 1 mod 4 is rejected" in {
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "AQABA") == Result.fail(KeyFailure.NotBase64Url(Component.Exponent)))
            val n       = rfc7515.n
            val tooLong = n + "A" * ((1 - n.length % 4 + 4) % 4)
            assert(tooLong.length % 4 == 1)
            assert(Rsa.verificationKeyFromJwk(tooLong, "AQAB") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
        }

        "nonzero bits after the last byte of n or e are rejected, so one key has one JWK text (base64 malleability)" in {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val n        = rfc7515.n
            // 256 bytes end in a two-character unit whose last character carries 4 bits after the data.
            assert(n.length % 4 == 2)
            val malleated = n.init + alphabet(alphabet.indexOf(n.last) ^ 1)
            assert(Rsa.verificationKeyFromJwk(malleated, "AQAB") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
            assert(keyOf(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "Aw")).exponent == 3)
            assert(Rsa.verificationKeyFromJwk(jwk(oddOfBits(2048)), "Ax") == Result.fail(KeyFailure.NotBase64Url(Component.Exponent)))
        }

        "n is checked before e" in {
            assert(Rsa.verificationKeyFromJwk("!", "!") == Result.fail(KeyFailure.NotBase64Url(Component.Modulus)))
        }
    }

end RsaTest

object RsaTest:

    def boundsFailure(failure: BoundsFailure): Result[KeyFailure, Nothing] = Result.fail(KeyFailure.Bounds(failure))

    def magnitude(value: BigInt): Array[Byte] = value.toByteArray.dropWhile(_ == 0)

    def jwk(value: BigInt): String = Base64.encodeUrl(Span.from(magnitude(value)))

    /** The odd number with exactly `bits` bits: `2^(bits - 1) + 1`. */
    def oddOfBits(bits: Int): BigInt = BigInt(0).setBit(bits - 1) + 1

    def keyOf(result: Result[KeyFailure, Rsa.VerificationKey]): Rsa.VerificationKey = result match
        case Result.Success(key) => key
        case other               => throw new IllegalStateException(s"expected a key, got $other")

    /** RFC 7515 appendix A.2, "Example JWS Using RSASSA-PKCS1-v1_5 SHA-256", read from the vendored RFC text. */
    final case class Rfc7515Example(n: String, e: String, key: Rsa.VerificationKey, signingInput: Array[Byte], signature: Array[Byte])

    lazy val rfc7515: Rfc7515Example =
        val text    = TestVectors.text("ietf-rfc7515", "rfc7515.txt")
        val section = text.substring(
            text.indexOf("\nA.2.  Example JWS Using RSASSA-PKCS1-v1_5 SHA-256"),
            text.indexOf("\nA.3.  Example JWS Using ECDSA P-256 SHA-256")
        )
        val lines = section.linesIterator.toSeq

        val jwkLines = lines.dropWhile(!_.trim.startsWith("{\"kty\":\"RSA\"")).takeWhile(_.trim != "}")
        val jwkJson  = Json.parse(jwkLines.map(_.trim).mkString + "}")
        val n        = jwkJson("n").string
        val e        = jwkJson("e").string

        val compactPart = """[A-Za-z0-9_.\-]+"""
        val compact     = lines
            .dropWhile(!_.contains("representation using the JWS Compact Serialization"))
            .drop(1)
            .dropWhile(!_.trim.matches(compactPart))
            .takeWhile(_.trim.matches(compactPart))
            .map(_.trim)
            .mkString
        val parts = compact.split('.')
        require(parts.length == 3, s"expected three JWS parts, got ${parts.length}")

        def octets(after: String): Array[Byte] =
            val from = section.indexOf("[", section.indexOf(after))
            section.substring(from + 1, section.indexOf("]", from)).split(",").map(_.trim.toInt.toByte)

        val signingInput = (parts(0) + "." + parts(1)).getBytes(StandardCharsets.US_ASCII)
        val signature    = Base64.decodeUrl(parts(2)) match
            case Result.Success(bytes) => bytes.toArray
            case other                 => throw new IllegalStateException(s"JWS signature is not base64url: $other")
        require(
            octets("The resulting JWS Signing Input value").sameElements(signingInput),
            "the listed JWS Signing Input octets differ from the compact serialization"
        )
        require(
            octets("which represents a big-endian integer").sameElements(signature),
            "the listed signature octets differ from the compact serialization"
        )
        Rfc7515Example(n, e, keyOf(Rsa.verificationKeyFromJwk(n, e)), signingInput, signature)
    end rfc7515

end RsaTest
