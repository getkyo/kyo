package kyo

class SlackIdTest extends kyo.test.Test[Any]:

    "channelId round-trips through JSON" in {
        val c       = SlackId.ChannelId("C123")
        val encoded = Json.encode(c)
        val decoded = Json.decode[SlackId.ChannelId](encoded)
        assert(encoded == "\"C123\"")
        assert(decoded == Result.Success(SlackId.ChannelId("C123")))
        assert(decoded.getOrElse(SlackId.ChannelId("")).value == "C123")
    }

    "SlackTs round-trips through JSON as a top-level opaque type" in {
        val t: SlackTs = SlackTs("1700000000.000100")
        val encoded    = Json.encode(t)
        val decoded    = Json.decode[SlackTs](encoded)
        assert(encoded == "\"1700000000.000100\"")
        assert(decoded == Result.Success(SlackTs("1700000000.000100")))
        assert(decoded.getOrElse(SlackTs("")).value == "1700000000.000100")
    }

    "each id extracts its underlying value" in {
        assert(SlackId.ChannelId("C1").value == "C1")
        assert(SlackId.UserId("U1").value == "U1")
        assert(SlackId.TeamId("T1").value == "T1")
        assert(SlackId.AppId("A1").value == "A1")
        assert(SlackId.TriggerId("TR1").value == "TR1")
        assert(SlackId.EnvelopeId("E1").value == "E1")
        assert(SlackId.ViewId("V1").value == "V1")
        assert(SlackId.BotId("B1").value == "B1")
        assert(SlackId.ActionId("a1").value == "a1")
        assert(SlackId.BlockId("b1").value == "b1")
        assert(SlackTs("1.0").value == "1.0")
    }

    "BotId/ActionId/BlockId round-trip through JSON" in {
        assert(Json.decode[SlackId.BotId](Json.encode(SlackId.BotId("B1"))) == Result.Success(SlackId.BotId("B1")))
        assert(Json.decode[SlackId.ActionId](Json.encode(SlackId.ActionId("a1"))) == Result.Success(SlackId.ActionId("a1")))
        assert(Json.decode[SlackId.BlockId](Json.encode(SlackId.BlockId("b1"))) == Result.Success(SlackId.BlockId("b1")))
    }

    "UserId decode of a non-string JSON token fails typed" in {
        val result = Json.decode[SlackId.UserId]("42")
        assert(result.isFailure)
    }

end SlackIdTest
