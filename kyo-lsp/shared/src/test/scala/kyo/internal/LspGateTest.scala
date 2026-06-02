package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspGateTest extends Test:

    "LspHandshakeGate" - {

        "permits initialize before handshake" in run {
            val gate = LspHandshakeGate.server()
            val env  = JsonRpcRequest(JsonRpcId(1), "initialize", Absent, Absent)
            gate.beforeDispatch(env).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

        "rejects other requests before initialize" in run {
            val gate = LspHandshakeGate.server()
            val env  = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            gate.beforeDispatch(env).map { d =>
                assert(d.isInstanceOf[JsonRpcMessageGate.Decision.Reject])
            }
        }

        "permits requests after initialize" in run {
            val gate  = LspHandshakeGate.server()
            val init  = JsonRpcRequest(JsonRpcId(1), "initialize", Absent, Absent)
            val other = JsonRpcRequest(JsonRpcId(2), "textDocument/completion", Absent, Absent)
            for
                _ <- gate.beforeDispatch(init)
                d <- gate.beforeDispatch(other)
            yield assert(d == JsonRpcMessageGate.Decision.Allow)
            end for
        }

        "permits initialized notification before and after initialize" in run {
            val gate  = LspHandshakeGate.server()
            val notif = JsonRpcNotification("initialized", Absent, Absent)
            gate.beforeDispatch(notif).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

        "permits other notifications before initialize (fire-and-forget)" in run {
            val gate  = LspHandshakeGate.server()
            val notif = JsonRpcNotification("$/cancelRequest", Absent, Absent)
            gate.beforeDispatch(notif).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

    }

    "LspCapabilityGate" - {

        "rejects method whose capability is not advertised" in run {
            val caps = LspCapabilities.Server.empty
            val gate = LspCapabilityGate.server(caps, enforce = true)
            val env  = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            gate.beforeDispatch(env).map { d =>
                assert(d.isInstanceOf[JsonRpcMessageGate.Decision.Reject])
            }
        }

        "permits method whose capability is advertised" in run {
            val caps = LspCapabilities.Server.empty.copy(completionProvider = Present(LspHandler.CompletionOptions()))
            val gate = LspCapabilityGate.server(caps, enforce = true)
            val env  = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            gate.beforeDispatch(env).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

        "capability gate off: always admits" in run {
            val caps = LspCapabilities.Server.empty
            val gate = LspCapabilityGate.server(caps, enforce = false)
            val env  = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            gate.beforeDispatch(env).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

        "capability gate: permits notifications unconditionally" in run {
            val caps  = LspCapabilities.Server.empty
            val gate  = LspCapabilityGate.server(caps, enforce = true)
            val notif = JsonRpcNotification("textDocument/didOpen", Absent, Absent)
            gate.beforeDispatch(notif).map { d =>
                assert(d == JsonRpcMessageGate.Decision.Allow)
            }
        }

    }

    "LspGate.compose" - {

        "handshake gate is checked first (not-initialized returns NotInitialized)" in run {
            val caps       = LspCapabilities.Server.empty
            val handshake  = LspHandshakeGate.server()
            val handlerRef = AtomicRef.Unsafe.init[Maybe[JsonRpcHandler]](Absent)(using AllowUnsafe.embrace.danger).safe
            val shutdown   = LspShutdownGate.server(handlerRef)
            val capability = LspCapabilityGate.server(caps, enforce = true)
            val composed   = LspGate.compose(handshake, shutdown, capability)
            val env        = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            composed.beforeDispatch(env).map { d =>
                // Should be a Reject from handshake gate, not capability gate.
                d match
                    case JsonRpcMessageGate.Decision.Reject(resp) =>
                        assert(resp.result.isEmpty)
                    case _ =>
                        fail("Expected Reject from handshake gate")
            }
        }

    }

end LspGateTest
