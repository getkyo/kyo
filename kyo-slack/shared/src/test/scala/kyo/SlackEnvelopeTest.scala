package kyo

class SlackEnvelopeTest extends kyo.test.Test[Any]:

    "an envelope case has no Schema, and asking for one names why: the module decodes Slack's frames itself" in {
        val reason = "has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        typeCheckFailure("summon[Schema[SlackEnvelope.SlashCommand]]")(s"SlackEnvelope $reason")
    }

    "a hello frame holds Slack's connection_info and debug_info, and appId and debugHost read them" in {
        val json =
            """{"type":"hello","num_connections":1,"debug_info":{"host":"applink-7fc4fdbb64-4x5xq","build_number":10,"approximate_connection_time":18060},"connection_info":{"app_id":"A01K58AR4RF"}}"""
        val hello = SlackEnvelope.Hello(
            1,
            SlackEnvelope.Hello.ConnectionInfo(SlackId.AppId("A01K58AR4RF")),
            Present(SlackEnvelope.Hello.DebugInfo(Present("applink-7fc4fdbb64-4x5xq")))
        )
        assert(Json.decode[SlackEnvelope.Hello](json) == Result.succeed(hello))
        assert(hello.appId == SlackId.AppId("A01K58AR4RF"))
        assert(hello.debugHost == Present("applink-7fc4fdbb64-4x5xq"))
        assert(Json.encode(hello) ==
            """{"num_connections":1,"connection_info":{"app_id":"A01K58AR4RF"},"debug_info":{"host":"applink-7fc4fdbb64-4x5xq"}}""")
    }

    "a hello frame's debugHost is Absent when debug_info is missing or names no host, and encoding leaves the key out" in {
        val noDebug    = """{"type":"hello","num_connections":2,"connection_info":{"app_id":"A1"}}"""
        val noHost     = """{"type":"hello","num_connections":2,"debug_info":{"build_number":10},"connection_info":{"app_id":"A1"}}"""
        val connection = SlackEnvelope.Hello.ConnectionInfo(SlackId.AppId("A1"))
        val hello      = SlackEnvelope.Hello(2, connection)
        assert(Json.decode[SlackEnvelope.Hello](noDebug) == Result.succeed(hello))
        assert(Json.decode[SlackEnvelope.Hello](noHost) == Result.succeed(
            SlackEnvelope.Hello(2, connection, Present(SlackEnvelope.Hello.DebugInfo()))
        ))
        assert(Json.decode[SlackEnvelope.Hello](noHost).map(_.debugHost) == Result.succeed(Absent))
        assert(Json.encode(hello) == """{"num_connections":2,"connection_info":{"app_id":"A1"}}""")
    }

    "a hello frame without connection_info.app_id fails to decode as a missing field" in {
        val json   = """{"type":"hello","num_connections":1,"connection_info":{}}"""
        val result = Json.decode[SlackEnvelope.Hello](json)
        assert(result.failure.exists(_.isInstanceOf[MissingFieldException]), result.toString)
    }

end SlackEnvelopeTest
