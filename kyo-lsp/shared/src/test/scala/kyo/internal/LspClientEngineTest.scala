package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspClientEngineTest extends Test:

    "LspClientEngineTest" - {

        "LspClientEngine.initClient catalog rejects wrong-direction handlers" in {
            // A server-handled handler passed to client-side init should throw WrongDirection.
            val serverHandler = LspHandler.initRequest[LspHandler.CompletionParams, Maybe[LspHandler.CompletionResult], Nothing](
                "textDocument/completion",
                LspHandler.Kind.Completion,
                _ => ???
            )
            val err = scala.util.Try(
                LspCatalog.fromHandlers(Seq(serverHandler), LspHandler.Direction.ClientHandled)
            ).failed.get
            assert(err.isInstanceOf[LspException.Dispatch.WrongDirection])
        }

        "LspClientEngine.initClient catalog accepts empty handlers" in {
            val catalog = LspCatalog.fromHandlers(Seq.empty, LspHandler.Direction.ClientHandled)
            assert(catalog.handlers.isEmpty)
        }

        "LspClientEngine.initClient catalog accepts client-direction handlers" in {
            val clientHandler = LspHandler.initNotification[LspHandler.ShowMessageParams, Nothing](
                "window/showMessage",
                LspHandler.Kind.ShowMessage,
                _ => ()
            )
            val catalog = LspCatalog.fromHandlers(Seq(clientHandler), LspHandler.Direction.ClientHandled)
            assert(catalog.handlers.size == 1)
        }

    }

end LspClientEngineTest
