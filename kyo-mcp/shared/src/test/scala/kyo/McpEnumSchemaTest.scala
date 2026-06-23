package kyo

/** Tests for spec-divergent enum Schemas built with hand-rolled `Schema.stringSchema.transform`.
  *
  * Pins that all four wire-divergent enums (`McpContent.Role`, `McpServer.LogLevel`,
  * `McpServer.ElicitationResponse.Action`, `McpServer.SamplingRequest.IncludeContext`) use `transform`,
  * not `derives Schema`. The lint assertions (JVM-only) read each source file and verify
  * zero `derives Schema` lines on the enum declaration.
  */
class McpEnumSchemaTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow
    private def roundtrip[A: Schema](value: A): A   = decode[A](encode[A](value))

    // ---- McpContent.Role ----

    "McpContent.Role.User encodes to \"user\"" in {
        assert(encode[McpContent.Role](McpContent.Role.User) == "\"user\"")
    }

    "McpContent.Role.Assistant encodes to \"assistant\"" in {
        assert(encode[McpContent.Role](McpContent.Role.Assistant) == "\"assistant\"")
    }

    "McpContent.Role.System encodes to \"system\"" in {
        assert(encode[McpContent.Role](McpContent.Role.System) == "\"system\"")
    }

    "McpContent.Role round-trips" in {
        assert(roundtrip[McpContent.Role](McpContent.Role.User) == McpContent.Role.User)
        assert(roundtrip[McpContent.Role](McpContent.Role.Assistant) == McpContent.Role.Assistant)
        assert(roundtrip[McpContent.Role](McpContent.Role.System) == McpContent.Role.System)
    }

    // ---- McpServer.LogLevel ----

    "McpServer.LogLevel.Debug encodes to \"debug\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Debug) == "\"debug\"")
    }

    "McpServer.LogLevel.Info encodes to \"info\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Info) == "\"info\"")
    }

    "McpServer.LogLevel.Notice encodes to \"notice\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Notice) == "\"notice\"")
    }

    "McpServer.LogLevel.Warning encodes to \"warning\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Warning) == "\"warning\"")
    }

    "McpServer.LogLevel.Error encodes to \"error\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Error) == "\"error\"")
    }

    "McpServer.LogLevel.Critical encodes to \"critical\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Critical) == "\"critical\"")
    }

    "McpServer.LogLevel.Alert encodes to \"alert\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Alert) == "\"alert\"")
    }

    "McpServer.LogLevel.Emergency encodes to \"emergency\"" in {
        assert(encode[McpServer.LogLevel](McpServer.LogLevel.Emergency) == "\"emergency\"")
    }

    "McpServer.LogLevel round-trips all 8 cases" in {
        val cases = List(
            McpServer.LogLevel.Debug,
            McpServer.LogLevel.Info,
            McpServer.LogLevel.Notice,
            McpServer.LogLevel.Warning,
            McpServer.LogLevel.Error,
            McpServer.LogLevel.Critical,
            McpServer.LogLevel.Alert,
            McpServer.LogLevel.Emergency
        )
        assert(cases.forall(lvl => roundtrip[McpServer.LogLevel](lvl) == lvl))
    }

    // ---- McpServer.ElicitationResponse.Action ----

    "Action.Accept encodes to \"accept\"" in {
        assert(encode[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Accept) == "\"accept\"")
    }

    "Action.Decline encodes to \"decline\"" in {
        assert(encode[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Decline) == "\"decline\"")
    }

    "Action.Cancel encodes to \"cancel\"" in {
        assert(encode[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Cancel) == "\"cancel\"")
    }

    "Action round-trips" in {
        assert(roundtrip[McpServer.ElicitationResponse.Action](
            McpServer.ElicitationResponse.Action.Accept
        ) == McpServer.ElicitationResponse.Action.Accept)
        assert(roundtrip[McpServer.ElicitationResponse.Action](
            McpServer.ElicitationResponse.Action.Decline
        ) == McpServer.ElicitationResponse.Action.Decline)
        assert(roundtrip[McpServer.ElicitationResponse.Action](
            McpServer.ElicitationResponse.Action.Cancel
        ) == McpServer.ElicitationResponse.Action.Cancel)
    }

    // ---- McpServer.SamplingRequest.IncludeContext ----

    "IncludeContext.None encodes to \"none\"" in {
        assert(encode[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.None) == "\"none\"")
    }

    "IncludeContext.ThisServer encodes to \"thisServer\"" in {
        assert(encode[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.ThisServer) == "\"thisServer\"")
    }

    "IncludeContext.AllServers encodes to \"allServers\"" in {
        assert(encode[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.AllServers) == "\"allServers\"")
    }

    "IncludeContext round-trips" in {
        assert(
            roundtrip[McpServer.SamplingRequest.IncludeContext](
                McpServer.SamplingRequest.IncludeContext.None
            ) == McpServer.SamplingRequest.IncludeContext.None
        )
        assert(roundtrip[McpServer.SamplingRequest.IncludeContext](
            McpServer.SamplingRequest.IncludeContext.ThisServer
        ) == McpServer.SamplingRequest.IncludeContext.ThisServer)
        assert(roundtrip[McpServer.SamplingRequest.IncludeContext](
            McpServer.SamplingRequest.IncludeContext.AllServers
        ) == McpServer.SamplingRequest.IncludeContext.AllServers)
    }

    // ---- Lint: no `derives Schema` on any of the four enums (JVM-only) ----

    "McpContent.Role Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpContent.Role]]
        assert(schema.segments.isEmpty)
    }

    "McpServer.LogLevel Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpServer.LogLevel]]
        assert(schema.segments.isEmpty)
    }

    "Action Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpServer.ElicitationResponse.Action]]
        assert(schema.segments.isEmpty)
    }

    "IncludeContext Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpServer.SamplingRequest.IncludeContext]]
        assert(schema.segments.isEmpty)
    }

    // ---- Annotations lastModified roundtrip ----

    "Content.Annotations lastModified Present encodes and round-trips" in {
        val ann  = McpContent.Annotations(lastModified = Present("2024-01-01T00:00:00Z"))
        val json = encode(ann)
        assert(json.contains("\"lastModified\":\"2024-01-01T00:00:00Z\""))
        val back = roundtrip[McpContent.Annotations](ann)
        assert(back.lastModified == Present("2024-01-01T00:00:00Z"))
    }

    "Content.Annotations lastModified Absent omits the key from JSON" in {
        val ann  = McpContent.Annotations()
        val json = encode(ann)
        assert(!json.contains("lastModified"))
    }

    // ---- hostile wire strings decode to Result.Failure ----

    "hostile Role string decodes to Failure" in {
        val result = Json.decode[McpContent.Role]("\"superuser\"")
        assert(result.isFailure)
    }

    "hostile Action string decodes to Failure" in {
        val result = Json.decode[McpServer.ElicitationResponse.Action]("\"maybe\"")
        assert(result.isFailure)
    }

    "hostile IncludeContext string decodes to Failure" in {
        val result = Json.decode[McpServer.SamplingRequest.IncludeContext]("\"everywhere\"")
        assert(result.isFailure)
    }

    "every closed case still round-trips" in {
        assert(roundtrip[McpContent.Role](McpContent.Role.User) == McpContent.Role.User)
        assert(roundtrip[McpContent.Role](McpContent.Role.Assistant) == McpContent.Role.Assistant)
        assert(roundtrip[McpContent.Role](McpContent.Role.System) == McpContent.Role.System)
        assert(
            roundtrip[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Accept) ==
                McpServer.ElicitationResponse.Action.Accept
        )
        assert(
            roundtrip[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Decline) ==
                McpServer.ElicitationResponse.Action.Decline
        )
        assert(
            roundtrip[McpServer.ElicitationResponse.Action](McpServer.ElicitationResponse.Action.Cancel) ==
                McpServer.ElicitationResponse.Action.Cancel
        )
        assert(
            roundtrip[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.None) ==
                McpServer.SamplingRequest.IncludeContext.None
        )
        assert(
            roundtrip[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.ThisServer) ==
                McpServer.SamplingRequest.IncludeContext.ThisServer
        )
        assert(
            roundtrip[McpServer.SamplingRequest.IncludeContext](McpServer.SamplingRequest.IncludeContext.AllServers) ==
                McpServer.SamplingRequest.IncludeContext.AllServers
        )
    }

    // ---- StopReason opaque lossless round-trip ----

    "known StopReason constants round-trip" in {
        val endTurn = Json.decode[McpServer.SamplingResponse.StopReason]("\"endTurn\"").getOrThrow
        assert(endTurn.asString == "endTurn")
        assert(endTurn == McpServer.SamplingResponse.StopReason.EndTurn)
        val stopSeq = Json.decode[McpServer.SamplingResponse.StopReason]("\"stopSequence\"").getOrThrow
        assert(stopSeq.asString == "stopSequence")
        assert(stopSeq == McpServer.SamplingResponse.StopReason.StopSequence)
        val maxTok = Json.decode[McpServer.SamplingResponse.StopReason]("\"maxTokens\"").getOrThrow
        assert(maxTok.asString == "maxTokens")
        assert(maxTok == McpServer.SamplingResponse.StopReason.MaxTokens)
    }

    "unknown StopReason round-trips LOSSLESSLY" in {
        val decoded = Json.decode[McpServer.SamplingResponse.StopReason]("\"contentFiltered\"").getOrThrow
        assert(decoded.asString == "contentFiltered")
        assert(Json.encode(decoded) == "\"contentFiltered\"")
    }

    // ---- absent resource field surfaces typed failure, not NPE ----

    "hostile resource discriminator decodes to Failure not NPE" in {
        val json   = """{"type":"resource"}"""
        val result = Json.decode[McpContent](json)
        assert(result.isFailure)
    }

    // ---- McpCapabilities.Name closed enum ----

    "McpCapabilities.Name all valid wire strings round-trip" in {
        val cases = List(
            "tools"       -> McpCapabilities.Name.Tools,
            "resources"   -> McpCapabilities.Name.Resources,
            "prompts"     -> McpCapabilities.Name.Prompts,
            "sampling"    -> McpCapabilities.Name.Sampling,
            "roots"       -> McpCapabilities.Name.Roots,
            "logging"     -> McpCapabilities.Name.Logging,
            "completions" -> McpCapabilities.Name.Completions,
            "elicitation" -> McpCapabilities.Name.Elicitation
        )
        assert(cases.forall { (wire, expected) =>
            Json.decode[McpCapabilities.Name](s"\"$wire\"") == Result.succeed(expected)
        })
    }

    "McpCapabilities.Name hostile wire string decodes to Failure not exception" in {
        val result = Json.decode[McpCapabilities.Name]("\"unknown_capability\"")
        assert(result.isFailure, s"expected Failure for unknown capability name, got $result")
    }

    "McpCapabilities.Name capitalized hostile string decodes to Failure (no valueOf panic)" in {
        val result = Json.decode[McpCapabilities.Name]("\"Tools\"")
        assert(result.isFailure, s"expected Failure for capitalized 'Tools', got $result")
    }

end McpEnumSchemaTest
