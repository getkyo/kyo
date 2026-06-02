package kyo.internal.lsp

import kyo.*

/** Engine-owned `JsonRpcRoute` instances for the LSP lifecycle and text-document sync.
  *
  * Built-in routes (registered ahead of user routes):
  *   - `initialize` + `initialized` handshake
  *   - `shutdown` + `exit` graceful teardown
  *   - `$/setTrace` trace level update
  *   - 5 textDocument sync routes that auto-feed `LspDocumentRegistryImpl` BEFORE invoking
  *     the user-registered handler (if any)
  *   - 4 notebookDocument sync routes that insert cell documents into the same registry
  *
  * The initialize handler performs encoding negotiation, populates the client-capabilities
  * and client-info refs, and returns the server's `InitializeResult` with spec version "3.17".
  *
  * Sync edge-case policies live in `LspDocumentRegistryImpl` mutators; the routes here call
  * those mutators unconditionally.
  */
private[kyo] object LspBuiltInRoutes:

    // Wire shapes for initialize exchange.
    final private case class InitializeParams(
        processId: Maybe[Int] = Absent,
        clientInfo: Maybe[LspInfo] = Absent,
        locale: Maybe[String] = Absent,
        rootUri: Maybe[String] = Absent,
        capabilities: LspCapabilities.Client.Client = LspCapabilities.Client.empty,
        workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]] = Absent,
        private[kyo] _rawInitializationOptions: Maybe[String] = Absent
    ) derives Schema

    final private case class GeneralCapabilities(
        positionEncodings: Chunk[LspHandler.PositionEncodingKind] = Chunk.empty
    ) derives Schema

    final private case class InitializeResult(
        capabilities: LspCapabilities.Server.Server,
        serverInfo: LspInfo
    ) derives Schema

    final private case class EmptyParams() derives Schema
    final private case class EmptyResult() derives Schema

    final private case class SetTraceParams(
        value: LspHandler.TraceValue
    ) derives Schema

    // Sync notification wire shapes.
    final private case class DidOpenParams(textDocument: LspHandler.TextDocumentItem) derives Schema
    final private case class DidChangeParams(
        textDocument: LspHandler.VersionedTextDocumentIdentifier,
        contentChanges: Chunk[LspHandler.TextDocumentContentChangeEvent]
    ) derives Schema
    final private case class DidSaveParams(textDocument: LspHandler.TextDocumentIdentifier) derives Schema
    final private case class DidCloseParams(textDocument: LspHandler.TextDocumentIdentifier) derives Schema
    final private case class WillSaveParams(
        textDocument: LspHandler.TextDocumentIdentifier,
        reason: LspHandler.TextDocumentSaveReason
    ) derives Schema

    // Notebook sync wire shapes.
    final private case class NotebookDocumentItem(
        uri: String,
        notebookType: String,
        version: Int,
        cells: Chunk[NotebookCellItem]
    ) derives Schema
    final private case class NotebookCellItem(
        kind: Int,
        document: String,
        source: Maybe[String] = Absent,
        language: Maybe[String] = Absent
    ) derives Schema
    final private case class DidOpenNotebookParams(notebookDocument: NotebookDocumentItem) derives Schema
    final private case class DidChangeNotebookParams(
        notebookDocument: NotebookDocumentVersioned,
        change: LspHandler.NotebookDocumentChangeEvent = LspHandler.NotebookDocumentChangeEvent()
    ) derives Schema
    final private case class NotebookDocumentVersioned(uri: String, version: Int) derives Schema
    final private case class DidSaveNotebookParams(notebookDocument: NotebookDocumentVersioned) derives Schema
    final private case class DidCloseNotebookParams(
        notebookDocument: NotebookDocumentVersioned,
        cellTextDocuments: Chunk[LspHandler.TextDocumentIdentifier] = Chunk.empty
    ) derives Schema

    // =========================================================================
    // Handshake routes
    // =========================================================================

    def initialize(
        config: LspConfig,
        serverCaps: LspCapabilities.Server,
        negotiatedEncodingRef: AtomicRef[LspHandler.PositionEncodingKind],
        clientCapabilitiesRef: AtomicRef[Maybe[LspCapabilities.Client.Client]],
        clientInfoRef: AtomicRef[Maybe[LspInfo]],
        workspaceFoldersRef: AtomicRef[Maybe[Chunk[LspHandler.WorkspaceFolder]]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[InitializeParams, InitializeResult]("initialize") { (params, _) =>
            // Negotiate position encoding: first match of client's list against server's list.
            val clientEncodings: Chunk[LspHandler.PositionEncodingKind] =
                params.capabilities.general.fold(Chunk.empty[LspHandler.PositionEncodingKind])(_.positionEncodings)
            val negotiated = config.positionEncodings.find(e => clientEncodings.contains(e))
                .getOrElse(LspHandler.PositionEncodingKind.UTF16)
            negotiatedEncodingRef.unsafe.set(negotiated)(using AllowUnsafe.embrace.danger)
            clientCapabilitiesRef.unsafe.set(Present(params.capabilities))(using AllowUnsafe.embrace.danger)
            clientInfoRef.unsafe.set(params.clientInfo)(using AllowUnsafe.embrace.danger)
            workspaceFoldersRef.unsafe.set(params.workspaceFolders)(using AllowUnsafe.embrace.danger)
            // Return InitializeResult with spec version 3.17 embedded in serverInfo.
            val serverInfo = config.serverInfo.copy(version = LspConfig.SpecVersion)
            InitializeResult(capabilities = serverCaps, serverInfo = serverInfo)
        }
    end initialize

    def initialized()(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[EmptyParams]("initialized") { (_, _) => () }

    // =========================================================================
    // Lifecycle routes
    // =========================================================================

    def shutdown()(using Frame): JsonRpcRoute[?, ?, ?] =
        // The shutdown gate already transitions state; this route just returns success.
        JsonRpcRoute.request[EmptyParams, EmptyResult]("shutdown") { (_, _) => EmptyResult() }

    def exit()(using Frame): JsonRpcRoute[?, ?, ?] =
        // The shutdown gate triggers handler.close on exit; this route is a no-op handler.
        JsonRpcRoute.notification[EmptyParams]("exit") { (_, _) => () }

    def setTrace(traceRef: AtomicRef[LspHandler.TraceValue])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[SetTraceParams]("$/setTrace") { (params, _) =>
            Sync.defer {
                traceRef.unsafe.set(params.value)(using AllowUnsafe.embrace.danger)
                ()
            }
        }

    // =========================================================================
    // textDocument sync routes (registry update BEFORE user handler)
    // =========================================================================

    def textDocumentDidOpen(
        registry: LspDocumentRegistryImpl,
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidOpenParams]("textDocument/didOpen") { (params, jrCtx) =>
            // Registry update first, then user handler.
            registry.insert(params.textDocument).andThen {
                userHandler match
                    case Present(h: LspHandler.NotificationHandler[LspHandler.DidOpenTextDocumentParams, ?]) =>
                        val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                            case Present(srv) =>
                                val ctx = Lsp.RequestContext(
                                    jsonRpc = jrCtx,
                                    peer = Lsp.Peer.Server(srv.safe),
                                    workDoneToken = Absent,
                                    partialResultToken = Absent,
                                    documents = registry,
                                    positionEncoding = enc
                                )
                                Lsp.local.let(Present(ctx)) {
                                    h.handlerFn(LspHandler.DidOpenTextDocumentParams(params.textDocument))
                                }
                            case Absent => ()
                        end match
                    case _ => ()
            }
        }
    end textDocumentDidOpen

    def textDocumentDidChange(
        registry: LspDocumentRegistryImpl,
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidChangeParams]("textDocument/didChange") { (params, jrCtx) =>
            registry.applyChanges(
                params.textDocument.uri,
                params.textDocument.version,
                params.contentChanges
            ).andThen {
                userHandler match
                    case Present(h: LspHandler.NotificationHandler[LspHandler.DidChangeTextDocumentParams, ?]) =>
                        val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                            case Present(srv) =>
                                val ctx = Lsp.RequestContext(
                                    jsonRpc = jrCtx,
                                    peer = Lsp.Peer.Server(srv.safe),
                                    workDoneToken = Absent,
                                    partialResultToken = Absent,
                                    documents = registry,
                                    positionEncoding = enc
                                )
                                Lsp.local.let(Present(ctx)) {
                                    h.handlerFn(LspHandler.DidChangeTextDocumentParams(
                                        params.textDocument,
                                        params.contentChanges
                                    ))
                                }
                            case Absent => ()
                        end match
                    case _ => ()
            }
        }
    end textDocumentDidChange

    def textDocumentDidSave(
        registry: LspDocumentRegistryImpl,
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidSaveParams]("textDocument/didSave") { (params, jrCtx) =>
            registry.setSaved(params.textDocument.uri).andThen {
                userHandler match
                    case Present(h: LspHandler.NotificationHandler[LspHandler.DidSaveTextDocumentParams, ?]) =>
                        val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                            case Present(srv) =>
                                val ctx = Lsp.RequestContext(
                                    jsonRpc = jrCtx,
                                    peer = Lsp.Peer.Server(srv.safe),
                                    workDoneToken = Absent,
                                    partialResultToken = Absent,
                                    documents = registry,
                                    positionEncoding = enc
                                )
                                Lsp.local.let(Present(ctx)) {
                                    h.handlerFn(LspHandler.DidSaveTextDocumentParams(params.textDocument))
                                }
                            case Absent => ()
                        end match
                    case _ => ()
            }
        }
    end textDocumentDidSave

    def textDocumentDidClose(
        registry: LspDocumentRegistryImpl,
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidCloseParams]("textDocument/didClose") { (params, jrCtx) =>
            registry.remove(params.textDocument.uri).andThen {
                userHandler match
                    case Present(h: LspHandler.NotificationHandler[LspHandler.DidCloseTextDocumentParams, ?]) =>
                        val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                            case Present(srv) =>
                                val ctx = Lsp.RequestContext(
                                    jsonRpc = jrCtx,
                                    peer = Lsp.Peer.Server(srv.safe),
                                    workDoneToken = Absent,
                                    partialResultToken = Absent,
                                    documents = registry,
                                    positionEncoding = enc
                                )
                                Lsp.local.let(Present(ctx)) {
                                    h.handlerFn(LspHandler.DidCloseTextDocumentParams(params.textDocument))
                                }
                            case Absent => ()
                        end match
                    case _ => ()
            }
        }
    end textDocumentDidClose

    def textDocumentWillSave(
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[WillSaveParams]("textDocument/willSave") { (params, jrCtx) =>
            userHandler match
                case Present(h: LspHandler.NotificationHandler[LspHandler.WillSaveTextDocumentParams, ?]) =>
                    val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                    serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                        case Present(srv) =>
                            val ctx = Lsp.RequestContext(
                                jsonRpc = jrCtx,
                                peer = Lsp.Peer.Server(srv.safe),
                                workDoneToken = Absent,
                                partialResultToken = Absent,
                                documents = registry,
                                positionEncoding = enc
                            )
                            Lsp.local.let(Present(ctx)) {
                                h.handlerFn(LspHandler.WillSaveTextDocumentParams(params.textDocument, params.reason))
                            }
                        case Absent => ()
                    end match
                case _ => ()
        }
    end textDocumentWillSave

    // =========================================================================
    // notebookDocument sync routes
    // =========================================================================

    def notebookDocumentDidOpen(
        registry: LspDocumentRegistryImpl,
        userHandler: Maybe[LspHandler[?, ?, ?]],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidOpenNotebookParams]("notebookDocument/didOpen") { (params, _) =>
            // Insert each cell text document into the registry with the session encoding.
            val cells = params.notebookDocument.cells
            cells.foldLeft(().asInstanceOf[Unit < Sync]) { (acc, cell) =>
                acc.andThen {
                    val uri     = LspHandler.LspDocument.Uri.fromWire(cell.document)
                    val lang    = cell.language.getOrElse("plaintext")
                    val text    = cell.source.getOrElse("")
                    val version = params.notebookDocument.version
                    registry.insertNotebookCell(uri, lang, text, version)
                }
            }
        }
    end notebookDocumentDidOpen

    def notebookDocumentDidChange(
        registry: LspDocumentRegistryImpl
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidChangeNotebookParams]("notebookDocument/didChange") { (params, _) =>
            // Process cell structural changes.
            // Silent log-and-skip for missing-doc cases.
            val cellChanges: Unit < Sync =
                params.change.cells match
                    case Absent      => ()
                    case Present(cc) =>
                        // Structure changes: insert newly opened cells, remove closed cells.
                        val structureEffect: Unit < Sync =
                            cc.structure match
                                case Absent => ()
                                case Present(sc) =>
                                    val opens: Unit < Sync =
                                        sc.didOpen.foldLeft(().asInstanceOf[Unit < Sync]) { (acc, item) =>
                                            acc.andThen(registry.insert(item))
                                        }
                                    val closes: Unit < Sync =
                                        sc.didClose.foldLeft(().asInstanceOf[Unit < Sync]) { (acc, id) =>
                                            acc.andThen(registry.remove(id.uri))
                                        }
                                    opens.andThen(closes)
                            end match
                        end structureEffect

                        // Text content changes: apply incremental changes to existing cell docs.
                        val contentEffect: Unit < Sync =
                            cc.textContent.foldLeft(().asInstanceOf[Unit < Sync]) { (acc, tc) =>
                                acc.andThen(
                                    registry.applyChanges(tc.document.uri, tc.document.version, tc.changes)
                                )
                            }

                        structureEffect.andThen(contentEffect)
                end match
            end cellChanges
            cellChanges
        }
    end notebookDocumentDidChange

    def notebookDocumentDidSave(
        registry: LspDocumentRegistryImpl
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidSaveNotebookParams]("notebookDocument/didSave") { (_, _) =>
            ()
        }
    end notebookDocumentDidSave

    def notebookDocumentDidClose(
        registry: LspDocumentRegistryImpl
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.notification[DidCloseNotebookParams]("notebookDocument/didClose") { (params, _) =>
            // Remove all cell text documents from the registry.
            params.cellTextDocuments.foldLeft(().asInstanceOf[Unit < Sync]) { (acc, td) =>
                acc.andThen(registry.remove(td.uri))
            }
        }
    end notebookDocumentDidClose

end LspBuiltInRoutes
