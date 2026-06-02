package kyo.internal

import kyo.*
import kyo.internal.mcp.McpHandshakeGate

/** Tests for the two-stage MCP handshake gate. */
class McpHandshakeGateTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val initReq: JsonRpcEnvelope =
        JsonRpcRequest(JsonRpcId(1L), "initialize", Absent, Absent)

    private val initNotif: JsonRpcEnvelope =
        JsonRpcNotification("notifications/initialized", Absent, Absent)

    private val pingReq: JsonRpcEnvelope =
        JsonRpcRequest(JsonRpcId(2L), "ping", Absent, Absent)

    private val toolsReq: JsonRpcEnvelope =
        JsonRpcRequest(JsonRpcId(3L), "tools/list", Absent, Absent)

    private val cancelNotif: JsonRpcEnvelope =
        JsonRpcNotification("notifications/cancelled", Absent, Absent)

    private val arbitraryReq: JsonRpcEnvelope =
        JsonRpcRequest(JsonRpcId(4L), "arbitrary/method", Absent, Absent)

    "pre-handshake: initialize request is admitted" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(initReq).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "pre-handshake: notifications/initialized notification is admitted (sets flag)" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(initNotif).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "pre-handshake: ping request is always admitted" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(pingReq).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "pre-handshake: tools/list request is rejected before initialize" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(toolsReq).map {
            case JsonRpcMessageGate.Decision.Reject(resp) =>
                assert(resp.error.isDefined)
            case other => fail(s"expected Reject, got $other")
        }
    }

    "pre-handshake: notifications/cancelled is admitted (notifications never rejected)" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(cancelNotif).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "pre-handshake: arbitrary request is rejected before initialize" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(arbitraryReq).map {
            case JsonRpcMessageGate.Decision.Reject(_) => assert(true)
            case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RequireInitializedNotification: after both flags set, all six messages are admitted" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        for
            _  <- gate.beforeDispatch(initReq)
            _  <- gate.beforeDispatch(initNotif)
            d1 <- gate.beforeDispatch(initReq)
            d2 <- gate.beforeDispatch(initNotif)
            d3 <- gate.beforeDispatch(pingReq)
            d4 <- gate.beforeDispatch(toolsReq)
            d5 <- gate.beforeDispatch(cancelNotif)
            d6 <- gate.beforeDispatch(arbitraryReq)
        yield
            assert(d1 == JsonRpcMessageGate.Decision.Allow)
            assert(d2 == JsonRpcMessageGate.Decision.Allow)
            assert(d3 == JsonRpcMessageGate.Decision.Allow)
            assert(d4 == JsonRpcMessageGate.Decision.Allow)
            assert(d5 == JsonRpcMessageGate.Decision.Allow)
            assert(d6 == JsonRpcMessageGate.Decision.Allow)
        end for
    }

    "RequireInitializedNotification: tools/list rejected after initialize-req but before initialized-notif" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        gate.beforeDispatch(initReq).flatMap { _ =>
            gate.beforeDispatch(toolsReq).map {
                case JsonRpcMessageGate.Decision.Reject(_) => assert(true)
                case other                                 => fail(s"expected Reject, got $other")
            }
        }
    }

    "RequireInitializeRequestOnly: tools/list admitted after initialize-req without initialized-notif" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializeRequestOnly)
        gate.beforeDispatch(initReq).flatMap { _ =>
            gate.beforeDispatch(toolsReq).map { dec =>
                assert(dec == JsonRpcMessageGate.Decision.Allow)
            }
        }
    }

    "rejection response carries the correct request id" in run {
        val gate = McpHandshakeGate.server(McpConfig.HandshakeOrder.RequireInitializedNotification)
        val req  = JsonRpcRequest(JsonRpcId(99L), "tools/call", Absent, Absent)
        gate.beforeDispatch(req).map {
            case JsonRpcMessageGate.Decision.Reject(resp) =>
                assert(resp.id == JsonRpcId(99L))
            case other => fail(s"expected Reject, got $other")
        }
    }

end McpHandshakeGateTest
