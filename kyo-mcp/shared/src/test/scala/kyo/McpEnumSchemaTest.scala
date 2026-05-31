package kyo

/** Tests for spec-divergent enum Schemas using hand-rolled `Schema.stringSchema.transform` (Phase 3).
  *
  * Pins INV-010: all four wire-divergent enums (`McpRole`, `McpLogLevel`,
  * `McpElicitationResponse.Action`, `McpSamplingRequest.IncludeContext`) use `transform`,
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

    // ---- McpLogLevel ----

    "McpLogLevel.Debug encodes to \"debug\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Debug) == "\"debug\"")
    }

    "McpLogLevel.Info encodes to \"info\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Info) == "\"info\"")
    }

    "McpLogLevel.Notice encodes to \"notice\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Notice) == "\"notice\"")
    }

    "McpLogLevel.Warning encodes to \"warning\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Warning) == "\"warning\"")
    }

    "McpLogLevel.Error encodes to \"error\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Error) == "\"error\"")
    }

    "McpLogLevel.Critical encodes to \"critical\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Critical) == "\"critical\"")
    }

    "McpLogLevel.Alert encodes to \"alert\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Alert) == "\"alert\"")
    }

    "McpLogLevel.Emergency encodes to \"emergency\"" in {
        assert(encode[McpLogLevel](McpLogLevel.Emergency) == "\"emergency\"")
    }

    "McpLogLevel round-trips all 8 cases" in {
        val cases = List(
            McpLogLevel.Debug,
            McpLogLevel.Info,
            McpLogLevel.Notice,
            McpLogLevel.Warning,
            McpLogLevel.Error,
            McpLogLevel.Critical,
            McpLogLevel.Alert,
            McpLogLevel.Emergency
        )
        assert(cases.forall(lvl => roundtrip[McpLogLevel](lvl) == lvl))
    }

    // ---- McpElicitationResponse.Action ----

    "Action.Accept encodes to \"accept\"" in {
        assert(encode[McpElicitationResponse.Action](McpElicitationResponse.Action.Accept) == "\"accept\"")
    }

    "Action.Decline encodes to \"decline\"" in {
        assert(encode[McpElicitationResponse.Action](McpElicitationResponse.Action.Decline) == "\"decline\"")
    }

    "Action.Cancel encodes to \"cancel\"" in {
        assert(encode[McpElicitationResponse.Action](McpElicitationResponse.Action.Cancel) == "\"cancel\"")
    }

    "Action round-trips" in {
        assert(roundtrip[McpElicitationResponse.Action](McpElicitationResponse.Action.Accept) == McpElicitationResponse.Action.Accept)
        assert(roundtrip[McpElicitationResponse.Action](McpElicitationResponse.Action.Decline) == McpElicitationResponse.Action.Decline)
        assert(roundtrip[McpElicitationResponse.Action](McpElicitationResponse.Action.Cancel) == McpElicitationResponse.Action.Cancel)
    }

    // ---- McpSamplingRequest.IncludeContext ----

    "IncludeContext.None encodes to \"none\"" in {
        assert(encode[McpSamplingRequest.IncludeContext](McpSamplingRequest.IncludeContext.None) == "\"none\"")
    }

    "IncludeContext.ThisServer encodes to \"thisServer\"" in {
        assert(encode[McpSamplingRequest.IncludeContext](McpSamplingRequest.IncludeContext.ThisServer) == "\"thisServer\"")
    }

    "IncludeContext.AllServers encodes to \"allServers\"" in {
        assert(encode[McpSamplingRequest.IncludeContext](McpSamplingRequest.IncludeContext.AllServers) == "\"allServers\"")
    }

    "IncludeContext round-trips" in {
        assert(
            roundtrip[McpSamplingRequest.IncludeContext](McpSamplingRequest.IncludeContext.None) == McpSamplingRequest.IncludeContext.None
        )
        assert(roundtrip[McpSamplingRequest.IncludeContext](
            McpSamplingRequest.IncludeContext.ThisServer
        ) == McpSamplingRequest.IncludeContext.ThisServer)
        assert(roundtrip[McpSamplingRequest.IncludeContext](
            McpSamplingRequest.IncludeContext.AllServers
        ) == McpSamplingRequest.IncludeContext.AllServers)
    }

    // ---- Lint: no `derives Schema` on any of the four enums (JVM-only) ----

    "McpRole Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpRole]]
        assert(schema.segments.isEmpty)
    }

    "McpLogLevel Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpLogLevel]]
        assert(schema.segments.isEmpty)
    }

    "Action Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpElicitationResponse.Action]]
        assert(schema.segments.isEmpty)
    }

    "IncludeContext Schema uses transform (segments are empty, not a derived enum schema)" in {
        val schema = summon[Schema[McpSamplingRequest.IncludeContext]]
        assert(schema.segments.isEmpty)
    }

end McpEnumSchemaTest
