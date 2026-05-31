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

    // Phase 1 stub; Phase 3 replaces with stringSchema.transform
    // Wire strings: "endTurn" | "stopSequence" | "maxTokens"
    given Schema[McpStopReason] = new Schema[McpStopReason](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpStopReason, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpStopReason.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpStopReason =
            throw new NotImplementedError("McpStopReason.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpStopReason): Maybe[Any]               = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpStopReason, next: Any): McpStopReason = v

end McpStopReason
