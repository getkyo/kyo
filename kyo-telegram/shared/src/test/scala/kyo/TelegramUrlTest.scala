package kyo

class TelegramUrlTest extends kyo.test.Test[Any]:

    "http, https and tg links in any ASCII case are accepted and are their value" in {
        val links =
            Chunk("https://t.me/standup_bot", "http://example.com/a?b=1", "tg://user?id=1", "HTTPS://T.ME/x", "Tg://resolve?domain=a")
        assert(links.map(Telegram.Url.init(_).map(_.value)) == links.map(Result.succeed(_)))
    }

    "init fails with what is wrong, never with the text" in {
        import TelegramInvalidUrlException.Problem
        val texts = Chunk("ftp://example.com/a", "hooks/x", "", "https://", "https://a b", "tg://café", "https://a\nb")
        assert(texts.map(Telegram.Url.init(_)) == Chunk(
            Problem.Scheme,
            Problem.Scheme,
            Problem.Scheme,
            Problem.Empty,
            Problem.Character(9),
            Problem.Character(8),
            Problem.Character(9)
        ).map(p => Result.fail(TelegramInvalidUrlException(p))))
    }

    "the message names the position and not the text" in {
        val e = Telegram.Url.init(Seq("https://secret", "host token").mkString("-")).failure
        assert(e.exists(_.getMessage.contains(
            "Telegram.Url is not usable: the character at position 19 is not printable ASCII other than space."
        )))
        assert(!e.exists(_.getMessage.contains("secret-host")))
    }

    "a link is a JSON string, and one this type refuses fails the decode" in {
        assert(Json.decode[Telegram.Url](""""tg://user?id=5"""") == Telegram.Url.init("tg://user?id=5"))
        assert(Telegram.Url.init("https://t.me/standup_bot").map(Json.encode(_)) == Result.succeed(""""https://t.me/standup_bot""""))
        assert(Json.decode[Telegram.Url](""""ftp://example.com/a"""").failure.map(_.getClass.getSimpleName) ==
            Present("ConstructorRejectedException"))
    }

end TelegramUrlTest
