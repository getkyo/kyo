package kyo

class SlackTokenTest extends kyo.test.Test[Any]:

    "AppLevel and Bot extract their underlying values" in {
        val app = SlackToken.AppLevel("xapp-1")
        val bot = SlackToken.Bot("xoxb-1")
        assert(app.value == "xapp-1")
        assert(bot.value == "xoxb-1")
    }

    "neither token type carries a Schema" in {
        typeCheckFailure("summon[Schema[SlackToken.Bot]]")
        typeCheckFailure("summon[Schema[SlackToken.AppLevel]]")
    }

end SlackTokenTest
