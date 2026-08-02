package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.auth.RsaOaep.RsaPublicKey

/** Unit tests for [[RsaOaep]], pure-Scala RSA-OAEP implementation.
  *
  * Coverage:
  *   - OAEP-encode output length
  *   - PEM parser correctness
  *   - PEM parser rejects missing header
  *   - PEM parser rejects bad base64
  *   - ASN.1 BER decoder extracts modulus and exponent
  *   - ASN.1 BER decoder rejects malformed DER
  *   - MGF1 known-answer test vector
  *   - Deterministic OAEP encryption pinned to known ciphertext
  *   - Non-deterministic OAEP (same input, distinct ciphertext on re-run)
  *   - Plaintext-too-long raises SqlRequestException
  *   - Empty plaintext encrypts successfully
  *   - A peer-supplied modulus and exponent are bounded, so the peer cannot choose how long modPow runs
  *
  * Test RSA key is a pre-generated 2048-bit RSA public key (SubjectPublicKeyInfo PEM). Tests involving full RSA encryption use the
  * [[seeded]] `SecureRandom` for determinism and compare against vectors pre-computed in Java using the same `java.util.Random(42)` seed.
  *
  * Test vectors were verified independently with Java:
  * {{{
  *   java.util.Random(42L).nextBytes(20) => 359d41baf78afe0de1bbe7ae28c0450ce43c084f
  *   OAEP-encode("hello", k=256, seed=above) first 4 bytes of EM => 005ce39c
  *   RSA-OAEP-encrypt("hello", seed=above) first 16 bytes of ciphertext => 651390aa73e80e41925aac7e098055c3
  * }}}
  */
class RsaOaepTest extends kyo.Test:

    /** A `SecureRandom` whose byte draws come from a `java.util.Random(seed)`, so the OAEP seed is deterministic and the ciphertext can be
      * pinned against vectors pre-computed in Java from the same seed. Production `RsaOaep.encrypt` takes the ambient secure source; this
      * seeded stand-in is a test-only substitution.
      */
    private def seeded(seed: Long): SecureRandom =
        SecureRandom(
            new SecureRandom.Unsafe:
                private val jr = new java.util.Random(seed)
                def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                    val arr = new Array[Byte](length)
                    jr.nextBytes(arr)
                    Span.fromUnsafe(arr)
                end nextBytes
        )

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

    // ─── OAEP-encode length ─────────────────────────────────────────────────────

    "RsaOaep OAEP-encode of a known message has correct length, 256 bytes for 2048-bit key" in {
        // k=256 bytes for 2048-bit modulus. encrypt() returns a Span of exactly k bytes.
        val plaintext = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        RsaOaep.encrypt(testPubPem, plaintext, seeded(42)).map { ct =>
            assert(ct.size == 256)
        }
    }

    // ─── PEM parser strips header/footer and decodes base64 ─────────────────────

    "RsaOaep PEM parser strips header/footer and decodes base64 correctly" in {
        RsaOaep.parsePem(testPubPem).map { key =>
            // 2048-bit RSA key: modulus is 256 bytes = 2048 bits.
            assert(key.modulus.bitLength >= 2047) // BigInt.bitLength ignores leading zeros
            assert(key.exponent == BigInt(65537))
        }
    }

    // ─── PEM parser rejects malformed header ─────────────────────────────────────

    "RsaOaep PEM parser rejects PEM with missing '-----BEGIN PUBLIC KEY-----' header" in {
        val noPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        Abort.run[SqlRequestException](RsaOaep.parsePem(noPem)).map {
            case Result.Failure(e: SqlRequestException) =>
                assert(e.getMessage.contains("BEGIN PUBLIC KEY"))
            case other =>
                fail(s"Expected SqlRequestException for missing header, got: $other")
        }
    }

    // ─── PEM parser rejects bad base64 ───────────────────────────────────────────

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

    // ─── ASN.1 BER decoder extracts modulus + exponent ──────────────────────────

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

    // ─── ASN.1 BER decoder rejects malformed structure ──────────────────────────

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

    // ─── MGF1 with SHA-1 matches known-answer test vector ────────────────────────

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

    // ─── OAEP with deterministic seed pins to known ciphertext ───────────────────

    "RsaOaep OAEP with deterministic seed (java.util.Random(42)) produces pre-computed ciphertext" in {
        // Pre-computed with Java: java.util.Random(42).nextBytes(20) = 359d41...
        // Then BigInt.modPow applied with this key's n and e.
        // First 16 bytes of ciphertext verified: 651390aa73e80e41925aac7e098055c3
        val plaintext = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        RsaOaep.encrypt(testPubPem, plaintext, seeded(42)).map { ct =>
            assert(ct.size == 256)
            // Pin first 16 bytes to known answer.
            assert(ct.toArray.take(16).sameElements(expectedCiphertextFirst16))
        }
    }

    // ─── Same plaintext encrypted twice produces distinct ciphertext ──────────────

    "RsaOaep same plaintext encrypted twice with SecureRandom.live produces distinct ciphertext" in {
        val plaintext = Span.from("test".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        RsaOaep.encrypt(testPubPem, plaintext, SecureRandom.live).flatMap { ct1 =>
            RsaOaep.encrypt(testPubPem, plaintext, SecureRandom.live).map { ct2 =>
                // OAEP uses a random seed, same plaintext must yield distinct ciphertext.
                assert(!ct1.toArray.sameElements(ct2.toArray))
            }
        }
    }

    // ─── Plaintext exceeding capacity raises SqlRequestException ─────────────────

    "RsaOaep plaintext exceeding k−2·hLen−2 = 214 bytes raises SqlRequestException" in {
        // For 2048-bit key: k=256, hLen=20, maxLen=256-40-2=214.
        val tooLong = Span.from(Array.fill[Byte](215)(0x42.toByte))
        Abort.run[SqlRequestException](
            RsaOaep.encrypt(testPubPem, tooLong, seeded(1))
        ).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "EME-OAEP", s"expected position 'EME-OAEP', got: ${e.position}")
                assert(e.tag == "plaintext-length", s"expected tag 'plaintext-length', got: ${e.tag}")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for oversized plaintext, got: $other")
        }
    }

    // ─── Empty plaintext encrypts successfully ───────────────────────────────────

    "RsaOaep empty plaintext encrypts to a 256-byte ciphertext" in {
        val empty = Span.from(Array.empty[Byte])
        RsaOaep.encrypt(testPubPem, empty, seeded(7)).map { ct =>
            assert(ct.size == 256)
        }
    }

    // ─── The peer does not get to choose how long modPow runs ────────────────────

    "RsaOaep refuses a server-supplied modulus above the ceiling before any exponentiation runs" in {
        // The key arrives from the peer in an AuthMoreData packet on a connection that is neither encrypted nor
        // authenticated, bounded on the wire only by MySQL's packet limit. modPow's cost is quadratic in the modulus
        // size and runs straight through with no suspension point, so an unbounded modulus is carrier time the peer
        // picks and no caller-side timeout can reclaim.
        val bits = 65536
        Abort.run[SqlRequestException](RsaOaep.parsePem(syntheticKeyPem(bits, BigInt(65537)))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRequestRsaKeyTooLargeException.Component.Modulus, s"got ${e.component}")
                assert(e.bits == bits, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == RsaOaep.MaxModulusBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a $bits-bit modulus, got: $other")
        }
    }

    "RsaOaep refuses a server-supplied exponent above the ceiling, the other multiplier of modPow's cost" in {
        // Bounding the modulus alone leaves the work open: a 2048-bit modulus with a 4096-bit exponent is 4096
        // squarings of a 2048-bit integer.
        val hugeExponent = (BigInt(1) << 4095) | BigInt(1)
        Abort.run[SqlRequestException](RsaOaep.parsePem(syntheticKeyPem(2048, hugeExponent))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRequestRsaKeyTooLargeException.Component.Exponent, s"got ${e.component}")
                assert(e.bits == 4096, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == RsaOaep.MaxExponentBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a 4096-bit exponent, got: $other")
        }
    }

    "RsaOaep accepts a key at both ceilings, so the bounds do not reject a legitimate server" in {
        // MySQL's auto-generated keys are 2048-bit with exponent 65537, well inside both. This leaf pins the boundary
        // itself: a ceiling that rejected the value equal to it would be an off-by-one nobody would notice until a
        // server was configured at exactly that size.
        RsaOaep.parsePem(syntheticKeyPem(RsaOaep.MaxModulusBits, (BigInt(1) << (RsaOaep.MaxExponentBits - 1)) | BigInt(1))).map { key =>
            assert(key.modulus.bitLength == RsaOaep.MaxModulusBits)
            assert(key.exponent.bitLength == RsaOaep.MaxExponentBits)
        }
    }

    // ─── DER fixture builder ─────────────────────────────────────────────────────

    /** A syntactically valid SubjectPublicKeyInfo PEM with a modulus of exactly `modulusBits` bits and the given exponent.
      *
      * Built here rather than generated with a key tool because the sizes these leaves need are ones no key tool will produce.
      */
    private def syntheticKeyPem(modulusBits: Int, exponent: BigInt): String =
        val modulus   = (BigInt(1) << (modulusBits - 1)) | BigInt(1)
        val rsaKey    = tlv(0x30, derInteger(modulus) ++ derInteger(exponent))
        val bitString = tlv(0x03, Array(0x00.toByte) ++ rsaKey)
        val spki      = tlv(0x30, rsaEncryptionAlgorithmId ++ bitString)
        val body      = java.util.Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(spki)
        s"-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    end syntheticKeyPem

    /** DER AlgorithmIdentifier for `rsaEncryption` (OID 1.2.840.113549.1.1.1) with a NULL parameter. */
    private val rsaEncryptionAlgorithmId: Array[Byte] =
        Array[Byte](0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte, 0x48, 0x86.toByte, 0xf7.toByte, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)

    /** One DER tag-length-value, using the long length form when the value needs it. */
    private def tlv(tag: Int, value: Array[Byte]): Array[Byte] =
        val header =
            if value.length < 0x80 then Array(tag.toByte, value.length.toByte)
            else
                val lengthBytes = BigInt(value.length).toByteArray.dropWhile(_ == 0.toByte)
                Array(tag.toByte, (0x80 | lengthBytes.length).toByte) ++ lengthBytes
        header ++ value
    end tlv

    private def derInteger(value: BigInt): Array[Byte] =
        tlv(0x02, value.toByteArray)

end RsaOaepTest
