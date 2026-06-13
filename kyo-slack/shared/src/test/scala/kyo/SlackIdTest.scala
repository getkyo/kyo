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

    "Ts round-trips and SlackTs is its alias" in {
        val t: SlackTs = SlackId.Ts("1700000000.000100")
        val encoded    = Json.encode(t)
        val decoded    = Json.decode[SlackTs](encoded)
        assert(encoded == "\"1700000000.000100\"")
        assert(decoded == Result.Success(SlackId.Ts("1700000000.000100")))
        assert(decoded.getOrElse(SlackId.Ts("")).value == "1700000000.000100")
        val _: (SlackTs =:= SlackId.Ts) = summon
    }

    "each of the 8 ids extracts its underlying value" in {
        assert(SlackId.ChannelId("C1").value == "C1")
        assert(SlackId.UserId("U1").value == "U1")
        assert(SlackId.TeamId("T1").value == "T1")
        assert(SlackId.AppId("A1").value == "A1")
        assert(SlackId.TriggerId("TR1").value == "TR1")
        assert(SlackId.EnvelopeId("E1").value == "E1")
        assert(SlackId.ViewId("V1").value == "V1")
        assert(SlackId.Ts("1.0").value == "1.0")
    }

    "UserId decode of a non-string JSON token fails typed" in {
        val result = Json.decode[SlackId.UserId]("42")
        assert(result.isFailure)
    }

end SlackIdTest
