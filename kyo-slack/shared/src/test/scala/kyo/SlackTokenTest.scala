package kyo

class SlackTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the full secret appears nowhere in this file's source text, which a
    // failing assertion's rendered source could otherwise echo.
    private val appSecret = Seq("xapp", "1", "A0TOKENTEST", "q7Zr").mkString("-")
    private val botSecret = Seq("xoxb", "22", "B0TOKENTEST", "m4Kd").mkString("-")

    private val app = SlackToken.AppLevel(appSecret)
    private val bot = SlackToken.Bot(botSecret)

    "AppLevel and Bot extract their underlying values" in {
        assert(app.value == appSecret)
        assert(bot.value == botSecret)
    }

    "a rendered AppLevel token is redacted" in {
        val rendered = app.toString
        assert(rendered == "SlackToken.AppLevel(<redacted>)")
        assert(!s"$app".contains(appSecret))
    }

    "a rendered Bot token is redacted" in {
        val rendered = bot.toString
        assert(rendered == "SlackToken.Bot(<redacted>)")
        assert(!s"$bot".contains(botSecret))
    }

    "tokens compare and hash by value" in {
        assert(SlackToken.AppLevel(appSecret) == app)
        assert(SlackToken.AppLevel(appSecret).hashCode == app.hashCode)
        assert(SlackToken.AppLevel(appSecret + "x") != app)
        assert(SlackToken.Bot(botSecret) == bot)
        assert(SlackToken.Bot(botSecret).hashCode == bot.hashCode)
        assert(SlackToken.Bot(botSecret + "x") != bot)
    }

    "a rotated bot token, xoxe.xoxb- in Slack's token rotation guide, is a Bot token" in {
        val rotated = Seq("xoxe.xoxb", "1", "B0ROTATED", "k2Qe").mkString("-")
        assert(SlackToken.Bot(rotated).value == rotated)
    }

    "a token of the wrong shape is refused at construction, naming a position and never a character" in {
        import SlackInvalidTokenException.Problem
        def refused(build: => Any): Maybe[SlackInvalidTokenException] =
            try
                build
                Absent
            catch
                case e: SlackInvalidTokenException => Present(e)
        val botPrefixes = Present(Problem.Prefix(Chunk("xoxb-", "xoxe.xoxb-")))
        assert(refused(SlackToken.Bot("")).map(_.problem) == Present(Problem.Empty))
        assert(refused(SlackToken.Bot(appSecret)).map(_.problem) == botPrefixes)
        assert(refused(SlackToken.AppLevel(botSecret)).map(_.problem) == Present(Problem.Prefix(Chunk("xapp-"))))
        assert(refused(SlackToken.Bot("xoxb-")).map(_.problem) == botPrefixes)
        assert(refused(SlackToken.Bot("xoxe.xoxb-")).map(_.problem) == botPrefixes)
        assert(refused(SlackToken.Bot("xoxe.xoxp-1-2")).map(_.problem) == botPrefixes)
        assert(refused(SlackToken.Bot(botSecret + "\r\nX-Injected: 1")).map(_.problem) ==
            Present(Problem.InvalidCharacter(botSecret.length)))
        assert(refused(SlackToken.Bot("xoxb-" + "a" * 251)).map(_.problem) == Present(Problem.TooLong(256, 255)))
        assert(refused(SlackToken.Bot("xoxb-" + "a" * 250)) == Absent)
        assert(refused(SlackToken.Bot("xoxb-1.2_3|x~y")) == Absent, "Slack documents no alphabet, only the prefix and a length")
        assert(refused(SlackToken.Bot("xoxb-1 2")).map(_.problem) == Present(Problem.InvalidCharacter(6)))
        assert(refused(SlackToken.AppLevel("xapp-1é")).map(_.problem) == Present(Problem.InvalidCharacter(6)))
        val rendered = refused(SlackToken.Bot(botSecret + "\n")).map(_.getMessage)
        assert(rendered.nonEmpty && !rendered.exists(_.contains(botSecret)), s"got: $rendered")
    }

    "the refusal names which kind of token was being built" in {
        import SlackInvalidTokenException.Token
        def refusedAs(build: => Any): Maybe[Token] =
            try
                build
                Absent
            catch
                case e: SlackInvalidTokenException => Present(e.token)
        assert(refusedAs(SlackToken.AppLevel(botSecret)) == Present(Token.AppLevel))
        assert(refusedAs(SlackToken.Bot(appSecret)) == Present(Token.Bot))
    }

end SlackTokenTest
