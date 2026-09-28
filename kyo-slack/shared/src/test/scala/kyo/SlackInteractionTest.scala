package kyo

class SlackInteractionTest extends kyo.test.Test[Any]:

    // Built from parts so the secret's literal is off the source lines a failure renders.
    private val secret = Seq("SECRET", "ACTION", "URL", "7d02").mkString("-")
    private val url    = SlackResponseUrl(s"https://hooks.slack.com/actions/T1/B1/$secret")

    "BlockActions and MessageAction render their response_url redacted, alone and in their envelope" in {
        val click = SlackInteraction.BlockActions(
            SlackId.UserId("U1"),
            SlackId.TriggerId("T1"),
            Present(SlackId.ChannelId("C1")),
            Absent,
            Chunk(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1"))),
            Present(SlackTs("1.2")),
            Present(url)
        )
        val action = SlackInteraction.MessageAction(
            SlackId.UserId("U1"),
            SlackId.TriggerId("T1"),
            "cb1",
            SlackId.ChannelId("C1"),
            SlackTs("1.2"),
            Present(url)
        )
        val meta     = SlackEnvelope.Meta(SlackId.EnvelopeId("E1"))
        val rendered =
            Chunk(click, action).map(_.toString) ++ Chunk(click, action).map(SlackEnvelope.Interactive(meta, _).toString)
        assert(rendered.forall(r => r.contains("SlackResponseUrl(<redacted>)") && !r.contains(secret)), rendered.toString)
    }

end SlackInteractionTest
