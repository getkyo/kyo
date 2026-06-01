package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspCatalogTest extends Test:

    // Helper: build a minimal server-handled request handler
    private def serverHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.mkReq[Unit, Unit](name, kind, _ => ())

    private def notifHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.mkNotif[Unit](name, kind, _ => ())

    private def clientHandler(kind: LspHandler.Kind, name: String): LspHandler[Unit, Unit, LspException] =
        LspHandler.mkReq[Unit, Unit](name, kind, _ => ())

    // Client-handled handler: window/showMessage
    private def showMessageHandler: LspHandler[LspHandler.ShowMessageParams, Unit, LspException] =
        LspHandler.mkNotif[LspHandler.ShowMessageParams]("window/showMessage", LspHandler.Kind.ShowMessage, _ => ())

    "LspCatalogTest" - {

        "INV-006: WrongDirection - ClientHandled handler on server init throws WrongDirection" in {
            val h = showMessageHandler
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.WrongDirection])
        }

        "INV-006: Direction.Either handler is accepted on server" in {
            val h = LspHandler.mkNotif[Unit]("$/cancelRequest", LspHandler.Kind.CancelRequest, _ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isSuccess)
        }

        "INV-006: Direction.Either handler is accepted on client" in {
            val h = LspHandler.mkNotif[Unit]("$/cancelRequest", LspHandler.Kind.CancelRequest, _ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ClientHandled)
            )
            assert(result.isSuccess)
        }

        "INV-039/INV-082: Reserved method in CustomHandler throws ReservedMethod" in {
            val h = LspHandler.custom[Unit, Unit]("initialize")(_ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.ReservedMethod])
        }

        "INV-039: All reserved method names are blocked" in {
            val reserved = Seq("initialize", "initialized", "shutdown", "exit", "$/cancelRequest", "$/progress", "$/setTrace")
            reserved.foreach { name =>
                val h = LspHandler.custom[Unit, Unit](name)(_ => ())
                val result = scala.util.Try(
                    LspCatalog.fromHandlers(Seq(h), LspHandler.Direction.ServerHandled)
                )
                assert(result.isFailure, s"Expected failure for reserved '$name'")
                assert(result.failed.get.isInstanceOf[LspException.Dispatch.ReservedMethod], s"Wrong exception for '$name'")
            }
            succeed
        }

        "INV-047: Duplicate Kind (non-Custom) throws DuplicateHandler" in {
            val h1 = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val h2 = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.DuplicateHandler])
        }

        "INV-047: Duplicate Custom name throws DuplicateHandler" in {
            val h1 = LspHandler.custom[Unit, Unit]("vendor/foo")(_ => ())
            val h2 = LspHandler.custom[Unit, Unit]("vendor/foo")(_ => ())
            val result = scala.util.Try(
                LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.DuplicateHandler])
        }

        "INV-041: Auto-derive capabilities when declaredServerCapabilities = Absent" in {
            val h1      = serverHandler(LspHandler.Kind.Completion, "textDocument/completion")
            val h2      = serverHandler(LspHandler.Kind.Definition, "textDocument/definition")
            val catalog = LspCatalog.fromHandlers(Seq(h1, h2), LspHandler.Direction.ServerHandled)
            val config  = LspConfig.default
            val caps    = catalog.autoDeriveServerCapabilities(config)
            assert(caps.completionProvider.isDefined)
            assert(caps.definitionProvider.isDefined)
            assert(!caps.hoverProvider.isDefined)
        }

        "INV-041: Declared capabilities returned verbatim when Present" in {
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
