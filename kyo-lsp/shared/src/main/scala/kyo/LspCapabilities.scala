package kyo

/** Server and client capability trees for the LSP 3.17 initialize handshake.
  *
  * The top-level `object LspCapabilities` nests two sub-objects, `Server` and `Client`, each
  * containing the matching capability case class and all its nested option records. The `Name`
  * enum provides a typed discriminator for every standard capability, used by the engine to
  * report `LspException.Dispatch.CapabilityNotAdvertised`.
  *
  * Canonical type aliases `BooleanOr[T]` and `StringOr[T]` re-export the sealed unions declared
  * inside `LspHandler`; they are provided here for ergonomic import at capability use sites.
  *
  * `Server.empty` and `Client.empty` provide zero-capability baselines for use in tests and as
  * starting points for builder calls.
  *
  * @see [[LspCapabilities.Server]]
  * @see [[LspCapabilities.Client]]
  * @see [[LspCapabilities.Name]]
  */
object LspCapabilities:

    /** Type alias resolving `LspCapabilities.Server` to the inner case class.
      *
      * External code writes `LspCapabilities.Server` and gets the case class directly.
      * The inner `object Server` is still the namespace for all nested option records.
      */
    type Server = Server.Server

    /** Type alias resolving `LspCapabilities.Client` to the inner case class.
      *
      * External code writes `LspCapabilities.Client` and gets the case class directly.
      * The inner `object Client` is still the namespace for all nested option records.
      */
    type Client = Client.Client

    type BooleanOr[T] = LspHandler.BooleanOr[T]
    type StringOr[T]  = LspHandler.StringOr[T]

    // =========================================================================
    // Server capabilities
    // =========================================================================

    /** The capabilities that the server provides. */
    object Server:

        /** All capabilities a server may advertise in the `InitializeResult`. */
        final case class Server(
            positionEncoding: Maybe[LspHandler.PositionEncodingKind] = Absent,
            textDocumentSync: Maybe[LspHandler.TextDocumentSyncValue] = Absent,
            completionProvider: Maybe[LspHandler.CompletionOptions] = Absent,
            hoverProvider: Maybe[LspHandler.BooleanOr[LspHandler.HoverOptions]] = Absent,
            signatureHelpProvider: Maybe[LspHandler.SignatureHelpOptions] = Absent,
            declarationProvider: Maybe[LspHandler.BooleanOr[LspHandler.DeclarationOptions]] = Absent,
            definitionProvider: Maybe[LspHandler.BooleanOr[LspHandler.DefinitionOptions]] = Absent,
            typeDefinitionProvider: Maybe[LspHandler.BooleanOr[LspHandler.TypeDefinitionOptions]] = Absent,
            implementationProvider: Maybe[LspHandler.BooleanOr[LspHandler.ImplementationOptions]] = Absent,
            referencesProvider: Maybe[LspHandler.BooleanOr[LspHandler.ReferenceOptions]] = Absent,
            documentHighlightProvider: Maybe[LspHandler.BooleanOr[LspHandler.DocumentHighlightOptions]] = Absent,
            documentSymbolProvider: Maybe[LspHandler.BooleanOr[LspHandler.DocumentSymbolOptions]] = Absent,
            codeActionProvider: Maybe[LspHandler.BooleanOr[LspHandler.CodeActionOptions]] = Absent,
            codeLensProvider: Maybe[LspHandler.CodeLensOptions] = Absent,
            documentLinkProvider: Maybe[LspHandler.DocumentLinkOptions] = Absent,
            colorProvider: Maybe[LspHandler.BooleanOr[WorkspaceColorProviderOptions]] = Absent,
            documentFormattingProvider: Maybe[LspHandler.BooleanOr[DocumentFormattingOptions]] = Absent,
            documentRangeFormattingProvider: Maybe[LspHandler.BooleanOr[DocumentRangeFormattingOptions]] = Absent,
            documentOnTypeFormattingProvider: Maybe[LspHandler.DocumentOnTypeFormattingOptions] = Absent,
            renameProvider: Maybe[LspHandler.BooleanOr[LspHandler.RenameOptions]] = Absent,
            foldingRangeProvider: Maybe[LspHandler.BooleanOr[LspHandler.FoldingRangeOptions]] = Absent,
            executeCommandProvider: Maybe[LspHandler.ExecuteCommandOptions] = Absent,
            selectionRangeProvider: Maybe[LspHandler.BooleanOr[SelectionRangeOptions]] = Absent,
            linkedEditingRangeProvider: Maybe[LspHandler.BooleanOr[LspHandler.LinkedEditingRangeOptions]] = Absent,
            callHierarchyProvider: Maybe[LspHandler.BooleanOr[LspHandler.CallHierarchyOptions]] = Absent,
            semanticTokensProvider: Maybe[LspHandler.SemanticTokensOptions] = Absent,
            monikerProvider: Maybe[LspHandler.BooleanOr[LspHandler.MonikerOptions]] = Absent,
            typeHierarchyProvider: Maybe[LspHandler.BooleanOr[LspHandler.TypeHierarchyOptions]] = Absent,
            inlineValueProvider: Maybe[LspHandler.BooleanOr[LspHandler.InlineValueOptions]] = Absent,
            inlayHintProvider: Maybe[LspHandler.BooleanOr[LspHandler.InlayHintOptions]] = Absent,
            diagnosticProvider: Maybe[DiagnosticOptions] = Absent,
            workspaceSymbolProvider: Maybe[LspHandler.BooleanOr[LspHandler.WorkspaceSymbolOptions]] = Absent,
            notebookDocumentSync: Maybe[NotebookDocumentSyncOptions] = Absent,
            workspace: Maybe[WorkspaceServerCapabilities] = Absent,
            private[kyo] _rawExperimental: Maybe[String] = Absent
        ) derives CanEqual

        object Server:
            given Schema[Server] = Schema.derived

        val empty: Server = Server()

        // --- Nested option records ---

        /** Options for workspace color providers. */
        final case class WorkspaceColorProviderOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Options for document formatting registration. */
        final case class DocumentFormattingOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Options for document range formatting registration. */
        final case class DocumentRangeFormattingOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Options for selection range registration. */
        final case class SelectionRangeOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Options for diagnostic providers. */
        final case class DiagnosticOptions(
            identifier: Maybe[String] = Absent,
            interFileDependencies: Boolean = false,
            workspaceDiagnostics: Boolean = false,
            workDoneProgress: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Options for notebook document synchronization. */
        final case class NotebookDocumentSyncOptions(
            notebookSelector: Chunk[NotebookSelector],
            save: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** A notebook selector item. */
        final case class NotebookSelector(
            notebook: Maybe[NotebookSelectorItem] = Absent,
            cells: Chunk[CellSelectorItem] = Chunk.empty
        ) derives Schema, CanEqual

        /** A notebook selector item (by notebook type, scheme, or pattern). */
        final case class NotebookSelectorItem(
            notebookType: Maybe[String] = Absent,
            scheme: Maybe[String] = Absent,
            pattern: Maybe[String] = Absent
        ) derives Schema, CanEqual

        /** A cell selector item. */
        final case class CellSelectorItem(language: String) derives Schema, CanEqual

        /** Workspace-level server capabilities. */
        final case class WorkspaceServerCapabilities(
            workspaceFolders: Maybe[WorkspaceFoldersServerCapabilities] = Absent,
            fileOperations: Maybe[FileOperationsServerCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Workspace folders server capabilities. */
        final case class WorkspaceFoldersServerCapabilities(
            supported: Maybe[Boolean] = Absent,
            changeNotifications: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** File operations server capabilities. */
        final case class FileOperationsServerCapabilities(
            didCreate: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent,
            willCreate: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent,
            didRename: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent,
            willRename: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent,
            didDelete: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent,
            willDelete: Maybe[LspHandler.FileOperationRegistrationOptions] = Absent
        ) derives Schema, CanEqual

    end Server

    // =========================================================================
    // Client capabilities
    // =========================================================================

    /** The capabilities that the client provides. */
    object Client:

        /** All capabilities a client may send in the `initialize` request. */
        final case class Client(
            workspace: Maybe[WorkspaceClientCapabilities] = Absent,
            textDocument: Maybe[TextDocumentClientCapabilities] = Absent,
            notebookDocument: Maybe[NotebookDocumentClientCapabilities] = Absent,
            window: Maybe[WindowClientCapabilities] = Absent,
            general: Maybe[GeneralClientCapabilities] = Absent,
            private[kyo] _rawExperimental: Maybe[String] = Absent
        ) derives CanEqual

        object Client:
            given Schema[Client] = Schema.derived

        val empty: Client = Client()

        // --- Workspace client capabilities ---

        /** Workspace-related client capabilities. */
        final case class WorkspaceClientCapabilities(
            applyEdit: Maybe[Boolean] = Absent,
            workspaceEdit: Maybe[WorkspaceEditClientCapabilities] = Absent,
            didChangeConfiguration: Maybe[DidChangeConfigurationClientCapabilities] = Absent,
            didChangeWatchedFiles: Maybe[DidChangeWatchedFilesClientCapabilities] = Absent,
            symbol: Maybe[WorkspaceSymbolClientCapabilities] = Absent,
            executeCommand: Maybe[ExecuteCommandClientCapabilities] = Absent,
            workspaceFolders: Maybe[Boolean] = Absent,
            configuration: Maybe[Boolean] = Absent,
            semanticTokens: Maybe[SemanticTokensWorkspaceClientCapabilities] = Absent,
            codeLens: Maybe[CodeLensWorkspaceClientCapabilities] = Absent,
            fileOperations: Maybe[FileOperationsClientCapabilities] = Absent,
            inlineValue: Maybe[InlineValueWorkspaceClientCapabilities] = Absent,
            inlayHint: Maybe[InlayHintWorkspaceClientCapabilities] = Absent,
            diagnostics: Maybe[DiagnosticWorkspaceClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for workspace edit operations (apply edits, resource operations). */
        final case class WorkspaceEditClientCapabilities(
            documentChanges: Maybe[Boolean] = Absent,
            resourceOperations: Chunk[String] = Chunk.empty,
            failureHandling: Maybe[String] = Absent,
            normalizesLineEndings: Maybe[Boolean] = Absent,
            changeAnnotationSupport: Maybe[ChangeAnnotationSupportOptions] = Absent
        ) derives Schema, CanEqual

        /** Whether the client groups workspace edit change annotations by label on display. */
        final case class ChangeAnnotationSupportOptions(groupsOnLabel: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `workspace/didChangeConfiguration` notifications. */
        final case class DidChangeConfigurationClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `workspace/didChangeWatchedFiles` notifications. */
        final case class DidChangeWatchedFilesClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            relativePatternSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `workspace/symbol` requests. */
        final case class WorkspaceSymbolClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            symbolKind: Maybe[SymbolKindOptions] = Absent,
            tagSupport: Maybe[SymbolTagSupportOptions] = Absent,
            resolveSupport: Maybe[ResolveSupport] = Absent
        ) derives Schema, CanEqual

        /** Which symbol kind values the client can display. */
        final case class SymbolKindOptions(valueSet: Chunk[LspHandler.SymbolKind] = Chunk.empty) derives Schema, CanEqual

        /** Which symbol tag values the client can display. */
        final case class SymbolTagSupportOptions(valueSet: Chunk[LspHandler.SymbolTag] = Chunk.empty) derives Schema, CanEqual

        /** Properties a client can resolve in a second request for a symbol or item. */
        final case class ResolveSupport(properties: Chunk[String]) derives Schema, CanEqual

        /** Client capabilities for `workspace/executeCommand` requests. */
        final case class ExecuteCommandClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Whether the client can refresh semantic tokens workspace-wide on a server request. */
        final case class SemanticTokensWorkspaceClientCapabilities(refreshSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Whether the client can refresh code lenses workspace-wide on a server request. */
        final case class CodeLensWorkspaceClientCapabilities(refreshSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for workspace file operation notifications and requests. */
        final case class FileOperationsClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            didCreate: Maybe[Boolean] = Absent,
            willCreate: Maybe[Boolean] = Absent,
            didRename: Maybe[Boolean] = Absent,
            willRename: Maybe[Boolean] = Absent,
            didDelete: Maybe[Boolean] = Absent,
            willDelete: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Whether the client can refresh inline values workspace-wide on a server request. */
        final case class InlineValueWorkspaceClientCapabilities(refreshSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Whether the client can refresh inlay hints workspace-wide on a server request. */
        final case class InlayHintWorkspaceClientCapabilities(refreshSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Whether the client can refresh diagnostics workspace-wide on a server request. */
        final case class DiagnosticWorkspaceClientCapabilities(refreshSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        // --- TextDocument client capabilities ---

        /** Text document related client capabilities. */
        final case class TextDocumentClientCapabilities(
            synchronization: Maybe[TextDocumentSyncClientCapabilities] = Absent,
            completion: Maybe[CompletionClientCapabilities] = Absent,
            hover: Maybe[HoverClientCapabilities] = Absent,
            signatureHelp: Maybe[SignatureHelpClientCapabilities] = Absent,
            declaration: Maybe[DeclarationClientCapabilities] = Absent,
            definition: Maybe[DefinitionClientCapabilities] = Absent,
            typeDefinition: Maybe[TypeDefinitionClientCapabilities] = Absent,
            implementation: Maybe[ImplementationClientCapabilities] = Absent,
            references: Maybe[ReferenceClientCapabilities] = Absent,
            documentHighlight: Maybe[DocumentHighlightClientCapabilities] = Absent,
            documentSymbol: Maybe[DocumentSymbolClientCapabilities] = Absent,
            codeAction: Maybe[CodeActionClientCapabilities] = Absent,
            codeLens: Maybe[CodeLensClientCapabilities] = Absent,
            documentLink: Maybe[DocumentLinkClientCapabilities] = Absent,
            colorProvider: Maybe[DocumentColorClientCapabilities] = Absent,
            formatting: Maybe[DocumentFormattingClientCapabilities] = Absent,
            rangeFormatting: Maybe[DocumentRangeFormattingClientCapabilities] = Absent,
            onTypeFormatting: Maybe[DocumentOnTypeFormattingClientCapabilities] = Absent,
            rename: Maybe[RenameClientCapabilities] = Absent,
            publishDiagnostics: Maybe[PublishDiagnosticsClientCapabilities] = Absent,
            foldingRange: Maybe[FoldingRangeClientCapabilities] = Absent,
            selectionRange: Maybe[SelectionRangeClientCapabilities] = Absent,
            linkedEditingRange: Maybe[LinkedEditingRangeClientCapabilities] = Absent,
            callHierarchy: Maybe[CallHierarchyClientCapabilities] = Absent,
            semanticTokens: Maybe[SemanticTokensClientCapabilities] = Absent,
            moniker: Maybe[MonikerClientCapabilities] = Absent,
            typeHierarchy: Maybe[TypeHierarchyClientCapabilities] = Absent,
            inlineValue: Maybe[InlineValueClientCapabilities] = Absent,
            inlayHint: Maybe[InlayHintClientCapabilities] = Absent,
            diagnostic: Maybe[DiagnosticClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for text document synchronization events. */
        final case class TextDocumentSyncClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            willSave: Maybe[Boolean] = Absent,
            willSaveWaitUntil: Maybe[Boolean] = Absent,
            didSave: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/completion` requests. */
        final case class CompletionClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            completionItem: Maybe[CompletionItemClientCapabilities] = Absent,
            completionItemKind: Maybe[CompletionItemKindOptions] = Absent,
            insertTextMode: Maybe[LspHandler.InsertTextMode] = Absent,
            contextSupport: Maybe[Boolean] = Absent,
            completionList: Maybe[CompletionListClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Fine-grained completion item capabilities (snippet, tags, insert mode, etc.). */
        final case class CompletionItemClientCapabilities(
            snippetSupport: Maybe[Boolean] = Absent,
            commitCharactersSupport: Maybe[Boolean] = Absent,
            documentationFormat: Chunk[LspHandler.MarkupKind] = Chunk.empty,
            deprecatedSupport: Maybe[Boolean] = Absent,
            preselectSupport: Maybe[Boolean] = Absent,
            tagSupport: Maybe[CompletionItemTagSupportOptions] = Absent,
            insertReplaceSupport: Maybe[Boolean] = Absent,
            resolveSupport: Maybe[ResolveSupport] = Absent,
            insertTextModeSupport: Maybe[InsertTextModeSupport] = Absent,
            labelDetailsSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Which completion item tags the client can display. */
        final case class CompletionItemTagSupportOptions(valueSet: Chunk[LspHandler.CompletionItemTag]) derives Schema, CanEqual

        /** Which insert text modes the client can apply. */
        final case class InsertTextModeSupport(valueSet: Chunk[LspHandler.InsertTextMode]) derives Schema, CanEqual

        /** Which completion item kind values the client can display. */
        final case class CompletionItemKindOptions(valueSet: Chunk[LspHandler.CompletionItemKind] = Chunk.empty) derives Schema, CanEqual

        /** Client-supported default properties for completion lists. */
        final case class CompletionListClientCapabilities(itemDefaults: Chunk[String] = Chunk.empty) derives Schema, CanEqual

        /** Client capabilities for `textDocument/hover` requests. */
        final case class HoverClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            contentFormat: Chunk[LspHandler.MarkupKind] = Chunk.empty
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/signatureHelp` requests. */
        final case class SignatureHelpClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            signatureInformation: Maybe[SignatureInformationClientCapabilities] = Absent,
            contextSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Signature information details the client can display. */
        final case class SignatureInformationClientCapabilities(
            documentationFormat: Chunk[LspHandler.MarkupKind] = Chunk.empty,
            parameterInformation: Maybe[ParameterInformationClientCapabilities] = Absent,
            activeParameterSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Whether the client supports label offsets in parameter information. */
        final case class ParameterInformationClientCapabilities(labelOffsetSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/declaration` requests. */
        final case class DeclarationClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            linkSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/definition` requests. */
        final case class DefinitionClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            linkSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/typeDefinition` requests. */
        final case class TypeDefinitionClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            linkSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/implementation` requests. */
        final case class ImplementationClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            linkSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/references` requests. */
        final case class ReferenceClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/documentHighlight` requests. */
        final case class DocumentHighlightClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/documentSymbol` requests. */
        final case class DocumentSymbolClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            symbolKind: Maybe[SymbolKindOptions] = Absent,
            hierarchicalDocumentSymbolSupport: Maybe[Boolean] = Absent,
            tagSupport: Maybe[SymbolTagSupportOptions] = Absent,
            labelSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/codeAction` requests. */
        final case class CodeActionClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            codeActionLiteralSupport: Maybe[CodeActionLiteralSupportOptions] = Absent,
            isPreferredSupport: Maybe[Boolean] = Absent,
            disabledSupport: Maybe[Boolean] = Absent,
            dataSupport: Maybe[Boolean] = Absent,
            resolveSupport: Maybe[ResolveSupport] = Absent,
            honorsChangeAnnotations: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Code action kind subsets the client can display. */
        final case class CodeActionLiteralSupportOptions(
            codeActionKind: CodeActionKindOptions
        ) derives Schema, CanEqual

        /** Which code action kind values the client can display. */
        final case class CodeActionKindOptions(valueSet: Chunk[LspHandler.CodeActionKind]) derives Schema, CanEqual

        /** Client capabilities for `textDocument/codeLens` requests. */
        final case class CodeLensClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/documentLink` requests. */
        final case class DocumentLinkClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            tooltipSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/documentColor` requests. */
        final case class DocumentColorClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/formatting` requests. */
        final case class DocumentFormattingClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/rangeFormatting` requests. */
        final case class DocumentRangeFormattingClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/onTypeFormatting` requests. */
        final case class DocumentOnTypeFormattingClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/rename` requests. */
        final case class RenameClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            prepareSupport: Maybe[Boolean] = Absent,
            prepareSupportDefaultBehavior: Maybe[Int] = Absent,
            honorsChangeAnnotations: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/publishDiagnostics` notifications. */
        final case class PublishDiagnosticsClientCapabilities(
            relatedInformation: Maybe[Boolean] = Absent,
            tagSupport: Maybe[DiagnosticTagSupportOptions] = Absent,
            versionSupport: Maybe[Boolean] = Absent,
            codeDescriptionSupport: Maybe[Boolean] = Absent,
            dataSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Which diagnostic tag values the client can display. */
        final case class DiagnosticTagSupportOptions(valueSet: Chunk[LspHandler.DiagnosticTag]) derives Schema, CanEqual

        /** Client capabilities for `textDocument/foldingRange` requests. */
        final case class FoldingRangeClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            rangeLimit: Maybe[Int] = Absent,
            lineFoldingOnly: Maybe[Boolean] = Absent,
            foldingRangeKind: Maybe[FoldingRangeKindOptions] = Absent,
            foldingRange: Maybe[FoldingRangeOptions] = Absent
        ) derives Schema, CanEqual

        /** Which folding range kind values the client can display. */
        final case class FoldingRangeKindOptions(valueSet: Chunk[LspHandler.FoldingRangeKind] = Chunk.empty) derives Schema, CanEqual

        /** Folding range display options (e.g. collapsed text support). */
        final case class FoldingRangeOptions(collapsedText: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/selectionRange` requests. */
        final case class SelectionRangeClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/linkedEditingRange` requests. */
        final case class LinkedEditingRangeClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/prepareCallHierarchy` requests. */
        final case class CallHierarchyClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/semanticTokens` requests. */
        final case class SemanticTokensClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            requests: SemanticTokensRequestsCapabilities,
            tokenTypes: Chunk[LspHandler.SemanticTokenTypes],
            tokenModifiers: Chunk[LspHandler.SemanticTokenModifiers],
            formats: Chunk[String],
            overlappingTokenSupport: Maybe[Boolean] = Absent,
            multilineTokenSupport: Maybe[Boolean] = Absent,
            serverCancelSupport: Maybe[Boolean] = Absent,
            augmentsSyntaxTokens: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Which semantic token request types (range / full) the client supports. */
        final case class SemanticTokensRequestsCapabilities(
            range: Maybe[Boolean] = Absent,
            full: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/moniker` requests. */
        final case class MonikerClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/prepareTypeHierarchy` requests. */
        final case class TypeHierarchyClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/inlineValue` requests. */
        final case class InlineValueClientCapabilities(dynamicRegistration: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Client capabilities for `textDocument/inlayHint` requests. */
        final case class InlayHintClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            resolveSupport: Maybe[ResolveSupport] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `textDocument/diagnostic` requests. */
        final case class DiagnosticClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            relatedDocumentSupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        // --- Notebook document client capabilities ---

        /** Notebook document related client capabilities. */
        final case class NotebookDocumentClientCapabilities(
            synchronization: Maybe[NotebookDocumentSyncClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for notebook document synchronization. */
        final case class NotebookDocumentSyncClientCapabilities(
            dynamicRegistration: Maybe[Boolean] = Absent,
            executionSummarySupport: Maybe[Boolean] = Absent
        ) derives Schema, CanEqual

        // --- Window client capabilities ---

        /** Window-related client capabilities. */
        final case class WindowClientCapabilities(
            workDoneProgress: Maybe[Boolean] = Absent,
            showMessage: Maybe[ShowMessageRequestClientCapabilities] = Absent,
            showDocument: Maybe[ShowDocumentClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Client capabilities for `window/showMessageRequest` requests. */
        final case class ShowMessageRequestClientCapabilities(
            messageActionItem: Maybe[MessageActionItemClientCapabilities] = Absent
        ) derives Schema, CanEqual

        /** Whether the client supports additional properties on message action items. */
        final case class MessageActionItemClientCapabilities(additionalPropertiesSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

        /** Whether the client supports the `window/showDocument` request. */
        final case class ShowDocumentClientCapabilities(support: Boolean) derives Schema, CanEqual

        // --- General client capabilities ---

        /** General client capabilities. */
        final case class GeneralClientCapabilities(
            staleRequestSupport: Maybe[StaleRequestSupportOptions] = Absent,
            regularExpressions: Maybe[RegularExpressionsClientCapabilities] = Absent,
            markdown: Maybe[MarkdownClientCapabilities] = Absent,
            positionEncodings: Chunk[LspHandler.PositionEncodingKind] = Chunk.empty
        ) derives Schema, CanEqual

        /** Options for stale request handling (cancel vs retry on content modified). */
        final case class StaleRequestSupportOptions(
            cancel: Boolean,
            retryOnContentModified: Chunk[String]
        ) derives Schema, CanEqual

        /** Client's regular expression engine name and version. */
        final case class RegularExpressionsClientCapabilities(engine: String, version: Maybe[String] = Absent) derives Schema, CanEqual

        /** Client's Markdown parser name, version, and allowed HTML tags. */
        final case class MarkdownClientCapabilities(
            parser: String,
            version: Maybe[String] = Absent,
            allowedTags: Chunk[String] = Chunk.empty
        ) derives Schema, CanEqual

    end Client

    // =========================================================================
    // Capability name enum
    // =========================================================================

    /** Typed discriminator for every standard LSP 3.17 capability.
      *
      * Used by `LspException.Dispatch.CapabilityNotAdvertised` and by the engine
      * capability gate to identify which capability was missing.
      */
    enum Name derives CanEqual:
        case Completion, Hover, SignatureHelp, Declaration, Definition, TypeDefinition
        case Implementation, References, DocumentHighlight, DocumentSymbol
        case CodeAction, CodeLens, DocumentLink, DocumentColor, Formatting
        case RangeFormatting, OnTypeFormatting, Rename, FoldingRange, SelectionRange
        case CallHierarchy, TypeHierarchy, SemanticTokens, Moniker, LinkedEditingRange
        case InlayHint, InlineValue, Diagnostic, NotebookDocumentSync
        case ExecuteCommand, WorkspaceSymbol, WorkspaceFolders, FileOperations
    end Name

    object Name:
        given Schema[Name] = internal.lsp.LspWireEnumSchemas.lspCapabilityNameSchema
    end Name

end LspCapabilities
