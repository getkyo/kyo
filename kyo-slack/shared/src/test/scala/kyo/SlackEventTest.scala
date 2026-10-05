package kyo

/** Each typed case's `Schema` against the event objects Slack's Events API reference documents. A case's own `Schema`
  * reads Slack's object, the `type` key and the keys the module does not model aside, and writes the modelled keys.
  */
class SlackEventTest extends kyo.test.Test[Any]:

    "a message event decodes from Slack's object, and a threaded reply's thread_ts is its threadTs" in {
        val top      = """{"type":"message","channel":"C123ABC456","user":"U123ABC456","text":"Hello world","ts":"1355517523.000005"}"""
        val threaded =
            """{"type":"message","channel":"C123ABC456","user":"U123ABC456","text":"reply","ts":"1355517524.000006","thread_ts":"1355517523.000005"}"""
        assert(Json.decode[SlackEvent.Message](top) == Result.succeed(SlackEvent.Message(
            SlackId.ChannelId("C123ABC456"),
            SlackId.UserId("U123ABC456"),
            "Hello world",
            SlackTs("1355517523.000005")
        )))
        assert(Json.decode[SlackEvent.Message](threaded).map(_.threadTs) == Result.succeed(Present(SlackTs("1355517523.000005"))))
        assert(Json.decode[SlackEvent.Message](threaded).map(Json.encode(_)) == Result.succeed(
            """{"channel":"C123ABC456","user":"U123ABC456","text":"reply","ts":"1355517524.000006","thread_ts":"1355517523.000005"}"""
        ))
    }

    "an app_mention event decodes from Slack's object and writes its modelled keys" in {
        val json =
            """{"type":"app_mention","user":"U061F7AUR","text":"<@U0LAN0Z89> is it everything a river should be?","ts":"1515449522.000016","channel":"C123ABC456","event_ts":"1515449522000016"}"""
        val event = SlackEvent.AppMention(
            SlackId.ChannelId("C123ABC456"),
            SlackId.UserId("U061F7AUR"),
            "<@U0LAN0Z89> is it everything a river should be?",
            SlackTs("1515449522.000016")
        )
        assert(Json.decode[SlackEvent.AppMention](json) == Result.succeed(event))
        assert(
            Json.encode(event) ==
                """{"channel":"C123ABC456","user":"U061F7AUR","text":"<@U0LAN0Z89> is it everything a river should be?","ts":"1515449522.000016"}"""
        )
    }

    "a reaction_added event's item is Slack's item object: the channel and ts of the message reacted to" in {
        val json =
            """{"type":"reaction_added","user":"U123ABC456","reaction":"thumbsup","item_user":"U222222222","item":{"type":"message","channel":"C123ABC456","ts":"1360782400.498405"},"event_ts":"1360782804.083113"}"""
        val event = SlackEvent.ReactionAdded(
            SlackId.UserId("U123ABC456"),
            "thumbsup",
            SlackEvent.ReactionAdded.Item(SlackId.ChannelId("C123ABC456"), SlackTs("1360782400.498405"))
        )
        assert(Json.decode[SlackEvent.ReactionAdded](json) == Result.succeed(event))
        assert(Json.encode(event) ==
            """{"user":"U123ABC456","reaction":"thumbsup","item":{"channel":"C123ABC456","ts":"1360782400.498405"}}""")
    }

    "an app_home_opened event's tab is Absent when Slack names none" in {
        val withTab = """{"type":"app_home_opened","user":"U123ABC456","channel":"D123ABC456","event_ts":"1515449522000016","tab":"home"}"""
        val withoutTab = """{"type":"app_home_opened","user":"U123ABC456","channel":"D123ABC456"}"""
        assert(Json.decode[SlackEvent.AppHomeOpened](withTab).map(_.tab) == Result.succeed(Present("home")))
        assert(Json.decode[SlackEvent.AppHomeOpened](withoutTab) == Result.succeed(
            SlackEvent.AppHomeOpened(SlackId.UserId("U123ABC456"), SlackId.ChannelId("D123ABC456"))
        ))
        assert(Json.encode(SlackEvent.AppHomeOpened(SlackId.UserId("U1"), SlackId.ChannelId("D1"))) == """{"user":"U1","channel":"D1"}""")
    }

    "a member_joined_channel event's inviter is the user who added the member" in {
        val json =
            """{"type":"member_joined_channel","user":"W123ABC456","channel":"C123ABC456","channel_type":"C","team":"T123ABC456","inviter":"U123456789"}"""
        val event = SlackEvent.MemberJoinedChannel(
            SlackId.UserId("W123ABC456"),
            SlackId.ChannelId("C123ABC456"),
            Present(SlackId.UserId("U123456789"))
        )
        assert(Json.decode[SlackEvent.MemberJoinedChannel](json) == Result.succeed(event))
        assert(Json.encode(event) == """{"user":"W123ABC456","channel":"C123ABC456","inviter":"U123456789"}""")
    }

end SlackEventTest
