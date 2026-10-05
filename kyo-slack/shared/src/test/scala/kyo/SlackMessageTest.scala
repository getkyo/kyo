package kyo

class SlackMessageTest extends kyo.test.Test[Any]:

    "a message with no blocks renders no block array" in {
        assert(Slack.messageBlocks(SlackMessage(SlackId.ChannelId("C1"), "hi")) == Absent)
    }

    "a message's typed blocks render to a Block Kit array on the wire" in {
        val msg = SlackMessage(
            SlackId.ChannelId("C1"),
            "hi",
            blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
        )
        assert(Slack.messageBlocks(msg).map(Json.encode(_)) == Present("""[{"type":"section","text":{"type":"mrkdwn","text":"x"}}]"""))
    }

end SlackMessageTest
