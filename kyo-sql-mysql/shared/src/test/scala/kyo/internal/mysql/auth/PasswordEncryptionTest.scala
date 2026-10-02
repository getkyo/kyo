package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlException
import kyo.crypto.*

/** Unit tests for [[PasswordEncryption]]: the seed drawn from the given `SecureRandom` reaching kyo-crypto's OAEP (the pinned ciphertext),
  * and every refusal of the shared key parser and the encoder surfacing as this module's leaf with its position and tag.
  *
  * kyo-crypto pins the encoding itself and decrypts it with the JDK.
  *
  * Test RSA key is a pre-generated 2048-bit RSA public key (SubjectPublicKeyInfo PEM). Tests involving full RSA encryption use the
  * [[seeded]] `SecureRandom` for determinism and compare against vectors pre-computed in Java using the same `java.util.Random(42)` seed:
  * {{{
  *   java.util.Random(42L).nextBytes(20) => 359d41baf78afe0de1bbe7ae28c0450ce43c084f
  *   RSA-OAEP-encrypt("hello", seed=above) => the 256 bytes of pinnedCiphertext, as the JDK's OAEP produces them from the same seed
  * }}}
  */
class PasswordEncryptionTest extends kyo.Test:

    /** A `SecureRandom` whose byte draws come from a `java.util.Random(seed)`, so the OAEP seed is deterministic and the ciphertext can be
      * pinned against vectors pre-computed in Java from the same seed. Production `PasswordEncryption.encrypt` takes the ambient secure
      * source; this seeded stand-in is a test-only substitution.
      */
    private def seeded(seed: Long): SecureRandom =
        SecureRandom(
            new SecureRandom.Unsafe:
                private val jr                                            = new java.util.Random(seed)
                def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                    val arr = new Array[Byte](length)
                    jr.nextBytes(arr)
                    // Unsafe: the array is fresh and held by nothing else.
                    Span.fromUnsafe(arr)
                end nextBytes
        )

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

    /** The ciphertext of `hello` under the key above and the seed `java.util.Random(42)` draws, as the JDK's OAEP produces it. */
    val pinnedCiphertext: String =
        "651390aa73e80e41925aac7e098055c30fcb6ded75b22f78a1f49e1c602a8540ffa2c6b5793b9f7737e6266cddbfd9d6691af1888678adc8effc84e9e8ecf157" +
            "14ca9386535ad59332b433ddabd9c53c4e600a563495d87c329b634c3dfe2617f6650c3c9bbfde9560a52b47250fae9810809452eaf41c5f8fe6d8da4c93e389" +
            "a6d43ff23d2b4ebcd346e903e467e047be1352fe79c6fb58bc0ad9c8968cf91c60046ede5f8022a68e25ceb541d785545cf485c44f5a3ae5b54834764de089cb" +
            "22d1b7e08cc4c233b12ed058ea1c556fb41f86481cf6bdf1daeae2526cca33605f46f858e0b9b170e4ed771317d2b9a9993a5e8aff5aaab0884d423d2210c0ac"

    private val hello: Span[Byte] = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))

    "OAEP-encode of a known message has correct length, 256 bytes for 2048-bit key" in {
        // k=256 bytes for 2048-bit modulus. encrypt() returns a Span of exactly k bytes.
        PasswordEncryption.encrypt(testPubPem, hello, seeded(42)).map { ct =>
            assert(ct.size == 256)
        }
    }

    "rejects a key with no '-----BEGIN PUBLIC KEY-----' header as position PEM, tag header-missing" in {
        val noPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(noPem, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM")
                assert(e.tag == "header-missing")
                assert(e.getCause.getMessage == "missing BEGIN PUBLIC KEY header")
                assert(e.getMessage.contains("BEGIN PUBLIC KEY"))
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for missing header, got: $other")
        }
    }

    "rejects a PEM body of a length base64 cannot have as position PEM, tag base64, the Base64.Failure's message as the cause" in {
        val badPem =
            "-----BEGIN PUBLIC KEY-----\n" +
                "!!!not-valid-base64!!!%%%\n" +
                "-----END PUBLIC KEY-----\n"
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(badPem, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM")
                assert(e.tag == "base64")
                assert(e.getCause.getMessage == Base64.Failure.BadLength(25).message)
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for bad base64, got: $other")
        }
    }

    "rejects a PEM body with a character outside the alphabet as position PEM, tag base64, the Base64.Failure's message as the cause" in {
        val badPem =
            "-----BEGIN PUBLIC KEY-----\n" +
                "!!!not-valid-base64!!!%%\n" +
                "-----END PUBLIC KEY-----\n"
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(badPem, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM")
                assert(e.tag == "base64")
                assert(e.getCause.getMessage == Base64.Failure.IllegalCharacter(0).message)
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for bad base64, got: $other")
        }
    }

    "rejects a truncated DER structure as position DER, tag length" in {
        // A lone SEQUENCE tag with no length byte after it.
        val truncated = pemOf(Array[Byte](0x30.toByte))
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(truncated, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "DER")
                assert(e.tag == "length")
                assert(e.getCause.getMessage == "unexpected end of data")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for malformed DER, got: $other")
        }
    }

    "rejects a DER structure whose tag is not the expected one as position DER, tag tag-0x30, naming what was found" in {
        val wrongTag = pemOf(Array[Byte](0x31.toByte, 0x00.toByte))
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(wrongTag, hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "DER")
                assert(e.tag == "tag-0x30")
                assert(e.getCause.getMessage == "found 0x31 at offset 0")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for a wrong tag, got: $other")
        }
    }

    "the seed java.util.Random(42) draws produces the pinned 256-byte ciphertext" in {
        PasswordEncryption.encrypt(testPubPem, hello, seeded(42)).map { ct =>
            assert(ct.size == 256)
            assert(Hex.encode(ct) == pinnedCiphertext)
        }
    }

    "same plaintext encrypted twice with SecureRandom.live produces distinct ciphertext" in {
        val plaintext = Span.from("test".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        PasswordEncryption.encrypt(testPubPem, plaintext, SecureRandom.live).flatMap { ct1 =>
            PasswordEncryption.encrypt(testPubPem, plaintext, SecureRandom.live).map { ct2 =>
                // OAEP uses a random seed, same plaintext must yield distinct ciphertext.
                assert(!ct1.toArray.sameElements(ct2.toArray))
            }
        }
    }

    "plaintext exceeding k−2·hLen−2 = 214 bytes raises SqlRequestException" in {
        // For 2048-bit key: k=256, hLen=20, maxLen=256-40-2=214.
        val tooLong = Span.from(Array.fill[Byte](215)(0x42.toByte))
        Abort.run[SqlRequestException](
            PasswordEncryption.encrypt(testPubPem, tooLong, seeded(1))
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

    "empty plaintext encrypts to a 256-byte ciphertext" in {
        val empty = Span.from(Array.empty[Byte])
        PasswordEncryption.encrypt(testPubPem, empty, seeded(7)).map { ct =>
            assert(ct.size == 256)
        }
    }

    "refuses a server-supplied modulus above the ceiling before any exponentiation runs" in {
        // The key arrives from the peer in an AuthMoreData packet on a connection that is neither encrypted nor
        // authenticated, bounded on the wire only by MySQL's packet limit. modPow's cost is quadratic in the modulus
        // size and runs straight through with no suspension point, so an unbounded modulus is carrier time the peer
        // picks and no caller-side timeout can reclaim.
        val bits = 65536
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(syntheticKeyPem(bits, BigInt(65537)), hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRsaKeyComponent.Modulus, s"got ${e.component}")
                assert(e.bits == bits, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == Rsa.MaxModulusBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a $bits-bit modulus, got: $other")
        }
    }

    "refuses a server-supplied exponent above the ceiling, the other multiplier of modPow's cost" in {
        // Bounding the modulus alone leaves the work open: a 2048-bit modulus with a 4096-bit exponent is 4096
        // squarings of a 2048-bit integer.
        val hugeExponent = (BigInt(1) << 4095) | BigInt(1)
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(syntheticKeyPem(2048, hugeExponent), hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaKeyTooLargeException) =>
                assert(e.component == SqlRsaKeyComponent.Exponent, s"got ${e.component}")
                assert(e.bits == 4096, s"the refusal must name the size offered, got ${e.bits}")
                assert(e.limit == Rsa.MaxExponentBits, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"Expected SqlRequestRsaKeyTooLargeException for a 4096-bit exponent, got: $other")
        }
    }

    "accepts a key at both ceilings, so the bounds do not reject a legitimate server" in {
        // MySQL's auto-generated keys are 2048-bit with exponent 65537, well inside both. This leaf pins the boundary
        // itself: a ceiling that rejected the value equal to it would be an off-by-one nobody would notice until a
        // server was configured at exactly that size.
        val pem = syntheticKeyPem(Rsa.MaxModulusBits, (BigInt(1) << (Rsa.MaxExponentBits - 1)) | BigInt(1))
        PasswordEncryption.encrypt(pem, hello, seeded(3)).map { ct =>
            assert(ct.size == Rsa.MaxModulusBits / 8)
        }
    }

    "refuses a modulus below 1024 bits, naming the size and the floor" in {
        val cases = Seq((BigInt(0), 0), (BigInt(1), 1), ((BigInt(1) << 1022) | BigInt(1), 1023))
        Kyo.foreach(cases) { (modulus, bits) =>
            Abort.run[SqlRequestException](PasswordEncryption.encrypt(keyPemOf(modulus, BigInt(65537)), hello, seeded(1))).map {
                case Result.Failure(e: SqlRequestRsaKeyTooSmallException) =>
                    assert(e.component == SqlRsaKeyComponent.Modulus, s"$bits bits: got ${e.component}")
                    assert(e.measured == BigInt(bits), s"$bits bits: got ${e.measured}")
                    assert(e.minimum == BigInt(1024), s"$bits bits: got minimum ${e.minimum}")
                    assert(e.getMessage.contains(s"RSA modulus is $bits bits, below the driver's floor of 1024"))
                case other =>
                    fail(s"$bits bits: expected SqlRequestRsaKeyTooSmallException, got $other")
            }
        }.map(_ => succeed)
    }

    "refuses an even modulus" in {
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(
            keyPemOf((BigInt(1) << 2047) | BigInt(2), BigInt(65537)),
            hello,
            seeded(1)
        )).map {
            case Result.Failure(e: SqlRequestRsaKeyEvenException) =>
                assert(e.component == SqlRsaKeyComponent.Modulus)
                assert(e.getMessage.contains("RSA modulus is even, which no RSA key has"))
            case other => fail(s"expected SqlRequestRsaKeyEvenException, got $other")
        }
    }

    "refuses an exponent of 0, 1 or 2, which would send the password recoverable to anyone on the wire" in {
        Kyo.foreach(Seq(0, 1, 2)) { e =>
            Abort.run[SqlRequestException](PasswordEncryption.encrypt(syntheticKeyPem(2048, BigInt(e)), hello, seeded(1))).map {
                case Result.Failure(f: SqlRequestRsaKeyTooSmallException) =>
                    assert(f.component == SqlRsaKeyComponent.Exponent, s"e = $e: got ${f.component}")
                    assert(f.measured == BigInt(e) && f.minimum == BigInt(3), s"e = $e: got ${f.measured} and ${f.minimum}")
                    assert(f.getMessage.contains(s"RSA public exponent is $e, below the minimum of 3"))
                case other =>
                    fail(s"e = $e: expected SqlRequestRsaKeyTooSmallException, got $other")
            }
        }.map(_ => succeed)
    }

    "refuses an even exponent" in {
        Abort.run[SqlRequestException](PasswordEncryption.encrypt(syntheticKeyPem(2048, BigInt(65536)), hello, seeded(1))).map {
            case Result.Failure(e: SqlRequestRsaKeyEvenException) =>
                assert(e.component == SqlRsaKeyComponent.Exponent)
                assert(e.getMessage.contains("RSA public exponent is even, which no RSA key has"))
            case other => fail(s"expected SqlRequestRsaKeyEvenException, got $other")
        }
    }

    "accepts a 1024-bit key with e = 3, the floor, so a server configured at the minimum still authenticates" in {
        PasswordEncryption.encrypt(syntheticKeyPem(1024, BigInt(3)), hello, seeded(5)).map(ct => assert(ct.size == 128))
    }

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

end PasswordEncryptionTest
