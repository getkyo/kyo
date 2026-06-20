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

    /** Client capability advertisement sent in the `initialize` request. */
    final case class Client(
        sampling: Maybe[SamplingCapability] = Absent,
        roots: Maybe[RootsCapability] = Absent,
        elicitation: Maybe[ElicitationCapability] = Absent,
        experimental: Map[String, Structure.Value] = Map.empty
    ) derives Schema, CanEqual

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

        // capitalize maps the lowercase wire string to the Scala case name: "tools" -> "Tools".
        given Schema[Name] =
            Schema.stringSchema.transform(s => Name.valueOf(s.capitalize))(_.toString.toLowerCase)

    end Name

end McpCapabilities
