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
        Slack.encodeView(view).map { vb =>
            val json = Json.encode(vb)
            assert(json.contains("\"modal\""), json)
            assert(json.contains("\"callback_id\":\"cb1\""), json)
            assert(json.contains("\"title\":{\"type\":\"plain_text\",\"text\":\"T\"}"), json)
            assert(json.contains("\"submit\":{\"type\":\"plain_text\",\"text\":\"Go\"}"), json)
            assert(json.contains("\"close\":{\"type\":\"plain_text\",\"text\":\"Cancel\"}"), json)
            assert(json.contains("\"private_metadata\":\"meta-123\""), json)
            assert(json.contains("\"notify_on_close\":true"), json)
            assert(json.contains("\"type\":\"input\""), json)
            assert(json.contains("\"type\":\"plain_text_input\""), json)
        }
    }

    "a home view omits title/submit/close AND notify_on_close (which views.publish rejects)" in {
        Slack.encodeView(SlackView(SlackView.Type.Home, blocks = Chunk(SlackBlock.Header("Home")))).map { vb =>
            val json = Json.encode(vb)
            assert(json.contains("\"home\""), json)
            assert(!json.contains("\"submit\""), s"submit should be omitted: $json")
            assert(!json.contains("\"close\""), s"close should be omitted: $json")
            // notify_on_close is a modal-only field; a home view (default notifyOnClose=false) must not carry it,
            // or views.publish fails with invalid_arguments (the live-validation finding).
            assert(!json.contains("notify_on_close"), s"notify_on_close must be omitted when false: $json")
            assert(json.contains("\"type\":\"header\""), json)
        }
    }

    "SlackView.Type maps the closed set to/from its wire string and preserves Unknown" in {
        assert(Json.encode(SlackView.Type.Modal: SlackView.Type).contains("modal"))
        assert(Json.encode(SlackView.Type.Home: SlackView.Type).contains("home"))
        assert(Json.encode(SlackView.Type.Unknown("workflow_step"): SlackView.Type).contains("workflow_step"))
        assert(Json.decode[SlackView.Type]("\"home\"") == Result.Success(SlackView.Type.Home))
        assert(Json.decode[SlackView.Type]("\"modal\"") == Result.Success(SlackView.Type.Modal))
        assert(Json.decode[SlackView.Type]("\"new_kind\"") == Result.Success(SlackView.Type.Unknown("new_kind")))
    }

end SlackViewTest
