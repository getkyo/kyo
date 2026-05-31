package kyo.internal.mcp

import kyo.*

/** Engine-side wire shape for the `roots/list` JSON-RPC response.
  *
  * Used internally by the roots route; not part of the user-facing API surface.
  */
final private[kyo] case class McpRootsListResponse(
    roots: Chunk[McpServer.Root]
) derives Schema
