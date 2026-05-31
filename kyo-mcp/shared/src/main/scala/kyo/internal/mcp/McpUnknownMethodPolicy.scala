package kyo.internal.mcp

import kyo.*

/** MCP-specific unknown-method policy: uses the strict preset.
  *
  * The strict preset replies `MethodNotFound` on unknown requests and rejects unknown
  * notifications (Q-016). MCP servers should not silently discard unrecognized notifications
  * because the protocol is versioned; an unexpected notification indicates a protocol mismatch.
  */
private[kyo] object McpUnknownMethodPolicy:

    val default: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy.strict

end McpUnknownMethodPolicy
