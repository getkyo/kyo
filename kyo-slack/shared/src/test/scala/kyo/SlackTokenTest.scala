package kyo

import kyo.SlackLiterals.*

class SlackTokenTest extends kyo.test.Test[Any]:

    // Built from parts so the full secret appears nowhere in this file's source text, which a
    // failing assertion's rendered source could otherwise echo.
    private val appSecret = Seq("xapp", "1", "A0TOKENTEST", "q7Zr").mkString("-")
    private val botSecret = Seq("xoxb", "22", "B0TOKENTEST", "m4Kd").mkString("-")

    private val app = appLevelOf(appSecret)
    private val bot = botOf(botSecret)

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
        assert(appLevelOf(appSecret) == app)
        assert(appLevelOf(appSecret).hashCode == app.hashCode)
        assert(appLevelOf(appSecret + "x") != app)
        assert(botOf(botSecret) == bot)
        assert(botOf(botSecret).hashCode == bot.hashCode)
        assert(botOf(botSecret + "x") != bot)
    }

    "a rotated bot token, xoxe.xoxb- in Slack's token rotation guide, is a Bot token" in {
        val rotated = Seq("xoxe.xoxb", "1", "B0ROTATED", "k2Qe").mkString("-")
        assert(botOf(rotated).value == rotated)
    }

    "a token of the wrong shape fails init, naming a position and never a character" in {
        import SlackInvalidTokenException.Problem
        def problem(built: Result[SlackInvalidTokenException, Any]): Maybe[Problem] = built.failure.map(_.problem)
        val botPrefixes                                                             = Present(Problem.Prefix(Chunk("xoxb-", "xoxe.xoxb-")))
        assert(problem(SlackToken.Bot.init("")) == Present(Problem.Empty))
        assert(problem(SlackToken.Bot.init(appSecret)) == botPrefixes)
        assert(problem(SlackToken.AppLevel.init(botSecret)) == Present(Problem.Prefix(Chunk("xapp-"))))
        assert(problem(SlackToken.Bot.init("xoxb-")) == botPrefixes)
        assert(problem(SlackToken.Bot.init("xoxe.xoxb-")) == botPrefixes)
        assert(problem(SlackToken.Bot.init("xoxe.xoxp-1-2")) == botPrefixes)
        assert(problem(SlackToken.Bot.init(botSecret + "\r\nX-Injected: 1")) == Present(Problem.InvalidCharacter(botSecret.length)))
        assert(problem(SlackToken.Bot.init("xoxb-" + "a" * 251)) == Present(Problem.TooLong(256, 255)))
        assert(SlackToken.Bot.init("xoxb-" + "a" * 250).isSuccess)
        assert(SlackToken.Bot.init("xoxb-1.2_3|x~y").isSuccess, "Slack documents no alphabet, only the prefix and a length")
        assert(problem(SlackToken.Bot.init("xoxb-1 2")) == Present(Problem.InvalidCharacter(6)))
        assert(problem(SlackToken.AppLevel.init("xapp-1é")) == Present(Problem.InvalidCharacter(6)))
        val rendered = refused(SlackToken.Bot.init(botSecret + "\n")).getMessage
        assert(!rendered.contains(botSecret), s"got: $rendered")
    }

    "the failure names which kind of token was being built" in {
        import SlackInvalidTokenException.Token
        assert(refused(SlackToken.AppLevel.init(botSecret)).token == Token.AppLevel)
        assert(refused(SlackToken.Bot.init(appSecret)).token == Token.Bot)
    }

end SlackTokenTest
