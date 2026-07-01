package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspBuiltInRoutesTest extends Test:

    private def encRef(enc: LspHandler.PositionEncodingKind = LspHandler.PositionEncodingKind.UTF16) =
        AtomicRef.Unsafe.init[LspHandler.PositionEncodingKind](enc)(using AllowUnsafe.embrace.danger).safe

    private def noServerRef =
        AtomicRef.Unsafe.init[Maybe[LspServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe

    private def mkRegistry(enc: LspHandler.PositionEncodingKind = LspHandler.PositionEncodingKind.UTF16): LspDocumentRegistryImpl < Sync =
        LspDocumentRegistryImpl.init(encRef(enc))

    private def uri(s: String): LspHandler.LspDocument.Uri = LspHandler.LspDocument.Uri.fromWire(s)

    private def makeTestCtx: JsonRpcRoute.Context < Sync =
        Fiber.Promise.init[Unit, Sync].map(p => JsonRpcRoute.Context.forTest(p, Absent, Absent, Absent))

    /** Builds the `notebookDocument/didChange` wire value (the route's params type is private), so a
      * test can drive the actual route via `route.handle` rather than calling the registry directly.
      */
    private def notebookChangeWire(uri: String, version: Int, change: LspHandler.NotebookDocumentChangeEvent)(
        using Frame
    ): Structure.Value =
        Structure.Value.Record(Chunk(
            "notebookDocument" -> Structure.Value.Record(Chunk(
                "uri"     -> Structure.Value.Str(uri),
                "version" -> Structure.Value.Integer(version.toLong)
            )),
            "change" -> Structure.encode[LspHandler.NotebookDocumentChangeEvent](change)
        ))

    "LspBuiltInRoutesTest" - {

        "textDocumentDidOpen route is named textDocument/didOpen" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.textDocumentDidOpen(registry, Absent, noServerRef, encRef())
                assert(route.name == "textDocument/didOpen")
            }
        }

        "textDocumentDidOpen route inserts the opened document into the registry" in {
            mkRegistry().flatMap { registry =>
                val route  = LspBuiltInRoutes.textDocumentDidOpen(registry, Absent, noServerRef, encRef())
                val docUri = uri("file:///Open.scala")
                val item   = LspHandler.TextDocumentItem(docUri, "scala", 1, "object Open")
                val params = Structure.Value.Record(Chunk("textDocument" -> Structure.encode[LspHandler.TextDocumentItem](item)))
                makeTestCtx.flatMap { ctx =>
                    route.handle(params, ctx).andThen(registry.get(docUri)).map {
                        case Present(doc) =>
                            assert(doc.languageId == "scala")
                            assert(doc.text == "object Open")
                        case Absent =>
                            fail("opened document should have been inserted by the route")
                    }
                }
            }
        }

        "textDocumentDidChange route is named textDocument/didChange" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.textDocumentDidChange(registry, Absent, noServerRef, encRef())
                assert(route.name == "textDocument/didChange")
            }
        }

        "textDocumentDidSave route is named textDocument/didSave" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.textDocumentDidSave(registry, Absent, noServerRef, encRef())
                assert(route.name == "textDocument/didSave")
            }
        }

        "textDocumentDidClose route is named textDocument/didClose" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.textDocumentDidClose(registry, Absent, noServerRef, encRef())
                assert(route.name == "textDocument/didClose")
            }
        }

        "textDocumentWillSave route is named textDocument/willSave" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.textDocumentWillSave(Absent, noServerRef, registry, encRef())
                assert(route.name == "textDocument/willSave")
            }
        }

        "initialize route is named initialize" in {
            val enc  = encRef()
            val caps = AtomicRef.Unsafe.init[Maybe[LspCapabilities.Client.Client]](Absent)(using AllowUnsafe.embrace.danger).safe
            val info = AtomicRef.Unsafe.init[Maybe[LspInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
            val wf   = AtomicRef.Unsafe.init[Maybe[Chunk[LspHandler.WorkspaceFolder]]](Absent)(using AllowUnsafe.embrace.danger).safe
            val route = LspBuiltInRoutes.initialize(
                LspConfig.default,
                LspCapabilities.Server.empty,
                enc,
                caps,
                info,
                wf
            )
            assert(route.name == "initialize")
        }

        "shutdown route is named shutdown" in {
            val route = LspBuiltInRoutes.shutdown()
            assert(route.name == "shutdown")
        }

        "exit route is named exit" in {
            val route = LspBuiltInRoutes.exit()
            assert(route.name == "exit")
        }

        "setTrace route is named $/setTrace" in {
            val traceRef = AtomicRef.Unsafe.init[LspHandler.TraceValue](LspHandler.TraceValue.Off)(using AllowUnsafe.embrace.danger).safe
            val route    = LspBuiltInRoutes.setTrace(traceRef)
            assert(route.name == "$/setTrace")
        }

        "notebookDocument/didOpen route is named correctly" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.notebookDocumentDidOpen(registry, Absent, noServerRef, encRef())
                assert(route.name == "notebookDocument/didOpen")
            }
        }

        "notebookDocument/didClose route is named correctly" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.notebookDocumentDidClose(registry)
                assert(route.name == "notebookDocument/didClose")
            }
        }

        "notebookDocumentDidChange route is named correctly" in {
            mkRegistry().map { registry =>
                val route = LspBuiltInRoutes.notebookDocumentDidChange(registry)
                assert(route.name == "notebookDocument/didChange")
            }
        }

        "notebookDocumentDidChange route inserts newly opened cells into the registry" in {
            mkRegistry().flatMap { registry =>
                val route    = LspBuiltInRoutes.notebookDocumentDidChange(registry)
                val cellUri  = uri("notebook-cell:///nb.ipynb#cell1")
                val cellItem = LspHandler.TextDocumentItem(cellUri, "python", 1, "print('hello')")
                val change = LspHandler.NotebookDocumentChangeEvent(cells =
                    Present(
                        LspHandler.NotebookDocumentChangeEvent.CellChanges(
                            structure = Present(LspHandler.NotebookDocumentChangeEvent.CellStructureChange(
                                array = LspHandler.NotebookCellArrayChange(0, 0),
                                didOpen = Chunk(cellItem),
                                didClose = Chunk.empty
                            )),
                            textContent = Chunk.empty
                        )
                    )
                )
                val params = notebookChangeWire("notebook:///nb.ipynb", 1, change)
                makeTestCtx.flatMap { ctx =>
                    route.handle(params, ctx).andThen(registry.get(cellUri)).map {
                        case Present(doc) =>
                            assert(doc.languageId == "python")
                            assert(doc.text == "print('hello')")
                        case Absent =>
                            fail("Cell document should have been inserted by the route")
                    }
                }
            }
        }

        "notebookDocumentDidChange removes closed cells from registry" in {
            mkRegistry().flatMap { registry =>
                val cellUri  = uri("notebook-cell:///nb.ipynb#cell2")
                val cellItem = LspHandler.TextDocumentItem(cellUri, "scala", 1, "val x = 1")
                registry.insert(cellItem).flatMap { _ =>
                    registry.remove(cellUri).flatMap { _ =>
                        registry.get(cellUri).map {
                            case Absent     => succeed
                            case Present(_) => fail("Cell document should have been removed")
                        }
                    }
                }
            }
        }

        "notebookDocumentDidChange applies text content changes to existing cells" in {
            mkRegistry().flatMap { registry =>
                val cellUri  = uri("notebook-cell:///nb.ipynb#cell3")
                val cellItem = LspHandler.TextDocumentItem(cellUri, "python", 1, "x = 1")
                val change   = LspHandler.TextDocumentContentChangeEvent.Full("x = 2")
                registry.insert(cellItem).flatMap { _ =>
                    registry.applyChanges(cellUri, 2, Chunk(change)).flatMap { _ =>
                        registry.get(cellUri).map {
                            case Present(doc) =>
                                assert(doc.text == "x = 2")
                                assert(doc.version == 2)
                            case Absent =>
                                fail("Cell document should exist after update")
                        }
                    }
                }
            }
        }

    }

end LspBuiltInRoutesTest
