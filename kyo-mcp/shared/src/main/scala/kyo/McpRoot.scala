package kyo

/** A root entry returned by the client in response to a `roots/list` request.
  *
  * INV-022: `uri` is typed `McpResourceUri`, not raw `String`, per Audit-A2.
  *
  * @param uri  the root URI
  * @param name optional human-readable name for the root
  */
final case class McpRoot(uri: McpResourceUri, name: Maybe[String] = Absent) derives Schema, CanEqual
