package kyo.internal.lsp

import kyo.*

/** Composed LSP gate chain: handshake gate, shutdown gate, and capability gate.
  *
  * Per INV-059 the three gates are always composed in fixed order:
  *   1. `LspHandshakeGate` - admits only `initialize` before the handshake completes.
  *   2. `LspShutdownGate` - admits only `exit` after `shutdown` is received.
  *   3. `LspCapabilityGate` - rejects methods whose capability is not advertised.
  *
  * Each gate is constructed independently and composed via `LspGate.compose`.
  * The composed gate's `beforeDispatch` short-circuits on `Reject`.
  *
  * @see [[LspHandshakeGate]]
  * @see [[LspShutdownGate]]
  * @see [[LspCapabilityGate]]
  */
private[kyo] object LspGate:

    /** Composes three gates in the fixed INV-059 order: handshake -> shutdown -> capability. */
    def compose(
        handshake: JsonRpcMessageGate,
        shutdown: JsonRpcMessageGate,
        capability: JsonRpcMessageGate
    ): JsonRpcMessageGate =
        new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                handshake.beforeDispatch(env).flatMap {
                    case JsonRpcMessageGate.Decision.Allow =>
                        shutdown.beforeDispatch(env).flatMap {
                            case JsonRpcMessageGate.Decision.Allow => capability.beforeDispatch(env)
                            case other                             => other
                        }
                    case other => other
                }
        end new
    end compose

end LspGate

/** LSP handshake gate: admits requests only after `initialize` is received.
  *
  * State machine: `Pending` -> `Initialized`. The `initialize` request transitions to
  * `Initialized` and is always admitted. The `initialized` notification is always admitted
  * (it completes the second stage of the handshake for clients that require it). All other
  * requests before `Initialized` are rejected with `LspException.Handshake.NotInitialized`.
  * Notifications (other than `initialized`) are admitted unconditionally to avoid breaking
  * the fire-and-forget notification contract.
  *
  * Per INV-057.
  */
private[kyo] object LspHandshakeGate:

    def server(): JsonRpcMessageGate =
        // AllowUnsafe: AtomicBoolean for init-seen flag shared across fibers.
        val initSeen = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger).safe
        new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                env match
                    case JsonRpcRequest(_, "initialize", _, _) =>
                        initSeen.set(true).andThen(JsonRpcMessageGate.Decision.Allow)

                    case JsonRpcNotification("initialized", _, _) =>
                        Sync.defer(JsonRpcMessageGate.Decision.Allow)

                    case JsonRpcNotification(_, _, _) =>
                        Sync.defer(JsonRpcMessageGate.Decision.Allow)

                    case req: JsonRpcRequest =>
                        initSeen.get.map { seen =>
                            if seen then
                                JsonRpcMessageGate.Decision.Allow
                            else
                                val err = LspException.Handshake.NotInitialized(req.method)
                                JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(req.id, err))
                        }

                    case _ =>
                        Sync.defer(JsonRpcMessageGate.Decision.Allow)
                end match
            end beforeDispatch
        end new
    end server

end LspHandshakeGate

/** LSP shutdown gate: enforces the two-phase shutdown / exit sequence.
  *
  * State machine: `Running` -> `Shutdown` (on inbound `shutdown` request) -> `Exited`
  * (on inbound `exit` notification; triggers `handler.close`).
  *
  * After `Shutdown`, only the `exit` notification is admitted; all other requests are
  * rejected with `LspException.Handshake.ShutdownInProgress`.
  *
  * The `handlerRef` forward reference is populated after the engine constructs the
  * `JsonRpcHandler`; the shutdown gate stores it to trigger `handler.close`.
  *
  * Per INV-058.
  */
private[kyo] object LspShutdownGate:

    enum State derives CanEqual:
        case Running, Shutdown, Exited

    def server(handlerRef: AtomicRef[Maybe[JsonRpcHandler]]): JsonRpcMessageGate =
        // AllowUnsafe: AtomicRef for shutdown state shared across fibers.
        val stateRef = AtomicRef.Unsafe.init[State](State.Running)(using AllowUnsafe.embrace.danger).safe
        new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                stateRef.get.flatMap { state =>
                    env match
                        case JsonRpcRequest(id, "shutdown", _, _) =>
                            stateRef.set(State.Shutdown).andThen(JsonRpcMessageGate.Decision.Allow)

                        case JsonRpcNotification("exit", _, _) =>
                            stateRef.set(State.Exited).andThen {
                                // AllowUnsafe: read handler ref to trigger close; discard the Fiber.Unsafe.
                                handlerRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
                                    case Present(h) =>
                                        Sync.Unsafe.defer {
                                            val _ = h.unsafe.close(Duration.Zero)(using AllowUnsafe.embrace.danger, Frame.internal)
                                            JsonRpcMessageGate.Decision.Allow
                                        }
                                    case Absent =>
                                        JsonRpcMessageGate.Decision.Allow
                            }

                        case req: JsonRpcRequest if state == State.Shutdown =>
                            val err = LspException.Handshake.ShutdownInProgress(req.method)
                            Sync.defer(JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(req.id, err)))

                        case _ =>
                            Sync.defer(JsonRpcMessageGate.Decision.Allow)
                    end match
                }
            end beforeDispatch
        end new
    end server

end LspShutdownGate

/** LSP capability gate: rejects inbound requests whose required server capability is not advertised.
  *
  * Only applies when `LspConfig.enforceCapabilities = true`. Methods not covered by any standard
  * capability are always admitted (the gate does not know about custom extensions).
  *
  * Per INV-007.
  */
private[kyo] object LspCapabilityGate:

    def server(caps: LspCapabilities.Server, enforce: Boolean): JsonRpcMessageGate =
        if !enforce then JsonRpcMessageGate.noop
        else
            new JsonRpcMessageGate:
                def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                    env match
                        case req: JsonRpcRequest =>
                            Sync.defer {
                                missingCapability(req.method, caps) match
                                    case Absent =>
                                        JsonRpcMessageGate.Decision.Allow
                                    case Present(name) =>
                                        val err = LspException.Dispatch.CapabilityNotAdvertised(name)
                                        JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(req.id, err))
                            }
                        case _ =>
                            Sync.defer(JsonRpcMessageGate.Decision.Allow)
                    end match
                end beforeDispatch
            end new
        end if
    end server

    // Returns the missing capability name, or Absent if the method is admitted.
    private def missingCapability(method: String, caps: LspCapabilities.Server): Maybe[LspCapabilities.Name] =
        method match
            case "textDocument/completion" | "completionItem/resolve" =>
                if caps.completionProvider.isDefined then Absent else Present(LspCapabilities.Name.Completion)
            case "textDocument/hover" =>
                if caps.hoverProvider.isDefined then Absent else Present(LspCapabilities.Name.Hover)
            case "textDocument/signatureHelp" =>
                if caps.signatureHelpProvider.isDefined then Absent else Present(LspCapabilities.Name.SignatureHelp)
            case "textDocument/declaration" =>
                if caps.declarationProvider.isDefined then Absent else Present(LspCapabilities.Name.Declaration)
            case "textDocument/definition" =>
                if caps.definitionProvider.isDefined then Absent else Present(LspCapabilities.Name.Definition)
            case "textDocument/typeDefinition" =>
                if caps.typeDefinitionProvider.isDefined then Absent else Present(LspCapabilities.Name.TypeDefinition)
            case "textDocument/implementation" =>
                if caps.implementationProvider.isDefined then Absent else Present(LspCapabilities.Name.Implementation)
            case "textDocument/references" =>
                if caps.referencesProvider.isDefined then Absent else Present(LspCapabilities.Name.References)
            case "textDocument/documentHighlight" =>
                if caps.documentHighlightProvider.isDefined then Absent else Present(LspCapabilities.Name.DocumentHighlight)
            case "textDocument/documentSymbol" =>
                if caps.documentSymbolProvider.isDefined then Absent else Present(LspCapabilities.Name.DocumentSymbol)
            case "textDocument/codeAction" | "codeAction/resolve" =>
                if caps.codeActionProvider.isDefined then Absent else Present(LspCapabilities.Name.CodeAction)
            case "textDocument/codeLens" | "codeLens/resolve" =>
                if caps.codeLensProvider.isDefined then Absent else Present(LspCapabilities.Name.CodeLens)
            case "textDocument/documentLink" | "documentLink/resolve" =>
                if caps.documentLinkProvider.isDefined then Absent else Present(LspCapabilities.Name.DocumentLink)
            case "textDocument/documentColor" | "textDocument/colorPresentation" =>
                if caps.colorProvider.isDefined then Absent else Present(LspCapabilities.Name.DocumentColor)
            case "textDocument/formatting" =>
                if caps.documentFormattingProvider.isDefined then Absent else Present(LspCapabilities.Name.Formatting)
            case "textDocument/rangeFormatting" =>
                if caps.documentRangeFormattingProvider.isDefined then Absent else Present(LspCapabilities.Name.RangeFormatting)
            case "textDocument/onTypeFormatting" =>
                if caps.documentOnTypeFormattingProvider.isDefined then Absent else Present(LspCapabilities.Name.OnTypeFormatting)
            case "textDocument/rename" | "textDocument/prepareRename" =>
                if caps.renameProvider.isDefined then Absent else Present(LspCapabilities.Name.Rename)
            case "textDocument/foldingRange" =>
                if caps.foldingRangeProvider.isDefined then Absent else Present(LspCapabilities.Name.FoldingRange)
            case "textDocument/selectionRange" =>
                if caps.selectionRangeProvider.isDefined then Absent else Present(LspCapabilities.Name.SelectionRange)
            case "textDocument/prepareCallHierarchy" | "callHierarchy/incomingCalls" | "callHierarchy/outgoingCalls" =>
                if caps.callHierarchyProvider.isDefined then Absent else Present(LspCapabilities.Name.CallHierarchy)
            case "textDocument/prepareTypeHierarchy" | "typeHierarchy/supertypes" | "typeHierarchy/subtypes" =>
                if caps.typeHierarchyProvider.isDefined then Absent else Present(LspCapabilities.Name.TypeHierarchy)
            case "textDocument/semanticTokens/full" | "textDocument/semanticTokens/full/delta" | "textDocument/semanticTokens/range" =>
                if caps.semanticTokensProvider.isDefined then Absent else Present(LspCapabilities.Name.SemanticTokens)
            case "textDocument/moniker" =>
                if caps.monikerProvider.isDefined then Absent else Present(LspCapabilities.Name.Moniker)
            case "textDocument/linkedEditingRange" =>
                if caps.linkedEditingRangeProvider.isDefined then Absent else Present(LspCapabilities.Name.LinkedEditingRange)
            case "textDocument/inlayHint" | "inlayHint/resolve" =>
                if caps.inlayHintProvider.isDefined then Absent else Present(LspCapabilities.Name.InlayHint)
            case "textDocument/inlineValue" =>
                if caps.inlineValueProvider.isDefined then Absent else Present(LspCapabilities.Name.InlineValue)
            case "textDocument/diagnostic" | "workspace/diagnostic" =>
                if caps.diagnosticProvider.isDefined then Absent else Present(LspCapabilities.Name.Diagnostic)
            case "workspace/symbol" | "workspaceSymbol/resolve" =>
                if caps.workspaceSymbolProvider.isDefined then Absent else Present(LspCapabilities.Name.WorkspaceSymbol)
            case "workspace/executeCommand" =>
                if caps.executeCommandProvider.isDefined then Absent else Present(LspCapabilities.Name.ExecuteCommand)
            case _ =>
                // Unknown or built-in methods always admitted.
                Absent
        end match
    end missingCapability

end LspCapabilityGate
