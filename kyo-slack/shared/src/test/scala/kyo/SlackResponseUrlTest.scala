package kyo

class SlackResponseUrlTest extends kyo.test.Test[Any]:

    // Built from parts so the secret's literal is off the source lines a failure renders.
    private val secret = Seq("SECRET", "HOOK", "PATH", "91be").mkString("-")
    private val text   = s"https://hooks.slack.com/actions/T0/1/$secret"

    "renders redacted, alone and inside a Maybe" in {
        assert(SlackResponseUrl(text).toString == "SlackResponseUrl(<redacted>)")
        assert(!Present(SlackResponseUrl(text)).toString.contains(secret))
    }

    "value is the text it was built from" in {
        assert(SlackResponseUrl(text).value == text)
    }

    "compares by its text" in {
        assert(SlackResponseUrl(text) == SlackResponseUrl(text))
        assert(SlackResponseUrl(text).hashCode == SlackResponseUrl(text).hashCode)
        assert(SlackResponseUrl(text) != SlackResponseUrl("https://hooks.slack.com/actions/T0/1/other"))
    }

end SlackResponseUrlTest
