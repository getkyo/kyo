package kyo

import kyo.internal.mcp.McpUnknownMethodPolicy

/** Tests for McpUnknownMethodPolicy.default (Phase 4, Rule 8c).
  *
  * Pins that the MCP unknown-method policy delegates to the strict preset,
  * which replies MethodNotFound on unknown requests and rejects unknown
  * notifications (Q-016 rationale: unexpected notifications indicate protocol mismatch).
  */
class McpUnknownMethodPolicyTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val policy = McpUnknownMethodPolicy.default

    "McpUnknownMethodPolicy.default is the strict preset (eq check)" in {
        assert(policy eq JsonRpcUnknownMethodPolicy.strict)
    }

    "onUnknownRequest is ReplyMethodNotFound" in {
        assert(policy.onUnknownRequest == JsonRpcUnknownMethodPolicy.UnknownAction.ReplyMethodNotFound)
    }

    "onUnknownNotification is Reject (strict mode)" in {
        assert(policy.onUnknownNotification == JsonRpcUnknownMethodPolicy.UnknownAction.Reject)
    }

end McpUnknownMethodPolicyTest
