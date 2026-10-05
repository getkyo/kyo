package kyo

class SlackRawJsonTest extends kyo.test.Test[Any]:
    import SlackRawJsonTest.of

    private val payload = """{"response_url":"https://hooks.slack.com/actions/T0/1/PathSecretQ4","token":"TokenSecretJ8"}"""

    "renders its text's length and never its text" in {
        val raw = of(payload)
        assert(raw.toString == s"SlackRawJson(${payload.length} characters)")
        assert(s"$raw" == "SlackRawJson(92 characters)")
    }

    "value is the JSON text of the payload it holds" in {
        assert(of(payload).value == payload)
    }

    "compares by its JSON" in {
        assert(of(payload) == of(payload))
        assert(of(payload).hashCode == of(payload).hashCode)
        assert(of(payload) != of("{}"))
    }

    "its Schema is the payload's own JSON, as a value and as a field" in {
        val nested = s"""{"kept":$payload,"n":1}"""
        assert(Json.decode[SlackRawJson](payload).map(Json.encode(_)) == Result.succeed(payload))
        assert(Json.decode[SlackRawJsonTest.Holder](nested) == Result.succeed(SlackRawJsonTest.Holder(of(payload), 1)))
        assert(Json.encode(SlackRawJsonTest.Holder(of(payload), 1)) == nested)
    }

end SlackRawJsonTest

object SlackRawJsonTest:
    /** The raw payload of a JSON text, for tests that state a payload as the text Slack sends. */
    def of(text: String)(using Frame): SlackRawJson =
        SlackRawJson(SlackLiterals.valid(Json.decode[Structure.Value](text)))

    final case class Holder(kept: SlackRawJson, n: Int) derives CanEqual, Schema
end SlackRawJsonTest
