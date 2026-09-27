package kyo

class TelegramTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text, which a failing
    // assertion's rendered source could otherwise echo.
    private val secret = Seq("123456", "ABC-DEF1234ghIkl_zyx57W2v1u123ew11").mkString(":")

    private val token = TelegramToken(secret)

    "value is the text the token was built from" in {
        assert(token.value == secret)
    }

    "a rendered token is redacted" in {
        assert(token.toString == "TelegramToken(<redacted>)")
        assert(!s"$token".contains(secret))
    }

    "tokens compare and hash by value" in {
        assert(TelegramToken(secret) == token)
        assert(TelegramToken(secret).hashCode == token.hashCode)
        assert(TelegramToken("1:other") != token)
    }

    "the bound is the Bot API server's, 80 characters" in {
        val longest = "1:" + "a" * 78
        assert(TelegramToken(longest).value == longest)
    }

    "every character of A-Z a-z 0-9 _ - : is accepted" in {
        val all = "0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        assert(TelegramToken(all).value == all)
    }

    "construction panics with the problem, and the message holds no part of the text" - {
        import TelegramInvalidTokenException.Problem
        import TelegramInvalidTokenException.Token.Bot
        def rejected(text: String)(using kyo.test.AssertScope): TelegramInvalidTokenException =
            intercept[TelegramInvalidTokenException](TelegramToken(text))

        "an empty token" in {
            val ex = rejected("")
            assert(ex == TelegramInvalidTokenException(Bot, Problem.Empty))
            assert(ex.getMessage.contains("TelegramToken is not usable: it is empty."))
        }

        "a token of 81 characters" in {
            val text = "1:" + "q" * 79
            val ex   = rejected(text)
            assert(ex == TelegramInvalidTokenException(Bot, Problem.TooLong(81, 80)))
            assert(ex.getMessage.contains("TelegramToken is not usable: it has 81 characters, more than 80."))
            assert(!ex.getMessage.contains("q" * 79))
        }

        "a token without a colon" in {
            val ex = rejected("123456SECRETPART")
            assert(ex == TelegramInvalidTokenException(Bot, Problem.NoColon))
            assert(ex.getMessage.contains("TelegramToken is not usable: it has no ':' between the bot id and the secret."))
            assert(!ex.getMessage.contains("SECRETPART"))
        }

        "a character that would change the URL, at its position" in {
            val cases = Chunk("12:ab/cd" -> 5, "12:ab?cd" -> 5, "12:ab#cd" -> 5, "12:ab%2F" -> 5, "12:ab cd" -> 5, "12:abécd" -> 5)
            val found = cases.map((text, _) => rejected(text))
            assert(found == cases.map((_, position) => TelegramInvalidTokenException(Bot, Problem.InvalidCharacter(position))))
            assert(found.map(_.getMessage).forall(_.contains(
                "TelegramToken is not usable: the character at position 5 is not one of A-Z a-z 0-9 _ - :."
            )))
        }

        "the first bad character is the one reported" in {
            assert(rejected("1:/a/") == TelegramInvalidTokenException(Bot, Problem.InvalidCharacter(2)))
        }
    }

end TelegramTokenTest
