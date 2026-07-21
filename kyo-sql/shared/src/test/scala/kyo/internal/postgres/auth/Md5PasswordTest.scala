package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import kyo.*

/** Unit tests for Md5Password.
  *
  * Known-vector test uses an independently-precomputed MD5 value (computed once outside this codebase via OpenSSL / Python's hashlib) to
  * verify the implementation. Recomputing the digest inside the test would require `java.security.MessageDigest`, which is unavailable on
  * Scala Native and Scala.js, and an in-test oracle would be both circular and platform-restricted.
  *
  * Formula: "md5" + hex(MD5(hex(MD5(password + user)) + rawSalt))
  */
class Md5PasswordTest extends kyo.Test:

    "Md5Password hash format, starts with 'md5' and is 35 characters total" in {
        val result = Md5Password.encode("anypassword", "anyuser", Array[Byte](1, 2, 3, 4))
        assert(result.startsWith("md5"), s"Expected 'md5' prefix, got: $result")
        assert(result.length == 35, s"Expected 35 chars (3 + 32 hex), got ${result.length}: $result")
    }

    "Md5Password hash matches known vector, password=secret user=bob salt=0xABCDEF12" in {
        // Precomputed expected vector (verified independently with Python's hashlib):
        //   inner = md5("secretbob")                                       = "21f3163f8f86fa10bdefbfbd502a8f06"
        //   outer = md5("21f3163f8f86fa10bdefbfbd502a8f06" ++ 0xAB,0xCD,0xEF,0x12) = "a87eba369f4a6ff7fffa7aee32fa9e81"
        //   result = "md5" + outer
        val password = "secret"
        val user     = "bob"
        val salt     = Array[Byte](0xab.toByte, 0xcd.toByte, 0xef.toByte, 0x12.toByte)
        val expected = "md5a87eba369f4a6ff7fffa7aee32fa9e81"

        val actual = Md5Password.encode(password, user, salt)
        assert(actual == expected, s"Expected: $expected, got: $actual")
    }

    "Md5Password hash is lowercase hex, output contains only lowercase hex digits" in {
        val result  = Md5Password.encode("pass", "user", Array[Byte](0, 1, 2, 3))
        val hexPart = result.drop(3) // strip "md5"
        assert(
            hexPart.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')),
            s"Expected lowercase hex only, got: $hexPart"
        )
    }

    "Md5Password different salts produce different hashes" in {
        val h1 = Md5Password.encode("pass", "user", Array[Byte](1, 2, 3, 4))
        val h2 = Md5Password.encode("pass", "user", Array[Byte](5, 6, 7, 8))
        assert(h1 != h2, "Different salts must produce different hashes")
    }

    "Md5Password different passwords produce different hashes" in {
        val salt = Array[Byte](0, 0, 0, 0)
        val h1   = Md5Password.encode("password1", "user", salt)
        val h2   = Md5Password.encode("password2", "user", salt)
        assert(h1 != h2, "Different passwords must produce different hashes")
    }

    "PlainPassword encode is UTF-8 bytes of password" in {
        val result = PlainPassword.encode("abc")
        assert(result sameElements "abc".getBytes(StandardCharsets.UTF_8))
    }

    "PlainPassword encode handles Unicode correctly" in {
        val pw     = "pässwørd"
        val result = PlainPassword.encode(pw)
        assert(result sameElements pw.getBytes(StandardCharsets.UTF_8))
    }

end Md5PasswordTest
