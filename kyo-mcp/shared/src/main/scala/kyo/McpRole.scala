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

    // Wire strings: "user" | "assistant" | "system" (INV-010).
    // capitalize maps lowercase wire string to Scala case name: "user" -> "User".
    given Schema[McpRole] = Schema.stringSchema.transform(s => McpRole.valueOf(s.capitalize))(
        _.toString.toLowerCase
    )

end McpRole
