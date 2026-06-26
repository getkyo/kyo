package kyo

/** Opaque pagination cursor for MCP list methods.
  *
  * A cursor is an engine-minted continuation token returned in `McpClient.Page.nextCursor`;
  * it is fed back unmodified to the next `listX(cursor = ...)` call. There is no public
  * constructor: a cursor is never user-minted, mirroring `McpConfig.ProtocolVersion`. Use
  * `asString` only for logging or storage; the engine decodes it via `fromWire`.
  */
opaque type McpCursor = String

object McpCursor:

    /** Wire decoder (engine-only): wraps a server-supplied cursor string. */
    private[kyo] def fromWire(s: String): McpCursor = s

    extension (c: McpCursor)
        /** Returns the underlying cursor string. */
        def asString: String = c

    given Schema[McpCursor] = Schema.stringSchema.transform[McpCursor](fromWire)(_.asString)

    given CanEqual[McpCursor, McpCursor] = CanEqual.derived

end McpCursor
