package kyo

class SlackInteractionTest extends kyo.test.Test[Any]:

    "BlockActions decodes with its actions chunk" in {
        val json    = """{"user":"U1","triggerId":"T1","channel":"C1","actions":[{"actionId":"a1","blockId":"b1","value":"v1"}]}"""
        val decoded = Json.decode[SlackInteraction.BlockActions](json)
        assert(decoded == Result.Success(
            SlackInteraction.BlockActions(
                SlackId.UserId("U1"),
                SlackId.TriggerId("T1"),
                Present(SlackId.ChannelId("C1")),
                Chunk(SlackInteraction.Action("a1", "b1", Present("v1")))
            )
        ))
    }

    "ViewSubmission/ViewClosed/Shortcut/MessageAction each decode" in {
        val viewSub = Json.decode[SlackInteraction.ViewSubmission](
            """{"user":"U1","viewId":"V1","stateJson":"{}"}"""
        )
        assert(viewSub == Result.Success(
            SlackInteraction.ViewSubmission(SlackId.UserId("U1"), SlackId.ViewId("V1"), "{}")
        ))

        val viewClosed = Json.decode[SlackInteraction.ViewClosed](
            """{"user":"U1","viewId":"V1","isCleared":false}"""
        )
        assert(viewClosed == Result.Success(
            SlackInteraction.ViewClosed(SlackId.UserId("U1"), SlackId.ViewId("V1"), false)
        ))

        val shortcut = Json.decode[SlackInteraction.Shortcut](
            """{"user":"U1","triggerId":"T1","callbackId":"cb1"}"""
        )
        assert(shortcut == Result.Success(
            SlackInteraction.Shortcut(SlackId.UserId("U1"), SlackId.TriggerId("T1"), "cb1")
        ))

        val msgAction = Json.decode[SlackInteraction.MessageAction](
            """{"user":"U1","triggerId":"T1","callbackId":"cb1","channel":"C1","messageTs":"1.2"}"""
        )
        assert(msgAction == Result.Success(
            SlackInteraction.MessageAction(
                SlackId.UserId("U1"),
                SlackId.TriggerId("T1"),
                "cb1",
                SlackId.ChannelId("C1"),
                SlackId.Ts("1.2")
            )
        ))
    }

    "Unknown preserves the raw payload JSON" in {
        val u = SlackInteraction.Unknown("workflow_step_edit", "{\"x\":1}")
        assert(u.`type` == "workflow_step_edit")
        assert(u.payloadJson == "{\"x\":1}")
    }

end SlackInteractionTest
