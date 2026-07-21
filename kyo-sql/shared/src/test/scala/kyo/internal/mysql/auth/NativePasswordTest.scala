package kyo.internal.mysql.auth

import kyo.*
import kyo.internal.mysql.auth.NativePassword

/** Unit tests for [[NativePassword.computeResponse]].
  *
  * Known vectors are computed offline with Python: import hashlib sha1 = lambda x: hashlib.sha1(x).digest() step1 = sha1(password); step2 =
  * sha1(step1); step3 = sha1(scramble + step2) result = bytes(a ^ b for a, b in zip(step1, step3))
  */
class NativePasswordTest extends kyo.Test:

    "NativePassword hash known vector — password='secret', scramble=20 zero bytes" in {
        // Expected: 8e7e678b27f70747e65fb2b381214427b0fced48
        // Computed: step1=sha1("secret"), step2=sha1(step1), step3=sha1(zeros20++step2), result=step1 XOR step3
        val scramble = Span.from(Array.fill[Byte](20)(0))
        val result   = NativePassword.computeResponse("secret", scramble)
        val expected = Array[Byte](
            0x8e.toByte,
            0x7e.toByte,
            0x67.toByte,
            0x8b.toByte,
            0x27.toByte,
            0xf7.toByte,
            0x07.toByte,
            0x47.toByte,
            0xe6.toByte,
            0x5f.toByte,
            0xb2.toByte,
            0xb3.toByte,
            0x81.toByte,
            0x21.toByte,
            0x44.toByte,
            0x27.toByte,
            0xb0.toByte,
            0xfc.toByte,
            0xed.toByte,
            0x48.toByte
        )
        assert(result.toArray.sameElements(expected))
    }

    "NativePassword hash known vector — password='root', scramble=bytes(0..19)" in {
        // Expected: 180d6d4732d2984043b7ce347748445924d1f493
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val result   = NativePassword.computeResponse("root", scramble)
        val expected = Array[Byte](
            0x18.toByte,
            0x0d.toByte,
            0x6d.toByte,
            0x47.toByte,
            0x32.toByte,
            0xd2.toByte,
            0x98.toByte,
            0x40.toByte,
            0x43.toByte,
            0xb7.toByte,
            0xce.toByte,
            0x34.toByte,
            0x77.toByte,
            0x48.toByte,
            0x44.toByte,
            0x59.toByte,
            0x24.toByte,
            0xd1.toByte,
            0xf4.toByte,
            0x93.toByte
        )
        assert(result.toArray.sameElements(expected))
    }

    "NativePassword hash length is always 20 bytes for non-empty password" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x42.toByte))
        val result   = NativePassword.computeResponse("anypassword", scramble)
        assert(result.size == 20)
    }

    "NativePassword empty password returns Span.empty (MySQL no-password sentinel)" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = NativePassword.computeResponse("", scramble)
        assert(result.size == 0)
    }

    "NativePassword different passwords with same scramble produce different responses" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x77.toByte))
        val result1  = NativePassword.computeResponse("passwordA", scramble)
        val result2  = NativePassword.computeResponse("passwordB", scramble)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

    "NativePassword same password with different scrambles produce different responses" in {
        val scramble1 = Span.from(Array.fill[Byte](20)(0x11.toByte))
        val scramble2 = Span.from(Array.fill[Byte](20)(0x22.toByte))
        val result1   = NativePassword.computeResponse("samepassword", scramble1)
        val result2   = NativePassword.computeResponse("samepassword", scramble2)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

end NativePasswordTest
