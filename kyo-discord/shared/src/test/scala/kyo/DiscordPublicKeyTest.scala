package kyo

import kyo.crypto.Ed25519

class DiscordPublicKeyTest extends kyo.test.Test[Any]:

    import DiscordInvalidPublicKeyException.Problem

    // RFC 8032 section 7.1, TEST 1: a valid Ed25519 public key.
    private val keyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    "a valid key is accepted and its hex is the key's bytes" in {
        val key = Discord.PublicKey.init(keyHex)
        assert(key.map(_.hex) == Result.succeed(keyHex))
    }

    "uppercase hex names the same key" in {
        assert(Discord.PublicKey.init(keyHex.toUpperCase) == Discord.PublicKey.init(keyHex))
    }

    "the key is not secret, so it renders its hex" in {
        assert(Discord.PublicKey.init(keyHex).map(_.toString) == Result.succeed(s"Discord.PublicKey($keyHex)"))
    }

    "the identity encoding is refused with kyo-crypto's SmallOrder" in {
        // The identity point: y = 1, sign 0. With it, a signature with R the identity and S = 0 verifies for every message.
        val identity = "01" + "00" * 31
        val ex       = Discord.PublicKey.init(identity).failure
        assert(ex == Present(DiscordInvalidPublicKeyException(Problem.Key(Ed25519.KeyFailure.SmallOrder))))
        assert(ex.exists(
            _.getMessage.contains("Discord.PublicKey is not usable: it is not an Ed25519 public key (the point has small order).")
        ))
    }

    "text that is not hex is refused with Hex's failure" in {
        assert(Discord.PublicKey.init("abc").failure == Present(DiscordInvalidPublicKeyException(Problem.Hex(Hex.Failure.OddLength(3)))))
        assert(Discord.PublicKey.init("zz" + keyHex.drop(2)).failure ==
            Present(DiscordInvalidPublicKeyException(Problem.Hex(Hex.Failure.IllegalCharacter(0)))))
    }

    "bytes that are not 32 are refused with kyo-crypto's Length" in {
        assert(Discord.PublicKey.init(keyHex.drop(2)).failure ==
            Present(DiscordInvalidPublicKeyException(Problem.Key(Ed25519.KeyFailure.Length(31)))))
        assert(Discord.PublicKey.init("").failure == Present(DiscordInvalidPublicKeyException(Problem.Key(Ed25519.KeyFailure.Length(0)))))
    }

end DiscordPublicKeyTest
