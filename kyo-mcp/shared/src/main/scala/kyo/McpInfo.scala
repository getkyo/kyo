package kyo

/** Identifies a client or server in the MCP handshake.
  *
  * The `version` field defaults to `"0.0.0"` per Audit-B2; callers that ship a real version
  * supply it explicitly. Both fields are free-form strings matching the MCP spec shape for
  * `clientInfo` / `serverInfo`.
  *
  * @param name    human-readable name of the implementation
  * @param version version string; defaults to `"0.0.0"` (Audit-B2)
  */
final case class McpInfo(name: String, version: String = "0.0.0") derives Schema, CanEqual
