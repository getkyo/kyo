package kyo

class SlackMessageTest extends kyo.test.Test[Any]:

    "a minimal message round-trips through JSON" in {
        val msg     = SlackMessage(SlackId.ChannelId("C1"), "hello")
        val encoded = Json.encode(msg)
        val decoded = Json.decode[SlackMessage](encoded)
        assert(decoded == Result.Success(msg))
        assert(decoded.getOrElse(msg).blocksJson == Absent)
        assert(decoded.getOrElse(msg).threadTs == Absent)
    }

    "blocksJson and threadTs survive the round-trip" in {
        val msg = SlackMessage(
            SlackId.ChannelId("C1"),
            "hi",
            Present("[{\"type\":\"section\"}]"),
            Present(SlackId.Ts("1.2"))
        )
        val encoded = Json.encode(msg)
        val decoded = Json.decode[SlackMessage](encoded)
        assert(decoded == Result.Success(msg))
        assert(decoded.getOrElse(msg).blocksJson == Present("[{\"type\":\"section\"}]"))
    }

end SlackMessageTest
