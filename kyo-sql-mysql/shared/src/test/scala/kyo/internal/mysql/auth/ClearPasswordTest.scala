package kyo.internal.mysql.auth

import kyo.*

/** Unit tests for [[ClearPassword]], the NUL-terminated payload the `mysql_clear_password` plugin sends.
  *
  * The encoding has no hashing and no key material, so the whole contract is the byte layout: the UTF-8 password followed by exactly one
  * NUL, and a bare NUL for a credential that is absent. Both are pinned here because the plugin is the one place where a wrong length or a
  * missing terminator would still authenticate against some servers and fail against others.
  */
class ClearPasswordTest extends kyo.Test:

    "encodes a password as its UTF-8 bytes followed by one NUL" in {
        val encoded = ClearPassword.encode(Present("secret"))
        assert(encoded.toArray.sameElements("secret".getBytes("UTF-8") :+ 0.toByte))
        assert(encoded.size == 7, s"six bytes plus one terminator, got ${encoded.size}")
    }

    "encodes a multi-byte password by its UTF-8 length, not its character count" in {
        // A password outside ASCII is where a length computed from String.length would diverge from the wire.
        val encoded = ClearPassword.encode(Present("pässwörd"))
        assert(encoded.toArray.sameElements("pässwörd".getBytes("UTF-8") :+ 0.toByte))
        assert(encoded.size == 11, s"eight characters are ten UTF-8 bytes plus one terminator, got ${encoded.size}")
    }

    "encodes an absent credential as the protocol's single NUL" in {
        assert(ClearPassword.encode(Absent).toArray.sameElements(Array[Byte](0)))
    }

    "encodes an empty password identically to an absent one" in {
        // MySQL treats absent and empty as one credential, so the two spellings must not take different wire paths.
        assert(ClearPassword.encode(Present("")).toArray.sameElements(ClearPassword.encode(Absent).toArray))
    }

end ClearPasswordTest
