package kyo

class SlackRawJsonTest extends kyo.test.Test[Any]:

    private val payload = """{"response_url":"https://hooks.slack.com/actions/T0/1/PathSecretQ4","token":"TokenSecretJ8"}"""

    "renders its length and never its text" in {
        val raw = SlackRawJson(payload)
        assert(raw.toString == s"SlackRawJson(${payload.length} characters)")
        assert(s"$raw" == "SlackRawJson(92 characters)")
        assert(SlackRawJson("").toString == "SlackRawJson(0 characters)")
    }

    "value is the text it was built from" in {
        assert(SlackRawJson(payload).value == payload)
    }

    "compares by its text" in {
        assert(SlackRawJson(payload) == SlackRawJson(payload))
        assert(SlackRawJson(payload).hashCode == SlackRawJson(payload).hashCode)
        assert(SlackRawJson(payload) != SlackRawJson("{}"))
    }

end SlackRawJsonTest
