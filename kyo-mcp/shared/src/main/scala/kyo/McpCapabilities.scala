package kyo

/** MCP capability advertisement records for both server and client roles.
  *
  * `Server` is sent in the `initialize` response; `Client` is sent in the `initialize` request.
  * The `experimental` field in both records is typed as `Map[String, Structure.Value]` because
  * the MCP spec defines experimental capabilities as an open JSON object.
  *
  * INV-021 allowlist: `experimental: Map[String, Structure.Value]` on both `Server` and `Client`
  * are documented pass-throughs per §11a.
  */
// flow-allow: Structure carve-out per §11a / INV-021
object McpCapabilities:

    /** Server capability advertisement sent in the `initialize` response.
      *
      * Fields default to `Absent`; the engine auto-derives from registered routes when
      * `McpConfig.declaredCapabilities` is `Absent` (Q-004 / INV-019).
      */
    final case class Server(
        tools: Maybe[ToolsCapability] = Absent,
        resources: Maybe[ResourcesCapability] = Absent,
        prompts: Maybe[PromptsCapability] = Absent,
        logging: Maybe[LoggingCapability] = Absent,
        completions: Maybe[CompletionsCapability] = Absent,
        // flow-allow: Structure carve-out per §11a / INV-021
        experimental: Map[String, Structure.Value] = Map.empty
    ) derives Schema, CanEqual

    /** Client capability advertisement sent in the `initialize` request. */
    final case class Client(
        sampling: Maybe[SamplingCapability] = Absent,
        roots: Maybe[RootsCapability] = Absent,
        elicitation: Maybe[ElicitationCapability] = Absent,
        // flow-allow: Structure carve-out per §11a / INV-021
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

end McpCapabilities
