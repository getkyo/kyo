package kyo

class SlackEventTest extends kyo.test.Test[Any]:

    "Message decodes from its flat JSON leaf" in {
        val json    = """{"channel":"C1","user":"U1","text":"hi","ts":"1.2"}"""
        val decoded = Json.decode[SlackEvent.Message](json)
        assert(decoded == Result.Success(
            SlackEvent.Message(
                SlackId.ChannelId("C1"),
                SlackId.UserId("U1"),
                "hi",
                SlackTs("1.2"),
                Absent
            )
        ))
    }

    "Message with threadTs decodes the optional field" in {
        val json    = """{"channel":"C1","user":"U1","text":"hi","ts":"1.2","threadTs":"1.0"}"""
        val decoded = Json.decode[SlackEvent.Message](json)
        assert(decoded.getOrElse(SlackEvent.Message(
            SlackId.ChannelId(""),
            SlackId.UserId(""),
            "",
            SlackTs(""),
            Absent
        )).threadTs == Present(SlackTs("1.0")))
    }

    "AppMention/ReactionAdded/AppHomeOpened/MemberJoinedChannel each decode" in {
        val appMention = Json.decode[SlackEvent.AppMention](
            """{"channel":"C1","user":"U1","text":"hi","ts":"1.2"}"""
        )
        assert(appMention == Result.Success(
            SlackEvent.AppMention(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2"))
        ))

        val reactionAdded = Json.decode[SlackEvent.ReactionAdded](
            """{"user":"U1","reaction":"thumbsup","itemChannel":"C1","itemTs":"1.2"}"""
        )
        assert(reactionAdded == Result.Success(
            SlackEvent.ReactionAdded(SlackId.UserId("U1"), "thumbsup", SlackId.ChannelId("C1"), SlackTs("1.2"))
        ))

        val appHomeOpened = Json.decode[SlackEvent.AppHomeOpened](
            """{"user":"U1","channel":"C1","tab":"home"}"""
        )
        assert(appHomeOpened == Result.Success(
            SlackEvent.AppHomeOpened(SlackId.UserId("U1"), SlackId.ChannelId("C1"), "home")
        ))

        val memberJoined = Json.decode[SlackEvent.MemberJoinedChannel](
            """{"user":"U1","channel":"C1"}"""
        )
        assert(memberJoined == Result.Success(
            SlackEvent.MemberJoinedChannel(SlackId.UserId("U1"), SlackId.ChannelId("C1"), Absent)
        ))
    }

    "Unknown is constructible and preserves raw event JSON" in {
        val u = SlackEvent.Unknown("team_join", "{\"user\":{\"id\":\"U9\"}}")
        assert(u.`type` == "team_join")
        assert(u.eventJson == "{\"user\":{\"id\":\"U9\"}}")
    }

end SlackEventTest
