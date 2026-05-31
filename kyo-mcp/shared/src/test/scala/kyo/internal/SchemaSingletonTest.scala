package kyo

/** Tests that each Phase-3 Schema instance is a singleton val (INV-013).
  *
  * Two consecutive `summon[Schema[T]]` calls must return `eq` references.
  * This holds for `given` declared as `val` (or as a `def` returning a stable `val`).
  * A `given` that creates a new instance on each call (e.g. `def` creating a new Schema)
  * would fail these assertions.
  *
  * Lives under `kyo/internal/` per the INV-009 exemption for internal tests.
  * Package declaration is `kyo` to allow access to `private[kyo]` members via opaque types.
  */
class SchemaSingletonTest extends Test:

    "Schema[McpContent] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpContent]]
        val s2 = summon[Schema[McpContent]]
        assert(s1 eq s2)
    }

    "Schema[McpRole] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpRole]]
        val s2 = summon[Schema[McpRole]]
        assert(s1 eq s2)
    }

    "Schema[McpLogLevel] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpLogLevel]]
        val s2 = summon[Schema[McpLogLevel]]
        assert(s1 eq s2)
    }

    "Schema[McpElicitationResponse.Action] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpElicitationResponse.Action]]
        val s2 = summon[Schema[McpElicitationResponse.Action]]
        assert(s1 eq s2)
    }

    "Schema[McpSamplingRequest.IncludeContext] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpSamplingRequest.IncludeContext]]
        val s2 = summon[Schema[McpSamplingRequest.IncludeContext]]
        assert(s1 eq s2)
    }

    "Schema[McpProtocolVersion] is a singleton val (INV-013)" in {
        val s1 = summon[Schema[McpProtocolVersion]]
        val s2 = summon[Schema[McpProtocolVersion]]
        assert(s1 eq s2)
    }

end SchemaSingletonTest
