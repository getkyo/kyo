package kyo.internal.mysql.auth

import kyo.*
import kyo.internal.mysql.auth.NativePasswordShared

/** Unit tests for [[NativePasswordShared.computeResponse]].
  *
  * Known vectors are computed offline with Python: import hashlib sha1 = lambda x: hashlib.sha1(x).digest() step1 = sha1(password); step2 =
  * sha1(step1); step3 = sha1(scramble + step2) result = bytes(a ^ b for a, b in zip(step1, step3))
  */
class NativePasswordSharedTest extends kyo.Test:

    "NativePasswordShared hash known vector, password='secret', scramble=20 zero bytes" in {
        // Expected: 8e7e678b27f70747e65fb2b381214427b0fced48
        // Computed: step1=sha1("secret"), step2=sha1(step1), step3=sha1(zeros20++step2), result=step1 XOR step3
        val scramble = Span.from(Array.fill[Byte](20)(0))
        val result   = NativePasswordShared.computeResponse("secret", scramble)
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

    "NativePasswordShared hash known vector, password='root', scramble=bytes(0..19)" in {
        // Expected: 180d6d4732d2984043b7ce347748445924d1f493
        val scramble = Span.from(Array.tabulate[Byte](20)(i => i.toByte))
        val result   = NativePasswordShared.computeResponse("root", scramble)
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

    "NativePasswordShared hash length is always 20 bytes for non-empty password" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x42.toByte))
        val result   = NativePasswordShared.computeResponse("anypassword", scramble)
        assert(result.size == 20)
    }

    "NativePasswordShared empty password returns Span.empty (MySQL no-password sentinel)" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x01.toByte))
        val result   = NativePasswordShared.computeResponse("", scramble)
        assert(result.size == 0)
    }

    "NativePasswordShared different passwords with same scramble produce different responses" in {
        val scramble = Span.from(Array.fill[Byte](20)(0x77.toByte))
        val result1  = NativePasswordShared.computeResponse("passwordA", scramble)
        val result2  = NativePasswordShared.computeResponse("passwordB", scramble)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

    "NativePasswordShared same password with different scrambles produce different responses" in {
        val scramble1 = Span.from(Array.fill[Byte](20)(0x11.toByte))
        val scramble2 = Span.from(Array.fill[Byte](20)(0x22.toByte))
        val result1   = NativePasswordShared.computeResponse("samepassword", scramble1)
        val result2   = NativePasswordShared.computeResponse("samepassword", scramble2)
        assert(!result1.toArray.sameElements(result2.toArray))
    }

end NativePasswordSharedTest
