package kyo

class SlackViewTest extends kyo.test.Test[Any]:

    "a modal view encodes title/submit/close as plain_text, renders typed blocks, and carries the controls" in {
        val view = SlackView(
            SlackView.Type.Modal,
            callbackId = Present("cb1"),
            title = Present("T"),
            submit = Present("Go"),
            close = Present("Cancel"),
            privateMetadata = Present("meta-123"),
            notifyOnClose = true,
            blocks = Chunk(SlackBlock.Input("Your name", SlackBlock.Element.TextInput(SlackId.ActionId("name"))))
        )
        assert(
            Json.encode(Slack.encodeView(view)) ==
                """{"type":"modal","callback_id":"cb1","blocks":[{"type":"input","label":{"type":"plain_text","text":"Your name","emoji":true},"element":{"type":"plain_text_input","action_id":"name","multiline":false},"optional":false}],"title":{"type":"plain_text","text":"T"},"submit":{"type":"plain_text","text":"Go"},"close":{"type":"plain_text","text":"Cancel"},"private_metadata":"meta-123","notify_on_close":true}"""
        )
    }

    "a home view omits title/submit/close AND notify_on_close (which views.publish rejects)" in {
        // notify_on_close is a modal-only field; views.publish answers invalid_arguments when a home view carries it.
        assert(
            Json.encode(Slack.encodeView(SlackView(SlackView.Type.Home, blocks = Chunk(SlackBlock.Header("Home"))))) ==
                """{"type":"home","blocks":[{"type":"header","text":{"type":"plain_text","text":"Home","emoji":true}}]}"""
        )
    }

    "SlackView.Type maps the closed set to/from its wire string and preserves Unknown" in {
        assert(Json.encode(SlackView.Type.Modal: SlackView.Type) == "\"modal\"")
        assert(Json.encode(SlackView.Type.Home: SlackView.Type) == "\"home\"")
        assert(Json.encode(SlackView.Type.Unknown("workflow_step"): SlackView.Type) == "\"workflow_step\"")
        assert(Json.decode[SlackView.Type]("\"home\"") == Result.Success(SlackView.Type.Home))
        assert(Json.decode[SlackView.Type]("\"modal\"") == Result.Success(SlackView.Type.Modal))
        assert(Json.decode[SlackView.Type]("\"new_kind\"") == Result.Success(SlackView.Type.Unknown("new_kind")))
    }

end SlackViewTest
