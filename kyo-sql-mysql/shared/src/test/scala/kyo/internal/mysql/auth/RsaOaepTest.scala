package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlException
import kyo.internal.crypto.Rsa

/** Unit tests for [[RsaOaep]], the OAEP encoding over kyo-data's RSA.
  *
  * Coverage:
  *   - OAEP-encode output length
  *   - Deterministic OAEP encryption pinned to known ciphertext
  *   - Non-deterministic OAEP (same input, distinct ciphertext on re-run)
  *   - Plaintext-too-long raises SqlRequestException
  *   - Empty plaintext encrypts successfully
  *   - Every way the shared key parser can refuse a server's key surfaces as this module's leaf, with the fields it carried before the
  *     parser moved: a missing PEM header, a body that is not base64, a truncated DER structure, and a modulus or exponent above its
  *     ceiling, so the peer cannot choose how long modPow runs
  *   - Degenerate keys under the ceilings (a modulus of 0, 1 or an even number; an exponent of 0, 1 or 2) end in a typed failure or a
  *     ciphertext, never a panic
  *
  * The JVM-only [[RsaOaepJvmTest]] decrypts this module's ciphertext with the JDK's OAEP and checks the plaintext.
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
                private val jr                                            = new java.util.Random(seed)
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

    private val hello: Span[Byte] = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))

    // ─── OAEP-encode length ─────────────────────────────────────────────────────

    "RsaOaep OAEP-encode of a known message has correct length, 256 bytes for 2048-bit key" in {
        // k=256 bytes for 2048-bit modulus. encrypt() returns a Span of exactly k bytes.
        RsaOaep.encrypt(testPubPem, hello, seeded(42)).map { ct =>
            assert(ct.size == 256)
        }
    }

    // ─── The shared parser's refusals, as this module's leaves ──────────────────

    "RsaOaep rejects a key with no '-----BEGIN PUBLIC KEY-----' header as position PEM, tag header-missing" in {
        val noPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        Abort.run[SqlRequestException](RsaOaep.encrypt(noPem, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM")
                assert(e.tag == "header-missing")
                assert(e.getCause.getMessage == "missing BEGIN PUBLIC KEY header")
                assert(e.getMessage.contains("BEGIN PUBLIC KEY"))
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for missing header, got: $other")
        }
    }

    "RsaOaep rejects a PEM body that is not base64 as position PEM, tag base64, the decoder's exception as the cause" in {
        val badPem =
            "-----BEGIN PUBLIC KEY-----\n" +
                "!!!not-valid-base64!!!%%%\n" +
                "-----END PUBLIC KEY-----\n"
        Abort.run[SqlRequestException](RsaOaep.encrypt(badPem, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM")
                assert(e.tag == "base64")
                assert(e.getCause.isInstanceOf[IllegalArgumentException])
                assert(e.getMessage.contains("base64") || e.getMessage.toLowerCase.contains("illegal"))
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for bad base64, got: $other")
        }
    }

    "RsaOaep rejects a truncated DER structure as position DER, tag length" in {
        // A lone SEQUENCE tag with no length byte after it.
        val truncated = pemOf(Array[Byte](0x30.toByte))
        Abort.run[SqlRequestException](RsaOaep.encrypt(truncated, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "DER")
                assert(e.tag == "length")
                assert(e.getCause.getMessage == "unexpected end of data")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for malformed DER, got: $other")
        }
    }

    "RsaOaep rejects a DER structure whose tag is not the expected one as position DER, tag tag-0x30, naming what was found" in {
        val wrongTag = pemOf(Array[Byte](0x31.toByte, 0x00.toByte))
        Abort.run[SqlRequestException](RsaOaep.encrypt(wrongTag, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "DER")
                assert(e.tag == "tag-0x30")
                assert(e.getCause.getMessage == "found 0x31 at offset 0")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for a wrong tag, got: $other")
        }
    }

    // ─── OAEP with deterministic seed pins to known ciphertext ───────────────────

    "RsaOaep OAEP with deterministic seed (java.util.Random(42)) produces pre-computed ciphertext" in {
        // Pre-computed with Java: java.util.Random(42).nextBytes(20) = 359d41...
        // Then BigInt.modPow applied with this key's n and e.
        // First 16 bytes of ciphertext verified: 651390aa73e80e41925aac7e098055c3
        RsaOaep.encrypt(testPubPem, hello, seeded(42)).map { ct =>
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
                // The plaintext is the password, so its length stays out of the message.
                assert(e.getCause.getMessage == "plaintext longer than 214 bytes, the most a 2048-bit key takes")
                assert(!e.getMessage.contains("215"))
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
        Abort.run[SqlRequestException](RsaOaep.encrypt(syntheticKeyPem(bits, BigInt(65537)), hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRequestRsaKeyTooLargeException.Component.Modulus, s"got ${e.component}")
                assert(e.bits == bits, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == Rsa.MaxModulusBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a $bits-bit modulus, got: $other")
        }
    }

    "RsaOaep refuses a server-supplied exponent above the ceiling, the other multiplier of modPow's cost" in {
        // Bounding the modulus alone leaves the work open: a 2048-bit modulus with a 4096-bit exponent is 4096
        // squarings of a 2048-bit integer.
        val hugeExponent = (BigInt(1) << 4095) | BigInt(1)
        Abort.run[SqlRequestException](RsaOaep.encrypt(syntheticKeyPem(2048, hugeExponent), hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRequestRsaKeyTooLargeException.Component.Exponent, s"got ${e.component}")
                assert(e.bits == 4096, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == Rsa.MaxExponentBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a 4096-bit exponent, got: $other")
        }
    }

    "RsaOaep accepts a key at both ceilings, so the bounds do not reject a legitimate server" in {
        // MySQL's auto-generated keys are 2048-bit with exponent 65537, well inside both. This leaf pins the boundary
        // itself: a ceiling that rejected the value equal to it would be an off-by-one nobody would notice until a
        // server was configured at exactly that size.
        val pem = syntheticKeyPem(Rsa.MaxModulusBits, (BigInt(1) << (Rsa.MaxExponentBits - 1)) | BigInt(1))
        RsaOaep.encrypt(pem, hello, seeded(3)).map { ct =>
            assert(ct.size == Rsa.MaxModulusBits / 8)
        }
    }

    // ─── Degenerate keys: a typed failure or a ciphertext, never a panic ─────────

    "RsaOaep with a modulus of 0 or 1 fails as plaintext-length, since such a key takes no plaintext at all" in {
        Kyo.foreach(Seq(BigInt(0), BigInt(1))) { modulus =>
            Abort.run[SqlRequestException](RsaOaep.encrypt(keyPemOf(modulus, BigInt(65537)), hello, seeded(1))).map {
                case Result.Failure(e: SqlRequestRsaOaepException) =>
                    assert(e.position == "EME-OAEP", s"modulus $modulus: got position ${e.position}")
                    assert(e.tag == "plaintext-length", s"modulus $modulus: got tag ${e.tag}")
                case other =>
                    fail(s"modulus $modulus: expected the plaintext-length leaf, got $other")
            }
        }.map(_ => succeed)
    }

    "RsaOaep with an even modulus, or an exponent of 0, 1 or 2, produces a ciphertext of the key's length" in {
        // The server key is accepted under the two ceilings alone (F3 of the security review, the user's Q11), so these
        // keys reach the arithmetic; what this leaf pins is that the arithmetic completes and returns k bytes.
        val even  = (BigInt(1) << 2047) | BigInt(2)
        val odd   = (BigInt(1) << 2047) | BigInt(1)
        val cases = Seq((even, BigInt(65537)), (odd, BigInt(0)), (odd, BigInt(1)), (odd, BigInt(2)))
        Kyo.foreach(cases) { (modulus, exponent) =>
            Abort.run[SqlRequestException](RsaOaep.encrypt(keyPemOf(modulus, exponent), hello, seeded(1))).map {
                case Result.Success(ct) => assert(ct.size == 256, s"exponent $exponent: got ${ct.size} bytes")
                case other => fail(s"exponent $exponent over a ${modulus.bitLength}-bit modulus: expected a ciphertext, got $other")
            }
        }.map(_ => succeed)
    }

    // ─── DER fixture builder ─────────────────────────────────────────────────────

    /** A syntactically valid SubjectPublicKeyInfo PEM with a modulus of exactly `modulusBits` bits and the given exponent.
      *
      * Built here rather than generated with a key tool because the sizes these leaves need are ones no key tool will produce.
      */
    private def syntheticKeyPem(modulusBits: Int, exponent: BigInt): String =
        keyPemOf((BigInt(1) << (modulusBits - 1)) | BigInt(1), exponent)

    private def keyPemOf(modulus: BigInt, exponent: BigInt): String =
        val rsaKey    = tlv(0x30, derInteger(modulus) ++ derInteger(exponent))
        val bitString = tlv(0x03, Array(0x00.toByte) ++ rsaKey)
        pemOf(tlv(0x30, rsaEncryptionAlgorithmId ++ bitString))
    end keyPemOf

    private def pemOf(der: Array[Byte]): String =
        val body = java.util.Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(der)
        s"-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"

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
