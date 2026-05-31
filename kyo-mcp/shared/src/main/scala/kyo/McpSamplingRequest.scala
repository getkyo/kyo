package kyo

/** Parameters for the `sampling/createMessage` reverse-direction request.
  *
  * The `metadata` field is an INV-021 allowlist pass-through: the MCP spec defines
  * `_meta` as an open JSON object, so the user receives it as `Structure.Value` rather
  * than a typed shape.
  *
  * @param messages          the conversation turns to continue
  * @param modelPreferences  optional model selection hints and cost/speed/intelligence weights
  * @param systemPrompt      optional system prompt to include
  * @param includeContext    whether to include server/client context in the message
  * @param temperature       sampling temperature hint
  * @param maxTokens         maximum tokens for the sampled response
  * @param stopSequences     sequences that halt generation early
  * @param metadata          spec-defined open `_meta` field (INV-021 allowlist pass-through per §11a)
  */
// flow-allow: Structure carve-out per §11a / INV-021
final case class McpSamplingRequest(
    messages: Chunk[McpSamplingRequest.Message],
    modelPreferences: Maybe[McpSamplingRequest.ModelPreferences] = Absent,
    systemPrompt: Maybe[String] = Absent,
    includeContext: Maybe[McpSamplingRequest.IncludeContext] = Absent,
    temperature: Maybe[Double] = Absent,
    maxTokens: Int,
    stopSequences: Chunk[String] = Chunk.empty,
    // flow-allow: Structure carve-out per §11a / INV-021
    metadata: Maybe[Structure.Value] = Absent
) derives Schema, CanEqual

object McpSamplingRequest:

    /** A single message in the sampling conversation. */
    final case class Message(role: McpRole, content: McpContent) derives Schema, CanEqual

    /** Hints for model selection. */
    final case class ModelPreferences(
        hints: Chunk[ModelHint] = Chunk.empty,
        costPriority: Maybe[Double] = Absent,
        speedPriority: Maybe[Double] = Absent,
        intelligencePriority: Maybe[Double] = Absent
    ) derives Schema, CanEqual

    /** A model name hint for selection. */
    final case class ModelHint(name: Maybe[String] = Absent) derives Schema, CanEqual

    /** Controls how much context from the server or all servers is included. */
    enum IncludeContext derives CanEqual:
        case None, ThisServer, AllServers

    object IncludeContext:
        // Wire strings use camelCase and do not match toString.toLowerCase (INV-010).
        // None is qualified as IncludeContext.None to avoid shadowing scala.None.
        given Schema[IncludeContext] = Schema.stringSchema.transform[IncludeContext] {
            case "none"       => IncludeContext.None
            case "thisServer" => IncludeContext.ThisServer
            case "allServers" => IncludeContext.AllServers
        } {
            case IncludeContext.None       => "none"
            case IncludeContext.ThisServer => "thisServer"
            case IncludeContext.AllServers => "allServers"
        }
    end IncludeContext

end McpSamplingRequest
