package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspCatalogTest extends Test:

    // Helper: build a minimal server-handled request handler
    private def serverHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.initRequest[Unit, Unit, Nothing](name, kind, _ => ())

    private def notifHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.initNotification[Unit, Nothing](name, kind, _ => ())

    private def clientHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.initRequest[Unit, Unit, Nothing](name, kind, _ => ())

    // Client-handled handler: window/showMessage
    private def showMessageHandler: LspHandler[LspHandler.ShowMessageParams, Unit, LspException] =
        LspHandler.initNotification[LspHandler.ShowMessageParams, Nothing]("window/showMessage", LspHandler.Kind.ShowMessage, _ => ())

    "LspCatalogTest" - {

        "ClientHandled handler on server init throws WrongDirection" in {
            val h = showMessageHandler
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspWrongDirectionException])
        }

        "Direction.Either handler is accepted on server" in {
            val h = LspHandler.initNotification[Unit, Nothing]("$/cancelRequest", LspHandler.Kind.CancelRequest, _ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isSuccess)
        }

        "Direction.Either handler is accepted on client" in {
            val h = LspHandler.initNotification[Unit, Nothing]("$/cancelRequest", LspHandler.Kind.CancelRequest, _ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ClientHandled)
            )
            assert(result.isSuccess)
        }

        "Reserved method in CustomHandler throws ReservedMethod" in {
            val h = LspHandler.custom[Unit]("initialize")(_ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspReservedMethodException])
        }

        "All reserved method names are blocked" in {
            val reserved = Seq("initialize", "initialized", "shutdown", "exit", "$/cancelRequest", "$/progress", "$/setTrace")
            reserved.foreach { name =>
                val h = LspHandler.custom[Unit](name)(_ => ())
                val result = scala.util.Try(
                    LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
                )
                assert(result.isFailure, s"Expected failure for reserved '$name'")
                assert(result.failed.get.isInstanceOf[LspReservedMethodException], s"Wrong exception for '$name'")
            }
            succeed
        }

        "Duplicate Kind (non-Custom) throws DuplicateHandler" in {
            val h1 = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val h2 = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspDuplicateHandlerException])
        }

        "Duplicate Custom name throws DuplicateHandler" in {
            val h1 = LspHandler.custom[Unit]("vendor/foo")(_ => ())
            val h2 = LspHandler.custom[Unit]("vendor/foo")(_ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspDuplicateHandlerException])
        }

        "Auto-derive capabilities when declaredServerCapabilities = Absent" in {
            val h1      = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val h2      = serverHandler(LspHandler.Kind.Definition, "textDocument/definition")
            val catalog = LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            val config  = LspConfig.default
            val caps    = catalog.autoDeriveServerCapabilities(config)
            assert(caps.completionProvider.isDefined)
            assert(caps.definitionProvider.isDefined)
            assert(!caps.hoverProvider.isDefined)
        }

        "Declared capabilities returned verbatim when Present" in {
            val declared = LspCapabilities.Server.empty
            val config   = LspConfig.default.withDeclaredServerCapabilities(declared)
            val catalog  = LspCatalog.fromHandlers(Seq.empty, LspHandler.Direction.ServerHandled)
            val caps     = catalog.autoDeriveServerCapabilities(config)
            assert(caps == declared)
        }

        "handlerFor returns Present for registered Kind" in {
            val h       = serverHandler(LspHandler.Kind.Hover, "textDocument/hover")
            val catalog = LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            assert(catalog.handlerFor(LspHandler.Kind.Hover).isDefined)
            assert(!catalog.handlerFor(LspHandler.Kind.Completion).isDefined)
        }

        "empty catalog has no handlers" in {
            val catalog = LspCatalog.fromHandlers(Seq.empty, LspHandler.Direction.ServerHandled)
            assert(catalog.handlers.isEmpty)
        }

    }

end LspCatalogTest
