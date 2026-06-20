package kyo

class SlackCommandTest extends kyo.test.Test[Any]:

    "a slash command round-trips with all fields" in {
        val cmd = SlackCommand(
            "/deploy",
            "prod",
            SlackId.ChannelId("C1"),
            SlackId.UserId("U1"),
            SlackId.TriggerId("T1"),
            "https://hooks.slack.com/x"
        )
        val encoded = Json.encode(cmd)
        val decoded = Json.decode[SlackCommand](encoded)
        assert(decoded == Result.Success(cmd))
        assert(decoded.getOrElse(cmd).responseUrl == "https://hooks.slack.com/x")
    }

end SlackCommandTest
