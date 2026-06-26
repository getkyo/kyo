package kyo

/** Tests that each Schema instance for the MCP enum/opaque types is a singleton val.
  *
  * Two consecutive `summon[Schema[T]]` calls must return `eq` references. This holds for `given`
  * declared as `val` (or as a `def` returning a stable `val`). A `given` that creates a new
  * instance on each call (e.g. `def` creating a new Schema) would fail these assertions.
  *
  * Package declaration is `kyo` to allow access to `private[kyo]` members via opaque types.
  */
class SchemaSingletonTest extends Test:

    "Schema[McpContent] is a singleton val" in {
        val s1 = summon[Schema[McpContent]]
        val s2 = summon[Schema[McpContent]]
        assert(s1 eq s2)
    }

    "Schema[McpContent.Role] is a singleton val" in {
        val s1 = summon[Schema[McpContent.Role]]
        val s2 = summon[Schema[McpContent.Role]]
        assert(s1 eq s2)
    }

    "Schema[McpServer.LogLevel] is a singleton val" in {
        val s1 = summon[Schema[McpServer.LogLevel]]
        val s2 = summon[Schema[McpServer.LogLevel]]
        assert(s1 eq s2)
    }

    "Schema[McpServer.ElicitationResponse.Action] is a singleton val" in {
        val s1 = summon[Schema[McpServer.ElicitationResponse.Action]]
        val s2 = summon[Schema[McpServer.ElicitationResponse.Action]]
        assert(s1 eq s2)
    }

    "Schema[McpServer.SamplingRequest.IncludeContext] is a singleton val" in {
        val s1 = summon[Schema[McpServer.SamplingRequest.IncludeContext]]
        val s2 = summon[Schema[McpServer.SamplingRequest.IncludeContext]]
        assert(s1 eq s2)
    }

    "Schema[McpConfig.ProtocolVersion] is a singleton val" in {
        val s1 = summon[Schema[McpConfig.ProtocolVersion]]
        val s2 = summon[Schema[McpConfig.ProtocolVersion]]
        assert(s1 eq s2)
    }

end SchemaSingletonTest
