package kyo

/** Public records for the MCP server-to-client notifications a client can receive.
  *
  * These mirror the engine's wire shapes so a caller registering an `onLog` /
  * `onResourceUpdated` sink never re-declares the private wire records by hand. The fields
  * are typed: `Log.level` is the `McpServer.LogLevel` enum (not a raw `String`), and
  * `ResourceUpdated.uri` is the opaque `McpResourceUri` (not a raw `String`).
  */
object McpNotification:

    /** A `notifications/message` payload (server-to-client structured log). */
    final case class Log(level: McpServer.LogLevel, data: Structure.Value, logger: Maybe[String] = Absent) derives Schema, CanEqual

    /** A `notifications/resources/updated` payload. */
    final case class ResourceUpdated(uri: McpResourceUri) derives Schema, CanEqual

end McpNotification
