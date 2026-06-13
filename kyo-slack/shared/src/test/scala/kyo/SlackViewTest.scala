package kyo

class SlackViewTest extends kyo.test.Test[Any]:

    "a modal view round-trips, raw blocks preserved" in {
        val view = SlackView(
            SlackView.Type.Modal,
            Present("cb1"),
            "[{\"type\":\"input\"}]",
            Present("{\"type\":\"plain_text\",\"text\":\"T\"}")
        )
        val encoded = Json.encode(view)
        val decoded = Json.decode[SlackView](encoded)
        assert(decoded == Result.Success(view))
        assert(decoded.getOrElse(view).blocksJson == "[{\"type\":\"input\"}]")
        assert(decoded.getOrElse(view).titleJson == Present("{\"type\":\"plain_text\",\"text\":\"T\"}"))
    }

    "SlackView.Type maps the closed set to/from its wire string and preserves Unknown" in {
        val modal   = SlackView(SlackView.Type.Modal, Absent, "[]", Absent)
        val home    = SlackView(SlackView.Type.Home, Absent, "[]", Absent)
        val unknown = SlackView(SlackView.Type.Unknown("workflow_step"), Absent, "[]", Absent)

        val encModal   = Json.encode(modal)
        val encHome    = Json.encode(home)
        val encUnknown = Json.encode(unknown)

        assert(encModal.contains("\"modal\""))
        assert(encHome.contains("\"home\""))
        assert(encUnknown.contains("\"workflow_step\""))

        val decModal   = Json.decode[SlackView](encModal)
        val decHome    = Json.decode[SlackView](encHome)
        val decUnknown = Json.decode[SlackView](encUnknown)

        assert(decModal == Result.Success(modal))
        assert(decHome == Result.Success(home))
        assert(decUnknown == Result.Success(unknown))

        val directDecode = Json.decode[SlackView.Type]("\"home\"")
        assert(directDecode == Result.Success(SlackView.Type.Home))
    }

end SlackViewTest
