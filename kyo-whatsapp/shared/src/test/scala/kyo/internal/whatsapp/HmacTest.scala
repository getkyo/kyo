package kyo.internal.whatsapp

import kyo.BaseWhatsAppTest

class HmacTest extends BaseWhatsAppTest:

    "SHA-256 of the empty string (NIST)" in {
        val result = Hmac.hexLower(Hmac.sha256(Array.emptyByteArray))
        assert(result == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    "SHA-256 of abc (NIST)" in {
        val result = Hmac.hexLower(Hmac.sha256("abc".getBytes("UTF-8")))
        assert(result == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    "SHA-256 of the 448-bit NIST multi-block string" in {
        val input  = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes("UTF-8")
        val result = Hmac.hexLower(Hmac.sha256(input))
        assert(result == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    "SHA-256 of a 1000000-byte a string (NIST long vector)" in {
        val input  = Array.fill(1000000)('a'.toByte)
        val result = Hmac.hexLower(Hmac.sha256(input))
        assert(result == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }

    "HMAC-SHA256 RFC-4231 test case 1" in {
        val key    = Array.fill(20)(0x0b.toByte)
        val data   = "Hi There".getBytes("UTF-8")
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
    }

    "HMAC-SHA256 RFC-4231 test case 2 (short key)" in {
        val key    = "Jefe".getBytes("UTF-8")
        val data   = "what do ya want for nothing?".getBytes("UTF-8")
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    }

    "HMAC-SHA256 RFC-4231 test case 3 (key=20x0xaa, data=50x0xdd)" in {
        val key    = Array.fill(20)(0xaa.toByte)
        val data   = Array.fill(50)(0xdd.toByte)
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe")
    }

    "HMAC-SHA256 RFC-4231 test case 4 (25-byte key, 50x0xcd data)" in {
        val key    = (1 to 25).map(_.toByte).toArray
        val data   = Array.fill(50)(0xcd.toByte)
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b")
    }

    "HMAC-SHA256 RFC-4231 test case 6 (key longer than block, 131x0xaa)" in {
        val key    = Array.fill(131)(0xaa.toByte)
        val data   = "Test Using Larger Than Block-Size Key - Hash Key First".getBytes("UTF-8")
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54")
    }

    "HMAC-SHA256 RFC-4231 test case 7 (131x0xaa key, long data)" in {
        val key = Array.fill(131)(0xaa.toByte)
        val data =
            "This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.".getBytes(
                "UTF-8"
            )
        val result = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(result == "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2")
    }

    "hexLower renders fixed bytes as zero-padded lowercase" in {
        val bytes  = Array[Byte](0x00, 0x0f, 0xab.toByte, 0xff.toByte)
        val result = Hmac.hexLower(bytes)
        assert(result == "000fabff")
    }

    "hexLower of an empty array is the empty string" in {
        val result = Hmac.hexLower(Array.emptyByteArray)
        assert(result == "")
    }

    "constantTimeEquals true for identical arrays" in {
        val a = Array[Byte](1, 2, 3, 4)
        val b = Array[Byte](1, 2, 3, 4)
        assert(Hmac.constantTimeEquals(a, b))
    }

    "constantTimeEquals false when the last byte differs" in {
        val a = Array[Byte](1, 2, 3, 4)
        val b = Array[Byte](1, 2, 3, 5)
        assert(!Hmac.constantTimeEquals(a, b))
    }

    "constantTimeEquals false when the first byte differs" in {
        val a = Array[Byte](9, 2, 3, 4)
        val b = Array[Byte](1, 2, 3, 4)
        assert(!Hmac.constantTimeEquals(a, b))
    }

    "constantTimeEquals false for different lengths" in {
        val a = Array[Byte](1, 2, 3)
        val b = Array[Byte](1, 2, 3, 4)
        assert(!Hmac.constantTimeEquals(a, b))
    }

    "constantTimeEquals true for two empty arrays" in {
        val a = Array.emptyByteArray
        val b = Array.emptyByteArray
        assert(Hmac.constantTimeEquals(a, b))
    }

    "hmacSha256 output is deterministic across repeated calls" in {
        val key    = "secret".getBytes("UTF-8")
        val data   = "payload".getBytes("UTF-8")
        val first  = Hmac.hexLower(Hmac.hmacSha256(key, data))
        val second = Hmac.hexLower(Hmac.hmacSha256(key, data))
        assert(first == second)
    }

    "a one-byte change to the data changes the HMAC digest" in {
        val k       = "k".getBytes("UTF-8")
        val result1 = Hmac.hexLower(Hmac.hmacSha256(k, "abc".getBytes("UTF-8")))
        val result2 = Hmac.hexLower(Hmac.hmacSha256(k, "abd".getBytes("UTF-8")))
        assert(result1 != result2)
    }

    "a one-byte change to the key changes the HMAC digest" in {
        val data    = "abc".getBytes("UTF-8")
        val result1 = Hmac.hexLower(Hmac.hmacSha256("k1".getBytes("UTF-8"), data))
        val result2 = Hmac.hexLower(Hmac.hmacSha256("k2".getBytes("UTF-8"), data))
        assert(result1 != result2)
    }

    "sha256 output is exactly 32 bytes" in {
        val result = Hmac.sha256("anything".getBytes("UTF-8"))
        assert(result.length == 32)
    }

    "hmacSha256 output is exactly 32 bytes" in {
        val result = Hmac.hmacSha256("k".getBytes("UTF-8"), "d".getBytes("UTF-8"))
        assert(result.length == 32)
    }

    "sha256 of a 64-byte one full block input (boundary)" in {
        val input  = Array.fill(64)('x'.toByte)
        val result = Hmac.hexLower(Hmac.sha256(input))
        assert(result == "7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c")
    }

    "sha256 of a 55-byte input (padding fits in one block)" in {
        val input  = Array.fill(55)('y'.toByte)
        val result = Hmac.hexLower(Hmac.sha256(input))
        assert(result == "fb66d40c3bfff05b0d5af8612d0abfbfacc6f5f26c330bc7ad634f1f44bc20ad")
    }

    "sha256 of a 56-byte input (padding overflows to a second block)" in {
        val input  = Array.fill(56)('z'.toByte)
        val result = Hmac.hexLower(Hmac.sha256(input))
        assert(result == "c66a5b692b9a20229733ef8b87cfec52679c86a0c0245643484c46d4dcd82afa")
    }

    "the empty-key HMAC is computed without error" in {
        val empty  = Array.emptyByteArray
        val data   = "x".getBytes("UTF-8")
        val result = Hmac.hexLower(Hmac.hmacSha256(empty, data))
        assert(result == "4cbc96099a6467ce002461f10549b4898265ebe6188b45efacc44293516e62c4")
    }

end HmacTest
