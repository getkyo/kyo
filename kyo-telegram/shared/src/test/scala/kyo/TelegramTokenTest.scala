package kyo

class TelegramTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text, which a failing
    // assertion's rendered source could otherwise echo.
    private val secret = Seq("123456", "ABC-DEF1234ghIkl_zyx57W2v1u123ew11").mkString(":")

    private val token = Telegram.Token.init(secret).getOrThrow

    "value is the text the token was built from" in {
        assert(token.value == secret)
    }

    "a rendered token is redacted" in {
        assert(token.toString == "Telegram.Token(<redacted>)")
        assert(!s"$token".contains(secret))
    }

    "tokens compare and hash by value" in {
        assert(Telegram.Token.init(secret) == Result.succeed(token))
        assert(Telegram.Token.init(secret).getOrThrow.hashCode == token.hashCode)
        assert(Telegram.Token.init("1:other").getOrThrow != token)
    }

    "the bound is the Bot API server's, 80 characters" in {
        val longest = "1:" + "a" * 78
        assert(Telegram.Token.init(longest).map(_.value) == Result.succeed(longest))
    }

    "every character of A-Z a-z 0-9 _ - : is accepted" in {
        val all = "0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        assert(Telegram.Token.init(all).map(_.value) == Result.succeed(all))
    }

    "init fails with the problem, and the message holds no part of the text" - {
        import TelegramInvalidTokenException.Problem
        import TelegramInvalidTokenException.Token.Bot
        def rejected(text: String): Maybe[TelegramInvalidTokenException] = Telegram.Token.init(text).failure

        "an empty token" in {
            val ex = rejected("")
            assert(ex == Present(TelegramInvalidTokenException(Bot, Problem.Empty)))
            assert(ex.exists(_.getMessage.contains("Telegram.Token is not usable: it is empty.")))
        }

        "a token of 81 characters" in {
            val text = "1:" + "q" * 79
            val ex   = rejected(text)
            assert(ex == Present(TelegramInvalidTokenException(Bot, Problem.TooLong(81, 80))))
            assert(ex.exists(_.getMessage.contains("Telegram.Token is not usable: it has 81 characters, more than 80.")))
            assert(!ex.exists(_.getMessage.contains("q" * 79)))
        }

        "a token without a colon" in {
            val ex = rejected("123456SECRETPART")
            assert(ex == Present(TelegramInvalidTokenException(Bot, Problem.NoColon)))
            assert(ex.exists(_.getMessage.contains("Telegram.Token is not usable: it has no ':' between the bot id and the secret.")))
            assert(!ex.exists(_.getMessage.contains("SECRETPART")))
        }

        "a character that would change the URL, at its position" in {
            val cases = Chunk("12:ab/cd" -> 5, "12:ab?cd" -> 5, "12:ab#cd" -> 5, "12:ab%2F" -> 5, "12:ab cd" -> 5, "12:abécd" -> 5)
            val found = cases.map((text, _) => rejected(text))
            assert(found == cases.map((_, position) => Present(TelegramInvalidTokenException(Bot, Problem.InvalidCharacter(position)))))
            assert(found.forall(_.exists(_.getMessage.contains(
                "Telegram.Token is not usable: the character at position 5 is not one of A-Z a-z 0-9 _ - :."
            ))))
        }

        "the first bad character is the one reported" in {
            assert(rejected("1:/a/") == Present(TelegramInvalidTokenException(Bot, Problem.InvalidCharacter(2))))
        }
    }

    "the Schema writes and reads the token itself, as a JSON string" in {
        assert(Json.encode(token) == s""""$secret"""")
        assert(Json.decode[Telegram.Token](s""""$secret"""") == Result.succeed(token))
    }

    "the Schema refuses text that cannot be a token, as a decode failure" in {
        assert(Json.decode[Telegram.Token](""""12:ab/cd"""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
        assert(Json.decode[Telegram.Token]("""""""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
    }

end TelegramTokenTest
