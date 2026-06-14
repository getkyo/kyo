package kyo

import kyo.internal.SlackRawJson

class SlackMessageTest extends kyo.test.Test[Any]:

    "a message with no blocks renders no block array" in {
        Slack.messageBlocks(SlackMessage(SlackId.ChannelId("C1"), "hi")).map { blocks =>
            assert(blocks == Absent)
        }
    }

    "a message's typed blocks render to a Block Kit array on the wire" in {
        val msg = SlackMessage(
            SlackId.ChannelId("C1"),
            "hi",
            blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
        )
        Slack.messageBlocks(msg).map {
            case Present(raw) =>
                val json = Json.encode(raw)
                assert(json.startsWith("["), json)
                assert(json.contains("\"type\":\"section\""), json)
                assert(json.contains("\"type\":\"mrkdwn\""), json)
                assert(json.contains("\"text\":\"x\""), json)
            case Absent => assert(false, "expected a rendered block array")
        }
    }

end SlackMessageTest
