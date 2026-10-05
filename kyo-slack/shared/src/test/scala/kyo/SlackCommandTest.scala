package kyo

class SlackCommandTest extends kyo.test.Test[Any]:

    // Built from parts so the secret's literal is off the source lines a failure renders.
    private val secret = Seq("SECRET", "COMMAND", "URL", "a41c").mkString("-")

    private val command = SlackCommand(
        "/deploy",
        "prod",
        SlackId.ChannelId("C1"),
        SlackId.UserId("U1"),
        SlackId.TriggerId("T1"),
        Present(SlackResponseUrl(s"https://hooks.slack.com/commands/T1/$secret"))
    )

    "a command renders its response_url redacted" in {
        assert(!command.toString.contains(secret), command.toString)
        assert(!SlackEnvelope.SlashCommand(SlackId.EnvelopeId("E1"), command).toString.contains(secret))
    }

    "a command's response_url is read through value" in {
        assert(command.responseUrl.map(_.value) == Present(s"https://hooks.slack.com/commands/T1/$secret"))
    }

end SlackCommandTest
