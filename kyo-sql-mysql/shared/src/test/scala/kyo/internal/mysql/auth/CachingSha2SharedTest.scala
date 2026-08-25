package kyo.internal.mysql.auth

import kyo.*
import kyo.internal.mysql.auth.CachingSha2Shared

/** Cross-platform tests for [[CachingSha2Shared]].
  *
  * Fast-path known vectors computed with Python:
  * {{{
  *   import hashlib
  *   password = b"test"
  *   scramble = bytes([0x01]*20)
  *   hash1 = hashlib.sha256(password).digest()
  *   hash2 = hashlib.sha256(hash1).digest()
  *   xor_with = hashlib.sha256(hash2 + scramble).digest()
  *   result = bytes(a ^ b for a, b in zip(hash1, xor_with))
  *   # result.hex() == "3d7e44ec568e4fbc170d1dd18b750ab06a2091b0a4cdf31e3725c01bfd151a2a"
  * }}}
  *
  * The full-auth path splits into two independently pinned halves: [[CachingSha2Shared.scrambledPlaintext]] is verified byte-exactly here
  * against Python-computed XOR vectors, and the RSA-OAEP encryption it feeds is pinned to a known ciphertext by `RsaOaepTest`. The composed
  * path also runs end-to-end against a real MySQL server in `CachingSha2FullAuthIntegrationTest`.
  */
class CachingSha2SharedTest extends kyo.Test:

    /** Pre-generated RSA 2048-bit public key in SubjectPublicKeyInfo PEM format, shared with `RsaOaepTest`. */
    private val testPubPem: String =
        """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0fjhZ5a4z9ULtk0Xdeq1
O79oB9+t9VWGEicXHNrkIsqPswer2tOwDE4hlu/GkDh8w2kO9K/x+q+mSPg5SzlT
dDHBTlLTQnHm8Wc74CPBJHExcwJzuq7Xy1c1tmD1m69EO1QFvJcso/10RK3pnJ8g
IpWqwVJ8QOsXSRnwvTJYAUX0A/HLISgxI4YFXQUKevNxdQlLd82Wne6qZIjZwiXc
JvvIoQ/d4dsFhMs0FSSw9fgXcG3x89kCSj2TyUl0KlyL5AWr1gRqS4Psjo62GTTc
sufsIMrHVlDaMkvdPnPFtyARqWknXA1Lj6DfjcBSwaQY9F0g7T4UxV9SobYFeftU
FwIDAQAB
-----END PUBLIC KEY-----"""

    // ─── Fast-path response ──────────────────────────────────────────────────────

    "CachingSha2Shared fastPathHash known vector, password='test', 20-byte scramble all 0x01" in {
        // Python-verified: see class-level scaladoc for computation steps.
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = CachingSha2Shared.computeFastResponse("test", scramble)
        val expected = Array[Byte](
            0x3d.toByte,
            0x7e.toByte,
            0x44.toByte,
            0xec.toByte,
            0x56.toByte,
            0x8e.toByte,
            0x4f.toByte,
            0xbc.toByte,
            0x17.toByte,
            0x0d.toByte,
            0x1d.toByte,
            0xd1.toByte,
            0x8b.toByte,
            0x75.toByte,
            0x0a.toByte,
            0xb0.toByte,
            0x6a.toByte,
            0x20.toByte,
            0x91.toByte,
            0xb0.toByte,
            0xa4.toByte,
            0xcd.toByte,
            0xf3.toByte,
            0x1e.toByte,
            0x37.toByte,
            0x25.toByte,
            0xc0.toByte,
            0x1b.toByte,
            0xfd.toByte,
            0x15.toByte,
            0x1a.toByte,
            0x2a.toByte
        )
        assert(result.toArray.sameElements(expected))
    }

    "CachingSha2Shared fastPathHash length is always 32 bytes for non-empty password" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x42.toByte))
        val result   = CachingSha2Shared.computeFastResponse("anypassword", scramble)
        assert(result.size == 32)
    }

    "CachingSha2Shared empty password fast-path returns Span.empty (MySQL no-password sentinel)" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = CachingSha2Shared.computeFastResponse("", scramble)
        assert(result.size == 0)
    }

    "CachingSha2Shared different passwords with same scramble produce different fast-path responses" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x77.toByte))
        val result1  = CachingSha2Shared.computeFastResponse("passwordA", scramble)
        val result2  = CachingSha2Shared.computeFastResponse("passwordB", scramble)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

    "CachingSha2Shared same password with different scrambles produces different fast-path responses" in {
        val scramble1 = Span.from(Array.fill[Byte](20)(0x11.toByte))
        val scramble2 = Span.from(Array.fill[Byte](20)(0x22.toByte))
        val result1   = CachingSha2Shared.computeFastResponse("samepassword", scramble1)
        val result2   = CachingSha2Shared.computeFastResponse("samepassword", scramble2)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

    // ─── Full-auth RSA plaintext ─────────────────────────────────────────────────

    "CachingSha2Shared scrambledPlaintext known vector, password='hunter2', scramble=bytes(0..19)" in {
        // Python: pt = b"hunter2" + b"\x00"; bytes(pt[i] ^ scramble[i % 20] for i in range(8)) == 68746c7761773407
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val result   = CachingSha2Shared.scrambledPlaintext("hunter2", scramble)
        val expected = Array[Byte](
            0x68.toByte,
            0x74.toByte,
            0x6c.toByte,
            0x77.toByte,
            0x61.toByte,
            0x77.toByte,
            0x34.toByte,
            0x07.toByte
        )
        assert(result.toArray.sameElements(expected))
    }

    "CachingSha2Shared scrambledPlaintext cycles the scramble when the password is longer" in {
        // 4-byte scramble over an 11-byte NUL-terminated plaintext, so the scramble repeats twice and a bit.
        // Python: pt = b"abcdefghij" + b"\x00"; scramble = 01 02 03 04 => 606060606464646c686803
        val scramble = Span.from(Array[Byte](0x01, 0x02, 0x03, 0x04))
        val result   = CachingSha2Shared.scrambledPlaintext("abcdefghij", scramble)
        val expected = Array[Byte](
            0x60.toByte,
            0x60.toByte,
            0x60.toByte,
            0x60.toByte,
            0x64.toByte,
            0x64.toByte,
            0x64.toByte,
            0x6c.toByte,
            0x68.toByte,
            0x68.toByte,
            0x03.toByte
        )
        assert(result.toArray.sameElements(expected))
    }

    "CachingSha2Shared scrambledPlaintext of an empty password is the NUL byte XOR the first scramble byte" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x5a.toByte))
        val result   = CachingSha2Shared.scrambledPlaintext("", scramble)
        assert(result.toArray.sameElements(Array[Byte](0x5a.toByte)))
    }

    "CachingSha2Shared scrambledPlaintext leaves the NUL-terminated password unchanged for an empty scramble" in {
        val result = CachingSha2Shared.scrambledPlaintext("pw", Span.empty[Byte])
        assert(result.toArray.sameElements(Array[Byte]('p'.toByte, 'w'.toByte, 0x00.toByte)))
    }

    // ─── Full-auth ciphertext ────────────────────────────────────────────────────

    "CachingSha2Shared computeFullAuthResponse encrypts to one RSA block for a 2048-bit server key" in {
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val pem      = Span.from(testPubPem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        CachingSha2Shared.computeFullAuthResponse("hunter2", scramble, pem).map { ciphertext =>
            assert(ciphertext.size == 256)
        }
    }

    "CachingSha2Shared computeFullAuthResponse re-randomizes the OAEP seed per call" in {
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val pem      = Span.from(testPubPem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        CachingSha2Shared.computeFullAuthResponse("hunter2", scramble, pem).flatMap { first =>
            CachingSha2Shared.computeFullAuthResponse("hunter2", scramble, pem).map { second =>
                assert(!first.toArray.sameElements(second.toArray))
            }
        }
    }

    "CachingSha2Shared computeFullAuthResponse rejects a server key that is not a PEM public key" in {
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val notPem   = Span.from("no header here".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        Abort.run[SqlRequestException](CachingSha2Shared.computeFullAuthResponse("hunter2", scramble, notPem)).map {
            case Result.Failure(e: SqlRequestRsaOaepException) =>
                assert(e.position == "PEM", s"expected position 'PEM', got: ${e.position}")
                assert(e.tag == "header-missing", s"expected tag 'header-missing', got: ${e.tag}")
            case other =>
                fail(s"Expected SqlRequestRsaOaepException for a non-PEM server key, got: $other")
        }
    }

end CachingSha2SharedTest
