package kyo.internal

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.internal.lsp.*

class LspEngineTest extends Test:

    "LspEngineTest" - {

        "INV-030: cancellation policy is installed (cancelMethod = $/cancelRequest)" in {
            // Verify the policy constant independently (no need for full engine init).
            assert(LspCancellationPolicy.default.cancelMethod == "$/cancelRequest")
        }

        "INV-030: progress policy is installed (progressMethod = $/progress)" in {
            assert(LspProgressPolicy.default.progressMethod == "$/progress")
        }

        "INV-031: expectReplyForCancelledRequest is true (LSP diverges from MCP)" in {
            assert(LspCancellationPolicy.default.expectReplyForCancelledRequest)
        }

        "INV-059: gate compose order is handshake -> shutdown -> capability" in {
            // Compose with a handshake gate that is not yet initialized.
            // Send a non-initialize request; should be rejected by handshake, not reached by capability.
            val caps       = LspCapabilities.Server.empty.copy(completionProvider = Present(LspHandler.CompletionOptions()))
            val handshake  = LspHandshakeGate.server()
            val handlerRef = AtomicRef.Unsafe.init[Maybe[JsonRpcHandler]](Absent)(using AllowUnsafe.embrace.danger).safe
            val shutdown   = LspShutdownGate.server(handlerRef)
            val capability = LspCapabilityGate.server(caps, enforce = true)
            val composed   = LspGate.compose(handshake, shutdown, capability)
            // completion IS in caps, so if composition were wrong order (capability before handshake),
            // this would Allow. Since handshake goes first, it should Reject.
            val env = JsonRpcRequest(JsonRpcId(1), "textDocument/completion", Absent, Absent)
            Sync.Unsafe.evalOrThrow(composed.beforeDispatch(env)) match
                case JsonRpcMessageGate.Decision.Reject(response) =>
                    // The rejection carries a JsonRpcResponse; verify it is an error response (error field is Present).
                    assert(response.error.isDefined, s"Expected error response, got: $response")
                case JsonRpcMessageGate.Decision.Allow => fail("Should have been rejected by handshake gate")
                case JsonRpcMessageGate.Decision.Drop  => fail("Should have been rejected by handshake gate")
            end match
        }

        "LspCatalog.fromHandlers WrongDirection error message contains Kind name" in {
            val h = LspHandler.initNotification[LspHandler.ShowMessageParams, Nothing](
                "window/showMessage",
                LspHandler.Kind.ShowMessage,
                _ => ()
            )
            val err = scala.util.Try(LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)).failed.get
            assert(err.getMessage.contains("ShowMessage"))
        }

        "LspCatalog.fromHandlers with empty handlers succeeds" in {
            val catalog = LspCatalog.fromHandlers(Seq.empty, LspHandler.Direction.ServerHandled)
            assert(catalog.handlers.isEmpty)
        }

    }

end LspEngineTest
