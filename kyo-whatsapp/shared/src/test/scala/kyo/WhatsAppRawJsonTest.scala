package kyo

class WhatsAppRawJsonTest extends BaseWhatsAppTest:
    import WhatsAppRawJsonTest.of

    private val payload = """{"from":"16505551234","text":{"body":"PLANTED-RAW-TEXT"}}"""

    "renders its text's length and never its text" in {
        val raw = of(payload)
        assert(raw.toString == s"WhatsAppRawJson(${payload.length} characters)")
        assert(!s"$raw".contains("PLANTED"))
    }

    "value is the JSON text of the payload it holds" in {
        assert(of(payload).value == payload)
    }

    "compares by its JSON" in {
        assert(of(payload) == of(payload))
        assert(of(payload).hashCode == of(payload).hashCode)
        assert(of("{}") != of("[]"))
    }

    "its Schema is the payload's own JSON, as a value and as a field" in {
        val nested = s"""{"kept":$payload,"n":1}"""
        assert(Json.decode[WhatsAppRawJson](payload).map(Json.encode(_)) == Result.succeed(payload))
        assert(Json.decode[WhatsAppRawJsonTest.Holder](nested) == Result.succeed(WhatsAppRawJsonTest.Holder(of(payload), 1)))
        assert(Json.encode(WhatsAppRawJsonTest.Holder(of(payload), 1)) == nested)
    }

end WhatsAppRawJsonTest

object WhatsAppRawJsonTest:
    /** The raw payload of a JSON text, for tests that state a payload as the text Meta sends. */
    def of(text: String)(using Frame): WhatsAppRawJson =
        WhatsAppRawJson(Json.decode[Structure.Value](text).getOrThrow)

    final case class Holder(kept: WhatsAppRawJson, n: Int) derives CanEqual, Schema
end WhatsAppRawJsonTest
