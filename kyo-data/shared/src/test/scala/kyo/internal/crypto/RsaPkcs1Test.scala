package kyo.internal.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectorsJson.Json

class RsaPkcs1Test extends kyo.test.Test[Any]:

    import RsaPkcs1Test.*
    import RsaTest.rfc7515

    "RFC 7515 appendix A.2's RS256 signature verifies" in {
        assert(RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, rfc7515.signature))
    }

    "Wycheproof" - {
        Seq(2048, 3072, 4096, 8192).foreach { size =>
            s"rsa_signature_${size}_sha256_test.json" in {
                val root = Json.parse(TestVectors.text("wycheproof", s"rsa_signature_${size}_sha256_test.json"))
                assert(root("algorithm").string == "RSASSA-PKCS1-v1_5")
                val outcomes = root("testGroups").items.flatMap { group =>
                    assert(group("type").string == "RsassaPkcs1Verify")
                    assert(group("sha").string == "SHA-256")
                    assert(group("keySize").int == size)
                    val key = RsaTest.keyOf(Rsa.publicKeyFromJwk(group("keyJwk")("n").string, group("keyJwk")("e").string))
                    assert(key.modulus == BigInt(group("publicKey")("modulus").string, 16))
                    assert(key.exponent == BigInt(group("publicKey")("publicExponent").string, 16))
                    group("tests").items.map { test =>
                        val id       = test("tcId").int
                        val flags    = test("flags").items.map(_.string)
                        val expected = test("result").string match
                            case "valid"                                     => true
                            case "invalid"                                   => false
                            case "acceptable" if flags == Seq("MissingNull") => false
                            case other => fail(s"tcId $id: result '$other' with flags $flags has no rule in this suite")
                        val answer = RsaPkcs1.verifySha256(key, bytes(test("msg").string), bytes(test("sig").string))
                        (id, answer == expected)
                    }
                }
                assert(outcomes.size == root("numberOfTests").int)
                val mismatched = outcomes.filterNot(_._2).map(_._1)
                assert(mismatched == Seq.empty, s"tcIds answering against their rule: $mismatched")
            }
        }
    }

    "signature length" - {
        "one byte short is rejected" in {
            assert(!RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, rfc7515.signature.drop(1)))
        }

        "one byte long is rejected, even when the extra byte is a leading zero that keeps the value" in {
            assert(!RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, rfc7515.signature :+ 0.toByte))
            assert(!RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, 0.toByte +: rfc7515.signature))
        }

        "empty is rejected" in {
            assert(!RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, Array.emptyByteArray))
        }
    }

    "signature value" - {
        "equal to the modulus is rejected" in {
            val key = rfc7515.key
            assert(!RsaPkcs1.verifySha256(key, rfc7515.signingInput, fixed(key.modulus, key.sizeInBytes)))
        }

        "the modulus plus one is rejected" in {
            val key = rfc7515.key
            assert(!RsaPkcs1.verifySha256(key, rfc7515.signingInput, fixed(key.modulus + 1, key.sizeInBytes)))
        }

        "a valid signature plus the modulus is rejected" in {
            val candidates = Seq(2048, 3072, 4096, 8192).flatMap(validCases).filter { c =>
                (BigInt(1, c.signature) + c.key.modulus).bitLength <= c.key.sizeInBytes * 8
            }
            assert(candidates.nonEmpty)
            candidates.foreach { c =>
                assert(RsaPkcs1.verifySha256(c.key, c.message, c.signature))
                val malleated = fixed(BigInt(1, c.signature) + c.key.modulus, c.key.sizeInBytes)
                assert(!RsaPkcs1.verifySha256(c.key, c.message, malleated))
            }
        }
    }

    "one flipped bit" - {
        "in the signature is rejected" in {
            Seq(0, 7, 1000, 2047).foreach { bit =>
                assert(!RsaPkcs1.verifySha256(rfc7515.key, rfc7515.signingInput, flip(rfc7515.signature, bit)))
            }
        }

        "in the message is rejected" in {
            Seq(0, 100).foreach { bit =>
                assert(!RsaPkcs1.verifySha256(rfc7515.key, flip(rfc7515.signingInput, bit), rfc7515.signature))
            }
        }
    }

    "a signature over one message does not verify another" in {
        assert(!RsaPkcs1.verifySha256(rfc7515.key, "another message".getBytes(StandardCharsets.UTF_8), rfc7515.signature))
    }

    "leaves the message and signature unchanged" in {
        val message   = rfc7515.signingInput.clone()
        val signature = rfc7515.signature.clone()
        assert(RsaPkcs1.verifySha256(rfc7515.key, message, signature))
        discard(RsaPkcs1.verifySha256(rfc7515.key, message, flip(signature, 5)))
        assert(message.sameElements(rfc7515.signingInput) && signature.sameElements(rfc7515.signature))
    }

    "a key read from a PEM, which passed only the ceilings, cannot be passed to verifySha256" in {
        typeCheckFailure("""kyo.internal.crypto.Rsa.encryptionKeyFromPem(kyo.internal.crypto.RsaSpkiTest.testPubPem).map { key =>
            kyo.internal.crypto.RsaPkcs1.verifySha256(key, Array.emptyByteArray, Array.emptyByteArray)
        }""")("Rsa.PublicKey")
    }

end RsaPkcs1Test

object RsaPkcs1Test:

    final case class Case(key: Rsa.PublicKey, message: Array[Byte], signature: Array[Byte])

    def bytes(hex: String): Array[Byte] =
        require(hex.length % 2 == 0, s"odd-length hex: $hex")
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    /** `value` as exactly `length` big-endian bytes. */
    def fixed(value: BigInt, length: Int): Array[Byte] =
        val raw = value.toByteArray.dropWhile(_ == 0)
        require(raw.length <= length, s"$value does not fit $length bytes")
        Array.fill[Byte](length - raw.length)(0) ++ raw
    end fixed

    def flip(bytes: Array[Byte], bit: Int): Array[Byte] =
        val copy = bytes.clone()
        copy(bit / 8) = (copy(bit / 8) ^ (1 << (bit % 8))).toByte
        copy
    end flip

    def validCases(size: Int): Seq[Case] =
        val root = Json.parse(TestVectors.text("wycheproof", s"rsa_signature_${size}_sha256_test.json"))
        root("testGroups").items.flatMap { group =>
            val key = RsaTest.keyOf(Rsa.publicKeyFromJwk(group("keyJwk")("n").string, group("keyJwk")("e").string))
            group("tests").items.filter(_("result").string == "valid").map { test =>
                Case(key, bytes(test("msg").string), bytes(test("sig").string))
            }
        }
    end validCases

end RsaPkcs1Test
