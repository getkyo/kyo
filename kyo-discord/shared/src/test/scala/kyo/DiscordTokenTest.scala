package kyo

class DiscordTokenTest extends kyo.test.Test[Any]:

    import DiscordInvalidTokenException.Problem
    import DiscordInvalidTokenException.Token as Kind

    // Built from parts so the whole secret appears nowhere in this file's source text, which a failing assertion's rendered source
    // could otherwise echo.
    private val botSecret         = Seq("MTA0OTI3NjU0MzIxMDk4NzY1", "Gx9yQk", "Zp3sTnRwVbLmXcEuYoHiQaJkDf").mkString(".")
    private val interactionSecret = Seq("aW50ZXJhY3Rpb246", "MTIzNDU2Nzg5", "cXdlcnR5_dWlvcA-").mkString(".")

    private val token       = Discord.Token.init(botSecret).getOrThrow
    private val interaction = Discord.InteractionToken.init(interactionSecret).getOrThrow

    "value is the text the token was built from" in {
        assert(token.value == botSecret)
        assert(interaction.value == interactionSecret)
    }

    "a rendered token is redacted" in {
        assert(token.toString == "Discord.Token(<redacted>)")
        assert(interaction.toString == "Discord.InteractionToken(<redacted>)")
        assert(!s"$token $interaction".contains("Zp3sTnRwVbLmXcEuYoHiQaJkDf"))
        assert(!s"$token $interaction".contains("cXdlcnR5_dWlvcA-"))
    }

    "tokens compare and hash by value" in {
        assert(Discord.Token.init(botSecret) == Result.succeed(token))
        assert(Discord.Token.init(botSecret).getOrThrow.hashCode == token.hashCode)
        assert(Discord.Token.init("other").getOrThrow != token)
        assert(Discord.InteractionToken.init(interactionSecret) == Result.succeed(interaction))
    }

    "a bot token is 1 to 256 characters of printable ASCII other than space" in {
        val printable = (33 to 126).map(_.toChar).mkString
        assert(Discord.Token.init(printable).map(_.value) == Result.succeed(printable))
        assert(Discord.Token.init("a" * 256).map(_.value.length) == Result.succeed(256))
    }

    "an interaction token is 1 to 512 characters of A-Z a-z 0-9 . _ -" in {
        val all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"
        assert(Discord.InteractionToken.init(all).map(_.value) == Result.succeed(all))
        assert(Discord.InteractionToken.init("t" * 512).map(_.value.length) == Result.succeed(512))
    }

    "init fails with the problem, and the message holds no part of the text" - {

        "an empty token" in {
            assert(Discord.Token.init("").failure == Present(DiscordInvalidTokenException(Kind.Bot, Problem.Empty)))
            assert(Discord.InteractionToken.init("").failure == Present(DiscordInvalidTokenException(Kind.Interaction, Problem.Empty)))
            assert(Discord.Token.init("").failure.exists(_.getMessage.contains("Discord.Token is not usable: it is empty.")))
        }

        "a token past its length" in {
            val bot = Discord.Token.init("q" * 257).failure
            assert(bot == Present(DiscordInvalidTokenException(Kind.Bot, Problem.TooLong(257, 256))))
            assert(bot.exists(_.getMessage.contains("Discord.Token is not usable: it has 257 characters, more than 256.")))
            assert(!bot.exists(_.getMessage.contains("q" * 257)))
            assert(Discord.InteractionToken.init("q" * 513).failure ==
                Present(DiscordInvalidTokenException(Kind.Interaction, Problem.TooLong(513, 512))))
        }

        "a bot token character a header cannot carry, at its position" in {
            val cases = Chunk("abc def" -> 3, "abc\rdef" -> 3, "abc\ndef" -> 3, "abcédef" -> 3, "abc\u007fdef" -> 3)
            assert(cases.map((text, _) => Discord.Token.init(text).failure) ==
                cases.map((_, at) => Present(DiscordInvalidTokenException(Kind.Bot, Problem.Character(at)))))
            assert(Discord.Token.init("abc def").failure.exists(
                _.getMessage.contains("Discord.Token is not usable: the character at position 3 is not printable ASCII other than space.")
            ))
        }

        "an interaction token character that would change the URL, at its position" in {
            val cases = Chunk("ab/cd" -> 2, "ab?cd" -> 2, "ab#cd" -> 2, "ab%2F" -> 2, "ab cd" -> 2, "ab:cd" -> 2)
            assert(cases.map((text, _) => Discord.InteractionToken.init(text).failure) ==
                cases.map((_, at) => Present(DiscordInvalidTokenException(Kind.Interaction, Problem.Character(at)))))
            assert(Discord.InteractionToken.init("ab/cd").failure.exists(
                _.getMessage.contains("Discord.InteractionToken is not usable: the character at position 2 is not A-Z a-z 0-9 . _ -.")
            ))
        }
    }

    "the Schemas write and read the token itself, and refuse text init refuses" in {
        assert(Json.encode(token) == s""""$botSecret"""")
        assert(Json.decode[Discord.Token](s""""$botSecret"""") == Result.succeed(token))
        assert(Json.decode[Discord.InteractionToken](s""""$interactionSecret"""") == Result.succeed(interaction))
        assert(Json.decode[Discord.InteractionToken](""""a/b"""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
        assert(Json.decode[Discord.Token]("""""""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
    }

end DiscordTokenTest
