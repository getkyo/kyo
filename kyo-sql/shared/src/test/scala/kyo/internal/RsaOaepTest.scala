package kyo.internal

import kyo.*
import kyo.SqlException
import kyo.internal.auth.RsaOaep
import kyo.internal.auth.RsaOaep.RsaPublicKey

/** Unit tests for [[RsaOaep]], pure-Scala RSA-OAEP implementation.
  *
  * All 11 test leaves are implemented as positive assertions. Tests cover:
  *   - OAEP-encode output length (leaf 1)
  *   - PEM parser correctness (leaf 2)
  *   - PEM parser rejects missing header (leaf 3)
  *   - PEM parser rejects bad base64 (leaf 4)
  *   - ASN.1 BER decoder extracts modulus and exponent (leaf 5)
  *   - ASN.1 BER decoder rejects malformed DER (leaf 6)
  *   - MGF1 known-answer test vector (leaf 7)
  *   - Deterministic OAEP encryption pinned to known ciphertext (leaf 8)
  *   - Non-deterministic OAEP (same input, distinct ciphertext on re-run) (leaf 9)
  *   - Plaintext-too-long raises SqlRequestException (leaf 10)
  *   - Empty plaintext encrypts successfully (leaf 11)
  *
  * Test RSA key is a pre-generated 2048-bit RSA public key (SubjectPublicKeyInfo PEM). Tests involving full RSA encryption use
  * [[Random.withSeed]] for determinism and compare against vectors pre-computed in Java using the same `java.util.Random(42)` seed.
  *
  * Test vectors were verified independently with Java:
  * {{{
  *   java.util.Random(42L).nextBytes(20) => 359d41baf78afe0de1bbe7ae28c0450ce43c084f
  *   OAEP-encode("hello", k=256, seed=above) first 4 bytes of EM => 005ce39c
  *   RSA-OAEP-encrypt("hello", seed=above) first 16 bytes of ciphertext => 651390aa73e80e41925aac7e098055c3
  * }}}
  */
class RsaOaepTest extends kyo.Test:

    // ─── Test RSA public key (2048-bit, pre-generated) ──────────────────────────

    /** Pre-generated RSA 2048-bit public key in SubjectPublicKeyInfo PEM format. */
    val testPubPem: String =
        """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0fjhZ5a4z9ULtk0Xdeq1
O79oB9+t9VWGEicXHNrkIsqPswer2tOwDE4hlu/GkDh8w2kO9K/x+q+mSPg5SzlT
dDHBTlLTQnHm8Wc74CPBJHExcwJzuq7Xy1c1tmD1m69EO1QFvJcso/10RK3pnJ8g
IpWqwVJ8QOsXSRnwvTJYAUX0A/HLISgxI4YFXQUKevNxdQlLd82Wne6qZIjZwiXc
JvvIoQ/d4dsFhMs0FSSw9fgXcG3x89kCSj2TyUl0KlyL5AWr1gRqS4Psjo62GTTc
sufsIMrHVlDaMkvdPnPFtyARqWknXA1Lj6DfjcBSwaQY9F0g7T4UxV9SobYFeftU
FwIDAQAB
-----END PUBLIC KEY-----"""

    // Expected ciphertext (first 16 bytes) for encrypt("hello", seed=java.util.Random(42).nextBytes(20))
    // Pre-computed in Java with identical BigInt.modPow logic.
    val expectedCiphertextFirst16: Array[Byte] = Array[Byte](
        0x65.toByte,
        0x13.toByte,
        0x90.toByte,
        0xaa.toByte,
        0x73.toByte,
        0xe8.toByte,
        0x0e.toByte,
        0x41.toByte,
        0x92.toByte,
        0x5a.toByte,
        0xac.toByte,
        0x7e.toByte,
        0x09.toByte,
        0x80.toByte,
        0x55.toByte,
        0xc3.toByte
    )

    // ─── Leaf 1: OAEP-encode length ─────────────────────────────────────────────

    "RsaOaep OAEP-encode of a known message has correct length, 256 bytes for 2048-bit key" in {
        // k=256 bytes for 2048-bit modulus. encrypt() returns a Span of exactly k bytes.
        val plaintext = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Random.withSeed(42) {
            Random.get.flatMap { r =>
                RsaOaep.encrypt(testPubPem, plaintext, r).map { ct =>
                    assert(ct.size == 256)
                }
            }
        }
    }

    // ─── Leaf 2: PEM parser strips header/footer and decodes base64 ─────────────

    "RsaOaep PEM parser strips header/footer and decodes base64 correctly" in {
        RsaOaep.parsePem(testPubPem).map { key =>
            // 2048-bit RSA key: modulus is 256 bytes = 2048 bits.
            assert(key.modulus.bitLength >= 2047) // BigInt.bitLength ignores leading zeros
            assert(key.exponent == BigInt(65537))
        }
    }

    // ─── Leaf 3: PEM parser rejects malformed header ─────────────────────────────

    "RsaOaep PEM parser rejects PEM with missing '-----BEGIN PUBLIC KEY-----' header" in {
        val noPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        Abort.run[SqlRequestException](RsaOaep.parsePem(noPem)).map {
            case Result.Failure(e: SqlRequestException) =>
                assert(e.getMessage.contains("BEGIN PUBLIC KEY"))
            case other =>
                fail(s"Expected SqlRequestException for missing header, got: $other")
        }
    }

    // ─── Leaf 4: PEM parser rejects bad base64 ───────────────────────────────────

    "RsaOaep PEM parser rejects PEM with invalid base64 body" in {
        val badPem =
            "-----BEGIN PUBLIC KEY-----\n" +
                "!!!not-valid-base64!!!%%%\n" +
                "-----END PUBLIC KEY-----\n"
        Abort.run[SqlRequestException](RsaOaep.parsePem(badPem)).map {
            case Result.Failure(e: SqlRequestException) =>
                assert(e.getMessage.contains("base64") || e.getMessage.toLowerCase.contains("illegal"))
            case other =>
                fail(s"Expected SqlRequestException for bad base64, got: $other")
        }
    }

    // ─── Leaf 5: ASN.1 BER decoder extracts modulus + exponent ──────────────────

    "RsaOaep ASN.1 BER decoder extracts modulus and exponent from SubjectPublicKeyInfo DER" in {
        // Parse the PEM to get DER, then test parseDerSpki directly.
        val cleaned = testPubPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "")
        val der = java.util.Base64.getDecoder.decode(cleaned)
        RsaOaep.parseDerSpki(der).map { key =>
            // 2048-bit modulus.
            assert(key.modulus.bitLength >= 2047)
            // Public exponent = 65537 = 0x010001.
            assert(key.exponent == BigInt(65537))
            // Modulus must be positive and large.
            assert(key.modulus > BigInt(0))
        }
    }

    // ─── Leaf 6: ASN.1 BER decoder rejects malformed structure ──────────────────

    "RsaOaep ASN.1 BER decoder rejects truncated DER (malformed structure)" in {
        // Provide a truncated DER, just a SEQUENCE tag with no length/content.
        val truncated = Array[Byte](0x30.toByte)
        Abort.run[SqlRequestException](RsaOaep.parseDerSpki(truncated)).map {
            case Result.Failure(_: SqlRequestException) =>
                succeed
            case other =>
                fail(s"Expected SqlRequestException for malformed DER, got: $other")
        }
    }

    // ─── Leaf 7: MGF1 with SHA-1 matches known-answer test vector ────────────────

    "RsaOaep MGF1 with SHA-1 produces RFC-verified output, two known-answer vectors" in {
        // Vector 1: MGF1(seed=00 00 00 00, maskLen=20)
        // SHA-1(00 00 00 00 || 00 00 00 00) = 05fe405753166f125559e7c9ac558654f107c7e9
        // Verified independently with Python hashlib.
        val seed1 = Array[Byte](0x00, 0x00, 0x00, 0x00)
        val expected1 = Array[Byte](
            0x05.toByte,
            0xfe.toByte,
            0x40.toByte,
            0x57.toByte,
            0x53.toByte,
            0x16.toByte,
            0x6f.toByte,
            0x12.toByte,
            0x55.toByte,
            0x59.toByte,
            0xe7.toByte,
            0xc9.toByte,
            0xac.toByte,
            0x55.toByte,
            0x86.toByte,
            0x54.toByte,
            0xf1.toByte,
            0x07.toByte,
            0xc7.toByte,
            0xe9.toByte
        )
        assert(RsaOaep.mgf1(seed1, 20).sameElements(expected1))

        // Vector 2: MGF1(seed=aa, maskLen=4)
        // SHA-1(aa || 00 00 00 00) first 4 bytes = f667b659
        // Verified independently with Python hashlib.
        val seed2     = Array[Byte](0xaa.toByte)
        val expected2 = Array[Byte](0xf6.toByte, 0x67.toByte, 0xb6.toByte, 0x59.toByte)
        assert(RsaOaep.mgf1(seed2, 4).sameElements(expected2))
    }

    // ─── Leaf 8: OAEP with deterministic seed pins to known ciphertext ───────────

    "RsaOaep OAEP with deterministic seed (java.util.Random(42)) produces pre-computed ciphertext" in {
        // Pre-computed with Java: java.util.Random(42).nextBytes(20) = 359d41...
        // Then BigInt.modPow applied with this key's n and e.
        // First 16 bytes of ciphertext verified: 651390aa73e80e41925aac7e098055c3
        val plaintext = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Random.withSeed(42) {
            Random.get.flatMap { r =>
                RsaOaep.encrypt(testPubPem, plaintext, r).map { ct =>
                    assert(ct.size == 256)
                    // Pin first 16 bytes to known answer.
                    assert(ct.toArray.take(16).sameElements(expectedCiphertextFirst16))
                }
            }
        }
    }

    // ─── Leaf 9: Same plaintext encrypted twice produces distinct ciphertext ──────

    "RsaOaep same plaintext encrypted twice with Random.secure produces distinct ciphertext" in {
        val plaintext = Span.from("test".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        RsaOaep.encrypt(testPubPem, plaintext, Random.secure).flatMap { ct1 =>
            RsaOaep.encrypt(testPubPem, plaintext, Random.secure).map { ct2 =>
                // OAEP uses a random seed, same plaintext must yield distinct ciphertext.
                assert(!ct1.toArray.sameElements(ct2.toArray))
            }
        }
    }

    // ─── Leaf 10: Plaintext exceeding capacity raises SqlRequestException ────────

    "RsaOaep plaintext exceeding k−2·hLen−2 = 214 bytes raises SqlRequestException" in {
        // For 2048-bit key: k=256, hLen=20, maxLen=256-40-2=214.
        val tooLong = Span.from(Array.fill[Byte](215)(0x42.toByte))
        Abort.run[SqlRequestException](
            Random.withSeed(1) {
                Random.get.flatMap { r =>
                    RsaOaep.encrypt(testPubPem, tooLong, r)
                }
            }
        ).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "EME-OAEP", s"expected position 'EME-OAEP', got: ${e.position}")
                assert(e.tag == "plaintext-length", s"expected tag 'plaintext-length', got: ${e.tag}")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for oversized plaintext, got: $other")
        }
    }

    // ─── Leaf 11: Empty plaintext encrypts successfully ──────────────────────────

    "RsaOaep empty plaintext encrypts to a 256-byte ciphertext" in {
        val empty = Span.from(Array.empty[Byte])
        Random.withSeed(7) {
            Random.get.flatMap { r =>
                RsaOaep.encrypt(testPubPem, empty, r).map { ct =>
                    assert(ct.size == 256)
                }
            }
        }
    }

end RsaOaepTest
