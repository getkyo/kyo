package kyo

/** MCP log level enum with 8 severity levels.
  *
  * Wire strings are lowercase and match the Scala case name lowercase: `"debug"` | `"info"` |
  * `"notice"` | `"warning"` | `"error"` | `"critical"` | `"alert"` | `"emergency"`.
  * Phase 3 replaces the Schema stub with `Schema.stringSchema.transform` per Q-006 / INV-010.
  * Do NOT add `Schema` to the `derives` clause.
  */
enum McpLogLevel derives CanEqual:
    case Debug, Info, Notice, Warning, Error, Critical, Alert, Emergency

object McpLogLevel:

    // Phase 1 stub; Phase 3 replaces with stringSchema.transform
    // Wire strings: "debug" | "info" | "notice" | "warning" | "error" | "critical" | "alert" | "emergency"
    given Schema[McpLogLevel] = new Schema[McpLogLevel](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpLogLevel, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpLogLevel.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpLogLevel =
            throw new NotImplementedError("McpLogLevel.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpLogLevel): Maybe[Any]             = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpLogLevel, next: Any): McpLogLevel = v

end McpLogLevel
