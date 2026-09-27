package kyo

class TelegramUrlTest extends kyo.test.Test[Any]:

    "http, https and tg links in any ASCII case are accepted and are their value" in {
        val links =
            Chunk("https://t.me/standup_bot", "http://example.com/a?b=1", "tg://user?id=1", "HTTPS://T.ME/x", "Tg://resolve?domain=a")
        assert(links.map(TelegramUrl(_).value) == links)
    }

    "construction panics with what is wrong, never with the text" in {
        import TelegramInvalidUrlException.Problem
        val found = Chunk(
            intercept[TelegramInvalidUrlException](TelegramUrl("ftp://example.com/a")),
            intercept[TelegramInvalidUrlException](TelegramUrl("hooks/x")),
            intercept[TelegramInvalidUrlException](TelegramUrl("")),
            intercept[TelegramInvalidUrlException](TelegramUrl("https://")),
            intercept[TelegramInvalidUrlException](TelegramUrl("https://a b")),
            intercept[TelegramInvalidUrlException](TelegramUrl("tg://café")),
            intercept[TelegramInvalidUrlException](TelegramUrl("https://a\nb"))
        )
        assert(found == Chunk(
            Problem.Scheme,
            Problem.Scheme,
            Problem.Scheme,
            Problem.Empty,
            Problem.Character(9),
            Problem.Character(8),
            Problem.Character(9)
        ).map(TelegramInvalidUrlException(_)))
    }

    // A KyoException's message quotes the source around its frame, so the text is passed in rather than written at the call.
    private def rejected(text: String)(using kyo.test.AssertScope): TelegramInvalidUrlException =
        intercept[TelegramInvalidUrlException](TelegramUrl(text))

    "the message names the position and not the text" in {
        val e = rejected(Seq("https://secret", "host token").mkString("-"))
        assert(e.getMessage.contains("TelegramUrl is not usable: the character at position 19 is not printable ASCII other than space."))
        assert(!e.getMessage.contains("secret-host"))
    }

end TelegramUrlTest
