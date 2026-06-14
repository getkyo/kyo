package kyo

class SlackConfigTest extends kyo.test.Test[Any]:

    "defaults are Overlap, 30s keepalive, 3s ackDeadline" in {
        val cfg = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
        assert(cfg.reconnect == SlackConfig.Reconnect.Overlap)
        assert(cfg.keepAliveInterval == Present(30.seconds))
        assert(cfg.ackDeadline == 3.seconds)
    }

    "the three Reconnect cases are exactly Overlap/Immediate/Off" in {
        val cases = SlackConfig.Reconnect.values.toSet
        assert(cases == Set(
            SlackConfig.Reconnect.Overlap,
            SlackConfig.Reconnect.Immediate,
            SlackConfig.Reconnect.Off
        ))
        assert(cases.size == 3)
    }

    "CanEqual holds and two equal configs compare equal" in {
        val a = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
        val b = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
        val c = SlackConfig(
            SlackToken.AppLevel("xapp-1"),
            SlackToken.Bot("xoxb-1"),
            reconnect = SlackConfig.Reconnect.Off
        )
        assert(a == b)
        assert(a != c)
    }

end SlackConfigTest
