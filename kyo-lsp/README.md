# kyo-lsp

Language Server Protocol (LSP 3.17) implementation for building language servers and clients on top of `kyo-jsonrpc`. An LSP server registers typed handlers for text-document requests (completion, hover, definition, code actions), workspace queries, and notebook-document synchronization. An LSP client sends those same requests and handles the reverse-direction calls the server originates (showMessage, applyEdit, registerCapability). Both sides share one engine: the `initialize` handshake, capability negotiation, document-registry plumbing, and progress/cancellation signalling are owned by the library so handler code stays focused on language-intelligence logic.

Handlers are namespaced under six sub-objects that mirror the LSP spec's own method-name prefixes (`TextDocument`, `Workspace`, `NotebookDocument`, `Window`, `Client`, `General`), making autocomplete act as curriculum. The engine owns document-sync events and feeds an in-memory document registry so handlers reach open file contents through `Lsp.documents` without manual bookkeeping. The same JSON-RPC transports that ship with `kyo-jsonrpc` (stdio with Content-Length framing, paired in-memory, custom byte-stream lifts) carry LSP traffic.

```scala
val completionHandler: LspHandler[LspHandler.CompletionParams, LspHandler.CompletionResult, LspException] =
    LspHandler.TextDocument.completion { params =>
        val items = Chunk(LspHandler.CompletionItem(label = "hello", kind = Present(LspHandler.CompletionItemKind.Text)))
        LspHandler.CompletionResult.Items(items)
    }
```

The single handler above is a full server-side LSP route. The next sections wire it into a live server, pair it with a client, walk through all handler categories, and cover the surrounding pieces: document registry, notebook integration, progress, cancellation, custom methods, and error handling.

## Building a server

`LspServer.init` takes a transport and a varargs list of handlers. The result is `LspServer < (Async & Scope)`: `Async` because background fibers drive JSON-RPC dispatch, `Scope` because those fibers and the transport must be released when the enclosing scope exits.

The minimal end-to-end server: define one handler, start `JsonRpcTransport.contentLengthStdio`, hand both to `LspServer.initWith`, and keep the process alive.

```scala
val completionHandler: LspHandler[LspHandler.CompletionParams, LspHandler.CompletionResult, LspException] =
    LspHandler.TextDocument.completion { params =>
        val items = Chunk(LspHandler.CompletionItem(label = "hello", kind = Present(LspHandler.CompletionItemKind.Text)))
        LspHandler.CompletionResult.Items(items)
    }

// JVM: use the Content-Length stdio transport for the standard LSP wire format.
// val program: Unit < (Async & Scope) =
//     JsonRpcTransport.contentLengthStdio(System.in, System.out).map { t =>
//         LspServer.initWith(t, completionHandler)(_ => Async.never)
//     }
```

The handler receives a typed `CompletionParams` value; the engine decoded it from the JSON-RPC `params` field using the auto-derived `Schema[CompletionParams]`. Capability advertisement is automatic: a server that registers `LspHandler.TextDocument.completion` automatically advertises `completionProvider` in its `InitializeResult` capabilities, so the client knows the feature is available.

`LspServer.initWith(transport, handlers*)(f)` is the common shape: start the server, run `f(server)`, release at scope exit. `LspServer.init(transport, handlers*)` returns the bare `LspServer < (Async & Scope)` for callers that need to thread the server into more complex flow. Both have curried `(transport, config)(handlers*)` overloads when a non-default `LspConfig` is required, and unscoped `initUnscoped` / `initUnscopedWith` variants for servers whose lifetime exceeds any single scope.

Handshake plumbing is invisible: the engine owns the `initialize` request and the `initialized` follow-up notification. User handlers never see those wire methods. The engine-owned methods (`initialize`, `initialized`, `shutdown`, `exit`, `$/cancelRequest`, `$/progress`, `$/setTrace`) are registered at the head of the route table, before any user-supplied routes.

## Building a client

`LspClient.init` is the mirror of `LspServer.init` with two additional mandatory arguments: the `clientInfo: LspInfo` and `capabilities: LspCapabilities.Client` the client advertises during the handshake. The `initialize` handshake runs eagerly inside `init`; `LspClient.init` returns `LspClient < (Async & Scope & Abort[LspException])` because handshake failures surface directly in the effect row.

```scala
val info = LspInfo(name = "my-language-client")
val caps = LspCapabilities.Client.Client()

// val program: LspHandler.CompletionResult < (Async & Scope & Abort[LspException]) =
//     for
//         transport <- JsonRpcTransport.contentLengthStdio(System.in, System.out)
//         client    <- LspClient.init(transport, info, caps)
//         result    <- client.completion(LspHandler.CompletionParams(
//                          textDocument = LspHandler.TextDocumentIdentifier("file:///main.rs"),
//                          position     = LspHandler.Position(line = 10, character = 5)
//                      ))
//     yield result
```

`LspInfo(name = "my-language-client")` uses the default `version = "0.0.0"` -- ship a real implementation version in production. `LspCapabilities.Client.Client()` advertises no client capabilities; add fields when the client needs to declare feature support.

`LspClient.initWith(transport, info, caps)(handler*)` runs `f(client)`, releasing at scope exit. `LspClient.initUnscoped` / `LspClient.initUnscopedWith` are the unscoped variants.

### Client request methods

`LspClient` exposes typed extension methods for every server-handled LSP request and notification. Each is named after the underlying request and returns a typed result:

- `completion(params)`: `Maybe[LspHandler.CompletionResult]`
- `hover(params)`: `Maybe[LspHandler.Hover]`
- `definition(params)`: `LspHandler.DefinitionResult`
- `references(params)`: `Chunk[LspHandler.Location]`
- `documentSymbol(params)`: `LspHandler.DocumentSymbolResult`
- `codeAction(params)`: `Chunk[LspHandler.CommandOrCodeAction]`
- `formatting(params)`: `Chunk[LspHandler.TextEdit]`
- `semanticTokensFull(params)`: `Maybe[LspHandler.SemanticTokens]`
- `executeCommand[T](params)`: `Maybe[T]`; typed-only, no untyped variant.
- `workspaceSymbol(params)`: `Chunk[LspHandler.WorkspaceSymbol]`

All methods return `< (Async & Abort[LspException | Closed])`.

## Handlers

`LspHandler[In, Out, +E]` is the sealed user-facing type for every LSP endpoint. It pairs descriptive metadata (method name, direction, schemas) with the closure the engine invokes at dispatch time. Construct values through the namespaced factory sub-objects.

Seven namespaces cover the full LSP surface:

| Namespace | Direction | Examples |
|-----------|-----------|---------|
| `LspHandler.TextDocument` | Server-handled | completion, hover, definition, codeAction, semanticTokens |
| `LspHandler.Workspace` | Mixed | executeCommand, symbol, didChangeConfiguration, applyEdit |
| `LspHandler.NotebookDocument` | Server-handled | didOpen, didChange, didSave, didClose |
| `LspHandler.Window` | Client-handled | showMessage, showMessageRequest, logMessage, telemetry |
| `LspHandler.Client` | Client-handled | registerCapability, unregisterCapability |
| `LspHandler.General` | Either | cancelRequest, progress, setTrace, logTrace |
| `LspHandler.custom` / `LspHandler.customClient` | Server/Client | vendor extensions |

### TextDocument handlers

The `TextDocument` namespace covers all `textDocument/X` and resolving `X/resolve` requests.

```scala
val hoverHandler: LspHandler[LspHandler.HoverParams, Maybe[LspHandler.Hover], LspException] =
    LspHandler.TextDocument.hover { params =>
        val uri = params.textDocument.uri
        val pos = params.position
        Present(LspHandler.Hover(
            contents = LspHandler.HoverContents.Markup(
                LspHandler.MarkupContent(LspHandler.MarkupKind.Markdown, s"Symbol at line ${pos.line}")
            )
        ))
    }
```

**Document-sync notifications** (`didOpen`, `didChange`, `didClose`, `didSave`, `willSave`, `willSaveWaitUntil`) run user code AFTER the engine has updated the document registry. The registry is always consistent when the handler body runs.

```scala
val didChangeHandler: LspHandler[LspHandler.DidChangeTextDocumentParams, Unit, LspException] =
    LspHandler.TextDocument.didChange { params =>
        Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
            case Present(doc) => () // doc.text is already updated; run lint/index here
            case Absent       => ()
        }
    }
```

**Language feature handlers** (`completion`, `hover`, `definition`, `declaration`, `typeDefinition`, `implementation`, `references`, `documentHighlight`, `documentSymbol`, `codeAction`, `codeActionResolve`, `codeLens`, `codeLensResolve`, `documentLink`, `documentLinkResolve`, `documentColor`, `colorPresentation`, `formatting`, `rangeFormatting`, `onTypeFormatting`, `rename`, `prepareRename`, `foldingRange`, `selectionRange`, `inlayHint`, `inlayHintResolve`, `inlineValue`, `documentDiagnostic`, `signatureHelp`, `prepareCallHierarchy`, `callHierarchyIncomingCalls`, `callHierarchyOutgoingCalls`, `prepareTypeHierarchy`, `typeHierarchySupertypes`, `typeHierarchySubtypes`, `semanticTokensFull`, `semanticTokensFullDelta`, `semanticTokensRange`, `linkedEditingRange`, `moniker`, `completionItemResolve`, `publishDiagnostics`) follow the same pattern: the factory receives the handler closure and returns an `LspHandler` value.

### Workspace handlers

The `Workspace` namespace covers server-handled workspace requests and notifications plus the client-handled reverse-direction methods that the server pushes to the client.

```scala
val executeHandler: LspHandler[LspHandler.ExecuteCommandParams, Maybe[String], LspException] =
    LspHandler.Workspace.executeCommand[String] { params =>
        params.command match
            case "greet" => Present(s"Hello from LSP server!")
            case _       => Absent
    }
```

`workspace/executeCommand[Out]` is typed-only: the type parameter `Out` determines what the server encodes into the JSON-RPC result. There is no untyped variant. `workspace/configuration[T]` and `workspace/workspaceFolders` are client-handled (registered on `LspClient`; the server pushes them to the client from handler code via `Lsp.server.flatMap(_.getConfiguration[T](...))` or `getWorkspaceFolders`).

### Window and Client handlers (reverse direction)

Window and Client handlers are registered on `LspClient.init`: the server sends these requests; the client handles them.

```scala
val showMsgHandler: LspHandler[LspHandler.ShowMessageParams, Unit, LspException] =
    LspHandler.Window.showMessage { params =>
        // client-side: display params.message to the user
        ()
    }

// val client = LspClient.init(transport, info, caps, showMsgHandler)
```

`LspHandler.Window` covers `showMessage`, `showMessageRequest`, `showDocument`, `logMessage`, `createWorkDoneProgress`, `workDoneProgressCancel`, and `telemetry[T]`. `LspHandler.Client` covers `registerCapability` and `unregisterCapability`. A handler registered with the wrong direction fails at init with `LspException.Dispatch.WrongDirection` before any transport traffic.

### Custom handlers

`LspHandler.custom[In, Out](method)(handler)` registers a server-handled vendor extension. `LspHandler.customClient[In, Out](method)(handler)` registers a client-handled vendor extension.

```scala
case class PingParams(id: String)    derives Schema
case class PingResult(id: String)    derives Schema

val pingHandler: LspHandler[PingParams, PingResult, LspException] =
    LspHandler.custom[PingParams, PingResult]("acme/ping") { params =>
        PingResult(id = params.id)
    }
```

The `method` string must not collide with a standard LSP method name. The engine will not include custom handlers in any standard `*/list` responses.

### Typed domain errors

`LspHandler[In, Out, +E].error[E2](code, message)` adds a typed user-domain error mapping. When the handler aborts with a value of type `E2`, the engine emits a JSON-RPC error response with the supplied code and message.

```scala
case class LintError(file: String, line: Int) derives Schema

val lintHandler: LspHandler[LspHandler.DocumentDiagnosticParams, LspHandler.DocumentDiagnosticReport, LspException | LintError] =
    LspHandler.TextDocument.documentDiagnostic { params =>
        Abort.fail(LintError("main.scala", 42))
    }.error[LintError](code = -32099, message = "lint failed")
```

## Document registry

The engine automatically maintains an in-memory document registry fed by the four sync notifications: `textDocument/didOpen`, `textDocument/didChange`, `textDocument/didClose`, `textDocument/didSave`. Handler code reaches the registry through `Lsp.documents`:

```scala
val docSymHandler: LspHandler[LspHandler.DocumentSymbolParams, LspHandler.DocumentSymbolResult, LspException] =
    LspHandler.TextDocument.documentSymbol { params =>
        Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
            case Present(doc) =>
                // doc.text: the current file contents as a String
                // doc.uri: LspHandler.LspDocument.Uri
                // doc.version: Int (the latest client-provided version)
                LspHandler.DocumentSymbolResult.Flat(Chunk.empty)
            case Absent =>
                LspHandler.DocumentSymbolResult.Flat(Chunk.empty)
        }
    }
```

`Lsp.DocumentRegistry` exposes five read methods: `get(uri)`, `version(uri)`, `listOpen`, `listOpenUris`, `isOpen(uri)`. There is no per-document encoding accessor; the position encoding is session-level and available via `Lsp.positionEncoding`.

Sync edge cases follow LSP convention: `didChange` on an unknown URI is a silent log-and-skip (TRACE level); `didOpen` on an already-open URI is an implicit re-open (WARN + replace); `didClose` on an unknown URI is a no-op. The registry never enters an inconsistent state.

## Notebook integration

Notebook cells are stored as regular text documents in the same `LspDocumentRegistry`. The `notebookDocument/didOpen` event inserts each cell's `TextDocumentItem` via the same path as `textDocument/didOpen`. Handler code reads cell text via `Lsp.documents.get(cellUri)` just like any other document.

```scala
val notebookHandler: LspHandler[LspHandler.DidOpenNotebookDocumentParams, Unit, LspException] =
    LspHandler.NotebookDocument.didOpen { params =>
        // params.notebookDocument: LspHandler.Notebook (notebook-level metadata)
        // params.cellTextDocuments: Chunk[LspHandler.TextDocumentItem] (already open in registry)
        ()
    }
```

Notebook-level metadata (notebook type, notebook URI, cell structure array) is accessible through `Lsp.notebookMetadataAs[T]`. Cell URIs from `notebookDocument/didChange.cells.structure.didOpen` are inserted into the shared registry; no synthetic `textDocument/didOpen` notifications are emitted.

## Progress and cancellation

### Work-done progress

Handlers emit work-done progress through `Lsp.workDoneBegin`, `Lsp.workDoneReport`, and `Lsp.workDoneEnd`, keyed on the `workDoneToken` the client supplies in the request params:

```scala
val slowAnalysis: LspHandler[LspHandler.WorkspaceDiagnosticParams, LspHandler.WorkspaceDiagnosticReport, LspException] =
    LspHandler.Workspace.diagnostic { params =>
        Lsp.workDoneToken.flatMap {
            case Absent => ()
            case Present(token) =>
                Lsp.workDoneBegin(token, title = "Analysing workspace", cancellable = true)
        }.flatMap { _ =>
            // ... do work ...
            Lsp.workDoneToken.flatMap {
                case Absent         => ()
                case Present(token) => Lsp.workDoneEnd(token)
            }.map(_ => LspHandler.WorkspaceDiagnosticReport(Chunk.empty))
        }
    }
```

`Lsp.workDoneBegin(token, title, ...)` wraps `$/progress` with a `WorkDoneProgressValue.Begin` value. `Lsp.workDoneReport(token, ...)` wraps it with `Report`. `Lsp.workDoneEnd(token, ...)` wraps it with `End`. For partial-result progress (`partialResultToken`), use `Lsp.emitProgress[T](token, value)` which encodes the value via `Schema[T]`.

### Cancellation

Handlers can observe cancellation through `Lsp.cancelled`, which returns a `Fiber.Promise[Unit, Sync]` that completes when the client sends `$/cancelRequest` for this request's id. Race it against long work:

```scala
val interruptibleHover: LspHandler[LspHandler.HoverParams, Maybe[LspHandler.Hover], LspException] =
    LspHandler.TextDocument.hover { params =>
        Lsp.cancelled.flatMap { cancelPromise =>
            Fiber.race(
                cancelPromise.get.map(_ => Absent),
                Async.sleep(100.millis).map(_ => Present(LspHandler.Hover(
                    contents = LspHandler.HoverContents.Markup(
                        LspHandler.MarkupContent(LspHandler.MarkupKind.PlainText, "result")
                    )
                )))
            )
        }
    }
```

When a request is cancelled, the engine sends a `-32800 RequestCancelled` error response to the client per LSP 3.17 spec.

## The per-request context

Handler closures take only the typed `In` parameter. All per-request fields are reached through the `Lsp.*` accessors, each of which reads from a `Local` the engine binds at dispatch time:

- `Lsp.server`: the live `LspServer` handle for reverse-direction calls (`showMessage`, `applyEdit`, `getConfiguration[T]`, `publishDiagnostics`, etc.)
- `Lsp.client`: the live `LspClient` handle for client-side handler callbacks
- `Lsp.documents`: the read-only document registry for this session
- `Lsp.requestId`: the JSON-RPC id of the inbound request (`Absent` for notifications)
- `Lsp.cancelled`: promise that completes when the peer cancels this request
- `Lsp.workDoneToken`: the work-done progress token from request params
- `Lsp.partialResultToken`: the partial-result token from request params
- `Lsp.positionEncoding`: the session-level negotiated position encoding
- `Lsp.trace`: the current trace level

Calling any `Lsp.*` accessor outside an active route handler raises `IllegalStateException` (kernel panic). This is a programmer-error path; no typed `Abort` row is added.

`Lsp.server.flatMap(_.applyEdit(params))` lets a handler push workspace edits to the client mid-handler. `Lsp.server.flatMap(_.getConfiguration[T](params))` fetches typed configuration from the client. The full server-side reverse-direction surface: `showMessage`, `showMessageRequest`, `showDocument`, `logMessage`, `createWorkDoneProgress`, `telemetry[T]`, `applyEdit`, `getConfiguration[T]`, `getWorkspaceFolders`, `refreshSemanticTokens`, `refreshInlineValue`, `refreshInlayHint`, `refreshDiagnostic`, `refreshCodeLens`, `registerCapability`, `unregisterCapability`, `publishDiagnostics`, `logTrace`, `workDoneProgress`, `cancel`.

## Errors

`LspException` is the root error type, organized by pipeline stage. Pattern-match on the four sealed subcategory traits to discriminate:

- `LspException.Handshake`: errors during `initialize` / `initialized` (NotInitialized, AlreadyInitialized, ShutdownInProgress)
- `LspException.Dispatch`: errors during routing and capability gating (MethodNotFound, InvalidParams, UnknownDocument, WrongDirection, CapabilityNotAdvertised, DuplicateHandler, ReservedMethod)
- `LspException.Execution`: errors during handler execution (RequestCancelled, ContentModified, ServerCancelled, ExecutionPanic, Decode, ProgressTokenAlreadyInUse, UnsupportedDocumentSync)
- `LspException.Application`: typed user-domain errors from handler bodies (no concrete library leaves; register via `.error[E2]`)

```scala
def stage(e: LspException): String = e match
    case _: LspException.Handshake   => "handshake"
    case _: LspException.Dispatch    => "dispatch"
    case _: LspException.Execution   => "execution"
    case _: LspException.Application => "application"
```

`LspException` extends `JsonRpcApplicationError`, so every `LspException` travels through `Abort[JsonRpcError | ...]` rows transparently. JSON-RPC error codes follow LSP 3.17: `-32002` for handshake state errors, `-32601` for method-not-found, `-32800` / `-32801` / `-32802` for the LSP-reserved cancellation codes.

## Capability gate

LSP peers advertise their feature set during the handshake. `LspCapabilities.Server` is what the server sends in the `InitializeResult`; `LspCapabilities.Client` is what the client sends in the `initialize` request.

By default the server derives its advertised capabilities from the registered handlers: a server with `LspHandler.TextDocument.completion` advertises `completionProvider`, one with `LspHandler.TextDocument.semanticTokensFull` requires a `LspConfig.semanticTokensLegend`. Override by setting `LspConfig.withDeclaredServerCapabilities(...)`:

```scala
val explicitCaps: LspConfig =
    LspConfig.default
        .withDeclaredServerCapabilities(
            LspCapabilities.Server.Server(
                completionProvider = Present(LspHandler.CompletionOptions()),
                hoverProvider      = Present(LspHandler.BooleanOr.Bool(true))
            )
        )
        .withSemanticTokensLegend(LspHandler.SemanticTokensLegend(
            tokenTypes = Chunk("keyword", "variable"),
            tokenModifiers = Chunk.empty
        ))
```

`LspConfig.withEnforceCapabilities(false)` disables the capability gate (for development against non-compliant peers). The default is `true`: an inbound method whose required capability was not advertised fails with `LspException.Dispatch.CapabilityNotAdvertised` before the handler runs.

## Configuration

`LspConfig` collects the per-peer behavior knobs. All setters return a new `LspConfig`:

```scala
val tuned: LspConfig =
    LspConfig.default
        .withServerInfo(LspInfo(name = "my-server", version = "1.0.0"))
        .withPositionEncodings(Chunk(LspHandler.PositionEncodingKind.UTF8, LspHandler.PositionEncodingKind.UTF16))
        .withDocumentSync(LspHandler.TextDocumentSyncKind.Full)
        .withCompletionTriggerCharacters(Chunk(".", ":", ">"))
        .withOnTypeFormattingTriggers(Chunk("{", "}"))
        .withEnforceCapabilities(true)
```

`LspConfig.require(config)` validates structural constraints and is called automatically by every `init` variant. An invalid config (empty `positionEncodings`, etc.) throws `IllegalArgumentException` at init time.

`LspConfig.SpecVersion` is the constant `"3.17"`. The LSP spec version is always fixed; `LspInfo.version` is the IMPLEMENTATION version of your server or client.

## Lifecycle

Three pairs of methods control a server's or client's lifecycle:

- `init` / `initUnscoped`: bring up a peer. Scoped variants release at scope exit; unscoped variants require manual close.
- `close` / `close(grace)` / `closeNow`: tear down gracefully or immediately.
- `awaitDrain` (server only): wait for in-flight requests to finish without closing.

```scala
// val graceful: Unit < (Async & Scope & Abort[LspException]) =
//     LspClient.initWith(transport, info, caps) { client =>
//         client.close(5.seconds)
//     }
```

The default `close` uses a 30-second grace period. `closeNow` is the immediate variant (zero grace period). Use `close(grace)` when you want a bounded drain window; use `closeNow` when the connection is already dead.

## Transports

`kyo-lsp` reuses every `JsonRpcTransport` factory from `kyo-jsonrpc`. The deployment shapes:

| Transport | JVM | JS | Native | Notes |
|-----------|-----|----|--------|-------|
| `JsonRpcTransport.contentLengthStdio(in, out)` | yes | no | no | Standard LSP wire format (Content-Length headers) |
| `JsonRpcTransport.stdio()` | yes | yes | yes | Line-delimited; non-standard for LSP but usable |
| `JsonRpcTransport.inMemory` | yes | yes | yes | Paired in-memory transports for tests |
| `JsonRpcTransport.fromWire(wire, framer, codec)` | yes | yes | yes | Custom byte-stream |
| `JsonRpcTransport.unixDomain(path)` | yes | aborts | aborts | JVM-only Unix domain socket |

`JsonRpcTransport.contentLengthStdio` uses the `Content-Length` framing that all standard LSP clients expect. It is JVM-only because it relies on NIO. For tests, pair a server and a client over `inMemory`:

```scala
// Sketch (requires Async & Scope context to run):
// JsonRpcTransport.inMemory.map { (serverT, clientT) =>
//     LspServer.initWith(serverT, completionHandler) { _ =>
//         LspClient.initWith(clientT, info, caps) { client =>
//             client.completion(LspHandler.CompletionParams(
//                 textDocument = LspHandler.TextDocumentIdentifier("file:///main.rs"),
//                 position     = LspHandler.Position(line = 0, character = 0)
//             ))
//         }
//     }
// }
```

For framers and codecs see the `kyo-jsonrpc` README. `kyo-lsp` sets `JsonRpcCodec.Strict2_0` by default; override via `LspConfig.withJsonRpc(...)`.

## Cross-platform behavior

The shared API compiles and runs on JVM, JavaScript, and Scala Native:

| Surface | JVM | JS | Native |
|---------|-----|----|----|
| `LspServer`, `LspClient`, all handlers | yes | yes | yes |
| `LspHandler` factories, all ADT types | yes | yes | yes |
| `LspException` hierarchy | yes | yes | yes |
| `LspCapabilities`, `LspConfig`, `LspInfo` | yes | yes | yes |
| `Lsp.*` accessors, document registry | yes | yes | yes |
| `JsonRpcTransport.inMemory`, `stdio()` | yes | yes | yes |
| `JsonRpcTransport.contentLengthStdio` | yes | no | no |
| `JsonRpcTransport.unixDomain` | yes | aborts | aborts |

The full LSP handler and client surface is cross-platform. The only per-platform restriction is `contentLengthStdio`, which uses JVM NIO APIs. Cross-platform LSP code uses `inMemory` for tests or provides a custom `fromWire` transport.
