package kyo

class SlackInteractionTest extends kyo.test.Test[Any]:

    // Built from parts so the secret's literal is off the source lines a failure renders.
    private val secret = Seq("SECRET", "ACTION", "URL", "7d02").mkString("-")
    private val url    = SlackResponseUrl(s"https://hooks.slack.com/actions/T1/B1/$secret")

    "BlockActions and MessageAction render their response_url redacted, alone and in their envelope" in {
        val click = SlackInteraction.BlockActions(
            SlackInteraction.User(SlackId.UserId("U1")),
            SlackId.TriggerId("T1"),
            Present(SlackId.ChannelId("C1")),
            Absent,
            Chunk(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1"))),
            Present(SlackTs("1.2")),
            Present(url)
        )
        val action = SlackInteraction.MessageAction(
            SlackInteraction.User(SlackId.UserId("U1")),
            SlackId.TriggerId("T1"),
            "cb1",
            SlackId.ChannelId("C1"),
            SlackTs("1.2"),
            Present(url)
        )
        val envelopeId = SlackId.EnvelopeId("E1")
        val rendered   =
            Chunk(click, action).map(_.toString) ++ Chunk(click, action).map(SlackEnvelope.Interactive(envelopeId, _).toString)
        assert(rendered.forall(r => r.contains("SlackResponseUrl(<redacted>)") && !r.contains(secret)), rendered.toString)
    }

    "an action decodes from Slack's block_actions action object, and value is Absent when Slack sends none" in {
        val button =
            """{"action_id":"approve","block_id":"b-1","text":{"type":"plain_text","text":"Approve","emoji":true},"value":"click_me_123","type":"button","action_ts":"1548426417.840180"}"""
        val select = """{"action_id":"pick","block_id":"b-2","type":"static_select","action_ts":"1548426417.840181"}"""
        assert(Json.decode[SlackInteraction.Action](button) == Result.succeed(
            SlackInteraction.Action(SlackId.ActionId("approve"), SlackId.BlockId("b-1"), Present("click_me_123"))
        ))
        assert(Json.decode[SlackInteraction.Action](select) == Result.succeed(
            SlackInteraction.Action(SlackId.ActionId("pick"), SlackId.BlockId("b-2"))
        ))
        assert(Json.encode(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1"))) ==
            """{"action_id":"a1","block_id":"b1"}""")
    }

    "a view_closed payload holds Slack's user and view objects, and viewId reads the view's id" in {
        val json =
            """{"type":"view_closed","team":{"id":"T1","domain":"acme"},"user":{"id":"U123","username":"ann","team_id":"T1"},"view":{"id":"V123","type":"modal","callback_id":"cb"},"api_app_id":"A1","is_cleared":false}"""
        val closed = SlackInteraction.ViewClosed(
            SlackInteraction.User(SlackId.UserId("U123")),
            SlackInteraction.ViewClosed.View(SlackId.ViewId("V123")),
            isCleared = false
        )
        assert(Json.decode[SlackInteraction.ViewClosed](json) == Result.succeed(closed))
        assert(closed.viewId == SlackId.ViewId("V123"))
        assert(Json.encode(closed) == """{"user":{"id":"U123"},"view":{"id":"V123"},"is_cleared":false}""")
    }

    "a view_closed payload without its user's id fails to decode, naming the missing key" in {
        val json   = """{"type":"view_closed","user":{"username":"ann"},"view":{"id":"V123"},"is_cleared":true}"""
        val result = Json.decode[SlackInteraction.ViewClosed](json)
        assert(result.failure.exists { case e: MissingFieldException => e.fieldName == "id"; case _ => false }, result.toString)
    }

    "a global shortcut payload decodes its user, trigger_id and callback_id" in {
        val json =
            """{"type":"shortcut","token":"XXXXXXXXXXXXX","action_ts":"1581106241.371594","team":{"id":"TXXXXXXXX","domain":"shortcuts-test"},"user":{"id":"UXXXXXXXXX","username":"aman","team_id":"TXXXXXXXX"},"callback_id":"shortcut_create_task","trigger_id":"944799105734.773906753841.38b5894552bdd4a780554ee59d1f3638"}"""
        val shortcut = SlackInteraction.Shortcut(
            SlackInteraction.User(SlackId.UserId("UXXXXXXXXX")),
            SlackId.TriggerId("944799105734.773906753841.38b5894552bdd4a780554ee59d1f3638"),
            "shortcut_create_task"
        )
        assert(Json.decode[SlackInteraction.Shortcut](json) == Result.succeed(shortcut))
        assert(
            Json.encode(shortcut) ==
                """{"user":{"id":"UXXXXXXXXX"},"trigger_id":"944799105734.773906753841.38b5894552bdd4a780554ee59d1f3638","callback_id":"shortcut_create_task"}"""
        )
    }

end SlackInteractionTest
