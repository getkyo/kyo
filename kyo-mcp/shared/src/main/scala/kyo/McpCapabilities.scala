package kyo

/** MCP capability advertisement records for both server and client roles.
  *
  * `Server` is sent in the `initialize` response; `Client` is sent in the `initialize` request.
  * The `experimental` field in both records is typed as `Map[String, Structure.Value]` because
  * the MCP spec defines experimental capabilities as an open JSON object.
  */
object McpCapabilities:

    /** Server capability advertisement sent in the `initialize` response.
      *
      * Fields default to `Absent`; the engine auto-derives from registered routes when
      * `McpConfig.declaredCapabilities` is `Absent`.
      */
    final case class Server(
        tools: Maybe[ToolsCapability] = Absent,
        resources: Maybe[ResourcesCapability] = Absent,
        prompts: Maybe[PromptsCapability] = Absent,
        logging: Maybe[LoggingCapability] = Absent,
        completions: Maybe[CompletionsCapability] = Absent,
        experimental: Map[String, Structure.Value] = Map.empty
    ) derives Schema, CanEqual

    object Server:
        /** Advertises the named server capabilities with their default sub-records. A capability
          * whose name is absent from `names` stays `Absent`. Use the case-class form directly for a
          * non-default sub-record (for example `ResourcesCapability(subscribe = true)`).
          */
        def of(names: Name*): Server =
            Server(
                tools = Maybe.when(names.contains(Name.Tools))(ToolsCapability()),
                resources = Maybe.when(names.contains(Name.Resources))(ResourcesCapability()),
                prompts = Maybe.when(names.contains(Name.Prompts))(PromptsCapability()),
                logging = Maybe.when(names.contains(Name.Logging))(LoggingCapability()),
                completions = Maybe.when(names.contains(Name.Completions))(CompletionsCapability())
            )
    end Server

    /** Client capability advertisement sent in the `initialize` request. */
    final case class Client(
        sampling: Maybe[SamplingCapability] = Absent,
        roots: Maybe[RootsCapability] = Absent,
        elicitation: Maybe[ElicitationCapability] = Absent,
        experimental: Map[String, Structure.Value] = Map.empty
    ) derives Schema, CanEqual

    object Client:
        /** Advertises the named client capabilities with their default sub-records. A capability
          * whose name is absent from `names` stays `Absent`.
          */
        def of(names: Name*): Client =
            Client(
                sampling = Maybe.when(names.contains(Name.Sampling))(SamplingCapability()),
                roots = Maybe.when(names.contains(Name.Roots))(RootsCapability()),
                elicitation = Maybe.when(names.contains(Name.Elicitation))(ElicitationCapability())
            )
    end Client

    final case class ToolsCapability(listChanged: Boolean = false) derives Schema, CanEqual
    final case class ResourcesCapability(subscribe: Boolean = false, listChanged: Boolean = false) derives Schema, CanEqual
    final case class PromptsCapability(listChanged: Boolean = false) derives Schema, CanEqual
    final case class LoggingCapability() derives Schema, CanEqual
    final case class CompletionsCapability() derives Schema, CanEqual
    final case class SamplingCapability() derives Schema, CanEqual
    final case class RootsCapability(listChanged: Boolean = false) derives Schema, CanEqual
    final case class ElicitationCapability() derives Schema, CanEqual

    /** Closed set of MCP capability names exchanged during handshake advertisement.
      *
      * Wire strings are lowercase Scala case-name spellings (`"tools"`, `"resources"`, `"prompts"`,
      * `"sampling"`, `"roots"`, `"logging"`, `"completions"`, `"elicitation"`). The Schema is
      * hand-rolled via `Schema.stringSchema.transform` (not `derives Schema`).
      *
      * Used by [[McpCapabilityNotAdvertisedException.requiredCapability]] and internal capability gating
      * so the public surface never carries a raw `String` for these closed-set values.
      */
    enum Name derives CanEqual:
        case Tools, Resources, Prompts, Sampling, Roots, Logging, Completions, Elicitation

    object Name:

        // Total: an unknown capability wire string decodes to Result.Failure, never a valueOf panic.
        given Schema[Name] = internal.mcp.McpEnumSchema.closed[Name](
            "tools"       -> Name.Tools,
            "resources"   -> Name.Resources,
            "prompts"     -> Name.Prompts,
            "sampling"    -> Name.Sampling,
            "roots"       -> Name.Roots,
            "logging"     -> Name.Logging,
            "completions" -> Name.Completions,
            "elicitation" -> Name.Elicitation
        )

    end Name

end McpCapabilities
