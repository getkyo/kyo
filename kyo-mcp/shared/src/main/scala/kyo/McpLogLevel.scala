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

    // Wire strings: "debug"|"info"|"notice"|"warning"|"error"|"critical"|"alert"|"emergency" (INV-010).
    // capitalize maps lowercase wire string to Scala case name: "debug" -> "Debug".
    given Schema[McpLogLevel] = Schema.stringSchema.transform(s => McpLogLevel.valueOf(s.capitalize))(
        _.toString.toLowerCase
    )

end McpLogLevel
