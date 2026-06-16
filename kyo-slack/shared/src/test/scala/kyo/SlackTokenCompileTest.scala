package kyo

class SlackTokenCompileTest extends kyo.test.Test[Any]:

    "a Bot token is rejected where AppLevel is required" in {
        typeCheckFailure(
            "SlackConfig(appLevel = SlackToken.Bot(\"xoxb-1\"), bot = SlackToken.Bot(\"xoxb-2\"))"
        )("Found:")
    }

    "an AppLevel token is rejected where Bot is required" in {
        typeCheckFailure(
            "SlackConfig(appLevel = SlackToken.AppLevel(\"xapp-1\"), bot = SlackToken.AppLevel(\"xapp-2\"))"
        )("Found:")
    }

end SlackTokenCompileTest
