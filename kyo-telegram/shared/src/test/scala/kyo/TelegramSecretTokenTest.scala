package kyo

class TelegramSecretTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val text = Seq("webhook", "Secret", "7Qx").mkString("_")

    private val secret = TelegramSecretToken(text)

    "value is the text the secret was built from" in {
        assert(secret.value == text)
    }

    "a rendered secret is redacted" in {
        assert(secret.toString == "TelegramSecretToken(<redacted>)")
        assert(!s"$secret".contains(text))
    }

    "secrets compare and hash by value" in {
        assert(TelegramSecretToken(text) == secret)
        assert(TelegramSecretToken(text).hashCode == secret.hashCode)
        assert(TelegramSecretToken("other") != secret)
    }

    "one character and 256 characters are accepted" in {
        assert(TelegramSecretToken("a").value == "a")
        assert(TelegramSecretToken("Z" * 256).value == "Z" * 256)
    }

    "every character of A-Z a-z 0-9 _ - is accepted" in {
        val all = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        assert(TelegramSecretToken(all).value == all)
    }

    "construction panics with the problem, and the message holds no part of the text" - {
        import TelegramInvalidTokenException.Problem
        import TelegramInvalidTokenException.Token.Secret
        def rejected(text: String)(using kyo.test.AssertScope): TelegramInvalidTokenException =
            intercept[TelegramInvalidTokenException](TelegramSecretToken(text))

        "an empty secret" in {
            val ex = rejected("")
            assert(ex == TelegramInvalidTokenException(Secret, Problem.Empty))
            assert(ex.getMessage.contains("TelegramSecretToken is not usable: it is empty."))
        }

        "a secret of 257 characters" in {
            val ex = rejected("k" * 257)
            assert(ex == TelegramInvalidTokenException(Secret, Problem.TooLong(257, 256)))
            assert(ex.getMessage.contains("TelegramSecretToken is not usable: it has 257 characters, more than 256."))
            assert(!ex.getMessage.contains("k" * 257))
        }

        "a character Telegram refuses, at its position" in {
            val cases = Chunk("abc:de" -> 3, "abc.de" -> 3, "abc de" -> 3, "abcéde" -> 3, "abc+de" -> 3)
            val found = cases.map((text, _) => rejected(text))
            assert(found == cases.map((_, position) => TelegramInvalidTokenException(Secret, Problem.InvalidCharacter(position))))
            assert(found.map(_.getMessage).forall(_.contains(
                "TelegramSecretToken is not usable: the character at position 3 is not one of A-Z a-z 0-9 _ -."
            )))
        }
    }

end TelegramSecretTokenTest
