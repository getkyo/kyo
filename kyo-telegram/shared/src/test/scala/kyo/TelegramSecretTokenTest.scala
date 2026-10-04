package kyo

class TelegramSecretTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val text = Seq("webhook", "Secret", "7Qx").mkString("_")

    private val secret = Telegram.SecretToken.init(text).getOrThrow

    "value is the text the secret was built from" in {
        assert(secret.value == text)
    }

    "a rendered secret is redacted" in {
        assert(secret.toString == "Telegram.SecretToken(<redacted>)")
        assert(!s"$secret".contains(text))
    }

    "secrets compare and hash by value" in {
        assert(Telegram.SecretToken.init(text) == Result.succeed(secret))
        assert(Telegram.SecretToken.init(text).getOrThrow.hashCode == secret.hashCode)
        assert(Telegram.SecretToken.init("other").getOrThrow != secret)
    }

    "the Schema writes and reads the secret itself, as a JSON string" in {
        assert(Json.encode(secret) == s""""$text"""")
        assert(Json.decode[Telegram.SecretToken](s""""$text"""") == Result.succeed(secret))
    }

    "the Schema refuses text Telegram would refuse, as a decode failure" in {
        assert(Json.decode[Telegram.SecretToken](""""abc:de"""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
        assert(Json.decode[Telegram.SecretToken](s""""${"k" * 257}"""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
    }

    "one character and 256 characters are accepted" in {
        assert(Telegram.SecretToken.init("a").map(_.value) == Result.succeed("a"))
        assert(Telegram.SecretToken.init("Z" * 256).map(_.value) == Result.succeed("Z" * 256))
    }

    "every character of A-Z a-z 0-9 _ - is accepted" in {
        val all = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        assert(Telegram.SecretToken.init(all).map(_.value) == Result.succeed(all))
    }

    "init fails with the problem, and the message holds no part of the text" - {
        import TelegramInvalidTokenException.Problem
        import TelegramInvalidTokenException.Token.Secret
        def rejected(text: String): Maybe[TelegramInvalidTokenException] = Telegram.SecretToken.init(text).failure

        "an empty secret" in {
            val ex = rejected("")
            assert(ex == Present(TelegramInvalidTokenException(Secret, Problem.Empty)))
            assert(ex.exists(_.getMessage.contains("Telegram.SecretToken is not usable: it is empty.")))
        }

        "a secret of 257 characters" in {
            val ex = rejected("k" * 257)
            assert(ex == Present(TelegramInvalidTokenException(Secret, Problem.TooLong(257, 256))))
            assert(ex.exists(_.getMessage.contains("Telegram.SecretToken is not usable: it has 257 characters, more than 256.")))
            assert(!ex.exists(_.getMessage.contains("k" * 257)))
        }

        "a character Telegram refuses, at its position" in {
            val cases = Chunk("abc:de" -> 3, "abc.de" -> 3, "abc de" -> 3, "abcéde" -> 3, "abc+de" -> 3)
            val found = cases.map((text, _) => rejected(text))
            assert(found == cases.map((_, position) => Present(TelegramInvalidTokenException(Secret, Problem.InvalidCharacter(position)))))
            assert(found.forall(_.exists(_.getMessage.contains(
                "Telegram.SecretToken is not usable: the character at position 3 is not one of A-Z a-z 0-9 _ -."
            ))))
        }
    }

end TelegramSecretTokenTest
