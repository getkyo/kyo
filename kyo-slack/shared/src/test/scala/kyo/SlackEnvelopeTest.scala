package kyo

class SlackEnvelopeTest extends kyo.test.Test[Any]:

    "Hello decodes its flat fields" in {
        val json    = """{"numConnections":1,"appId":"A1"}"""
        val decoded = Json.decode[SlackEnvelope.Hello](json)
        assert(decoded == Result.Success(
            SlackEnvelope.Hello(1, SlackId.AppId("A1"), Absent)
        ))
    }

    "Meta decodes with defaults for the optional fields" in {
        val json    = """{"envelopeId":"E1"}"""
        val decoded = Json.decode[SlackEnvelope.Meta](json)
        assert(decoded == Result.Success(
            SlackEnvelope.Meta(
                SlackId.EnvelopeId("E1"),
                acceptsResponsePayload = false,
                retryAttempt = Absent,
                retryReason = Absent
            )
        ))
    }

    "DisconnectReason enum maps its four cases" in {
        import SlackEnvelope.DisconnectReason.*
        assert(Warning == Warning)
        assert(Warning != RefreshRequested)
        assert(RefreshRequested != LinkDisabled)
        assert(LinkDisabled != Unknown("x"))
        assert(Unknown("x") != Unknown("y"))
        assert(Unknown("x") == Unknown("x"))
    }

    "Disconnect round-trips through JSON" in {
        val value   = SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled)
        val encoded = Json.encode(value)
        val decoded = Json.decode[SlackEnvelope.Disconnect](encoded)
        assert(decoded == Result.Success(value))
    }

end SlackEnvelopeTest
