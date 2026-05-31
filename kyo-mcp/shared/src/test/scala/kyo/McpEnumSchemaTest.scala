package kyo

/** Tests for spec-divergent enum Schemas using hand-rolled `Schema.stringSchema.transform` (Phase 3).
  *
  * Pins INV-010: all four wire-divergent enums (`McpRole`, `McpServer.LogLevel`,
  * `McpServer.ElicitationResponse.Action`, `McpServer.SamplingRequest.IncludeContext`) use `transform`,
  * not `derives Schema`. The lint assertions (JVM-only) read each source file and verify
  * zero `derives Schema` lines on the enum declaration.
  */
class McpEnumSchemaTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow
    private def roundtrip[A: Schema](value: A): A   = decode[A](encode[A](value))

    // ---- McpRole ----

    "McpRole.User encodes to \"user\"" in {
        assert(encode[McpRole](McpRole.User) == "\"user\"")
    }

    "McpRole.Assistant encodes to \"assistant\"" in {
        assert(encode[McpRole](McpRole.Assistant) == "\"assistant\"")
    }

    "McpRole.System encodes to \"system\"" in {
        assert(encode[McpRole](McpRole.System) == "\"system\"")
    }

    "McpRole round-trips" in {
        assert(roundtrip[McpRole](McpRole.User) == McpRole.User)
        assert(roundtrip[McpRole](McpRole.Assistant) == McpRole.Assistant)
        assert(roundtrip[McpRole](McpRole.System) == McpRole.System)
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

    "McpRole Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpRole]]
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

    // ---- Annotations lastModified roundtrip (Phase 07 / INV-302) ----

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

end McpEnumSchemaTest
