package kyo

import scala.annotation.nowarn

/** Unified route and handler for an LSP endpoint.
  *
  * An `LspHandler[In, Out, +E]` pairs the declarative metadata (method name, direction, schemas,
  * capability hints) for a single LSP 3.17 endpoint with the implementation closure the engine
  * invokes on each inbound request or outbound notification. The engine lifts each value to a
  * `JsonRpcRoute` at `LspServer.init` / `LspClient.init` time, wrapping the handler closure in
  * `Lsp.local.let` so route bodies can reach the per-request context through the `Lsp.*`
  * accessors.
  *
  * Construct values via the namespaced factory companions:
  *
  *   - [[LspHandler.textDocument]]      ; 38+ server-handled textDocument/X endpoints
  *   - [[LspHandler.workspace]]         ; workspace/X endpoints (mixed direction)
  *   - [[LspHandler.notebookDocument]]  ; notebookDocument/X endpoints
  *   - [[LspHandler.window]]            ; window/X client-handled reverse-direction endpoints
  *   - [[LspHandler.client]]            ; client/X reverse-direction endpoints
  *   - [[LspHandler.custom]]            ; arbitrary server-handled extension method
  *   - [[LspHandler.customClient]]      ; arbitrary client-handled extension method
  *
  * Domain-error mappings are added with `.error[E2](code, message)`, mirroring `JsonRpcRoute.error`.
  *
  * Every feature-specific LSP 3.17 ADT (Position, Range, Location, CompletionItem, CodeAction,
  * Diagnostic, WorkspaceEdit, SemanticTokens, etc.) is nested inside `object LspHandler`.
  * Import `kyo.LspHandler.*` at the use site to bring all ADTs into scope.
  *
  * @tparam In  the request parameter type
  * @tparam Out the response payload type
  * @tparam E   the union of user-registered domain error types
  */
sealed trait LspHandler[In, Out, +E]:
    /** The endpoint's wire-level method name (e.g. `"textDocument/completion"`). */
    def name: String

    /** Engine dispatch flavor for this handler. */
    private[kyo] def kind: LspHandler.Kind

    /** The direction this handler runs in. */
    private[kyo] def direction: LspHandler.Direction

    /** Per-handler error mappings registered via `.error[E2]`. */
    private[kyo] def errorMappings: Chunk[LspHandler.ErrorMapping[?]]

    /** Adds a typed-error mapping. When the handler aborts with a value of type `E2`, the engine
      * emits a JSON-RPC error with the supplied `code` and `message`. Mirrors `JsonRpcRoute.error[E2]`.
      */
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): LspHandler[In, Out, E | E2]
end LspHandler

// =============================================================================
// companion
// =============================================================================

object LspHandler:

    // MARK: -- Kind, Direction, ErrorMapping, Lifeable (internal types)

    /** Engine dispatch flavor: one value per distinct LSP method. */
    private[kyo] enum Kind derives CanEqual:
        // General
        case CancelRequest, Progress, SetTrace, LogTrace
        // textDocument sync
        case TextDocumentDidOpen, TextDocumentDidChange, TextDocumentDidSave, TextDocumentDidClose
        case TextDocumentWillSave, TextDocumentWillSaveWaitUntil
        // textDocument language features
        case Completion, CompletionItemResolve
        case Hover, SignatureHelp
        case Declaration, Definition, TypeDefinition, Implementation, References
        case DocumentHighlight, DocumentSymbol
        case CodeAction, CodeActionResolve, CodeLens, CodeLensResolve
        case DocumentLink, DocumentLinkResolve
        case DocumentColor, ColorPresentation
        case Formatting, RangeFormatting, OnTypeFormatting
        case Rename, PrepareRename
        case FoldingRange, SelectionRange
        case PrepareCallHierarchy, CallHierarchyIncomingCalls, CallHierarchyOutgoingCalls
        case PrepareTypeHierarchy, TypeHierarchySupertypes, TypeHierarchySubtypes
        case SemanticTokensFull, SemanticTokensFullDelta, SemanticTokensRange
        case LinkedEditingRange, Moniker
        case InlayHint, InlayHintResolve, InlineValue
        case DocumentDiagnostic, PublishDiagnostics
        // workspace
        case DidChangeWorkspaceFolders, DidChangeConfiguration, DidChangeWatchedFiles
        case DidCreateFiles, DidRenameFiles, DidDeleteFiles
        case WillCreateFiles, WillRenameFiles, WillDeleteFiles
        case WorkspaceSymbol, WorkspaceSymbolResolve
        case ExecuteCommand
        case ApplyEdit, Configuration, WorkspaceFolders
        case RefreshSemanticTokens, RefreshInlineValue, RefreshInlayHint
        case RefreshDiagnostic, RefreshCodeLens
        case WorkspaceDiagnostic
        // notebookDocument
        case NotebookDidOpen, NotebookDidChange, NotebookDidSave, NotebookDidClose
        // window
        case ShowMessage, ShowMessageRequest, ShowDocument, LogMessage
        case WorkDoneProgressCreate, WorkDoneProgressCancel
        case Telemetry
        // client
        case RegisterCapability, UnregisterCapability
        // escape hatch
        case Custom
    end Kind

    /** The direction a handler runs in: server-handled, client-handled, or either. */
    private[kyo] enum Direction derives CanEqual:
        case ServerHandled
        case ClientHandled
        case Either
    end Direction

    /** Maps Kind to its runtime direction. */
    private[kyo] def direction(kind: Kind): Direction = kind match
        case Kind.ShowMessage | Kind.ShowMessageRequest | Kind.ShowDocument | Kind.LogMessage
            | Kind.WorkDoneProgressCreate | Kind.Telemetry
            | Kind.ApplyEdit | Kind.Configuration | Kind.WorkspaceFolders
            | Kind.RefreshSemanticTokens | Kind.RefreshInlineValue | Kind.RefreshInlayHint
            | Kind.RefreshDiagnostic | Kind.RefreshCodeLens
            | Kind.RegisterCapability | Kind.UnregisterCapability
            | Kind.PublishDiagnostics | Kind.LogTrace => Direction.ClientHandled
        case Kind.CancelRequest | Kind.Progress => Direction.Either
        case _                                  => Direction.ServerHandled

    /** A single `.error[E2]` registration: the type tag, the schema, and the wire code/message. */
    final private[kyo] class ErrorMapping[E](val code: Int, val message: String)(using val tag: ConcreteTag[E], val schema: Schema[E]):
        def matches(e: Any): Boolean = tag.accepts(e)
    end ErrorMapping

    // Shorthand effect row used by factory stubs (private to avoid top-level leak).
    private[kyo] type LE[E] = Async & Abort[LspException | JsonRpcResponse.Halt | E]

    // Internal factory helpers to avoid repeating summon calls at every factory site.
    private[kyo] def initRequest[In: Schema, Out: Schema, E](
        name: String,
        kind: Kind,
        fn: In => Out < LE[E],
        errorMappings: Chunk[ErrorMapping[?]] = Chunk.empty
    ): RequestHandler[In, Out, LspException | E] =
        new RequestHandler(name, kind, fn, errorMappings, summon[Schema[In]], summon[Schema[Out]])

    private[kyo] def initNotification[In: Schema, E](
        name: String,
        kind: Kind,
        fn: In => Unit < LE[E],
        errorMappings: Chunk[ErrorMapping[?]] = Chunk.empty
    ): NotificationHandler[In, LspException | E] =
        new NotificationHandler(name, kind, fn, errorMappings, summon[Schema[In]])

    private[kyo] def initCustom[In: Schema, Out: Schema, E](
        name: String,
        dir: Direction,
        fn: In => Out < LE[E],
        errorMappings: Chunk[ErrorMapping[?]] = Chunk.empty
    ): CustomHandler[In, Out, LspException | E] =
        new CustomHandler(name, dir, fn, errorMappings, summon[Schema[In]], summon[Schema[Out]])

    // MARK: -- Core types (Position, Range, Location, LocationLink, Color, ...)

    /** A zero-based line and character position in a text document. */
    final case class Position(line: Int, character: Int) derives Schema, CanEqual

    /** A range in a text document expressed as start and end positions. */
    final case class Range(start: Position, end: Position) derives Schema, CanEqual

    /** A location inside a resource, such as a line inside a text file. */
    final case class Location(uri: String, range: Range) derives Schema, CanEqual

    /** A link between a source and a target location. */
    final case class LocationLink(
        originSelectionRange: Maybe[Range] = Absent,
        targetUri: String,
        targetRange: Range,
        targetSelectionRange: Range
    ) derives Schema, CanEqual

    /** An RGBA color. */
    final case class Color(red: Double, green: Double, blue: Double, alpha: Double) derives Schema, CanEqual

    /** A color range inside a text document. */
    final case class ColorInformation(range: Range, color: Color) derives Schema, CanEqual

    /** A presentation for a color value. */
    final case class ColorPresentation(
        label: String,
        textEdit: Maybe[TextEdit] = Absent,
        additionalTextEdits: Chunk[TextEdit] = Chunk.empty
    ) derives Schema, CanEqual

    /** A highlight in a text document. */
    final case class DocumentHighlight(range: Range, kind: Maybe[DocumentHighlightKind] = Absent) derives Schema, CanEqual

    /** The kind of a document highlight. */
    enum DocumentHighlightKind derives CanEqual:
        case Text, Read, Write

    object DocumentHighlightKind:
        given Schema[DocumentHighlightKind] = internal.lsp.LspWireEnumSchemas.documentHighlightKindSchema

    // MARK: -- Document types (LspDocument + Uri opaque + identifier records + TextDocumentContentChangeEvent + TextDocumentSyncKind)

    /** A managed text document tracked by the document registry.
      *
      * Users receive instances through `Lsp.documents.get(uri)`. The `encoding` field is stamped
      * by the engine at insert time and is not part of the public constructor surface.
      */
    final case class LspDocument(
        uri: LspDocument.Uri,
        languageId: String,
        version: Int,
        text: String,
        private[kyo] encoding: PositionEncodingKind = PositionEncodingKind.UTF16
    ) derives CanEqual

    object LspDocument:

        /** Opaque URI for a text document. Use [[parse]] to construct; the engine uses [[fromWire]].
          *
          * Validation: non-empty, not all-whitespace. Mirrors `McpResourceUri` shape.
          */
        opaque type Uri = String

        object Uri:
            /** Constructs a URI from a string, returning `Absent` if empty or blank. */
            def parse(s: String): Maybe[Uri] =
                if s.nonEmpty && !s.forall(_.isWhitespace) then Present(s) else Absent

            private[kyo] def fromWire(s: String): Uri = s

            extension (u: Uri)
                def asString: String = u

            given Schema[Uri]        = Schema.stringSchema.transform[Uri](fromWire)(_.asString)
            given CanEqual[Uri, Uri] = CanEqual.derived
        end Uri

        given Schema[LspDocument] = Schema.derived

        /** Applies a sequence of incremental or full text changes to a document.
          * Returns the updated document with the new text and incremented version.
          */
        def applyChanges(doc: LspDocument, changes: Chunk[TextDocumentContentChangeEvent]): LspDocument =
            changes.foldLeft(doc) { (current, change) =>
                change match
                    case TextDocumentContentChangeEvent.Full(text) =>
                        current.copy(text = text, version = current.version + 1)
                    case TextDocumentContentChangeEvent.Incremental(range, text) =>
                        val lines     = current.text.split("\n", -1)
                        val before    = lines.take(range.start.line)
                        val startLine = if range.start.line < lines.length then lines(range.start.line) else ""
                        val endLine   = if range.end.line < lines.length then lines(range.end.line) else ""
                        val prefix    = startLine.take(range.start.character)
                        val suffix    = if range.end.character <= endLine.length then endLine.drop(range.end.character) else ""
                        val after     = lines.drop(range.end.line + 1)
                        val newLine   = prefix + text + suffix
                        val allLines  = before ++ Array(newLine) ++ after
                        current.copy(text = allLines.mkString("\n"), version = current.version + 1)
            }

    end LspDocument

    /** A text document identifier (URI only, no version). */
    final case class TextDocumentIdentifier(uri: LspDocument.Uri) derives Schema, CanEqual

    /** A text document identifier with a version number. */
    final case class VersionedTextDocumentIdentifier(uri: LspDocument.Uri, version: Int) derives Schema, CanEqual

    /** A text document identifier with an optional version number. */
    final case class OptionalVersionedTextDocumentIdentifier(uri: LspDocument.Uri, version: Maybe[Int] = Absent) derives Schema, CanEqual

    /** Describes a text document to be opened. */
    final case class TextDocumentItem(uri: LspDocument.Uri, languageId: String, version: Int, text: String) derives Schema, CanEqual

    /** Describes how text document synchronization is achieved. */
    enum TextDocumentSyncKind derives CanEqual:
        case None, Incremental, Full

    object TextDocumentSyncKind:
        given Schema[TextDocumentSyncKind] = internal.lsp.LspWireEnumSchemas.textDocumentSyncKindSchema

    /** Options for text document synchronization. */
    final case class TextDocumentSyncOptions(
        openClose: Maybe[Boolean] = Absent,
        change: Maybe[TextDocumentSyncKind] = Absent,
        willSave: Maybe[Boolean] = Absent,
        willSaveWaitUntil: Maybe[Boolean] = Absent,
        save: Maybe[BooleanOr[SaveOptions]] = Absent
    ) derives Schema, CanEqual

    /** Options for save notifications. */
    final case class SaveOptions(includeText: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** A text document content change event. Full or incremental. */
    sealed trait TextDocumentContentChangeEvent derives CanEqual

    object TextDocumentContentChangeEvent:
        /** A full document content change (no range). */
        final case class Full(text: String) extends TextDocumentContentChangeEvent

        /** An incremental document content change within a specific range. */
        final case class Incremental(range: Range, text: String) extends TextDocumentContentChangeEvent

        given Schema[TextDocumentContentChangeEvent] = internal.lsp.LspContentSchemas.textDocumentContentChangeEventSchema
    end TextDocumentContentChangeEvent

    /** The reason why a text document is saved. */
    enum TextDocumentSaveReason derives CanEqual:
        case Manual, AfterDelay, FocusOut

    object TextDocumentSaveReason:
        given Schema[TextDocumentSaveReason] = internal.lsp.LspWireEnumSchemas.textDocumentSaveReasonSchema

    /** The kind of position encoding. */
    opaque type PositionEncodingKind = String

    object PositionEncodingKind:
        val UTF8: PositionEncodingKind  = "utf-8"
        val UTF16: PositionEncodingKind = "utf-16"
        val UTF32: PositionEncodingKind = "utf-32"

        def apply(s: String): PositionEncodingKind = s

        extension (k: PositionEncodingKind)
            def asString: String = k

        given Schema[PositionEncodingKind]                         = Schema.stringSchema.transform[PositionEncodingKind](apply)(_.asString)
        given CanEqual[PositionEncodingKind, PositionEncodingKind] = CanEqual.derived
    end PositionEncodingKind

    // MARK: -- Progress types (ProgressToken + WorkDoneProgressValue + WorkDoneProgress* records)

    /** An LSP progress token: either an integer or a string. */
    sealed trait ProgressToken derives CanEqual

    object ProgressToken:
        final case class IntToken(value: Int)       extends ProgressToken
        final case class StringToken(value: String) extends ProgressToken
        given Schema[ProgressToken] = internal.lsp.LspContentSchemas.progressTokenSchema
    end ProgressToken

    /** The value of a work-done progress notification. */
    sealed trait WorkDoneProgressValue derives CanEqual

    object WorkDoneProgressValue:
        final case class Begin(
            title: String,
            cancellable: Maybe[Boolean] = Absent,
            message: Maybe[String] = Absent,
            percentage: Maybe[Int] = Absent
        ) extends WorkDoneProgressValue

        final case class Report(
            cancellable: Maybe[Boolean] = Absent,
            message: Maybe[String] = Absent,
            percentage: Maybe[Int] = Absent
        ) extends WorkDoneProgressValue

        final case class End(message: Maybe[String] = Absent) extends WorkDoneProgressValue

        given Schema[WorkDoneProgressValue] = internal.lsp.LspContentSchemas.workDoneProgressValueSchema
    end WorkDoneProgressValue

    /** Options for work-done progress support. */
    final case class WorkDoneProgressOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Parameters carrying an optional work-done progress token. */
    final case class WorkDoneProgressParams(workDoneToken: Maybe[ProgressToken] = Absent) derives Schema, CanEqual

    /** Parameters carrying an optional partial-result token. */
    final case class PartialResultParams(partialResultToken: Maybe[ProgressToken] = Absent) derives Schema, CanEqual

    /** Parameters for the `window/workDoneProgress/create` request. */
    final case class WorkDoneProgressCreateParams(token: ProgressToken) derives Schema, CanEqual

    /** Parameters for the `window/workDoneProgress/cancel` notification. */
    final case class WorkDoneProgressCancelParams(token: ProgressToken) derives Schema, CanEqual

    /** Parameters for the `$/progress` notification. The value type T is application-defined: servers emit work-done
      * progress shapes (WorkDoneProgressBegin, WorkDoneProgressReport, WorkDoneProgressEnd) and clients emit
      * partial-result chunks whose shape is specific to the originating request. The Schema constraint is enforced at
      * factory and encode sites.
      */
    final case class ProgressParams[T](token: ProgressToken, value: T) derives CanEqual

    // MARK: -- Markup types (MarkupContent, MarkupKind, MarkedString, HoverContents)

    /** The kind of markup content. */
    enum MarkupKind derives CanEqual:
        case PlainText, Markdown

    object MarkupKind:
        given Schema[MarkupKind] = internal.lsp.LspWireEnumSchemas.markupKindSchema

    /** A human-readable string that can be rendered as plain text or markdown. */
    final case class MarkupContent(kind: MarkupKind, value: String) derives Schema, CanEqual

    /** A marked string can be used to render human readable text. Deprecated in LSP 3.x; use MarkupContent. */
    sealed trait MarkedString derives CanEqual

    object MarkedString:
        final case class Plain(value: String)                  extends MarkedString
        final case class Code(language: String, value: String) extends MarkedString
        given Schema[MarkedString] = internal.lsp.LspContentSchemas.markedStringSchema
    end MarkedString

    /** The content of a hover response: either MarkupContent or one/more MarkedStrings. */
    sealed trait HoverContents derives CanEqual

    object HoverContents:
        final case class Markup(value: MarkupContent)        extends HoverContents
        final case class Strings(value: Chunk[MarkedString]) extends HoverContents
        given Schema[HoverContents] = internal.lsp.LspContentSchemas.hoverContentsSchema
    end HoverContents

    // MARK: -- Diagnostic types

    /** Represents the severity of a diagnostic. */
    enum DiagnosticSeverity derives CanEqual:
        case Error, Warning, Information, Hint

    object DiagnosticSeverity:
        given Schema[DiagnosticSeverity] = internal.lsp.LspWireEnumSchemas.diagnosticSeveritySchema

    /** Diagnostic tags to flag special kinds of diagnostics. */
    enum DiagnosticTag derives CanEqual:
        case Unnecessary, Deprecated

    object DiagnosticTag:
        given Schema[DiagnosticTag] = internal.lsp.LspWireEnumSchemas.diagnosticTagSchema

    /** Structure to capture a description for an error code. */
    final case class CodeDescription(href: String) derives Schema, CanEqual

    /** Represents a related message and source code location for a diagnostic. */
    final case class DiagnosticRelatedInformation(location: Location, message: String) derives Schema, CanEqual

    /** Represents a diagnostic, such as a compiler error or warning. */
    final case class Diagnostic(
        range: Range,
        severity: Maybe[DiagnosticSeverity] = Absent,
        code: Maybe[String] = Absent,
        codeDescription: Maybe[CodeDescription] = Absent,
        source: Maybe[String] = Absent,
        message: String,
        tags: Chunk[DiagnosticTag] = Chunk.empty,
        relatedInformation: Chunk[DiagnosticRelatedInformation] = Chunk.empty
    ) derives Schema, CanEqual

    /** Parameters for the `textDocument/publishDiagnostics` notification. */
    final case class PublishDiagnosticsParams(
        uri: LspDocument.Uri,
        version: Maybe[Int] = Absent,
        diagnostics: Chunk[Diagnostic]
    ) derives Schema, CanEqual

    /** A document diagnostic report. */
    sealed trait DocumentDiagnosticReport derives CanEqual

    object DocumentDiagnosticReport:
        final case class Full(kind: String = "full", resultId: Maybe[String] = Absent, items: Chunk[Diagnostic])
            extends DocumentDiagnosticReport
        final case class Unchanged(kind: String = "unchanged", resultId: String) extends DocumentDiagnosticReport
        given Schema[DocumentDiagnosticReport] = internal.lsp.LspContentSchemas.documentDiagnosticReportSchema
    end DocumentDiagnosticReport

    /** A workspace diagnostic report. */
    sealed trait WorkspaceDocumentDiagnosticReport derives CanEqual

    object WorkspaceDocumentDiagnosticReport:
        final case class Full(
            uri: LspDocument.Uri,
            version: Maybe[Int] = Absent,
            kind: String = "full",
            resultId: Maybe[String] = Absent,
            items: Chunk[Diagnostic]
        ) extends WorkspaceDocumentDiagnosticReport
        final case class Unchanged(uri: LspDocument.Uri, version: Maybe[Int] = Absent, kind: String = "unchanged", resultId: String)
            extends WorkspaceDocumentDiagnosticReport
        given Schema[WorkspaceDocumentDiagnosticReport] = internal.lsp.LspContentSchemas.workspaceDocumentDiagnosticReportSchema
    end WorkspaceDocumentDiagnosticReport

    /** The result of a workspace diagnostic request. */
    final case class WorkspaceDiagnosticReport(items: Chunk[WorkspaceDocumentDiagnosticReport]) derives Schema, CanEqual

    /** Parameters for `textDocument/diagnostic`. */
    final case class DocumentDiagnosticParams(
        textDocument: TextDocumentIdentifier,
        identifier: Maybe[String] = Absent,
        previousResultId: Maybe[String] = Absent,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `workspace/diagnostic`. */
    final case class WorkspaceDiagnosticParams(
        identifier: Maybe[String] = Absent,
        previousResultIds: Chunk[PreviousResultId] = Chunk.empty,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** A previous result id in a workspace pull request. */
    final case class PreviousResultId(uri: LspDocument.Uri, value: String) derives Schema, CanEqual

    // MARK: -- Symbol types

    /** A symbol kind. */
    enum SymbolKind derives CanEqual:
        case File, Module, Namespace, Package, Class, Method, Property, Field, Constructor
        case Enum, Interface, Function, Variable, Constant, String, Number, Boolean, Array
        case Object, Key, Null, EnumMember, Struct, Event, Operator, TypeParameter
    end SymbolKind

    object SymbolKind:
        given Schema[SymbolKind] = internal.lsp.LspWireEnumSchemas.symbolKindSchema

    /** A symbol tag. */
    enum SymbolTag derives CanEqual:
        case Deprecated

    object SymbolTag:
        given Schema[SymbolTag] = internal.lsp.LspWireEnumSchemas.symbolTagSchema

    /** Represents programming constructs like variables, classes, interfaces etc. that appear in a document. */
    final case class DocumentSymbol(
        name: String,
        detail: Maybe[String] = Absent,
        kind: SymbolKind,
        tags: Chunk[SymbolTag] = Chunk.empty,
        deprecated: Maybe[Boolean] = Absent,
        range: Range,
        selectionRange: Range,
        children: Chunk[DocumentSymbol] = Chunk.empty
    ) derives Schema, CanEqual

    /** Represents information about programming constructs like variables, classes, interfaces etc. */
    final case class SymbolInformation(
        name: String,
        kind: SymbolKind,
        tags: Chunk[SymbolTag] = Chunk.empty,
        deprecated: Maybe[Boolean] = Absent,
        location: Location,
        containerName: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A document symbol result: either a list of DocumentSymbol or SymbolInformation. */
    sealed trait DocumentSymbolResult derives CanEqual

    object DocumentSymbolResult:
        final case class Symbols(items: Chunk[DocumentSymbol])        extends DocumentSymbolResult
        final case class Information(items: Chunk[SymbolInformation]) extends DocumentSymbolResult
        given Schema[DocumentSymbolResult] = internal.lsp.LspContentSchemas.documentSymbolResultSchema
    end DocumentSymbolResult

    /** A workspace symbol with typed data carrier. */
    final case class WorkspaceSymbol(
        name: String,
        kind: SymbolKind,
        tags: Chunk[SymbolTag] = Chunk.empty,
        containerName: Maybe[String] = Absent,
        location: WorkspaceSymbolLocation,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): WorkspaceSymbol = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("workspace/symbol", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("workspace/symbol", e.getMessage, e))
    end WorkspaceSymbol

    object WorkspaceSymbol:
        given Schema[WorkspaceSymbol] = Schema.derived

    /** The location of a workspace symbol: either a URI with range or a URI only. */
    sealed trait WorkspaceSymbolLocation derives CanEqual

    object WorkspaceSymbolLocation:
        final case class WithRange(uri: LspDocument.Uri, range: Range) extends WorkspaceSymbolLocation
        final case class UriOnly(uri: LspDocument.Uri)                 extends WorkspaceSymbolLocation
        given Schema[WorkspaceSymbolLocation] = internal.lsp.LspContentSchemas.workspaceSymbolLocationSchema
    end WorkspaceSymbolLocation

    // MARK: -- Completion types

    /** The kind of a completion item. */
    enum CompletionItemKind derives CanEqual:
        case Text, Method, Function, Constructor, Field, Variable, Class, Interface, Module
        case Property, Unit, Value, Enum, Keyword, Snippet, Color, File, Reference, Folder
        case EnumMember, Constant, Struct, Event, Operator, TypeParameter
    end CompletionItemKind

    object CompletionItemKind:
        given Schema[CompletionItemKind] = internal.lsp.LspWireEnumSchemas.completionItemKindSchema

    /** A tag for completion items. */
    enum CompletionItemTag derives CanEqual:
        case Deprecated

    object CompletionItemTag:
        given Schema[CompletionItemTag] = internal.lsp.LspWireEnumSchemas.completionItemTagSchema

    /** How whitespace and indentation is handled during completion item insertion. */
    enum InsertTextFormat derives CanEqual:
        case PlainText, Snippet

    object InsertTextFormat:
        given Schema[InsertTextFormat] = internal.lsp.LspWireEnumSchemas.insertTextFormatSchema

    /** How the insert text of a completion item is treated. */
    enum InsertTextMode derives CanEqual:
        case AsIs, AdjustIndentation

    object InsertTextMode:
        given Schema[InsertTextMode] = internal.lsp.LspWireEnumSchemas.insertTextModeSchema

    /** The trigger kind for a completion request. */
    enum CompletionTriggerKind derives CanEqual:
        case Invoked, TriggerCharacter, TriggerForIncompleteCompletions

    object CompletionTriggerKind:
        given Schema[CompletionTriggerKind] = internal.lsp.LspWireEnumSchemas.completionTriggerKindSchema

    /** Contains additional information about the context in which a completion request is triggered. */
    final case class CompletionContext(
        triggerKind: CompletionTriggerKind,
        triggerCharacter: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** Additional details for a completion item label. */
    final case class CompletionItemLabelDetails(
        detail: Maybe[String] = Absent,
        description: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A special text edit to provide an insert and a replace operation. */
    final case class InsertReplaceEdit(newText: String, insert: Range, replace: Range) derives Schema, CanEqual

    /** A completion item with typed data carrier. */
    final case class CompletionItem(
        label: String,
        labelDetails: Maybe[CompletionItemLabelDetails] = Absent,
        kind: Maybe[CompletionItemKind] = Absent,
        tags: Chunk[CompletionItemTag] = Chunk.empty,
        detail: Maybe[String] = Absent,
        documentation: Maybe[MarkupContent] = Absent,
        deprecated: Maybe[Boolean] = Absent,
        preselect: Maybe[Boolean] = Absent,
        sortText: Maybe[String] = Absent,
        filterText: Maybe[String] = Absent,
        insertText: Maybe[String] = Absent,
        insertTextFormat: Maybe[InsertTextFormat] = Absent,
        insertTextMode: Maybe[InsertTextMode] = Absent,
        textEdit: Maybe[TextEdit] = Absent,
        textEditText: Maybe[String] = Absent,
        additionalTextEdits: Chunk[TextEdit] = Chunk.empty,
        commitCharacters: Chunk[String] = Chunk.empty,
        command: Maybe[Command] = Absent,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): CompletionItem = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("textDocument/completion", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("textDocument/completion", e.getMessage, e))
    end CompletionItem

    object CompletionItem:
        given Schema[CompletionItem] = Schema.derived

    /** Optional default values for completions returned by the server. */
    final case class CompletionItemDefaults(
        commitCharacters: Chunk[String] = Chunk.empty,
        editRange: Maybe[Range] = Absent,
        insertTextFormat: Maybe[InsertTextFormat] = Absent,
        insertTextMode: Maybe[InsertTextMode] = Absent
    ) derives Schema, CanEqual

    /** Represents a collection of completion items to be presented in the editor. */
    final case class CompletionList(
        isIncomplete: Boolean,
        itemDefaults: Maybe[CompletionItemDefaults] = Absent,
        items: Chunk[CompletionItem]
    ) derives Schema, CanEqual

    /** The completion result: either a list of CompletionItem or a CompletionList. */
    sealed trait CompletionResult derives CanEqual

    object CompletionResult:
        final case class Items(items: Chunk[CompletionItem]) extends CompletionResult
        final case class List(list: CompletionList)          extends CompletionResult
        given Schema[CompletionResult] = internal.lsp.LspContentSchemas.completionResultSchema
    end CompletionResult

    /** Options to register for completion. */
    final case class CompletionOptions(
        workDoneProgress: Maybe[Boolean] = Absent,
        triggerCharacters: Chunk[String] = Chunk.empty,
        allCommitCharacters: Chunk[String] = Chunk.empty,
        resolveProvider: Maybe[Boolean] = Absent,
        completionItem: Maybe[CompletionOptions.ItemOptions] = Absent
    ) derives Schema, CanEqual

    object CompletionOptions:
        final case class ItemOptions(labelDetailsSupport: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- Signature help types

    /** How a signature help was triggered. */
    enum SignatureHelpTriggerKind derives CanEqual:
        case Invoked, TriggerCharacter, ContentChange

    object SignatureHelpTriggerKind:
        given Schema[SignatureHelpTriggerKind] = internal.lsp.LspWireEnumSchemas.signatureHelpTriggerKindSchema

    /** Additional information about the context in which a signature help request was triggered. */
    final case class SignatureHelpContext(
        triggerKind: SignatureHelpTriggerKind,
        triggerCharacter: Maybe[String] = Absent,
        isRetrigger: Boolean,
        activeSignatureHelp: Maybe[SignatureHelp] = Absent
    ) derives Schema, CanEqual

    /** The label in a parameter information can be a range or a string. */
    sealed trait ParameterLabel derives CanEqual

    object ParameterLabel:
        final case class StringLabel(value: String)       extends ParameterLabel
        final case class RangeLabel(start: Int, end: Int) extends ParameterLabel
        given Schema[ParameterLabel] = internal.lsp.LspContentSchemas.parameterLabelSchema
    end ParameterLabel

    /** Represents a parameter of a callable-signature. */
    final case class ParameterInformation(
        label: ParameterLabel,
        documentation: Maybe[MarkupContent] = Absent
    ) derives Schema, CanEqual

    /** Represents the signature of a callable. */
    final case class SignatureInformation(
        label: String,
        documentation: Maybe[MarkupContent] = Absent,
        parameters: Chunk[ParameterInformation] = Chunk.empty,
        activeParameter: Maybe[Int] = Absent
    ) derives Schema, CanEqual

    /** Signature help represents the signature of a callable (a function, a method, etc.). */
    final case class SignatureHelp(
        signatures: Chunk[SignatureInformation],
        activeSignature: Maybe[Int] = Absent,
        activeParameter: Maybe[Int] = Absent
    ) derives Schema, CanEqual

    /** Registration options for signature help. */
    final case class SignatureHelpOptions(
        workDoneProgress: Maybe[Boolean] = Absent,
        triggerCharacters: Chunk[String] = Chunk.empty,
        retriggerCharacters: Chunk[String] = Chunk.empty
    ) derives Schema, CanEqual

    // MARK: -- Code action + command types

    /** A reference to a command. */
    final case class Command(title: String, command: String, arguments: Chunk[String] = Chunk.empty) derives Schema, CanEqual

    /** An opaque string code action kind (extensible set). */
    opaque type CodeActionKind = String

    object CodeActionKind:
        val Empty: CodeActionKind                 = ""
        val QuickFix: CodeActionKind              = "quickfix"
        val Refactor: CodeActionKind              = "refactor"
        val RefactorExtract: CodeActionKind       = "refactor.extract"
        val RefactorInline: CodeActionKind        = "refactor.inline"
        val RefactorRewrite: CodeActionKind       = "refactor.rewrite"
        val Source: CodeActionKind                = "source"
        val SourceOrganizeImports: CodeActionKind = "source.organizeImports"
        val SourceFixAll: CodeActionKind          = "source.fixAll"

        def apply(s: String): CodeActionKind = s

        extension (k: CodeActionKind)
            def asString: String = k

        given Schema[CodeActionKind]                   = Schema.stringSchema.transform[CodeActionKind](apply)(_.asString)
        given CanEqual[CodeActionKind, CodeActionKind] = CanEqual.derived
    end CodeActionKind

    /** The trigger kind for a code action request. */
    enum CodeActionTriggerKind derives CanEqual:
        case Invoked, Automatic

    object CodeActionTriggerKind:
        given Schema[CodeActionTriggerKind] = internal.lsp.LspWireEnumSchemas.codeActionTriggerKindSchema

    /** Contains additional diagnostic information about the context in which a code action is run. */
    final case class CodeActionContext(
        diagnostics: Chunk[Diagnostic],
        only: Chunk[CodeActionKind] = Chunk.empty,
        triggerKind: Maybe[CodeActionTriggerKind] = Absent
    ) derives Schema, CanEqual

    /** A code action disabled info. */
    final case class CodeActionDisabled(reason: String) derives Schema, CanEqual

    /** A code action with typed data carrier. */
    final case class CodeAction(
        title: String,
        kind: Maybe[CodeActionKind] = Absent,
        diagnostics: Chunk[Diagnostic] = Chunk.empty,
        isPreferred: Maybe[Boolean] = Absent,
        disabled: Maybe[CodeActionDisabled] = Absent,
        edit: Maybe[WorkspaceEdit] = Absent,
        command: Maybe[Command] = Absent,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): CodeAction = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("textDocument/codeAction", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("textDocument/codeAction", e.getMessage, e))
    end CodeAction

    object CodeAction:
        given Schema[CodeAction] = Schema.derived

    /** A command-or-code-action discriminated union. */
    sealed trait CommandOrCodeAction derives CanEqual

    object CommandOrCodeAction:
        final case class Cmd(value: Command)       extends CommandOrCodeAction
        final case class Action(value: CodeAction) extends CommandOrCodeAction
        given Schema[CommandOrCodeAction] = internal.lsp.LspContentSchemas.commandOrCodeActionSchema
    end CommandOrCodeAction

    /** Options for code action registration. */
    final case class CodeActionOptions(
        workDoneProgress: Maybe[Boolean] = Absent,
        codeActionKinds: Chunk[CodeActionKind] = Chunk.empty,
        resolveProvider: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    // MARK: -- Code lens types

    /** A code lens with typed data carrier. */
    final case class CodeLens(
        range: Range,
        command: Maybe[Command] = Absent,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): CodeLens = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("textDocument/codeLens", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("textDocument/codeLens", e.getMessage, e))
    end CodeLens

    object CodeLens:
        given Schema[CodeLens] = Schema.derived

    /** Code lens registration options. */
    final case class CodeLensOptions(workDoneProgress: Maybe[Boolean] = Absent, resolveProvider: Maybe[Boolean] = Absent) derives Schema,
          CanEqual

    // MARK: -- Document link types

    /** A document link with typed data carrier. */
    final case class DocumentLink(
        range: Range,
        target: Maybe[String] = Absent,
        tooltip: Maybe[String] = Absent,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): DocumentLink = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("textDocument/documentLink", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("textDocument/documentLink", e.getMessage, e))
    end DocumentLink

    object DocumentLink:
        given Schema[DocumentLink] = Schema.derived

    /** Document link registration options. */
    final case class DocumentLinkOptions(workDoneProgress: Maybe[Boolean] = Absent, resolveProvider: Maybe[Boolean] = Absent)
        derives Schema, CanEqual

    // MARK: -- Formatting types

    /** A text edit applicable to a text document. */
    final case class TextEdit(range: Range, newText: String) derives Schema, CanEqual

    /** An annotated text edit. */
    final case class AnnotatedTextEdit(range: Range, newText: String, annotationId: String) derives Schema, CanEqual

    /** An identifier for a change annotation. */
    final case class ChangeAnnotation(label: String, needsConfirmation: Maybe[Boolean] = Absent, description: Maybe[String] = Absent)
        derives Schema, CanEqual

    /** A text document edit (versioned). */
    final case class TextDocumentEdit(
        textDocument: OptionalVersionedTextDocumentIdentifier,
        edits: Chunk[TextEdit]
    ) derives Schema, CanEqual

    /** The kind of a file create/rename/delete operation. */
    enum ResourceOperationKind derives CanEqual:
        case Create, Rename, Delete

    object ResourceOperationKind:
        given Schema[ResourceOperationKind] = internal.lsp.LspWireEnumSchemas.resourceOperationKindSchema

    /** Options for creating a file. */
    final case class CreateFileOptions(overwrite: Maybe[Boolean] = Absent, ignoreIfExists: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for renaming a file. */
    final case class RenameFileOptions(overwrite: Maybe[Boolean] = Absent, ignoreIfExists: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for deleting a file. */
    final case class DeleteFileOptions(recursive: Maybe[Boolean] = Absent, ignoreIfNotExists: Maybe[Boolean] = Absent) derives Schema,
          CanEqual

    /** Create file operation. */
    final case class CreateFile(
        kind: String = "create",
        uri: String,
        options: Maybe[CreateFileOptions] = Absent,
        annotationId: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** Rename file operation. */
    final case class RenameFile(
        kind: String = "rename",
        oldUri: String,
        newUri: String,
        options: Maybe[RenameFileOptions] = Absent,
        annotationId: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** Delete file operation. */
    final case class DeleteFile(
        kind: String = "delete",
        uri: String,
        options: Maybe[DeleteFileOptions] = Absent,
        annotationId: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A document change can be a TextDocumentEdit, CreateFile, RenameFile, or DeleteFile. */
    sealed trait WorkspaceEditDocumentChange derives CanEqual

    object WorkspaceEditDocumentChange:
        final case class Edit(value: TextDocumentEdit) extends WorkspaceEditDocumentChange
        final case class Create(value: CreateFile)     extends WorkspaceEditDocumentChange
        final case class Rename(value: RenameFile)     extends WorkspaceEditDocumentChange
        final case class Delete(value: DeleteFile)     extends WorkspaceEditDocumentChange
        given Schema[WorkspaceEditDocumentChange] = internal.lsp.LspContentSchemas.workspaceEditDocumentChangeSchema
    end WorkspaceEditDocumentChange

    /** A workspace edit represents changes to many resources managed in the workspace. */
    final case class WorkspaceEdit(
        changes: Maybe[Map[String, Chunk[TextEdit]]] = Absent,
        documentChanges: Chunk[WorkspaceEditDocumentChange] = Chunk.empty,
        changeAnnotations: Maybe[Map[String, ChangeAnnotation]] = Absent
    ) derives Schema, CanEqual

    /** Value-object describing what options formatting should use. */
    final case class FormattingOptions(
        tabSize: Int,
        insertSpaces: Boolean,
        trimTrailingWhitespace: Maybe[Boolean] = Absent,
        insertFinalNewline: Maybe[Boolean] = Absent,
        trimFinalNewlines: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/formatting`. */
    final case class DocumentFormattingParams(
        textDocument: TextDocumentIdentifier,
        options: FormattingOptions,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/rangeFormatting`. */
    final case class DocumentRangeFormattingParams(
        textDocument: TextDocumentIdentifier,
        range: Range,
        options: FormattingOptions,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/onTypeFormatting`. */
    final case class DocumentOnTypeFormattingParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        ch: String,
        options: FormattingOptions
    ) derives Schema, CanEqual

    /** On-type formatting registration options. */
    final case class DocumentOnTypeFormattingOptions(
        firstTriggerCharacter: String,
        moreTriggerCharacter: Chunk[String] = Chunk.empty
    ) derives Schema, CanEqual

    // MARK: -- Navigation types (declaration, definition, typeDefinition, implementation, references, documentHighlight, prepareRename)

    /** A reference context. */
    final case class ReferenceContext(includeDeclaration: Boolean) derives Schema, CanEqual

    /** A hover response. */
    final case class Hover(contents: HoverContents, range: Maybe[Range] = Absent) derives Schema, CanEqual

    /** Options for the rename operation. */
    final case class RenameOptions(workDoneProgress: Maybe[Boolean] = Absent, prepareProvider: Maybe[Boolean] = Absent) derives Schema,
          CanEqual

    /** The result of a prepare rename request. */
    sealed trait PrepareRenameResult derives CanEqual

    object PrepareRenameResult:
        final case class JustRange(range: Range)                                 extends PrepareRenameResult
        final case class RangeWithPlaceholder(range: Range, placeholder: String) extends PrepareRenameResult
        final case class DefaultBehavior(defaultBehavior: Boolean)               extends PrepareRenameResult
        given Schema[PrepareRenameResult] = internal.lsp.LspContentSchemas.prepareRenameResultSchema
    end PrepareRenameResult

    /** A definition result: one or more locations. */
    sealed trait DefinitionResult derives CanEqual

    object DefinitionResult:
        final case class One(location: Location)           extends DefinitionResult
        final case class Many(locations: Chunk[Location])  extends DefinitionResult
        final case class Links(links: Chunk[LocationLink]) extends DefinitionResult
        given Schema[DefinitionResult] = internal.lsp.LspContentSchemas.definitionResultSchema
    end DefinitionResult

    /** A declaration result: one or more locations. */
    sealed trait DeclarationResult derives CanEqual

    object DeclarationResult:
        final case class One(location: Location)           extends DeclarationResult
        final case class Many(locations: Chunk[Location])  extends DeclarationResult
        final case class Links(links: Chunk[LocationLink]) extends DeclarationResult
        given Schema[DeclarationResult] = internal.lsp.LspContentSchemas.declarationResultSchema
    end DeclarationResult

    /** A type definition result: one or more locations. */
    sealed trait TypeDefinitionResult derives CanEqual

    object TypeDefinitionResult:
        final case class One(location: Location)           extends TypeDefinitionResult
        final case class Many(locations: Chunk[Location])  extends TypeDefinitionResult
        final case class Links(links: Chunk[LocationLink]) extends TypeDefinitionResult
        given Schema[TypeDefinitionResult] = internal.lsp.LspContentSchemas.typeDefinitionResultSchema
    end TypeDefinitionResult

    /** An implementation result: one or more locations. */
    sealed trait ImplementationResult derives CanEqual

    object ImplementationResult:
        final case class One(location: Location)           extends ImplementationResult
        final case class Many(locations: Chunk[Location])  extends ImplementationResult
        final case class Links(links: Chunk[LocationLink]) extends ImplementationResult
        given Schema[ImplementationResult] = internal.lsp.LspContentSchemas.implementationResultSchema
    end ImplementationResult

    /** Options for hover registration. */
    final case class HoverOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for definition registration. */
    final case class DefinitionOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for declaration registration. */
    final case class DeclarationOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for type definition registration. */
    final case class TypeDefinitionOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for implementation registration. */
    final case class ImplementationOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for references registration. */
    final case class ReferenceOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for document highlight registration. */
    final case class DocumentHighlightOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** Options for document symbol registration. */
    final case class DocumentSymbolOptions(workDoneProgress: Maybe[Boolean] = Absent, label: Maybe[String] = Absent) derives Schema,
          CanEqual

    // MARK: -- Hierarchy types (call hierarchy, type hierarchy, monikers)

    /** A call hierarchy item with typed data carrier. */
    final case class CallHierarchyItem(
        name: String,
        kind: SymbolKind,
        tags: Chunk[SymbolTag] = Chunk.empty,
        detail: Maybe[String] = Absent,
        uri: LspDocument.Uri,
        range: Range,
        selectionRange: Range,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): CallHierarchyItem = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("callHierarchy/prepare", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("callHierarchy/prepare", e.getMessage, e))
    end CallHierarchyItem

    object CallHierarchyItem:
        given Schema[CallHierarchyItem] = Schema.derived

    /** Represents an incoming call to a call hierarchy item. */
    final case class CallHierarchyIncomingCall(
        from: CallHierarchyItem,
        fromRanges: Chunk[Range]
    ) derives Schema, CanEqual

    /** Represents an outgoing call from a call hierarchy item. */
    final case class CallHierarchyOutgoingCall(
        to: CallHierarchyItem,
        fromRanges: Chunk[Range]
    ) derives Schema, CanEqual

    /** Options for call hierarchy registration. */
    final case class CallHierarchyOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** A type hierarchy item with typed data carrier. */
    final case class TypeHierarchyItem(
        name: String,
        kind: SymbolKind,
        tags: Chunk[SymbolTag] = Chunk.empty,
        detail: Maybe[String] = Absent,
        uri: LspDocument.Uri,
        range: Range,
        selectionRange: Range,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): TypeHierarchyItem = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("typeHierarchy/prepare", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("typeHierarchy/prepare", e.getMessage, e))
    end TypeHierarchyItem

    object TypeHierarchyItem:
        given Schema[TypeHierarchyItem] = Schema.derived

    /** Options for type hierarchy registration. */
    final case class TypeHierarchyOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** The moniker kind. */
    enum MonikerKind derives CanEqual:
        case Import, Export, Local

    object MonikerKind:
        given Schema[MonikerKind] = internal.lsp.LspWireEnumSchemas.monikerKindSchema

    /** The degree of uniqueness for a moniker. */
    enum UniquenessLevel derives CanEqual:
        case Document, Project, Group, Scheme, Global

    object UniquenessLevel:
        given Schema[UniquenessLevel] = internal.lsp.LspWireEnumSchemas.uniquenessLevelSchema

    /** A moniker is a language-agnostic name for a symbol. */
    final case class Moniker(
        scheme: String,
        identifier: String,
        unique: UniquenessLevel,
        kind: Maybe[MonikerKind] = Absent
    ) derives Schema, CanEqual

    /** Options for moniker registration. */
    final case class MonikerOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- Folding and selection types

    /** An opaque string folding range kind (extensible set). */
    opaque type FoldingRangeKind = String

    object FoldingRangeKind:
        val Comment: FoldingRangeKind = "comment"
        val Imports: FoldingRangeKind = "imports"
        val Region: FoldingRangeKind  = "region"

        def apply(s: String): FoldingRangeKind = s

        extension (k: FoldingRangeKind)
            def asString: String = k

        given Schema[FoldingRangeKind]                     = Schema.stringSchema.transform[FoldingRangeKind](apply)(_.asString)
        given CanEqual[FoldingRangeKind, FoldingRangeKind] = CanEqual.derived
    end FoldingRangeKind

    /** Represents a folding range inside a document. */
    final case class FoldingRange(
        startLine: Int,
        startCharacter: Maybe[Int] = Absent,
        endLine: Int,
        endCharacter: Maybe[Int] = Absent,
        kind: Maybe[FoldingRangeKind] = Absent,
        collapsedText: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** Options for folding range registration. */
    final case class FoldingRangeOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** A selection range in a document. */
    final case class SelectionRange(range: Range, parent: Maybe[SelectionRange] = Absent) derives Schema, CanEqual

    /** Linked editing ranges. */
    final case class LinkedEditingRanges(ranges: Chunk[Range], wordPattern: Maybe[String] = Absent) derives Schema, CanEqual

    /** Options for linked editing range registration. */
    final case class LinkedEditingRangeOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- Semantic token types

    /** An opaque string semantic token type (extensible set). */
    opaque type SemanticTokenTypes = String

    object SemanticTokenTypes:
        val Namespace: SemanticTokenTypes     = "namespace"
        val Type: SemanticTokenTypes          = "type"
        val Class: SemanticTokenTypes         = "class"
        val Enum: SemanticTokenTypes          = "enum"
        val Interface: SemanticTokenTypes     = "interface"
        val Struct: SemanticTokenTypes        = "struct"
        val TypeParameter: SemanticTokenTypes = "typeParameter"
        val Parameter: SemanticTokenTypes     = "parameter"
        val Variable: SemanticTokenTypes      = "variable"
        val Property: SemanticTokenTypes      = "property"
        val EnumMember: SemanticTokenTypes    = "enumMember"
        val Event: SemanticTokenTypes         = "event"
        val Function: SemanticTokenTypes      = "function"
        val Method: SemanticTokenTypes        = "method"
        val Macro: SemanticTokenTypes         = "macro"
        val Keyword: SemanticTokenTypes       = "keyword"
        val Modifier: SemanticTokenTypes      = "modifier"
        val Comment: SemanticTokenTypes       = "comment"
        val String: SemanticTokenTypes        = "string"
        val Number: SemanticTokenTypes        = "number"
        val Regexp: SemanticTokenTypes        = "regexp"
        val Operator: SemanticTokenTypes      = "operator"
        val Decorator: SemanticTokenTypes     = "decorator"

        def apply(s: String): SemanticTokenTypes = s

        extension (t: SemanticTokenTypes)
            def asString: String = t

        given Schema[SemanticTokenTypes]                       = Schema.stringSchema.transform[SemanticTokenTypes](apply)(_.asString)
        given CanEqual[SemanticTokenTypes, SemanticTokenTypes] = CanEqual.derived
    end SemanticTokenTypes

    /** An opaque string semantic token modifier (extensible set). */
    opaque type SemanticTokenModifiers = String

    object SemanticTokenModifiers:
        val Declaration: SemanticTokenModifiers    = "declaration"
        val Definition: SemanticTokenModifiers     = "definition"
        val Readonly: SemanticTokenModifiers       = "readonly"
        val Static: SemanticTokenModifiers         = "static"
        val Deprecated: SemanticTokenModifiers     = "deprecated"
        val Abstract: SemanticTokenModifiers       = "abstract"
        val Async: SemanticTokenModifiers          = "async"
        val Modification: SemanticTokenModifiers   = "modification"
        val Documentation: SemanticTokenModifiers  = "documentation"
        val DefaultLibrary: SemanticTokenModifiers = "defaultLibrary"

        def apply(s: String): SemanticTokenModifiers = s

        extension (m: SemanticTokenModifiers)
            def asString: String = m

        given Schema[SemanticTokenModifiers] = Schema.stringSchema.transform[SemanticTokenModifiers](apply)(_.asString)
        given CanEqual[SemanticTokenModifiers, SemanticTokenModifiers] = CanEqual.derived
    end SemanticTokenModifiers

    /** Describes the semantic tokens legend. */
    final case class SemanticTokensLegend(
        tokenTypes: Chunk[SemanticTokenTypes],
        tokenModifiers: Chunk[SemanticTokenModifiers]
    ) derives Schema, CanEqual

    /** Semantic tokens for the full document. */
    final case class SemanticTokens(resultId: Maybe[String] = Absent, data: Chunk[Int]) derives Schema, CanEqual

    /** An edit to semantic tokens. */
    final case class SemanticTokensEdit(start: Int, deleteCount: Int, data: Chunk[Int] = Chunk.empty) derives Schema, CanEqual

    /** A delta of semantic tokens. */
    final case class SemanticTokensDelta(resultId: Maybe[String] = Absent, edits: Chunk[SemanticTokensEdit]) derives Schema, CanEqual

    /** The result of a semantic tokens full request: either tokens or a delta. */
    sealed trait SemanticTokensResult derives CanEqual

    object SemanticTokensResult:
        final case class Full(tokens: SemanticTokens)      extends SemanticTokensResult
        final case class Delta(delta: SemanticTokensDelta) extends SemanticTokensResult
        given Schema[SemanticTokensResult] = internal.lsp.LspContentSchemas.semanticTokensResultSchema
    end SemanticTokensResult

    /** Options for semantic tokens registration. */
    final case class SemanticTokensOptions(
        legend: SemanticTokensLegend,
        range: Maybe[Boolean] = Absent,
        full: Maybe[BooleanOr[SemanticTokensOptions.FullOptions]] = Absent,
        workDoneProgress: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    object SemanticTokensOptions:
        final case class FullOptions(delta: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- Inlay hint + inline value types

    /** The kind of an inlay hint. */
    enum InlayHintKind derives CanEqual:
        case Type, Parameter

    object InlayHintKind:
        given Schema[InlayHintKind] = internal.lsp.LspWireEnumSchemas.inlayHintKindSchema

    /** An inlay hint label part: either a plain string or a structured part. */
    sealed trait InlayHintLabelPart derives CanEqual

    object InlayHintLabelPart:
        final case class StringPart(value: String) extends InlayHintLabelPart
        final case class StructuredPart(
            value: String,
            tooltip: Maybe[MarkupContent] = Absent,
            location: Maybe[Location] = Absent,
            command: Maybe[Command] = Absent
        ) extends InlayHintLabelPart
        given Schema[InlayHintLabelPart] = internal.lsp.LspContentSchemas.inlayHintLabelPartSchema
    end InlayHintLabelPart

    /** An inlay hint label: either a plain string or a list of parts. */
    sealed trait InlayHintLabel derives CanEqual

    object InlayHintLabel:
        final case class PlainString(value: String)              extends InlayHintLabel
        final case class Parts(value: Chunk[InlayHintLabelPart]) extends InlayHintLabel
        given Schema[InlayHintLabel] = internal.lsp.LspContentSchemas.inlayHintLabelSchema
    end InlayHintLabel

    /** An inlay hint with typed data carrier. */
    final case class InlayHint(
        position: Position,
        label: InlayHintLabel,
        kind: Maybe[InlayHintKind] = Absent,
        textEdits: Chunk[TextEdit] = Chunk.empty,
        tooltip: Maybe[MarkupContent] = Absent,
        paddingLeft: Maybe[Boolean] = Absent,
        paddingRight: Maybe[Boolean] = Absent,
        private[kyo] _rawData: Maybe[String] = Absent
    ) derives CanEqual:
        def withData[X](x: X)(using Schema[X], Frame): InlayHint = copy(_rawData = Present(Json.encode[X](x)))
        def dataAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawData match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) => Abort.fail(LspException.Execution.Decode("textDocument/inlayHint", e.toString, e))
                        case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode("textDocument/inlayHint", e.getMessage, e))
    end InlayHint

    object InlayHint:
        given Schema[InlayHint] = Schema.derived

    /** Options for inlay hints registration. */
    final case class InlayHintOptions(workDoneProgress: Maybe[Boolean] = Absent, resolveProvider: Maybe[Boolean] = Absent) derives Schema,
          CanEqual

    /** Context for an inline value request. */
    final case class InlineValueContext(frameId: Int, stoppedLocation: Range) derives Schema, CanEqual

    /** An inline value: either plain text, a variable lookup, or an evaluatable expression. */
    sealed trait InlineValue derives CanEqual

    object InlineValue:
        final case class Text(range: Range, text: String) extends InlineValue
        final case class VariableLookup(range: Range, variableName: Maybe[String] = Absent, caseSensitiveLookup: Boolean)
            extends InlineValue
        final case class EvaluatableExpression(range: Range, expression: Maybe[String] = Absent) extends InlineValue
        given Schema[InlineValue] = internal.lsp.LspContentSchemas.inlineValueSchema
    end InlineValue

    /** Options for inline value registration. */
    final case class InlineValueOptions(workDoneProgress: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- Notebook types

    /** The kind of a notebook cell. */
    enum NotebookCellKind derives CanEqual:
        case Markup, Code

    object NotebookCellKind:
        given Schema[NotebookCellKind] = internal.lsp.LspWireEnumSchemas.notebookCellKindSchema

    /** An execution summary for a notebook cell. */
    final case class ExecutionSummary(executionOrder: Int, success: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** A notebook cell with optional metadata wire slot. */
    final case class NotebookCell(
        kind: NotebookCellKind,
        document: String,
        metadata: Maybe[NotebookCell.Metadata] = Absent,
        executionSummary: Maybe[ExecutionSummary] = Absent,
        private[kyo] _rawMetadata: Maybe[String] = Absent
    ) derives CanEqual

    object NotebookCell:
        final case class Metadata() derives Schema, CanEqual
        given Schema[NotebookCell] = Schema.derived

    /** A notebook document with optional metadata wire slot. */
    final case class Notebook(
        uri: String,
        notebookType: String,
        version: Int,
        cells: Chunk[NotebookCell],
        private[kyo] _rawMetadata: Maybe[String] = Absent
    ) derives CanEqual

    object Notebook:
        given Schema[Notebook] = Schema.derived

    /** A notebook document filter. */
    sealed trait NotebookDocumentFilter derives CanEqual

    object NotebookDocumentFilter:
        final case class WithNotebookType(notebookType: String, scheme: Maybe[String] = Absent, pattern: Maybe[String] = Absent)
            extends NotebookDocumentFilter
        final case class WithScheme(notebookType: Maybe[String] = Absent, scheme: String, pattern: Maybe[String] = Absent)
            extends NotebookDocumentFilter
        final case class WithPattern(notebookType: Maybe[String] = Absent, scheme: Maybe[String] = Absent, pattern: String)
            extends NotebookDocumentFilter
        given Schema[NotebookDocumentFilter] = internal.lsp.LspContentSchemas.notebookDocumentFilterSchema
    end NotebookDocumentFilter

    /** A notebook cell text document filter. */
    final case class NotebookCellTextDocumentFilter(
        notebook: NotebookDocumentFilter,
        language: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A notebook cell array change. */
    final case class NotebookCellArrayChange(
        start: Int,
        deleteCount: Int,
        cells: Chunk[NotebookCell] = Chunk.empty
    ) derives Schema, CanEqual

    /** A change to notebook cell text content. */
    final case class NotebookCellTextDocumentChange(
        document: VersionedTextDocumentIdentifier,
        changes: Chunk[TextDocumentContentChangeEvent]
    ) derives Schema, CanEqual

    /** A change event for a notebook document. */
    final case class NotebookDocumentChangeEvent(
        metadata: Maybe[Notebook] = Absent,
        cells: Maybe[NotebookDocumentChangeEvent.CellChanges] = Absent
    ) derives Schema, CanEqual

    object NotebookDocumentChangeEvent:
        final case class CellChanges(
            structure: Maybe[CellStructureChange] = Absent,
            data: Chunk[NotebookCell] = Chunk.empty,
            textContent: Chunk[NotebookCellTextDocumentChange] = Chunk.empty
        ) derives Schema, CanEqual
        final case class CellStructureChange(
            array: NotebookCellArrayChange,
            didOpen: Chunk[TextDocumentItem] = Chunk.empty,
            didClose: Chunk[TextDocumentIdentifier] = Chunk.empty
        ) derives Schema, CanEqual
    end NotebookDocumentChangeEvent

    /** Parameters for `notebookDocument/didOpen`. */
    final case class DidOpenNotebookDocumentParams(
        notebookDocument: Notebook,
        cellTextDocuments: Chunk[TextDocumentItem]
    ) derives Schema, CanEqual

    /** Parameters for `notebookDocument/didChange`. */
    final case class DidChangeNotebookDocumentParams(
        notebookDocument: VersionedNotebookDocumentIdentifier,
        change: NotebookDocumentChangeEvent
    ) derives Schema, CanEqual

    /** A versioned notebook document identifier. */
    final case class VersionedNotebookDocumentIdentifier(version: Int, uri: String) derives Schema, CanEqual

    /** Parameters for `notebookDocument/didSave`. */
    final case class DidSaveNotebookDocumentParams(notebookDocument: NotebookDocumentIdentifier) derives Schema, CanEqual

    /** An unversioned notebook document identifier. */
    final case class NotebookDocumentIdentifier(uri: String) derives Schema, CanEqual

    /** Parameters for `notebookDocument/didClose`. */
    final case class DidCloseNotebookDocumentParams(
        notebookDocument: NotebookDocumentIdentifier,
        cellTextDocuments: Chunk[TextDocumentIdentifier]
    ) derives Schema, CanEqual

    // MARK: -- Window / messaging types

    /** The type of a message. */
    enum MessageType derives CanEqual:
        case Error, Warning, Info, Log, Debug

    object MessageType:
        given Schema[MessageType] = internal.lsp.LspWireEnumSchemas.messageTypeSchema

    /** An action item of a message. */
    final case class MessageActionItem(title: String) derives Schema, CanEqual

    /** Parameters for the `window/showMessage` notification. */
    final case class ShowMessageParams(messageType: MessageType, message: String) derives Schema, CanEqual

    /** Parameters for the `window/showMessageRequest` request. */
    final case class ShowMessageRequestParams(
        messageType: MessageType,
        message: String,
        actions: Chunk[MessageActionItem] = Chunk.empty
    ) derives Schema, CanEqual

    /** Parameters for the `window/logMessage` notification. */
    final case class LogMessageParams(messageType: MessageType, message: String) derives Schema, CanEqual

    /** Parameters for the `window/showDocument` request. */
    final case class ShowDocumentParams(
        uri: String,
        external: Maybe[Boolean] = Absent,
        takeFocus: Maybe[Boolean] = Absent,
        selection: Maybe[Range] = Absent
    ) derives Schema, CanEqual

    /** The result of a `window/showDocument` request. */
    final case class ShowDocumentResult(success: Boolean) derives Schema, CanEqual

    /** The trace value setting. */
    opaque type TraceValue = String

    object TraceValue:
        val Off: TraceValue      = "off"
        val Messages: TraceValue = "messages"
        val Verbose: TraceValue  = "verbose"

        def apply(s: String): TraceValue = s

        extension (v: TraceValue)
            def asString: String = v

        given Schema[TraceValue]               = Schema.stringSchema.transform[TraceValue](apply)(_.asString)
        given CanEqual[TraceValue, TraceValue] = CanEqual.derived
    end TraceValue

    /** Parameters for the `$/cancelRequest` notification. The `id` field identifies the request to cancel. */
    final case class CancelParams(id: JsonRpcId) derives Schema, CanEqual

    /** Parameters for the `$/setTrace` notification. */
    final case class SetTraceParams(value: TraceValue) derives Schema, CanEqual

    /** Parameters for the `$/logTrace` notification. */
    final case class LogTraceParams(message: String, verbose: Maybe[String] = Absent) derives Schema, CanEqual

    // MARK: -- Workspace types (WorkspaceFolder, Registration, Configuration, FileOperations, ...)

    /** A workspace folder. */
    final case class WorkspaceFolder(uri: String, name: String) derives Schema, CanEqual

    /** A workspace folders change event. */
    final case class WorkspaceFoldersChangeEvent(
        added: Chunk[WorkspaceFolder],
        removed: Chunk[WorkspaceFolder]
    ) derives Schema, CanEqual

    /** Parameters for `workspace/didChangeWorkspaceFolders`. */
    final case class DidChangeWorkspaceFoldersParams(event: WorkspaceFoldersChangeEvent) derives Schema, CanEqual

    /** Parameters for `workspace/didChangeConfiguration`. The settings type T is application-defined: the client sends
      * whatever configuration shape the server registered for. The Schema constraint is enforced at the factory site
      * (didChangeConfiguration[T]) so the engine can decode the wire payload into T.
      */
    final case class DidChangeConfigurationParams[T](settings: T) derives CanEqual

    /** A file event. */
    final case class FileEvent(uri: String, fileChangeType: FileChangeType) derives Schema, CanEqual

    /** The kind of a file event. */
    enum FileChangeType derives CanEqual:
        case Created, Changed, Deleted

    object FileChangeType:
        given Schema[FileChangeType] = internal.lsp.LspWireEnumSchemas.fileChangeTypeSchema

    /** Parameters for `workspace/didChangeWatchedFiles`. */
    final case class DidChangeWatchedFilesParams(changes: Chunk[FileEvent]) derives Schema, CanEqual

    /** A configuration item. */
    final case class ConfigurationItem(scopeUri: Maybe[String] = Absent, section: Maybe[String] = Absent) derives Schema, CanEqual

    /** Parameters for `workspace/configuration`. */
    final case class ConfigurationParams(items: Chunk[ConfigurationItem]) derives Schema, CanEqual

    /** Parameters for `workspace/applyEdit`. */
    final case class ApplyWorkspaceEditParams(label: Maybe[String] = Absent, edit: WorkspaceEdit) derives Schema, CanEqual

    /** The result of `workspace/applyEdit`. */
    final case class ApplyWorkspaceEditResult(applied: Boolean, failureReason: Maybe[String] = Absent, failedChange: Maybe[Int] = Absent)
        derives Schema, CanEqual

    /** Parameters for `workspace/executeCommand`. */
    final case class ExecuteCommandParams(
        command: String,
        arguments: Chunk[String] = Chunk.empty,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** File operation filter options. */
    final case class FileOperationPatternOptions(ignoreCase: Maybe[Boolean] = Absent) derives Schema, CanEqual

    /** The kind of a file operation pattern. */
    enum FileOperationPatternKind derives CanEqual:
        case File, Folder

    object FileOperationPatternKind:
        given Schema[FileOperationPatternKind] = internal.lsp.LspWireEnumSchemas.fileOperationPatternKindSchema

    /** A file operation pattern. */
    final case class FileOperationPattern(
        glob: String,
        matches: Maybe[FileOperationPatternKind] = Absent,
        options: Maybe[FileOperationPatternOptions] = Absent
    ) derives Schema, CanEqual

    /** A filter for file operations. */
    final case class FileOperationFilter(scheme: Maybe[String] = Absent, pattern: FileOperationPattern) derives Schema, CanEqual

    /** Registration options for file operations. */
    final case class FileOperationRegistrationOptions(filters: Chunk[FileOperationFilter]) derives Schema, CanEqual

    /** A file create event. */
    final case class FileCreate(uri: String) derives Schema, CanEqual

    /** A file rename event. */
    final case class FileRename(oldUri: String, newUri: String) derives Schema, CanEqual

    /** A file delete event. */
    final case class FileDelete(uri: String) derives Schema, CanEqual

    /** Parameters for `workspace/willCreateFiles` / `workspace/didCreateFiles`. */
    final case class CreateFilesParams(files: Chunk[FileCreate]) derives Schema, CanEqual

    /** Parameters for `workspace/willRenameFiles` / `workspace/didRenameFiles`. */
    final case class RenameFilesParams(files: Chunk[FileRename]) derives Schema, CanEqual

    /** Parameters for `workspace/willDeleteFiles` / `workspace/didDeleteFiles`. */
    final case class DeleteFilesParams(files: Chunk[FileDelete]) derives Schema, CanEqual

    /** Options for workspace symbols registration. */
    final case class WorkspaceSymbolOptions(workDoneProgress: Maybe[Boolean] = Absent, resolveProvider: Maybe[Boolean] = Absent)
        derives Schema, CanEqual

    /** Options for execute command registration. */
    final case class ExecuteCommandOptions(workDoneProgress: Maybe[Boolean] = Absent, commands: Chunk[String] = Chunk.empty) derives Schema,
          CanEqual

    // Registration types

    /** A document filter. */
    final case class DocumentFilter(
        language: Maybe[String] = Absent,
        scheme: Maybe[String] = Absent,
        pattern: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A document selector is the combination of one or more document filters. */
    type DocumentSelector = Chunk[DocumentFilter]

    /** Static registration options. */
    final case class StaticRegistrationOptions(id: Maybe[String] = Absent) derives Schema, CanEqual

    /** Text document registration options. */
    final case class TextDocumentRegistrationOptions(documentSelector: Maybe[DocumentSelector] = Absent) derives Schema, CanEqual

    /** Represents a registration for a given method. */
    final class Registration private[kyo] (
        val id: String,
        val method: String,
        private[kyo] val _rawRegisterOptions: Maybe[String]
    ) derives CanEqual:
        def registerOptionsAs[X](using frame: Frame, schema: Schema[X]): Maybe[X] < Abort[LspException.Execution.Decode] =
            _rawRegisterOptions match
                case Absent => Absent
                case Present(s) =>
                    Json.decode[X](s) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) =>
                            Abort.fail(LspException.Execution.Decode("client/registerCapability", e.toString, e))
                        case Result.Panic(e) =>
                            Abort.fail(LspException.Execution.Decode("client/registerCapability", e.getMessage, e))
    end Registration

    object Registration:
        def apply[X](id: String, method: String, options: X)(using Schema[X], Frame): Registration =
            new Registration(id, method, Present(Json.encode[X](options)))
        def apply(id: String, method: String): Registration =
            new Registration(id, method, Absent)
        given Schema[Registration] = internal.lsp.LspContentSchemas.registrationSchema
    end Registration

    /** Represents an unregistration. */
    final case class Unregistration(id: String, method: String) derives Schema, CanEqual

    /** Parameters for `client/registerCapability`. */
    final case class RegistrationParams(registrations: Chunk[Registration]) derives Schema, CanEqual

    /** Parameters for `client/unregisterCapability`. */
    final case class UnregistrationParams(unregisterations: Chunk[Unregistration]) derives Schema, CanEqual

    // Initialize types

    /** Information about the client. */
    final case class ClientInfo(name: String, version: Maybe[String] = Absent) derives Schema, CanEqual

    /** Information about the server. */
    final case class ServerInfo(name: String, version: Maybe[String] = Absent) derives Schema, CanEqual

    /** Parameters for the `initialize` request. */
    final case class InitializeParams(
        processId: Maybe[Int] = Absent,
        clientInfo: Maybe[ClientInfo] = Absent,
        locale: Maybe[String] = Absent,
        rootPath: Maybe[String] = Absent,
        rootUri: Maybe[String] = Absent,
        capabilities: LspCapabilities.Client.Client,
        trace: Maybe[TraceValue] = Absent,
        workspaceFolders: Maybe[Chunk[WorkspaceFolder]] = Absent,
        private[kyo] _rawInitializationOptions: Maybe[String] = Absent
    ) derives CanEqual

    object InitializeParams:
        given Schema[InitializeParams] = Schema.derived

    /** Parameters for the `initialized` notification. */
    final case class InitializedParams() derives Schema, CanEqual

    /** The result of the `initialize` request. */
    final case class InitializeResult(
        capabilities: LspCapabilities.Server.Server,
        serverInfo: Maybe[ServerInfo] = Absent
    ) derives Schema, CanEqual

    /** The error of the `initialize` request. */
    final case class InitializeError(retry: Boolean) derives Schema, CanEqual

    // textDocument request/notification params

    /** Parameters for `textDocument/didOpen`. */
    final case class DidOpenTextDocumentParams(textDocument: TextDocumentItem) derives Schema, CanEqual

    /** Parameters for `textDocument/didChange`. */
    final case class DidChangeTextDocumentParams(
        textDocument: VersionedTextDocumentIdentifier,
        contentChanges: Chunk[TextDocumentContentChangeEvent]
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/willSave`. */
    final case class WillSaveTextDocumentParams(textDocument: TextDocumentIdentifier, reason: TextDocumentSaveReason) derives Schema,
          CanEqual

    /** Parameters for `textDocument/didSave`. */
    final case class DidSaveTextDocumentParams(textDocument: TextDocumentIdentifier, text: Maybe[String] = Absent) derives Schema, CanEqual

    /** Parameters for `textDocument/didClose`. */
    final case class DidCloseTextDocumentParams(textDocument: TextDocumentIdentifier) derives Schema, CanEqual

    /** Parameters for `textDocument/completion`. */
    final case class CompletionParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent,
        context: Maybe[CompletionContext] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/hover`. */
    final case class HoverParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/signatureHelp`. */
    final case class SignatureHelpParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        context: Maybe[SignatureHelpContext] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/declaration`. */
    final case class DeclarationParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/definition`. */
    final case class DefinitionParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/typeDefinition`. */
    final case class TypeDefinitionParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/implementation`. */
    final case class ImplementationParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/references`. */
    final case class ReferenceParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent,
        context: ReferenceContext
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/documentHighlight`. */
    final case class DocumentHighlightParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/documentSymbol`. */
    final case class DocumentSymbolParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/codeAction`. */
    final case class CodeActionParams(
        textDocument: TextDocumentIdentifier,
        range: Range,
        context: CodeActionContext,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/codeLens`. */
    final case class CodeLensParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/documentLink`. */
    final case class DocumentLinkParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/documentColor`. */
    final case class DocumentColorParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/colorPresentation`. */
    final case class ColorPresentationParams(
        textDocument: TextDocumentIdentifier,
        color: Color,
        range: Range,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/rename`. */
    final case class RenameParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        newName: String,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/prepareRename`. */
    final case class PrepareRenameParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/foldingRange`. */
    final case class FoldingRangeParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/selectionRange`. */
    final case class SelectionRangeParams(
        textDocument: TextDocumentIdentifier,
        positions: Chunk[Position],
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/linkedEditingRange`. */
    final case class LinkedEditingRangeParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/prepareCallHierarchy`. */
    final case class CallHierarchyPrepareParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `callHierarchy/incomingCalls`. */
    final case class CallHierarchyIncomingCallsParams(
        item: CallHierarchyItem,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `callHierarchy/outgoingCalls`. */
    final case class CallHierarchyOutgoingCallsParams(
        item: CallHierarchyItem,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/prepareTypeHierarchy`. */
    final case class TypeHierarchyPrepareParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `typeHierarchy/supertypes`. */
    final case class TypeHierarchySupertypesParams(
        item: TypeHierarchyItem,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `typeHierarchy/subtypes`. */
    final case class TypeHierarchySubtypesParams(
        item: TypeHierarchyItem,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/semanticTokens/full`. */
    final case class SemanticTokensParams(
        textDocument: TextDocumentIdentifier,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/semanticTokens/full/delta`. */
    final case class SemanticTokensDeltaParams(
        textDocument: TextDocumentIdentifier,
        previousResultId: String,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/semanticTokens/range`. */
    final case class SemanticTokensRangeParams(
        textDocument: TextDocumentIdentifier,
        range: Range,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/moniker`. */
    final case class MonikerParams(
        textDocument: TextDocumentIdentifier,
        position: Position,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/inlayHint`. */
    final case class InlayHintParams(
        textDocument: TextDocumentIdentifier,
        range: Range,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `textDocument/inlineValue`. */
    final case class InlineValueParams(
        textDocument: TextDocumentIdentifier,
        range: Range,
        context: InlineValueContext,
        workDoneToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    /** Parameters for `workspace/symbol`. */
    final case class WorkspaceSymbolParams(
        query: String,
        workDoneToken: Maybe[ProgressToken] = Absent,
        partialResultToken: Maybe[ProgressToken] = Absent
    ) derives Schema, CanEqual

    // MARK: -- BooleanOr / StringOr sealed unions

    /** A value that is either a boolean or a typed options record. */
    sealed trait BooleanOr[+T] derives CanEqual

    object BooleanOr:
        final case class Bool[+T](value: Boolean) extends BooleanOr[T]
        final case class Options[+T](value: T)    extends BooleanOr[T]
        given [T: Schema]: Schema[BooleanOr[T]] = internal.lsp.LspWireEnumSchemas.given_Schema_BooleanOr
    end BooleanOr

    /** A value that is either a string or a typed options record. */
    sealed trait StringOr[+T] derives CanEqual

    object StringOr:
        final case class Str[+T](value: String) extends StringOr[T]
        final case class Options[+T](value: T)  extends StringOr[T]
        given [T: Schema]: Schema[StringOr[T]] = internal.lsp.LspWireEnumSchemas.given_Schema_StringOr
    end StringOr

    /** A `textDocumentSync` capability value: either a sync-kind ordinal or a full options record.
      *
      * LSP 3.17 spec declares `ServerCapabilities.textDocumentSync` as `TextDocumentSyncKind |
      * TextDocumentSyncOptions`. The `Kind` case carries the numeric sync mode; the `Options`
      * case carries the full options record with per-notification granularity.
      */
    sealed trait TextDocumentSyncValue derives CanEqual

    object TextDocumentSyncValue:
        /** Numeric sync mode: None (0), Incremental (1), or Full (2). */
        final case class Kind(value: TextDocumentSyncKind) extends TextDocumentSyncValue

        /** Full sync options record with per-notification control. */
        final case class Options(value: TextDocumentSyncOptions) extends TextDocumentSyncValue

        @nowarn("msg=anonymous")
        given Schema[TextDocumentSyncValue] = Schema.init[TextDocumentSyncValue](
            writeFn = (v, w) =>
                v match
                    case Kind(k)    => summon[Schema[TextDocumentSyncKind]].serializeWrite(k, w)
                    case Options(o) => summon[Schema[TextDocumentSyncOptions]].serializeWrite(o, w),
            readFn = reader =>
                val captured = reader.captureValue()
                try Kind(summon[Schema[TextDocumentSyncKind]].serializeRead(captured))
                catch case _: Exception => Options(summon[Schema[TextDocumentSyncOptions]].serializeRead(captured))
        )
    end TextDocumentSyncValue

    // MARK: -- TextDocument namespace factory object

    /** Namespaced factories for `textDocument/X` server-handled LSP endpoints. */
    object TextDocument:

        def completion[E](
            handler: CompletionParams => CompletionResult < LE[E]
        )(using Frame): LspHandler[CompletionParams, CompletionResult, LspException | E] =
            initRequest("textDocument/completion", Kind.Completion, handler)

        def completionItemResolve[E](
            handler: CompletionItem => CompletionItem < LE[E]
        )(using Frame): LspHandler[CompletionItem, CompletionItem, LspException | E] =
            initRequest("completionItem/resolve", Kind.CompletionItemResolve, handler)

        def hover[E](
            handler: HoverParams => Maybe[Hover] < LE[E]
        )(using Frame): LspHandler[HoverParams, Maybe[Hover], LspException | E] =
            initRequest("textDocument/hover", Kind.Hover, handler)

        def signatureHelp[E](
            handler: SignatureHelpParams => Maybe[SignatureHelp] < LE[E]
        )(using Frame): LspHandler[SignatureHelpParams, Maybe[SignatureHelp], LspException | E] =
            initRequest("textDocument/signatureHelp", Kind.SignatureHelp, handler)

        def declaration[E](
            handler: DeclarationParams => DeclarationResult < LE[E]
        )(using Frame): LspHandler[DeclarationParams, DeclarationResult, LspException | E] =
            initRequest("textDocument/declaration", Kind.Declaration, handler)

        def definition[E](
            handler: DefinitionParams => DefinitionResult < LE[E]
        )(using Frame): LspHandler[DefinitionParams, DefinitionResult, LspException | E] =
            initRequest("textDocument/definition", Kind.Definition, handler)

        def typeDefinition[E](
            handler: TypeDefinitionParams => DefinitionResult < LE[E]
        )(using Frame): LspHandler[TypeDefinitionParams, DefinitionResult, LspException | E] =
            initRequest("textDocument/typeDefinition", Kind.TypeDefinition, handler)

        def implementation[E](
            handler: ImplementationParams => DefinitionResult < LE[E]
        )(using Frame): LspHandler[ImplementationParams, DefinitionResult, LspException | E] =
            initRequest("textDocument/implementation", Kind.Implementation, handler)

        def references[E](
            handler: ReferenceParams => Chunk[Location] < LE[E]
        )(using Frame): LspHandler[ReferenceParams, Chunk[Location], LspException | E] =
            initRequest("textDocument/references", Kind.References, handler)

        def documentHighlight[E](
            handler: DocumentHighlightParams => Chunk[DocumentHighlight] < LE[E]
        )(using Frame): LspHandler[DocumentHighlightParams, Chunk[DocumentHighlight], LspException | E] =
            initRequest("textDocument/documentHighlight", Kind.DocumentHighlight, handler)

        def documentSymbol[E](
            handler: DocumentSymbolParams => DocumentSymbolResult < LE[E]
        )(using Frame): LspHandler[DocumentSymbolParams, DocumentSymbolResult, LspException | E] =
            initRequest("textDocument/documentSymbol", Kind.DocumentSymbol, handler)

        def codeAction[E](
            handler: CodeActionParams => Chunk[CommandOrCodeAction] < LE[E]
        )(using Frame): LspHandler[CodeActionParams, Chunk[CommandOrCodeAction], LspException | E] =
            initRequest("textDocument/codeAction", Kind.CodeAction, handler)

        def codeActionResolve[E](
            handler: CodeAction => CodeAction < LE[E]
        )(using Frame): LspHandler[CodeAction, CodeAction, LspException | E] =
            initRequest("codeAction/resolve", Kind.CodeActionResolve, handler)

        def codeLens[E](
            handler: CodeLensParams => Chunk[CodeLens] < LE[E]
        )(using Frame): LspHandler[CodeLensParams, Chunk[CodeLens], LspException | E] =
            initRequest("textDocument/codeLens", Kind.CodeLens, handler)

        def codeLensResolve[E](
            handler: CodeLens => CodeLens < LE[E]
        )(using Frame): LspHandler[CodeLens, CodeLens, LspException | E] =
            initRequest("codeLens/resolve", Kind.CodeLensResolve, handler)

        def documentLink[E](
            handler: DocumentLinkParams => Chunk[DocumentLink] < LE[E]
        )(using Frame): LspHandler[DocumentLinkParams, Chunk[DocumentLink], LspException | E] =
            initRequest("textDocument/documentLink", Kind.DocumentLink, handler)

        def documentLinkResolve[E](
            handler: DocumentLink => DocumentLink < LE[E]
        )(using Frame): LspHandler[DocumentLink, DocumentLink, LspException | E] =
            initRequest("documentLink/resolve", Kind.DocumentLinkResolve, handler)

        def documentColor[E](
            handler: DocumentColorParams => Chunk[ColorInformation] < LE[E]
        )(using Frame): LspHandler[DocumentColorParams, Chunk[ColorInformation], LspException | E] =
            initRequest("textDocument/documentColor", Kind.DocumentColor, handler)

        def colorPresentation[E](
            handler: ColorPresentationParams => Chunk[ColorPresentation] < LE[E]
        )(using Frame): LspHandler[ColorPresentationParams, Chunk[ColorPresentation], LspException | E] =
            initRequest("textDocument/colorPresentation", Kind.ColorPresentation, handler)

        def formatting[E](
            handler: DocumentFormattingParams => Chunk[TextEdit] < LE[E]
        )(using Frame): LspHandler[DocumentFormattingParams, Chunk[TextEdit], LspException | E] =
            initRequest("textDocument/formatting", Kind.Formatting, handler)

        def rangeFormatting[E](
            handler: DocumentRangeFormattingParams => Chunk[TextEdit] < LE[E]
        )(using Frame): LspHandler[DocumentRangeFormattingParams, Chunk[TextEdit], LspException | E] =
            initRequest("textDocument/rangeFormatting", Kind.RangeFormatting, handler)

        def onTypeFormatting[E](
            handler: DocumentOnTypeFormattingParams => Chunk[TextEdit] < LE[E]
        )(using Frame): LspHandler[DocumentOnTypeFormattingParams, Chunk[TextEdit], LspException | E] =
            initRequest("textDocument/onTypeFormatting", Kind.OnTypeFormatting, handler)

        def rename[E](
            handler: RenameParams => Maybe[WorkspaceEdit] < LE[E]
        )(using Frame): LspHandler[RenameParams, Maybe[WorkspaceEdit], LspException | E] =
            initRequest("textDocument/rename", Kind.Rename, handler)

        def prepareRename[E](
            handler: PrepareRenameParams => Maybe[PrepareRenameResult] < LE[E]
        )(using Frame): LspHandler[PrepareRenameParams, Maybe[PrepareRenameResult], LspException | E] =
            initRequest("textDocument/prepareRename", Kind.PrepareRename, handler)

        def foldingRange[E](
            handler: FoldingRangeParams => Chunk[FoldingRange] < LE[E]
        )(using Frame): LspHandler[FoldingRangeParams, Chunk[FoldingRange], LspException | E] =
            initRequest("textDocument/foldingRange", Kind.FoldingRange, handler)

        def selectionRange[E](
            handler: SelectionRangeParams => Chunk[SelectionRange] < LE[E]
        )(using Frame): LspHandler[SelectionRangeParams, Chunk[SelectionRange], LspException | E] =
            initRequest("textDocument/selectionRange", Kind.SelectionRange, handler)

        def linkedEditingRange[E](
            handler: LinkedEditingRangeParams => Maybe[LinkedEditingRanges] < LE[E]
        )(using Frame): LspHandler[LinkedEditingRangeParams, Maybe[LinkedEditingRanges], LspException | E] =
            initRequest("textDocument/linkedEditingRange", Kind.LinkedEditingRange, handler)

        def prepareCallHierarchy[E](
            handler: CallHierarchyPrepareParams => Chunk[CallHierarchyItem] < LE[E]
        )(using Frame): LspHandler[CallHierarchyPrepareParams, Chunk[CallHierarchyItem], LspException | E] =
            initRequest("textDocument/prepareCallHierarchy", Kind.PrepareCallHierarchy, handler)

        def callHierarchyIncomingCalls[E](
            handler: CallHierarchyIncomingCallsParams => Chunk[
                CallHierarchyIncomingCall
            ] < LE[E]
        )(using Frame): LspHandler[CallHierarchyIncomingCallsParams, Chunk[CallHierarchyIncomingCall], LspException | E] =
            initRequest("callHierarchy/incomingCalls", Kind.CallHierarchyIncomingCalls, handler)

        def callHierarchyOutgoingCalls[E](
            handler: CallHierarchyOutgoingCallsParams => Chunk[
                CallHierarchyOutgoingCall
            ] < LE[E]
        )(using Frame): LspHandler[CallHierarchyOutgoingCallsParams, Chunk[CallHierarchyOutgoingCall], LspException | E] =
            initRequest("callHierarchy/outgoingCalls", Kind.CallHierarchyOutgoingCalls, handler)

        def prepareTypeHierarchy[E](
            handler: TypeHierarchyPrepareParams => Chunk[TypeHierarchyItem] < LE[E]
        )(using Frame): LspHandler[TypeHierarchyPrepareParams, Chunk[TypeHierarchyItem], LspException | E] =
            initRequest("textDocument/prepareTypeHierarchy", Kind.PrepareTypeHierarchy, handler)

        def typeHierarchySupertypes[E](
            handler: TypeHierarchySupertypesParams => Chunk[TypeHierarchyItem] < LE[E]
        )(using Frame): LspHandler[TypeHierarchySupertypesParams, Chunk[TypeHierarchyItem], LspException | E] =
            initRequest("typeHierarchy/supertypes", Kind.TypeHierarchySupertypes, handler)

        def typeHierarchySubtypes[E](
            handler: TypeHierarchySubtypesParams => Chunk[TypeHierarchyItem] < LE[E]
        )(using Frame): LspHandler[TypeHierarchySubtypesParams, Chunk[TypeHierarchyItem], LspException | E] =
            initRequest("typeHierarchy/subtypes", Kind.TypeHierarchySubtypes, handler)

        def semanticTokensFull[E](
            handler: SemanticTokensParams => Maybe[SemanticTokens] < LE[E]
        )(using Frame): LspHandler[SemanticTokensParams, Maybe[SemanticTokens], LspException | E] =
            initRequest("textDocument/semanticTokens/full", Kind.SemanticTokensFull, handler)

        def semanticTokensFullDelta[E](
            handler: SemanticTokensDeltaParams => Maybe[SemanticTokensResult] < LE[E]
        )(using Frame): LspHandler[SemanticTokensDeltaParams, Maybe[SemanticTokensResult], LspException | E] =
            initRequest("textDocument/semanticTokens/full/delta", Kind.SemanticTokensFullDelta, handler)

        def semanticTokensRange[E](
            handler: SemanticTokensRangeParams => Maybe[SemanticTokens] < LE[E]
        )(using Frame): LspHandler[SemanticTokensRangeParams, Maybe[SemanticTokens], LspException | E] =
            initRequest("textDocument/semanticTokens/range", Kind.SemanticTokensRange, handler)

        def moniker[E](
            handler: MonikerParams => Chunk[Moniker] < LE[E]
        )(using Frame): LspHandler[MonikerParams, Chunk[Moniker], LspException | E] =
            initRequest("textDocument/moniker", Kind.Moniker, handler)

        def inlayHint[E](
            handler: InlayHintParams => Chunk[InlayHint] < LE[E]
        )(using Frame): LspHandler[InlayHintParams, Chunk[InlayHint], LspException | E] =
            initRequest("textDocument/inlayHint", Kind.InlayHint, handler)

        def inlayHintResolve[E](
            handler: InlayHint => InlayHint < LE[E]
        )(using Frame): LspHandler[InlayHint, InlayHint, LspException | E] =
            initRequest("inlayHint/resolve", Kind.InlayHintResolve, handler)

        def inlineValue[E](
            handler: InlineValueParams => Chunk[InlineValue] < LE[E]
        )(using Frame): LspHandler[InlineValueParams, Chunk[InlineValue], LspException | E] =
            initRequest("textDocument/inlineValue", Kind.InlineValue, handler)

        def diagnostic[E](
            handler: DocumentDiagnosticParams => DocumentDiagnosticReport < LE[E]
        )(using Frame): LspHandler[DocumentDiagnosticParams, DocumentDiagnosticReport, LspException | E] =
            initRequest("textDocument/diagnostic", Kind.DocumentDiagnostic, handler)

        def willSaveWaitUntil[E](
            handler: WillSaveTextDocumentParams => Chunk[TextEdit] < LE[E]
        )(using Frame): LspHandler[WillSaveTextDocumentParams, Chunk[TextEdit], LspException | E] =
            initRequest("textDocument/willSaveWaitUntil", Kind.TextDocumentWillSaveWaitUntil, handler)

        def didOpen[E](
            handler: DidOpenTextDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidOpenTextDocumentParams, Unit, LspException | E] =
            initNotification("textDocument/didOpen", Kind.TextDocumentDidOpen, handler)

        def didChange[E](
            handler: DidChangeTextDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidChangeTextDocumentParams, Unit, LspException | E] =
            initNotification("textDocument/didChange", Kind.TextDocumentDidChange, handler)

        def didSave[E](
            handler: DidSaveTextDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidSaveTextDocumentParams, Unit, LspException | E] =
            initNotification("textDocument/didSave", Kind.TextDocumentDidSave, handler)

        def didClose[E](
            handler: DidCloseTextDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidCloseTextDocumentParams, Unit, LspException | E] =
            initNotification("textDocument/didClose", Kind.TextDocumentDidClose, handler)

        def willSave[E](
            handler: WillSaveTextDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[WillSaveTextDocumentParams, Unit, LspException | E] =
            initNotification("textDocument/willSave", Kind.TextDocumentWillSave, handler)

    end TextDocument

    // MARK: -- Workspace namespace factory object

    /** Namespaced factories for `workspace/X` LSP endpoints. */
    object Workspace:

        def symbol[E](
            handler: WorkspaceSymbolParams => Chunk[WorkspaceSymbol] < LE[E]
        )(using Frame): LspHandler[WorkspaceSymbolParams, Chunk[WorkspaceSymbol], LspException | E] =
            initRequest("workspace/symbol", Kind.WorkspaceSymbol, handler)

        def symbolResolve[E](
            handler: WorkspaceSymbol => WorkspaceSymbol < LE[E]
        )(using Frame): LspHandler[WorkspaceSymbol, WorkspaceSymbol, LspException | E] =
            initRequest("workspaceSymbol/resolve", Kind.WorkspaceSymbolResolve, handler)

        def executeCommand[Out](using
            outSchema: Schema[Out]
        )[E](
            handler: ExecuteCommandParams => Maybe[Out] < LE[E]
        )(using frame: Frame): LspHandler[ExecuteCommandParams, Maybe[Out], LspException | E] =
            initRequest("workspace/executeCommand", Kind.ExecuteCommand, handler)

        def didChangeConfiguration[T](using
            Schema[T]
        )[E](
            handler: DidChangeConfigurationParams[T] => Unit < LE[E]
        )(using Frame): LspHandler[DidChangeConfigurationParams[T], Unit, LspException | E] =
            initNotification("workspace/didChangeConfiguration", Kind.DidChangeConfiguration, handler)

        def didChangeWatchedFiles[E](
            handler: DidChangeWatchedFilesParams => Unit < LE[E]
        )(using Frame): LspHandler[DidChangeWatchedFilesParams, Unit, LspException | E] =
            initNotification("workspace/didChangeWatchedFiles", Kind.DidChangeWatchedFiles, handler)

        def didChangeWorkspaceFolders[E](
            handler: DidChangeWorkspaceFoldersParams => Unit < LE[E]
        )(using Frame): LspHandler[DidChangeWorkspaceFoldersParams, Unit, LspException | E] =
            initNotification("workspace/didChangeWorkspaceFolders", Kind.DidChangeWorkspaceFolders, handler)

        def willCreateFiles[E](
            handler: CreateFilesParams => Maybe[WorkspaceEdit] < LE[E]
        )(using Frame): LspHandler[CreateFilesParams, Maybe[WorkspaceEdit], LspException | E] =
            initRequest("workspace/willCreateFiles", Kind.WillCreateFiles, handler)

        def didCreateFiles[E](
            handler: CreateFilesParams => Unit < LE[E]
        )(using Frame): LspHandler[CreateFilesParams, Unit, LspException | E] =
            initNotification("workspace/didCreateFiles", Kind.DidCreateFiles, handler)

        def willRenameFiles[E](
            handler: RenameFilesParams => Maybe[WorkspaceEdit] < LE[E]
        )(using Frame): LspHandler[RenameFilesParams, Maybe[WorkspaceEdit], LspException | E] =
            initRequest("workspace/willRenameFiles", Kind.WillRenameFiles, handler)

        def didRenameFiles[E](
            handler: RenameFilesParams => Unit < LE[E]
        )(using Frame): LspHandler[RenameFilesParams, Unit, LspException | E] =
            initNotification("workspace/didRenameFiles", Kind.DidRenameFiles, handler)

        def willDeleteFiles[E](
            handler: DeleteFilesParams => Maybe[WorkspaceEdit] < LE[E]
        )(using Frame): LspHandler[DeleteFilesParams, Maybe[WorkspaceEdit], LspException | E] =
            initRequest("workspace/willDeleteFiles", Kind.WillDeleteFiles, handler)

        def didDeleteFiles[E](
            handler: DeleteFilesParams => Unit < LE[E]
        )(using Frame): LspHandler[DeleteFilesParams, Unit, LspException | E] =
            initNotification("workspace/didDeleteFiles", Kind.DidDeleteFiles, handler)

        def diagnostic[E](
            handler: WorkspaceDiagnosticParams => WorkspaceDiagnosticReport < LE[E]
        )(using Frame): LspHandler[WorkspaceDiagnosticParams, WorkspaceDiagnosticReport, LspException | E] =
            initRequest("workspace/diagnostic", Kind.WorkspaceDiagnostic, handler)

        // Reverse-direction (ClientHandled) workspace factories

        def applyEdit[E](
            handler: ApplyWorkspaceEditParams => ApplyWorkspaceEditResult < LE[E]
        )(using Frame): LspHandler[ApplyWorkspaceEditParams, ApplyWorkspaceEditResult, LspException | E] =
            initRequest("workspace/applyEdit", Kind.ApplyEdit, handler)

        def configuration[T](using
            outSchema: Schema[T]
        )[E](
            handler: ConfigurationParams => Chunk[T] < LE[E]
        )(using frame: Frame): LspHandler[ConfigurationParams, Chunk[T], LspException | E] =
            initRequest("workspace/configuration", Kind.Configuration, handler)

        def workspaceFolders[E](
            handler: Unit => Maybe[Chunk[WorkspaceFolder]] < LE[E]
        )(using Frame): LspHandler[Unit, Maybe[Chunk[WorkspaceFolder]], LspException | E] =
            initRequest("workspace/workspaceFolders", Kind.WorkspaceFolders, handler)

        def refreshSemanticTokens(using Frame): LspHandler[Unit, Unit, LspException] =
            initNotification[Unit, Nothing]("workspace/semanticTokens/refresh", Kind.RefreshSemanticTokens, _ => ())

        def refreshInlineValue(using Frame): LspHandler[Unit, Unit, LspException] =
            initNotification[Unit, Nothing]("workspace/inlineValue/refresh", Kind.RefreshInlineValue, _ => ())

        def refreshInlayHint(using Frame): LspHandler[Unit, Unit, LspException] =
            initNotification[Unit, Nothing]("workspace/inlayHint/refresh", Kind.RefreshInlayHint, _ => ())

        def refreshDiagnostic(using Frame): LspHandler[Unit, Unit, LspException] =
            initNotification[Unit, Nothing]("workspace/diagnostic/refresh", Kind.RefreshDiagnostic, _ => ())

        def refreshCodeLens(using Frame): LspHandler[Unit, Unit, LspException] =
            initNotification[Unit, Nothing]("workspace/codeLens/refresh", Kind.RefreshCodeLens, _ => ())

    end Workspace

    // MARK: -- NotebookDocument namespace factory object

    /** Namespaced factories for `notebookDocument/X` LSP endpoints. */
    object NotebookDocument:

        def didOpen[E](
            handler: DidOpenNotebookDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidOpenNotebookDocumentParams, Unit, LspException | E] =
            initNotification("notebookDocument/didOpen", Kind.NotebookDidOpen, handler)

        def didChange[E](
            handler: DidChangeNotebookDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidChangeNotebookDocumentParams, Unit, LspException | E] =
            initNotification("notebookDocument/didChange", Kind.NotebookDidChange, handler)

        def didSave[E](
            handler: DidSaveNotebookDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidSaveNotebookDocumentParams, Unit, LspException | E] =
            initNotification("notebookDocument/didSave", Kind.NotebookDidSave, handler)

        def didClose[E](
            handler: DidCloseNotebookDocumentParams => Unit < LE[E]
        )(using Frame): LspHandler[DidCloseNotebookDocumentParams, Unit, LspException | E] =
            initNotification("notebookDocument/didClose", Kind.NotebookDidClose, handler)

    end NotebookDocument

    // MARK: -- Window namespace factory object

    /** Namespaced factories for `window/X` client-handled reverse-direction LSP endpoints. */
    object Window:

        def showMessage[E](
            handler: ShowMessageParams => Unit < LE[E]
        )(using Frame): LspHandler[ShowMessageParams, Unit, LspException | E] =
            initNotification("window/showMessage", Kind.ShowMessage, handler)

        def showMessageRequest[E](
            handler: ShowMessageRequestParams => Maybe[MessageActionItem] < LE[E]
        )(using Frame): LspHandler[ShowMessageRequestParams, Maybe[MessageActionItem], LspException | E] =
            initRequest("window/showMessageRequest", Kind.ShowMessageRequest, handler)

        def showDocument[E](
            handler: ShowDocumentParams => ShowDocumentResult < LE[E]
        )(using Frame): LspHandler[ShowDocumentParams, ShowDocumentResult, LspException | E] =
            initRequest("window/showDocument", Kind.ShowDocument, handler)

        def logMessage[E](
            handler: LogMessageParams => Unit < LE[E]
        )(using Frame): LspHandler[LogMessageParams, Unit, LspException | E] =
            initNotification("window/logMessage", Kind.LogMessage, handler)

        def createWorkDoneProgress[E](
            handler: WorkDoneProgressCreateParams => Unit < LE[E]
        )(using Frame): LspHandler[WorkDoneProgressCreateParams, Unit, LspException | E] =
            initRequest("window/workDoneProgress/create", Kind.WorkDoneProgressCreate, handler)

        def workDoneProgressCancel[E](
            handler: WorkDoneProgressCancelParams => Unit < LE[E]
        )(using Frame): LspHandler[WorkDoneProgressCancelParams, Unit, LspException | E] =
            initNotification("window/workDoneProgress/cancel", Kind.WorkDoneProgressCancel, handler)

        def telemetry[T](using
            outSchema: Schema[T]
        )[E](
            handler: T => Unit < LE[E]
        )(using frame: Frame): LspHandler[T, Unit, LspException | E] =
            initNotification("telemetry/event", Kind.Telemetry, handler)

    end Window

    // MARK: -- Client namespace factory object

    /** Namespaced factories for `client/X` reverse-direction LSP endpoints. */
    object Client:

        def registerCapability[E](
            handler: RegistrationParams => Unit < LE[E]
        )(using Frame): LspHandler[RegistrationParams, Unit, LspException | E] =
            initRequest("client/registerCapability", Kind.RegisterCapability, handler)

        def unregisterCapability[E](
            handler: UnregistrationParams => Unit < LE[E]
        )(using Frame): LspHandler[UnregistrationParams, Unit, LspException | E] =
            initRequest("client/unregisterCapability", Kind.UnregisterCapability, handler)

    end Client

    // MARK: -- General namespace factory object

    /** Namespaced factories for `$/X` bidirectional general LSP endpoints.
      *
      * The engine ALWAYS handles `$/cancelRequest` and `$/progress` at the substrate level.
      * Factories in this namespace register USER observers that run AFTER the engine's built-in
      * handling, enabling logging and instrumentation. `$/setTrace` (server-side from client)
      * and `$/logTrace` (client-side from server) are similarly user-registerable.
      *
      * Engine-reserved methods (`initialize`, `shutdown`, `exit`, `initialized`) are NOT in this
      * namespace; they are engine-owned and never user-registerable.
      */
    object General:

        /** Observer for inbound `$/cancelRequest`. The engine has already completed the matching
          * `JsonRpcRoute.Context.cancelled` promise; this hook lets user code log or instrument
          * cancellation after the fact.
          */
        def cancelRequest[E](handler: CancelParams => Unit < LE[E])(using Frame): LspHandler[CancelParams, Unit, LspException | E] =
            initNotification("$/cancelRequest", Kind.CancelRequest, handler)

        /** Observer for inbound `$/progress`. The engine has already dispatched the value to the
          * per-token stream subscription; this hook lets user code observe progress notifications
          * for ad-hoc instrumentation.
          */
        def progress[T](using
            Schema[T]
        )[E](
            handler: ProgressParams[T] => Unit < LE[E]
        )(using Frame): LspHandler[ProgressParams[T], Unit, LspException | E] =
            initNotification("$/progress", Kind.Progress, handler)

        /** Server-side observer for `$/setTrace` notifications from the client. */
        def setTrace[E](handler: SetTraceParams => Unit < LE[E])(using Frame): LspHandler[SetTraceParams, Unit, LspException | E] =
            initNotification("$/setTrace", Kind.SetTrace, handler)

        /** Client-side observer for `$/logTrace` notifications from the server. */
        def logTrace[E](handler: LogTraceParams => Unit < LE[E])(using Frame): LspHandler[LogTraceParams, Unit, LspException | E] =
            initNotification("$/logTrace", Kind.LogTrace, handler)

    end General

    // MARK: -- custom / customClient escape hatches

    /** Creates a custom server-handled extension method handler.
      *
      * Use for vendor extensions and future spec methods not yet covered by the typed factories.
      * The `method` string must not collide with a standard LSP method name.
      */
    def custom[In](method: String)(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < LE[E]
    )(using outSchema: Schema[Out], frame: Frame): LspHandler[In, Out, LspException | E] =
        initCustom(method, Direction.ServerHandled, handler)

    /** Creates a custom client-handled extension method handler.
      *
      * Use for client-direction vendor extensions.
      */
    def customClient[In](method: String)(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < LE[E]
    )(using outSchema: Schema[Out], frame: Frame): LspHandler[In, Out, LspException | E] =
        initCustom(method, Direction.ClientHandled, handler)

    // MARK: -- Private[kyo] carrier subclasses

    /** Carrier for server-handled request handlers. */
    final private[kyo] class RequestHandler[In, Out, +E] private[kyo] (
        val wireName: String,
        val handlerKind: Kind,
        val handlerFn: In => Out < LE[E],
        val errorMappings: Chunk[ErrorMapping[?]],
        val inSchema: Schema[In],
        val outSchema: Schema[Out]
    ) extends LspHandler[In, Out, E]:
        def name: String         = wireName
        def kind: Kind           = handlerKind
        def direction: Direction = LspHandler.direction(handlerKind)
        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): LspHandler[In, Out, E | E2] =
            new RequestHandler[In, Out, E | E2](
                wireName,
                handlerKind,
                handlerFn,
                errorMappings.append(new ErrorMapping[E2](code, message)),
                inSchema,
                outSchema
            )
    end RequestHandler

    /** Carrier for server-handled notification handlers. */
    final private[kyo] class NotificationHandler[In, +E] private[kyo] (
        val wireName: String,
        val handlerKind: Kind,
        val handlerFn: In => Unit < LE[E],
        val errorMappings: Chunk[ErrorMapping[?]],
        val inSchema: Schema[In]
    ) extends LspHandler[In, Unit, E]:
        def name: String         = wireName
        def kind: Kind           = handlerKind
        def direction: Direction = LspHandler.direction(handlerKind)
        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): LspHandler[In, Unit, E | E2] =
            new NotificationHandler[In, E | E2](
                wireName,
                handlerKind,
                handlerFn,
                errorMappings.append(new ErrorMapping[E2](code, message)),
                inSchema
            )
    end NotificationHandler

    /** Carrier for custom (escape-hatch) handlers. */
    final private[kyo] class CustomHandler[In, Out, +E] private[kyo] (
        val wireName: String,
        val handlerDirection: Direction,
        val handlerFn: In => Out < LE[E],
        val errorMappings: Chunk[ErrorMapping[?]],
        val inSchema: Schema[In],
        val outSchema: Schema[Out]
    ) extends LspHandler[In, Out, E]:
        def name: String         = wireName
        def kind: Kind           = Kind.Custom
        def direction: Direction = handlerDirection
        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): LspHandler[In, Out, E | E2] =
            new CustomHandler[In, Out, E | E2](
                wireName,
                handlerDirection,
                handlerFn,
                errorMappings.append(new ErrorMapping[E2](code, message)),
                inSchema,
                outSchema
            )
    end CustomHandler

end LspHandler
