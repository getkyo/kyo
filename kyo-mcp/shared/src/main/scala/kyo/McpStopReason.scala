package kyo

/** MCP sampling stop reason.
  *
  * Wire strings use camelCase: `"endTurn"` | `"stopSequence"` | `"maxTokens"`.
  * Phase 3 replaces the Schema stub with `Schema.stringSchema.transform` per Q-006.
  * Do NOT add `Schema` to the `derives` clause.
  */
enum McpStopReason derives CanEqual:
    case EndTurn, StopSequence, MaxTokens

object McpStopReason:

    // Wire strings use camelCase and do not match toString.toLowerCase (INV-010).
    // Explicit match on both sides is required.
    given Schema[McpStopReason] = Schema.stringSchema.transform[McpStopReason] {
        case "endTurn"      => McpStopReason.EndTurn
        case "stopSequence" => McpStopReason.StopSequence
        case "maxTokens"    => McpStopReason.MaxTokens
    } {
        case McpStopReason.EndTurn      => "endTurn"
        case McpStopReason.StopSequence => "stopSequence"
        case McpStopReason.MaxTokens    => "maxTokens"
    }

end McpStopReason
