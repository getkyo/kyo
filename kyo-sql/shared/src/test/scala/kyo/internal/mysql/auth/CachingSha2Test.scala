package kyo.internal.mysql.auth

import kyo.*
import kyo.internal.mysql.auth.CachingSha2

/** Cross-platform tests for [[CachingSha2]] fast-path SHA-256 response.
  *
  * Known vectors computed with Python:
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
  * The RSA full-auth path (PEM decode + RSA-OAEP encrypt) is exercised end-to-end against a real MySQL server by
  * `CachingSha2FullAuthIntegrationTest`, which runs on JVM, Native, and JS.
  */
class CachingSha2Test extends kyo.Test:

    // ─── Fast-path response ──────────────────────────────────────────────────────

    "CachingSha2 fastPathHash known vector, password='test', 20-byte scramble all 0x01" in {
        // Python-verified: see class-level scaladoc for computation steps.
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = CachingSha2.computeFastResponse("test", scramble)
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

    "CachingSha2 fastPathHash length is always 32 bytes for non-empty password" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x42.toByte))
        val result   = CachingSha2.computeFastResponse("anypassword", scramble)
        assert(result.size == 32)
    }

    "CachingSha2 empty password fast-path returns Span.empty (MySQL no-password sentinel)" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = CachingSha2.computeFastResponse("", scramble)
        assert(result.size == 0)
    }

    "CachingSha2 different passwords with same scramble produce different fast-path responses" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x77.toByte))
        val result1  = CachingSha2.computeFastResponse("passwordA", scramble)
        val result2  = CachingSha2.computeFastResponse("passwordB", scramble)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

    "CachingSha2 same password with different scrambles produces different fast-path responses" in {
        val scramble1 = Span.from(Array.fill[Byte](20)(0x11.toByte))
        val scramble2 = Span.from(Array.fill[Byte](20)(0x22.toByte))
        val result1   = CachingSha2.computeFastResponse("samepassword", scramble1)
        val result2   = CachingSha2.computeFastResponse("samepassword", scramble2)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

end CachingSha2Test
