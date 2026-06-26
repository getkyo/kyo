package kyo

/** Identifies a client or server in the MCP handshake.
  *
  * The `version` field defaults to `"0.0.0"`; callers that ship a real version supply it
  * explicitly. Both fields are free-form strings matching the MCP spec shape for `clientInfo` /
  * `serverInfo`. The `title` field is the optional human-readable display name added in
  * MCP 2025-06-18 §3.20; it is omitted from the wire when `Absent`.
  *
  * @param name    human-readable name of the implementation
  * @param version version string; defaults to `"0.0.0"`
  * @param title   optional display title for the implementation (§3.20)
  */
final case class McpInfo(name: String, version: String = "0.0.0", title: Maybe[String] = Absent) derives Schema, CanEqual
