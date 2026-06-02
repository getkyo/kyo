<!-- doctest:setup
```scala
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

case class TodoEntry(keyword: String, body: String, line: Int) derives Schema, CanEqual
case class ReindexResult(scanned: Int) derives Schema, CanEqual
case class TodoLintFailure(line: Int, reason: String) derives Schema, CanEqual

val todoUri: LspHandler.LspDocument.Uri =
    LspHandler.LspDocument.Uri.parse("file:///tasks.todo").getOrElse(throw new IllegalStateException("uri"))

def parseEntry(text: String, line: Int): Maybe[TodoEntry] =
    text.linesIterator.zipWithIndex.find(_._2 == line - 1) match
        case Some((l, _)) =>
            val parts = l.trim.split(" ", 2)
            if parts.length >= 1 then Maybe(TodoEntry(parts(0), parts.lift(1).getOrElse(""), line))
            else Maybe.empty
        case None => Maybe.empty
```
-->

# kyo-lsp

Language Server Protocol 3.17 implementation for building editor-tooling servers (and clients) on top of `kyo-jsonrpc`. A server is a `JsonRpcTransport` plus a list of typed `LspHandler[In, Out, +E]` values, one per LSP endpoint (`textDocument/hover`, `workspace/executeCommand`, ...). The engine drives the `initialize` handshake, advertises capabilities, maintains an in-memory document registry, runs progress and cancellation, and dispatches each inbound message to the matching handler. Handler bodies receive typed parameter records, return typed responses, and reach for per-request state (the live server, the document registry, the cancellation promise) through `Lsp.*` accessors that read a per-dispatch `Local`.

The same handler vocabulary works in both directions. Server-handled endpoints come from `LspHandler.TextDocument.*` and `LspHandler.Workspace.*`; the corresponding reverse-direction (server-initiated) endpoints that a client must answer come from `LspHandler.Window.*` and `LspHandler.Client.*`. One sealed `LspException` hierarchy carries every protocol error, four stages deep (Handshake / Dispatch / Execution / Application); user-domain errors are registered per-handler via `.error[E2](code, message)` and arrive on the peer as `LspException.Application.Remote`. The module is `shared/`-only Scala source; tests run on JVM, JavaScript, and Scala Native against an in-memory `JsonRpcTransport`.

```scala
val hover: LspHandler[LspHandler.HoverParams, Maybe[LspHandler.Hover], LspException] =
    LspHandler.TextDocument.hover { params =>
        Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
            case Present(doc) =>
                val line = params.position.line + 1
                parseEntry(doc.text, line) match
                    case Present(entry) =>
                        Present(LspHandler.Hover(
                            contents = LspHandler.HoverContents.Markup(
                                LspHandler.MarkupContent(LspHandler.MarkupKind.PlainText, s"${entry.keyword}: ${entry.body}")
                            ),
                            range = Absent
                        ))
                    case Absent => Absent
            case Absent => Absent
        }
    }
```

The single handler above is a complete server-side route. The sections below wire it into a live server, pair it with a client, walk through each handler namespace, explain the per-request context the engine binds around every dispatch, cover the document registry and edit vocabulary, the progress and cancellation surface, errors, capabilities, configuration, lifecycle, and cross-platform behavior.

## Building a server

A server is a transport plus a list of handlers. `LspServer.init` takes both, returning `LspServer < (Async & Scope)` after the engine builds the catalog, validates cross-field configuration invariants, and starts the dispatch fiber. `Async` because background fibers drive the JSON-RPC dispatch loop; `Scope` because those fibers and the transport must be released when the enclosing scope exits.

The minimal end-to-end server: define one or more handlers, start a transport, hand both to `LspServer.initWith`, park the main fiber while the dispatch loop runs. Each handler receives a typed parameter record; the engine decoded it from the JSON-RPC `params` field using the auto-derived `Schema` for the params type. Capability advertisement is automatic: registering `LspHandler.TextDocument.completion` advertises `completionProvider` in the `InitializeResult` so the client knows the feature exists.

`LspServer.initWith(transport, handlers*)(f)` is the common shape: get the server, run `f(server)`, release at scope exit. `LspServer.init(transport, handlers*)` returns the bare `LspServer < (Async & Scope)` for callers that need to thread the server into more complex flow. Both have curried `(transport, config)(handlers*)` overloads when a non-default `LspConfig` is required.

Handshake plumbing is invisible: the engine owns `initialize`, `initialized`, `shutdown`, `exit`, `$/cancelRequest`, `$/progress`, and `$/setTrace`. User handlers never see those wire methods; attempting to register one of the four reserved request/notification names (`initialize`, `initialized`, `shutdown`, `exit`) via `LspHandler.custom` aborts at init time with `LspException.Dispatch.ReservedMethod`. Both `LspServer` and `LspClient` expose `underlying: JsonRpcHandler` as the escape hatch for issuing raw JSON-RPC outside the typed LSP surface (for example, to send a peer-specific method the typed API does not cover yet).

### Running a server as a process

An `LspServer.initWith(...)` expression is an effect description, not a running process. Wrap it in a `KyoApp` to produce a `main`-method-bearing object the JVM can launch:

```scala
// object TodoServer extends KyoApp:
//     run {
//         JsonRpcTransport.contentLengthStdio(java.lang.System.in, java.lang.System.out).map { t =>
//             LspServer.initWith(t, hover) { _ =>
//                 Async.never
//             }
//         }
//     }
```

`KyoApp.run { ... }` accepts any `Any < (Async & Scope & ...)`, runs the effect, and releases the scope when the body completes. `Async.never` parks the main fiber forever; the inbound stdio dispatch fiber drives all work, so the main fiber has nothing to do besides hold the scope open until the JVM is killed (Ctrl-C, parent process exit).

## Building a client

A client needs three things the server does not: an identity record, a capability tree to advertise, and a willingness to wait for the handshake before issuing requests. `LspClient.init` takes them in a locked argument order (`transport, clientInfo, capabilities, [config,] handlers*`) and performs the LSP `initialize` / `initialized` handshake eagerly before returning. The result type is `LspClient < (Async & Scope & Abort[LspException])` because handshake failures (protocol mismatch, transport-closed mid-handshake) surface directly in the effect row.

```scala
val info = LspInfo(name = "todo-tester")
val caps = LspCapabilities.Client.empty

// val program: Maybe[LspHandler.Hover] < (Async & Scope & Abort[LspException | Closed]) =
//     JsonRpcTransport.inMemory.map { case (serverT, clientT) =>
//         LspServer.initWith(serverT, hover) { _ =>
//             LspClient.initWith(clientT, info, caps) { client =>
//                 client.hover(LspHandler.HoverParams(
//                     textDocument = LspHandler.TextDocumentIdentifier(todoUri),
//                     position     = LspHandler.Position(line = 0, character = 0)
//                 ))
//             }
//         }
//     }
```

When `LspClient.init` returns, the handshake has already completed: `client.serverCapabilities`, `client.serverInfo`, and `client.positionEncoding` are populated, ready to read synchronously without a follow-up await. The eager-handshake post-condition is what lets the next line of caller code branch on what the peer advertised, rather than chaining a separate "wait for ready" fiber.

The client exposes one typed extension method per server-handled endpoint. Each returns a typed result inside `(Async & Abort[LspException | Closed])`:

| Family | Methods |
|--|--|
| Code intelligence | `completion`, `completionItemResolve`, `hover`, `signatureHelp` |
| Navigation | `declaration`, `definition`, `typeDefinition`, `implementation`, `references`, `documentHighlight` |
| Outline / lens | `documentSymbol`, `codeAction`, `codeActionResolve`, `codeLens`, `codeLensResolve`, `documentLink`, `documentLinkResolve` |
| Color / format | `documentColor`, `colorPresentation`, `formatting`, `rangeFormatting`, `onTypeFormatting` |
| Rename / fold | `rename`, `prepareRename`, `foldingRange`, `selectionRange`, `linkedEditingRange` |
| Hierarchy | `prepareCallHierarchy`, `callHierarchyIncomingCalls`, `callHierarchyOutgoingCalls`, `prepareTypeHierarchy`, `typeHierarchySupertypes`, `typeHierarchySubtypes` |
| Semantic tokens | `semanticTokensFull`, `semanticTokensFullDelta`, `semanticTokensRange` |
| Inlay / inline | `moniker`, `inlayHint`, `inlayHintResolve`, `inlineValue` |
| Diagnostics | `documentDiagnostic`, `workspaceDiagnostic` |
| Sync | `didOpen`, `didChange`, `didSave`, `didClose`, `willSave`, `willSaveWaitUntil` |
| Workspace | `workspaceSymbol`, `executeCommand[T]` |
| Lifecycle | `workDoneProgressCancel`, `setTrace`, `cancel` |
| Session | `specVersion`, `positionEncoding`, `serverCapabilities`, `serverInfo`, `awaitDrain`, `close` / `closeNow` |

`executeCommand[T]` is typed-only; there is no untyped overload. The `T` requires a `Schema[T]` because the engine decodes the wire result into `Maybe[T]`. When the command returns no result, the wire value is `null` and the method yields `Absent`.

```scala
// val res: Maybe[ReindexResult] < (Async & Scope & Abort[LspException | Closed]) =
//     JsonRpcTransport.inMemory.map { case (_, clientT) =>
//         LspClient.initWith(clientT, info, caps) { client =>
//             client.executeCommand[ReindexResult](
//                 LspHandler.ExecuteCommandParams(command = "todo-indexer.reindex", arguments = Chunk.empty)
//             )
//         }
//     }
```

Reverse-direction requests are handled by passing client-side handlers to `LspClient.init`. The same factory shape applies: `LspHandler.Window.showMessage { params => ... }` registers an observer for `window/showMessage` notifications the server may send. Passing a server-side handler (anything from `TextDocument.*` or the request-shaped `Workspace.*` factories) to `LspClient.init` aborts at init time with `LspException.Dispatch.WrongDirection`, before any transport traffic flows; the engine catalog checks every registered handler's `direction` against the side that owns the call. When you need to issue a raw JSON-RPC call the typed client API does not cover, reach for `client.underlying: JsonRpcHandler`.

## Handlers

Every endpoint is constructed through a namespaced factory and yields an `LspHandler[In, Out, +E]`. The namespaces mirror LSP method-name prefixes:

| Namespace | Direction | Examples |
|--|--|--|
| `LspHandler.TextDocument.*` | server-handled | `completion`, `hover`, `definition`, `formatting`, `codeAction`, `didOpen`, `didChange` |
| `LspHandler.Workspace.*` | mixed | `symbol`, `executeCommand[Out]`, `didChangeConfiguration[T]` (server-handled); `applyEdit`, `configuration[T]`, `workspaceFolders`, `refreshSemanticTokens` (client-handled) |
| `LspHandler.NotebookDocument.*` | server-handled | `didOpen`, `didChange`, `didSave`, `didClose` |
| `LspHandler.Window.*` | client-handled | `showMessage`, `showMessageRequest`, `showDocument`, `logMessage`, `createWorkDoneProgress` |
| `LspHandler.Client.*` | client-handled | `registerCapability`, `unregisterCapability` |
| `LspHandler.General.*` | bidirectional observers | `cancelRequest`, `progress[T]`, `setTrace`, `logTrace` |
| `LspHandler.custom[In]` / `customClient[In]` | escape hatch | vendor extensions, unmodelled methods |

The `General` factories register USER OBSERVERS that run AFTER the engine's built-in handlers. The engine already drives cancellation (completing the `Lsp.cancelled` promise) and progress dispatch (publishing values onto the per-token subscription); the General factories exist for logging and instrumentation only. Reaching for `LspHandler.General.cancelRequest` to "implement" cancellation would do nothing useful; the cancellation effect has already happened.

Bidirectional symmetry is the design point: a server registering `LspHandler.Window.showMessage` would be rejected (wrong direction), and a client registering `LspHandler.TextDocument.completion` likewise. Each factory's wire direction is baked into the `LspHandler.Kind` enum and validated at `init` time.

### Opaque data carriers

Several LSP request/response shapes carry an optional protocol field named `data` whose value is opaque to the protocol but meaningful between a "list" call and its corresponding "resolve" call. The engine treats the field as a private string carrier (`_rawData`) and exposes a typed round-trip:

```scala
// Producing side: encode typed state into the carrier.
val richItem: LspHandler.CompletionItem =
    LspHandler.CompletionItem(
        label = "TODO",
        kind = Present(LspHandler.CompletionItemKind.Keyword)
    ).withData[TodoEntry](TodoEntry("TODO", "Implement parser", 12))

// Resolving side: decode back into the typed shape.
val decoded: Maybe[TodoEntry] < Abort[LspException.Execution.Decode] =
    richItem.dataAs[TodoEntry]
```

The carriers are `CompletionItem`, `CodeAction`, `CodeLens`, `DocumentLink`, `InlayHint`, `CallHierarchyItem`, `TypeHierarchyItem`, and `WorkspaceSymbol`. The rule: never construct one of these case classes with a public `data` field, because there is none; reach for `.withData[X]` when producing and `.dataAs[X]` when consuming. The carrier's wire shape stays `Schema`-encoded JSON regardless of `X`; only the encode/decode boundary needs to agree on the type.

### Adding domain errors with `.error[E2]`

A handler's `+E` type-parameter grows by one each time the user calls `.error[E2](code, message)`. The engine then maps any `Abort[E2]` inside the body to a JSON-RPC error response carrying the registered code and message:

```scala
val lintCmd =
    LspHandler.Workspace.executeCommand[ReindexResult] { _ =>
        Abort.fail(TodoLintFailure(line = 3, reason = "unknown keyword"))
            .andThen((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
    }.error[TodoLintFailure](code = -32099, message = "Todo lint failure")
```

`-32099` falls in the JSON-RPC user-defined error code range (`-32000` to `-32099`). On the receiving peer, the error arrives shaped as `LspException.Application.Remote`; see [Errors](#errors) below for the round-trip teaching.

## Inside a handler

A handler closure runs inside a per-request context the engine sets up before dispatch and tears down after. The context carries the live `LspServer` (or `LspClient`, on reverse-direction handlers), the read-only document registry, the JSON-RPC request id, a cancellation promise, the progress tokens from the params, the negotiated position encoding, and typed accessors for the raw extensibility slots. Inside a handler body, all of this is reachable through `Lsp.*` accessors that read a `Local`:

| Accessor | Returns | Use |
|--|--|--|
| `Lsp.server` | `LspServer < Sync` | reverse-direction calls (server side only) |
| `Lsp.client` | `LspClient < Sync` | reverse-direction calls (client side only) |
| `Lsp.documents` | `Lsp.DocumentRegistry < Sync` | open document snapshot |
| `Lsp.requestId` | `Maybe[JsonRpcId] < Sync` | the inbound request's id |
| `Lsp.cancelled` | `Fiber.Promise[Unit, Sync] < Sync` | resolves when the peer cancels |
| `Lsp.workDoneToken` | `Maybe[ProgressToken] < Sync` | client-supplied progress token |
| `Lsp.partialResultToken` | `Maybe[ProgressToken] < Sync` | client-supplied partial-result token |
| `Lsp.positionEncoding` | `PositionEncodingKind < Sync` | session-level negotiated encoding |
| `Lsp.trace` | `TraceValue < Sync` | session trace level |
| `Lsp.extras[T]` | `Maybe[T] < (Sync & Abort[InvalidParams])` | typed JSON-RPC extras |

Calling any of these accessors outside an active handler dispatch raises an `IllegalStateException`. This is a programmer-error path, not a recoverable effect; no typed `Abort` row is added to the signature. `Lsp.server` and `Lsp.client` are additionally side-correct: calling `Lsp.server` from a client-handled handler body (or `Lsp.client` from a server-handled body) raises the same `IllegalStateException`. The engine binds exactly one of the two for any given dispatch.

A typical hover handler reaches for the document registry:

```scala
val docSizeHover =
    LspHandler.TextDocument.hover { params =>
        Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
            case Present(doc) =>
                Present(LspHandler.Hover(
                    contents = LspHandler.HoverContents.Markup(
                        LspHandler.MarkupContent(LspHandler.MarkupKind.PlainText, s"${doc.text.length} chars")
                    ),
                    range = Absent
                ))
            case Absent => Absent
        }
    }
```

When `documents.get(uri)` returns `Absent`, the engine does not have an open document for that URI (the request fired before a `didOpen`, or after a `didClose`). The handler is free to return `Absent` to skip producing a hover.

### Typed accessors for raw extensibility slots

Several LSP wire fields are typed as "arbitrary JSON" in the spec: `InitializeParams.initializationOptions`, `clientCapabilities.experimental`, `serverCapabilities.experimental`, `NotebookDocument.metadata`, `NotebookCell.metadata`, and `Registration.registerOptions`. The engine stores each as a private `String` (encoded JSON); `Lsp` exposes typed decoders:

```scala
case class TodoInit(workspaceRoot: String) derives Schema, CanEqual

val init =
    LspHandler.TextDocument.didOpen { _ =>
        Lsp.initializationOptions[TodoInit].map {
            case Present(opts) => () // configure from opts.workspaceRoot
            case Absent        => ()
        }
    }
```

The decoders are `Lsp.initializationOptions[X]`, `Lsp.clientExperimentalCapabilities[X]`, `Lsp.serverExperimentalCapabilities[X]`, `Lsp.notebookMetadataAs[X]`, `Lsp.notebookCellMetadataAs[X]`, and `Lsp.registerOptionsAs[X]`. All return `Maybe[X]` inside `Sync & Abort[LspException.Execution.Decode]`; a decode failure is a typed effect, not a panic.

The encoder counterpart is `LspConfig.default.experimentalServerCapabilities[X](value)`, which serializes the typed `value` via `Schema[X]` into the capability tree's `experimental` slot at handshake time. Servers wiring their own experimental extension use this to publish; peers decode via `Lsp.serverExperimentalCapabilities[X]`.

## Reverse-direction calls

A handler runs inside the engine's per-request context, so `Lsp.server` returns the live `LspServer` handle for the same session. That handle exposes the full set of server-initiated (client-bound) calls as extension methods on `LspServer`. They divide along the protocol's reverse-direction families: user-visible messaging, diagnostics, edits, configuration queries, client-cache invalidations, dynamic capability registration, and a few engine-substrate helpers.

The messaging family targets the editor's UI: `showMessage(ShowMessageParams)` and `logMessage(LogMessageParams)` fire-and-forget notifications (the latter is silent, intended for the trace/output panel; the former pops up). `showMessageRequest(ShowMessageRequestParams)` is the request-shaped variant; the client answers with `Maybe[MessageActionItem]` telling the server which action button the user clicked. `showDocument(ShowDocumentParams)` asks the client to navigate to a URI (file or web), optionally taking focus, optionally selecting a `Range`; the result `ShowDocumentResult.success` confirms whether the client honored the request.

```scala
val notifyOnLint =
    LspHandler.TextDocument.didChange { _ =>
        Lsp.server.flatMap(_.showMessage(LspHandler.ShowMessageParams(
            messageType = LspHandler.MessageType.Info,
            message     = "Re-scanning TODOs"
        )))
    }
```

`publishDiagnostics(PublishDiagnosticsParams)` is how a server delivers lint / type-check / parse results to the editor's gutter and problems panel. The params carry the document URI, an optional version (the engine matches against the registered document's version to avoid stale paints), and the `Chunk[Diagnostic]` itself. A diagnostic without a corresponding `publishDiagnostics` call is invisible; the registry does not auto-derive diagnostics from handler return values.

```scala
val validKeywords = Set("TODO", "DONE", "WAIT")

val onOpenLint =
    LspHandler.TextDocument.didOpen { params =>
        Lsp.documents.flatMap(_.get(params.textDocument.uri)).flatMap { maybeDoc =>
            val diags: Chunk[LspHandler.Diagnostic] = maybeDoc match
                case Absent => Chunk.empty
                case Present(doc) =>
                    Chunk.from(doc.text.linesIterator.zipWithIndex.flatMap { case (l, i) =>
                        val line = i + 1
                        parseEntry(doc.text, line) match
                            case Present(entry) if !validKeywords.contains(entry.keyword) =>
                                Chunk(LspHandler.Diagnostic(
                                    range    = LspHandler.Range(LspHandler.Position(i, 0), LspHandler.Position(i, entry.keyword.length)),
                                    severity = Present(LspHandler.DiagnosticSeverity.Warning),
                                    message  = s"Unknown keyword '${entry.keyword}'; expected TODO, DONE, or WAIT"
                                ))
                            case _ => Chunk.empty
                    }.toSeq)
            Lsp.server.flatMap(_.publishDiagnostics(LspHandler.PublishDiagnosticsParams(
                uri         = params.textDocument.uri,
                version     = Present(params.textDocument.version),
                diagnostics = diags
            )))
        }
    }
```

`applyEdit(ApplyWorkspaceEditParams)` asks the client to apply a `WorkspaceEdit` to its file buffers; the server-side use case is code actions, refactorings, and quick fixes that need to modify files other than (or beyond) the one the action was triggered on. The signature is `applyEdit(params): ApplyWorkspaceEditResult < (Async & Abort[LspException | Closed])`; the returned `ApplyWorkspaceEditResult` reports `applied`, an optional `failureReason`, and (for `documentChanges`) the index of the first failed change.

```scala
val applyAcrossFiles =
    LspHandler.Workspace.executeCommand[Unit] { _ =>
        val edit = LspHandler.WorkspaceEdit(
            changes = Present(Map(
                "file:///workspace/a.todo" -> Chunk(
                    LspHandler.TextEdit(
                        range   = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)),
                        newText = "DONE Migrate\n"
                    )
                )
            ))
        )
        Lsp.server.flatMap(_.applyEdit(LspHandler.ApplyWorkspaceEditParams(edit = edit)))
            .map(_ => Present(()))
    }
```

`getConfiguration[T](ConfigurationParams)` and `getWorkspaceFolders` are the inbound queries: ask the client what its current settings look like, ask which folders are open. `getConfiguration[T]` is typed; each `ConfigurationItem` (scoped to a URI and/or section) is decoded into `T` via `Schema[T]`. `getWorkspaceFolders` returns `Maybe[Chunk[WorkspaceFolder]]` (`Absent` if the client did not advertise the capability). The post-handshake snapshot is cached as `server.workspaceFolders: Maybe[Chunk[WorkspaceFolder]]`; reach for it when you need the folders without re-issuing `getWorkspaceFolders`.

The `refresh*` family invalidates client-side caches after server state changes that affect rendered output: `refreshSemanticTokens`, `refreshCodeLens`, `refreshInlayHint`, `refreshDiagnostic`, `refreshInlineValue`. Each is a parameter-less notification the client treats as "redraw next time"; the typical trigger is the server finishing an index rebuild and noticing every previously-issued token / lens / hint is now stale. They are workspace-wide; there is no URI-scoped variant.

`registerCapability(RegistrationParams)` and `unregisterCapability(UnregistrationParams)` are the dynamic-capability surface: at runtime, after the static `initialize` exchange, advertise (or retract) a method the server wants the client to start routing to it. The `Registration` carries an opaque `registerOptions` slot whose typed shape is decoded peer-side via `Lsp.registerOptionsAs[X]`. Most servers do not need dynamic registration; static capabilities advertised in the `InitializeResult` cover the common case.

`telemetry[T](payload)` ships an arbitrary typed payload to the client's telemetry sink (the `telemetry/event` notification); editors generally surface telemetry to their own analytics rather than to the user. `logTrace(message, verbose)` is the `$/logTrace` channel, used when the client has enabled tracing via `$/setTrace`.

`createWorkDoneProgress(WorkDoneProgressCreateParams)` is the server-initiated half of the work-done progress lifecycle; see [Progress and cancellation](#progress-and-cancellation) below for the begin/report/end pattern that consumes the created token.

The complete reverse-direction surface, as extension methods on `LspServer`:

| Family | Methods |
|--|--|
| Messaging | `showMessage`, `showMessageRequest`, `showDocument`, `logMessage` |
| Diagnostics | `publishDiagnostics` |
| Edits | `applyEdit` |
| Queries | `getConfiguration[T]`, `getWorkspaceFolders` |
| Cache invalidation | `refreshSemanticTokens`, `refreshCodeLens`, `refreshInlayHint`, `refreshDiagnostic`, `refreshInlineValue` |
| Dynamic registration | `registerCapability`, `unregisterCapability` |
| Telemetry / trace | `telemetry[T]`, `logTrace` |
| Progress | `createWorkDoneProgress`, `workDoneProgress` |
| Session control | `cancel` |

Each method returns its result inside `(Async & Abort[LspException | Closed])` (or just `Abort[Closed]` for the pure notification shapes); the engine routes the call through the same JSON-RPC layer that serves inbound traffic, so a closed transport mid-call surfaces as `Closed` in the effect row.

## Document registry

When the client opens a file via `textDocument/didOpen`, the engine stores the text in an in-memory registry. Subsequent `didChange` notifications update it incrementally, `didSave` marks it as saved, `didClose` removes it. The registry is the source of truth handlers read through `Lsp.documents`. Users never write to it directly; mutation lives inside the engine's document-sync pipeline, and all sync notification handlers (`TextDocument.didOpen`, `didChange`, `didSave`, `didClose`) run AFTER the registry has been updated. A handler that reads `documents.get(uri)` after a `didChange` notification observes the new text, not the old.

The read interface is sealed:

```scala
val tableOfContents =
    LspHandler.TextDocument.documentSymbol { _ =>
        Lsp.documents.flatMap { docs =>
            docs.listOpen.map { open =>
                LspHandler.DocumentSymbolResult.Symbols(
                    open.map(d => LspHandler.DocumentSymbol(
                        name           = d.uri.asString,
                        detail         = Present(s"v${d.version}"),
                        kind           = LspHandler.SymbolKind.File,
                        range          = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)),
                        selectionRange = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0))
                    ))
                )
            }
        }
    }
```

The accessors are `get(uri)`, `version(uri)`, `listOpen`, `listOpenUris`, and `isOpen(uri)`. Subscription callbacks and external mutation are intentionally absent: there is one writer (the engine) and many readers (handlers).

`LspDocument.encoding` is `private[kyo]`: the engine stamps each opened document with the negotiated session position encoding (UTF-16 by default, configurable via `LspConfig.withPositionEncodings`). The field is excluded from `==` equality, so two documents with the same uri/version/text compare equal even if the engine re-stamped them under a different session encoding.

A document's `uri` is an opaque type:

```scala
LspHandler.LspDocument.Uri.parse("file:///todo/tasks.todo")  // Present(...)
LspHandler.LspDocument.Uri.parse("   ")                       // Absent
LspHandler.LspDocument.Uri.parse("")                          // Absent
```

`parse` rejects empty and whitespace-only inputs by returning `Absent`, so user code that builds a URI from external input gets the validation for free. The wire-decoded path (`Uri.fromWire`) is `private[kyo]`; the engine uses it to deserialize without re-validating.

### Applying edits

The protocol describes edits as `TextEdit(range, newText)` and `AnnotatedTextEdit(range, newText, annotationId)`. A `WorkspaceEdit` groups them into either `changes: Map[String, Chunk[TextEdit]]` (URI to edits) or the richer `documentChanges: Chunk[WorkspaceEditDocumentChange]` (which supports versioned text edits and resource operations). Resource operations are `CreateFile`, `RenameFile`, `DeleteFile`, each tagged by `ResourceOperationKind` (`Create`, `Rename`, `Delete`). A `ChangeAnnotation` (`label`, `needsConfirmation`, `description`) attaches user-visible metadata to one or more edits via `annotationId`.

The reverse-direction `workspace/applyEdit` shape carries the entire `WorkspaceEdit`:

```scala
val applyOnce =
    LspHandler.TextDocument.codeAction { params =>
        val edit = LspHandler.WorkspaceEdit(
            changes = Present(Map(
                params.textDocument.uri.asString -> Chunk(
                    LspHandler.TextEdit(
                        range   = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)),
                        newText = "TODO Take a break\n"
                    )
                )
            ))
        )
        val action = LspHandler.CodeAction(
            title = "Insert TODO",
            kind  = Present(LspHandler.CodeActionKind.QuickFix),
            edit  = Present(edit)
        )
        Chunk(LspHandler.CommandOrCodeAction.Action(action))
    }
```

For incremental in-memory updates against the registry's snapshot, `LspHandler.LspDocument.applyChanges(doc, changes)` is pure: it takes a document and a chunk of `TextDocumentContentChangeEvent` values (each `Full(text)` or `Incremental(range, text)`) and returns the new document with the new text and an incremented version. The engine uses the same function inside the registry; user code can call it on any `LspDocument` it has in hand (for example, to preview an edit before submitting it).

The sync mode the engine negotiates with the client is controlled by `LspConfig.documentSync: TextDocumentSyncKind`, with three values:

- `None`: the engine does not request document open/change/close notifications.
- `Full`: each change carries the entire new text.
- `Incremental` (default): each change carries a `range` and the replacement text.

Position encodings are negotiated similarly. `LspConfig.positionEncodings: Chunk[PositionEncodingKind]` (default `Chunk(UTF16)`) lists the server's accepted set; the engine intersects with the client's advertised set at handshake time and stamps the chosen encoding into every registered document. `LspHandler.PositionEncodingKind` carries three opaque-string values: `UTF8`, `UTF16`, `UTF32`. UTF-16 is the LSP 3.17 default.

## Notebook integration

Notebooks (Jupyter, VS Code interactive windows) ride alongside text documents. The engine treats every notebook cell as a separate `LspDocument` inside the same registry, keyed by the cell URI. Handlers receive the cell document through `Lsp.documents.get(cellUri)` just like any other text document.

The notebook-specific factories are `LspHandler.NotebookDocument.didOpen`, `didChange`, `didSave`, `didClose`. The notebook itself is described by `LspHandler.Notebook` (with cells), and each cell by `LspHandler.NotebookCell`. Both carry an opaque `metadata` slot; `Lsp.notebookMetadataAs[X]` and `Lsp.notebookCellMetadataAs[X]` decode it on demand.

```scala
case class CellMeta(executionCount: Int) derives Schema, CanEqual

val onCellOpen =
    LspHandler.NotebookDocument.didOpen { params =>
        params.cellTextDocuments.foldLeft((): Unit < Async) { (acc, _) =>
            acc // the engine has already populated each cell into Lsp.documents
        }
    }
```

`workspace/didChangeConfiguration[T]` and `workspace/executeCommand[Out]` take a `Schema`-typed parameter so user-defined configuration shapes and command results round-trip without manual JSON wrangling.

## Progress and cancellation

Long-running operations report progress through LSP's `$/progress` notification family. The protocol distinguishes two flavors:

- **Work-done progress** announces an indeterminate or percentage-based job's lifecycle (`Begin` → many `Report` → `End`).
- **Partial result progress** streams intermediate chunks of an otherwise-large response on a request-scoped token.

When the client supplies a `workDoneToken` in the request params, the server emits progress on that token directly. When it does not, the server allocates a fresh token via `LspServer.createWorkDoneProgress` (the `window/workDoneProgress/create` reverse-direction request), then reports against it.

### Work-done progress

```scala
val reindex =
    LspHandler.Workspace.executeCommand[ReindexResult] { params =>
        if params.command != "todo-indexer.reindex" then
            ((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
        else
            for
                supplied <- Lsp.workDoneToken
                token <- supplied match
                    case Present(t) => (t: LspHandler.ProgressToken < Async)
                    case Absent =>
                        val fresh = LspHandler.ProgressToken.StringToken(s"reindex-${java.util.UUID.randomUUID()}")
                        Lsp.server.map(_.createWorkDoneProgress(LspHandler.WorkDoneProgressCreateParams(fresh)))
                            .map(_ => fresh)
                            .handle(Abort.recover[LspException](_ => fresh))
                _ <- Lsp.workDoneBegin(token, title = "Reindexing TODOs", percentage = Present(0))
                _ <- Lsp.workDoneReport(token, message = Present("scanning"), percentage = Present(50))
                _ <- Lsp.workDoneEnd(token, message = Present("done"))
            yield Present(ReindexResult(scanned = 42))
            end for
        end if
    }
```

When `Lsp.workDoneToken` is `Absent`, the handler must FIRST ask the client to create a token via `Lsp.server.flatMap(_.createWorkDoneProgress(...))` before calling `workDoneBegin`. Skipping the create-then-report dance is silent breakage: `workDoneBegin` on an uncreated token reaches the client as an unrecognized progress stream. The pattern above is the canonical one (the `TodoIndexer` demo in `kyo-lsp/jvm/src/test/scala/demo/TodoIndexer.scala` exercises it end-to-end).

### Partial-result progress

For requests that return a sequence (`textDocument/completion`, `workspace/symbol`, ...), the client may supply a `partialResultToken` to opt into streaming. The handler emits intermediate chunks through `Lsp.emitPartialResult[T]`:

```scala
// val streamingSymbols =
//     LspHandler.Workspace.symbol { _ =>
//         Lsp.partialResultToken.flatMap {
//             case Present(token) =>
//                 val firstBatch: Chunk[LspHandler.WorkspaceSymbol] = Chunk.empty
//                 Lsp.emitPartialResult[Chunk[LspHandler.WorkspaceSymbol]](token, firstBatch)
//                     .andThen(Chunk.empty[LspHandler.WorkspaceSymbol])
//             case Absent =>
//                 Chunk.empty[LspHandler.WorkspaceSymbol]
//         }
//     }
```

The final return value is the last chunk; intermediate values flow through `emitPartialResult`. The `T` type parameter must carry a `Schema[T]`; the engine `Json.encode`s each partial chunk into a `$/progress` notification.

### Cancellation

When the client cancels a request, the engine completes `Lsp.cancelled` (a `Fiber.Promise[Unit, Sync]`). A long-running handler races its work against the cancellation promise:

```scala
// val cancellable =
//     LspHandler.TextDocument.documentSymbol { _ =>
//         Lsp.cancelled.flatMap { cancel =>
//             // race the body against the cancellation promise's get
//             Async.race(longRunningWork, cancel.get)
//         }
//     }
```

`Lsp.cancelled` is a kernel-level promise; reading it never aborts. A handler that wants to interrupt itself when the client cancels can use `Async.race` between the body and the promise's `.get`. The engine has already published the cancellation by the time the promise resolves; the `General.cancelRequest` observer factory is for logging, not for driving the cancellation effect.

## Errors

Every protocol error in `kyo-lsp` lives under one sealed root, `LspException`, divided into four stage subcategories (`Handshake`, `Dispatch`, `Execution`, `Application`):

- `LspException.Handshake` (3 leaves): `NotInitialized`, `AlreadyInitialized`, `ShutdownInProgress`.
- `LspException.Dispatch` (8 leaves): `MethodNotFound`, `InvalidParams`, `InvalidRequest`, `UnknownDocument`, `WrongDirection`, `ReservedMethod`, `CapabilityNotAdvertised`, `DuplicateHandler`.
- `LspException.Execution` (7 leaves): `RequestCancelled`, `ContentModified`, `ServerCancelled`, `ExecutionPanic`, `ProgressTokenAlreadyInUse`, `UnsupportedDocumentSync`, `Decode`.
- `LspException.Application` is sealed with exactly one concrete leaf, `Application.Remote(remoteCode, remoteMessage, remoteData)`, representing user-domain errors RECEIVED from the peer.

Library code never returns subclasses of `LspException` other than these leaves; user code never subclasses `LspException` directly. New user-domain errors enter the protocol exclusively through `handler.error[E2](code, message)` on the SENDING side.

```scala
val abortingCmd =
    LspHandler.Workspace.executeCommand[ReindexResult] { params =>
        if params.command == "todo-indexer.reindex" then
            Abort.fail(TodoLintFailure(line = 1, reason = "empty body"))
                .andThen((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
        else
            ((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
    }.error[TodoLintFailure](code = -32099, message = "Todo lint failure")
```

A `.error[E2]`-registered error arrives on the peer as `LspException.Application.Remote(remoteCode, remoteMessage, remoteData)`, NOT as the original `E2`. The peer recovers the discriminator by matching on `remoteCode`, not by runtime type. On the receiving side:

```scala
import kyo.LspException.Application.Remote

// val handled: Maybe[ReindexResult] < (Async & Scope & Abort[LspException | Closed]) =
//     JsonRpcTransport.inMemory.map { case (_, clientT) =>
//         LspClient.initWith(clientT, LspInfo(name = "todo-tester"), LspCapabilities.Client.empty) { client =>
//             client.executeCommand[ReindexResult](
//                 LspHandler.ExecuteCommandParams(command = "todo-indexer.reindex", arguments = Chunk.empty)
//             ).handle(Abort.recover[LspException] {
//                 case Remote(-32099, _, _) => (Absent: Maybe[ReindexResult]) // recognized todo-lint failure
//                 case other                => Abort.fail(other)
//             })
//         }
//     }
```

`Remote.remoteCode` is the wire-level JSON-RPC code; pattern-match on it to discriminate user-defined error families. The wire-stable contract is `(code, message, data)`, not the sending side's Scala type. This keeps the cross-language case (a TypeScript client talking to a Scala server, or vice versa) honest.

The standard JSON-RPC code ranges:

- `-32700` to `-32600` are reserved JSON-RPC envelope errors.
- `-32601` covers method-not-found and capability-not-advertised on the dispatch stage.
- `-32602` covers invalid-params, unknown-document, and `Execution.Decode`.
- `-32800`, `-32801`, `-32802` are the LSP-spec request/content/server cancellation codes.
- `-32099` through `-32000` is the implementation-defined user-error range you advertise via `.error[E2]`.

## Configuring a server or client

The protocol negotiates a session at `initialize` time: the server advertises its capabilities, the client confirms which it consumes, both pick the position encoding. `LspConfig` collects the server-side knobs (handler-registration cross-field constraints, capability tree mode, JSON-RPC underlying config); `LspCapabilities` describes the trees themselves. The supported protocol version is `LspConfig.SpecVersion` (currently `"3.17"`); use it for runtime version checks or to print the negotiated version in logs.

### The capability trees

`LspCapabilities.Server.Server` and `LspCapabilities.Client.Client` are the capability trees the protocol negotiates. The type aliases `LspCapabilities.Server` and `LspCapabilities.Client` resolve to the inner case classes so callers write `LspCapabilities.Server` and get the record. `LspCapabilities.Server.empty` and `LspCapabilities.Client.empty` provide zero-capability baselines.

When `LspConfig.declaredServerCapabilities = Absent` (the default), the engine auto-derives the tree from the registered handlers at init time: a server that registers `TextDocument.completion` and `TextDocument.hover` advertises `completionProvider` and `hoverProvider` automatically. Declaring an explicit tree disables the derivation; the explicit tree wins. Either way, `LspConfig.enforceCapabilities = true` (the default) rejects requests for methods not advertised in the tree with `LspException.Dispatch.CapabilityNotAdvertised`. Set it to `false` to permit unadvertised methods to dispatch (useful for vendor extensions that ride alongside the standard capability tree).

```scala
val explicit: LspCapabilities.Server =
    LspCapabilities.Server.empty.copy(
        hoverProvider      = Present(LspHandler.BooleanOr.Bool(true)),
        completionProvider = Present(LspHandler.CompletionOptions(
            triggerCharacters = Chunk(".", ":")
        ))
    )

val cfg: LspConfig =
    LspConfig.default
        .withDeclaredServerCapabilities(explicit)
        .withEnforceCapabilities(true)
```

Many capability slots accept either a plain boolean ("yes, I provide this, with default options") or a typed options record ("yes, with these specific settings"). `BooleanOr[T]` and `StringOr[T]` are the sealed unions that carry this choice on the wire:

```scala
val asFlag: LspCapabilities.Server =
    LspCapabilities.Server.empty.copy(
        hoverProvider = Present(LspHandler.BooleanOr.Bool(true))
    )

val asOptions: LspCapabilities.Server =
    LspCapabilities.Server.empty.copy(
        hoverProvider = Present(LspHandler.BooleanOr.Options(LspHandler.HoverOptions()))
    )
```

`BooleanOr` has `Bool(value: Boolean)` and `Options(value: T)` cases; `StringOr` has `Str(value: String)` and `Options(value: T)`. The `Schema` reads the wire bytes and branches by JSON shape: `true`/`false` becomes `Bool`/`Str`, an object becomes `Options`. Use these everywhere the LSP spec writes "either a flag or an options record", which is most of the capability tree. The `BooleanOr` and `StringOr` sealed unions are also re-exported as `LspCapabilities.BooleanOr` and `LspCapabilities.StringOr` for ergonomic import at the capability use site.

`LspCapabilities.Name` is a typed discriminator (32 cases) used by `LspException.Dispatch.CapabilityNotAdvertised` to identify which capability the rejected method needed.

### Document and sync options

Configure the sync kind via `LspConfig.default.withDocumentSync(TextDocumentSyncKind.Incremental)` and the position encoding via `LspConfig.default.withPositionEncodings(Chunk(PositionEncodingKind.UTF16))`. See [Document registry](#document-registry) for the semantics of each sync mode.

Several handler kinds require a cross-field config setting, validated by the engine catalog at init time (after `LspConfig.require`):

- `TextDocument.onTypeFormatting` requires `LspConfig.withOnTypeFormattingTriggers(...)` (non-empty `Chunk[String]`).
- Any `semanticTokensFull` / `semanticTokensRange` handler requires `LspConfig.withSemanticTokensLegend(...)`.
- `Workspace.executeCommand[Out]` requires `LspConfig.withExecuteCommandCommands(...)` listing the supported command names.

Registering one of these handlers without the matching config setting aborts at `LspServer.init` time. The catalog enumerates the requirement; the error message names the missing field.

### Lifecycle

`LspServer.init` is `Scope`-managed: the server is closed (with the default 30-second grace period) when the enclosing scope exits. The unscoped variant has no automatic cleanup:

```scala
// Scoped: closes automatically at scope exit.
// val scoped: Unit < (Async & Scope) =
//     JsonRpcTransport.inMemory.map { case (serverT, _) =>
//         LspServer.initWith(serverT) { _ => () }
//     }

// Unscoped: caller is responsible for close.
// val unscoped: Unit < Async =
//     for
//         transport <- JsonRpcTransport.inMemory.map(_._1)
//         server    <- LspServer.initUnscoped(transport)
//         _         <- server.close
//     yield ()
```

Pick `init` / `initWith` (Scope-managed) by default. Reach for `initUnscoped` / `initUnscopedWith` when the server's lifetime must outlive any single Scope; the caller then must invoke `close` (default 30-second grace) or `closeNow` (immediate, equivalent to `close(Duration.Zero)`) explicitly.

`LspClient.init` mirrors the same quartet (`init`, `initWith`, `initUnscoped`, `initUnscopedWith`) with the eager-handshake post-condition described in [Building a client](#building-a-client).

## Transports

`kyo-lsp` does not define its own transport; it reuses every `JsonRpcTransport` factory from `kyo-jsonrpc`. The two everyday choices for an LSP wire are `contentLengthStdio` (the LSP spec's framed stdio, JVM-only) and `stdio` (line-delimited JSON, for testing and bespoke peers):

- `JsonRpcTransport.contentLengthStdio(in, out)` (JVM only): the LSP standard wire framing. Each message is preceded by a `Content-Length: N\r\n\r\n` header. This is what editors expect from a stdio server.
- `JsonRpcTransport.inMemory` and `JsonRpcTransport.inMemory(capacity)`: returns a paired `(t1, t2)` for in-process tests. Each end's `send` becomes the other's `incoming`.

The transport seam is intentionally thin (`send`, `incoming`, `close`); other framings (line-delimited stdio, Unix domain sockets, custom byte streams) are available through `JsonRpcTransport.stdio()`, `JsonRpcTransport.unixDomain(...)` (JVM only), and `JsonRpcTransport.fromWire(...)`.

## Cross-platform behavior

Source lives entirely under `shared/` and compiles on JVM, JavaScript, and Scala Native. The integration test suite exercises every handler factory and the engine policies against `JsonRpcTransport.inMemory` on all three platforms. The Content-Length stdio framer (`JsonRpcTransport.contentLengthStdio`) and the Unix-domain-socket transport are JVM-only; JS and Native servers run against in-memory or custom-framed transports the host supplies.
