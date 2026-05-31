package kyo

/** Closed set of MCP capability names exchanged during handshake advertisement.
  *
  * Wire strings are lowercase Scala case-name spellings (`"tools"`, `"resources"`, `"prompts"`,
  * `"sampling"`, `"roots"`, `"logging"`, `"completions"`, `"elicitation"`). Per Q-006 / INV-010
  * the Schema is hand-rolled via `Schema.stringSchema.transform` (not `derives Schema`).
  *
  * Used by [[McpCapabilityNotAdvertisedException.requiredCapability]] and internal capability gating
  * so the public surface never carries a raw `String` for these closed-set values.
  */
enum McpCapabilityName derives CanEqual:
    case Tools, Resources, Prompts, Sampling, Roots, Logging, Completions, Elicitation

object McpCapabilityName:

    // Wire strings: lowercase Scala case-name spellings (Q-006 / INV-010).
    // capitalize maps the lowercase wire string to the Scala case name: "tools" -> "Tools".
    given Schema[McpCapabilityName] =
        Schema.stringSchema.transform(s => McpCapabilityName.valueOf(s.capitalize))(_.toString.toLowerCase)

end McpCapabilityName
