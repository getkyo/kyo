package kyo

class SlackIdTest extends kyo.test.Test[Any]:

    "channelId round-trips through JSON" in {
        val c       = SlackId.ChannelId("C123")
        val encoded = Json.encode(c)
        val decoded = Json.decode[SlackId.ChannelId](encoded)
        assert(encoded == "\"C123\"")
        assert(decoded == Result.Success(SlackId.ChannelId("C123")))
        assert(decoded.map(_.value) == Result.Success("C123"))
    }

    "SlackTs round-trips through JSON as a top-level opaque type" in {
        val t: SlackTs = SlackTs("1700000000.000100")
        val encoded    = Json.encode(t)
        val decoded    = Json.decode[SlackTs](encoded)
        assert(encoded == "\"1700000000.000100\"")
        assert(decoded == Result.Success(SlackTs("1700000000.000100")))
        assert(decoded.map(_.value) == Result.Success("1700000000.000100"))
    }

    "BotId/ActionId/BlockId round-trip through JSON" in {
        assert(Json.decode[SlackId.BotId](Json.encode(SlackId.BotId("B1"))) == Result.Success(SlackId.BotId("B1")))
        assert(Json.decode[SlackId.ActionId](Json.encode(SlackId.ActionId("a1"))) == Result.Success(SlackId.ActionId("a1")))
        assert(Json.decode[SlackId.BlockId](Json.encode(SlackId.BlockId("b1"))) == Result.Success(SlackId.BlockId("b1")))
    }

    "UserId decode of a non-string JSON token is a parse failure at the token, expecting a string's opening quote" in {
        Json.decode[SlackId.UserId]("42") match
            case Result.Failure(e: ParseException) =>
                assert((e.path, e.position, e.targetType) == (Seq.empty[String], 0, "Expected '\"'"))
            case other => fail(s"expected a parse failure, got: $other")
    }

end SlackIdTest
