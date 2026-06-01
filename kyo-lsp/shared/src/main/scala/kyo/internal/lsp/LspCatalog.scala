package kyo.internal.lsp

import kyo.*

/** Frozen handler index built once at `LspEngine.initServer` time.
  *
  * `LspCatalog.fromHandlers` partitions handlers by `LspHandler.Direction`, rejects wrong-direction
  * registrations with `LspException.Dispatch.WrongDirection` (INV-006), rejects duplicate
  * non-Custom Kinds with `LspException.Dispatch.DuplicateHandler` (INV-047), and rejects
  * reserved-method `CustomHandler` registrations with `LspException.Dispatch.ReservedMethod`
  * (INV-039, INV-082). No mutation is possible after construction.
  *
  * `autoDeriveServerCapabilities` walks the registered Kinds and builds a `LspCapabilities.Server`
  * matching the handler set when `config.declaredServerCapabilities` is `Absent` (INV-041).
  */
final private[kyo] class LspCatalog private (val handlers: Seq[LspHandler[?, ?, ?]]):

    /** All handlers indexed by Kind for O(1) lookup. */
    val byKind: Map[LspHandler.Kind, LspHandler[?, ?, ?]] =
        handlers.map(h => h.kind -> h).toMap

    /** True if a handler for this Kind is registered. */
    def hasKind(k: LspHandler.Kind): Boolean = byKind.contains(k)

    /** Returns the handler for this Kind, or Absent. */
    def handlerFor(k: LspHandler.Kind): Maybe[LspHandler[?, ?, ?]] =
        Maybe.fromOption(byKind.get(k))

    /** Custom handlers (Kind.Custom) keyed by wire name. */
    val customHandlers: Map[String, LspHandler[?, ?, ?]] =
        handlers.collect {
            case h if h.kind == LspHandler.Kind.Custom => h.name -> h
        }.toMap

    /** Auto-derives server capabilities from registered handler Kinds.
      *
      * When `declaredServerCapabilities` is `Present`, returns it verbatim (INV-041).
      * Otherwise walks registered Kinds and builds the capability tree.
      */
    def autoDeriveServerCapabilities(config: LspConfig): LspCapabilities.Server =
        config.declaredServerCapabilities match
            case Present(c) => c
            case Absent     => LspCatalog.deriveFromKinds(handlers.map(_.kind).toSet)

end LspCatalog

private[kyo] object LspCatalog:

    /** Reserved wire method names that user-registered CustomHandlers may not claim. */
    val ReservedMethods: Set[String] = Set(
        "initialize",
        "initialized",
        "shutdown",
        "exit",
        "$/cancelRequest",
        "$/progress",
        "$/setTrace"
    )

    /** Constructs a `LspCatalog` from `handlers`, validating direction, duplicates, and reserved names.
      *
      * Throws synchronously for programmer-error violations:
      *   - `WrongDirection`: handler direction does not match `expectedDirection`
      *   - `DuplicateHandler`: two handlers share a non-Custom Kind, or two Custom handlers share a wire name
      *   - `ReservedMethod`: a CustomHandler claims a reserved wire method name
      *
      * Direction.Either handlers (`CancelRequest`, `Progress`) are accepted on both sides.
      */
    def fromHandlers(
        handlers: Seq[LspHandler[?, ?, ?]],
        expectedDirection: LspHandler.Direction
    )(using Frame): LspCatalog =
        val seenKinds   = scala.collection.mutable.Set[LspHandler.Kind]()
        val seenCustoms = scala.collection.mutable.Set[String]()
        handlers.foreach { h =>
            val handlerDir = h.direction
            val allowed = handlerDir == LspHandler.Direction.Either ||
                handlerDir == expectedDirection
            if !allowed then
                throw LspException.Dispatch.WrongDirection(h.kind, expectedDirection)
            end if

            if h.kind == LspHandler.Kind.Custom then
                if ReservedMethods.contains(h.name) then
                    throw LspException.Dispatch.ReservedMethod(h.name)
                end if
                if seenCustoms.contains(h.name) then
                    throw LspException.Dispatch.DuplicateHandler(h.kind)
                end if
                seenCustoms += h.name
            else
                if seenKinds.contains(h.kind) then
                    throw LspException.Dispatch.DuplicateHandler(h.kind)
                end if
                seenKinds += h.kind
            end if
        }
        new LspCatalog(handlers)
    end fromHandlers

    /** Derives `LspCapabilities.Server` from the set of registered Kinds (INV-041). */
    private[lsp] def deriveFromKinds(kinds: Set[LspHandler.Kind]): LspCapabilities.Server =
        val K  = LspHandler.Kind
        val H  = LspHandler
        val cs = LspCapabilities.Server
        cs.Server(
            completionProvider = if kinds.contains(K.Completion) then Present(H.CompletionOptions()) else Absent,
            hoverProvider = if kinds.contains(K.Hover) then Present(H.BooleanOr.Bool(true)) else Absent,
            signatureHelpProvider = if kinds.contains(K.SignatureHelp) then Present(H.SignatureHelpOptions()) else Absent,
            declarationProvider = if kinds.contains(K.Declaration) then Present(H.BooleanOr.Bool(true)) else Absent,
            definitionProvider = if kinds.contains(K.Definition) then Present(H.BooleanOr.Bool(true)) else Absent,
            typeDefinitionProvider = if kinds.contains(K.TypeDefinition) then Present(H.BooleanOr.Bool(true)) else Absent,
            implementationProvider = if kinds.contains(K.Implementation) then Present(H.BooleanOr.Bool(true)) else Absent,
            referencesProvider = if kinds.contains(K.References) then Present(H.BooleanOr.Bool(true)) else Absent,
            documentHighlightProvider = if kinds.contains(K.DocumentHighlight) then Present(H.BooleanOr.Bool(true)) else Absent,
            documentSymbolProvider = if kinds.contains(K.DocumentSymbol) then Present(H.BooleanOr.Bool(true)) else Absent,
            codeActionProvider = if kinds.contains(K.CodeAction) then Present(H.BooleanOr.Bool(true)) else Absent,
            codeLensProvider = if kinds.contains(K.CodeLens) then Present(H.CodeLensOptions()) else Absent,
            documentLinkProvider = if kinds.contains(K.DocumentLink) then Present(H.DocumentLinkOptions()) else Absent,
            colorProvider = if kinds.contains(K.DocumentColor) then Present(H.BooleanOr.Bool(true)) else Absent,
            documentFormattingProvider = if kinds.contains(K.Formatting) then Present(H.BooleanOr.Bool(true)) else Absent,
            documentRangeFormattingProvider = if kinds.contains(K.RangeFormatting) then Present(H.BooleanOr.Bool(true)) else Absent,
            documentOnTypeFormattingProvider = if kinds.contains(K.OnTypeFormatting) then
                Present(H.DocumentOnTypeFormattingOptions(firstTriggerCharacter = ""))
            else Absent,
            renameProvider = if kinds.contains(K.Rename) then Present(H.BooleanOr.Bool(true)) else Absent,
            foldingRangeProvider = if kinds.contains(K.FoldingRange) then Present(H.BooleanOr.Bool(true)) else Absent,
            executeCommandProvider = if kinds.contains(K.ExecuteCommand) then Present(H.ExecuteCommandOptions()) else Absent,
            selectionRangeProvider = if kinds.contains(K.SelectionRange) then Present(H.BooleanOr.Bool(true)) else Absent,
            linkedEditingRangeProvider = if kinds.contains(K.LinkedEditingRange) then Present(H.BooleanOr.Bool(true)) else Absent,
            callHierarchyProvider = if kinds.contains(K.PrepareCallHierarchy) then Present(H.BooleanOr.Bool(true)) else Absent,
            semanticTokensProvider =
                if kinds.exists(k => k == K.SemanticTokensFull || k == K.SemanticTokensFullDelta || k == K.SemanticTokensRange)
                then Present(H.SemanticTokensOptions(legend = H.SemanticTokensLegend(Chunk.empty, Chunk.empty)))
                else Absent,
            monikerProvider = if kinds.contains(K.Moniker) then Present(H.BooleanOr.Bool(true)) else Absent,
            typeHierarchyProvider = if kinds.contains(K.PrepareTypeHierarchy) then Present(H.BooleanOr.Bool(true)) else Absent,
            inlineValueProvider = if kinds.contains(K.InlineValue) then Present(H.BooleanOr.Bool(true)) else Absent,
            inlayHintProvider = if kinds.contains(K.InlayHint) then Present(H.BooleanOr.Bool(true)) else Absent,
            diagnosticProvider = if kinds.contains(K.DocumentDiagnostic) then
                Present(cs.DiagnosticOptions(interFileDependencies = false, workspaceDiagnostics = false))
            else Absent,
            workspaceSymbolProvider = if kinds.contains(K.WorkspaceSymbol) then Present(H.BooleanOr.Bool(true)) else Absent,
            notebookDocumentSync =
                if kinds.exists(k =>
                        k == K.NotebookDidOpen || k == K.NotebookDidChange || k == K.NotebookDidSave || k == K.NotebookDidClose
                    )
                then Present(cs.NotebookDocumentSyncOptions(Chunk.empty))
                else Absent,
            textDocumentSync =
                if kinds.exists(k =>
                        k == K.TextDocumentDidOpen || k == K.TextDocumentDidChange ||
                            k == K.TextDocumentDidSave || k == K.TextDocumentDidClose
                    )
                then Present(H.TextDocumentSyncValue.Kind(H.TextDocumentSyncKind.Incremental))
                else Absent
        )
    end deriveFromKinds

end LspCatalog
