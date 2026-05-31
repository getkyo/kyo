package kyo

/** MCP conversation role.
  *
  * Wire strings diverge from Scala case names: `"user"` / `"assistant"` / `"system"` (lowercase).
  * Phase 3 replaces the Schema stub with `Schema.stringSchema.transform` per Q-006 / INV-010.
  * Do NOT add `Schema` to the `derives` clause.
  */
enum McpRole derives CanEqual:
    case User, Assistant, System

object McpRole:

    // Phase 1 stub; Phase 3 replaces with:
    //   Schema.stringSchema.transform(s => McpRole.valueOf(s.capitalize))(_.toString.toLowerCase)
    // Wire strings: "user" | "assistant" | "system"
    given Schema[McpRole] = new Schema[McpRole](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpRole, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpRole.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpRole =
            throw new NotImplementedError("McpRole.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpRole): Maybe[Any]         = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpRole, next: Any): McpRole = v

end McpRole
