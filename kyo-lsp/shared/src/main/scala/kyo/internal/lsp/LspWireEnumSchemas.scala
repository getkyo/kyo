package kyo.internal.lsp

import kyo.*
import scala.annotation.publicInBinary

/** Hand-rolled Schemas for numeric-keyed and string-keyed LSP 3.17 enums, plus the
  * parameterized BooleanOr[T] and StringOr[T] discriminator schemas.
  *
  * Numeric enums use `Schema.intSchema.transform`. String enums use
  * `Schema.stringSchema.transform`. Every instance is a `val` singleton (BooleanOr / StringOr
  * are `given [T: Schema]` because they are parameterized). None of these enums use
  * `derives Schema`.
  *
  * All numeric wire values are taken verbatim from the LSP 3.17 specification.
  * `TextDocumentSyncKind.None` is the only case with wire value 0.
  */
private[kyo] object LspWireEnumSchemas:

    // MARK: -- Numeric-keyed enum schemas

    /** Schema[DocumentHighlightKind]: Text=1, Read=2, Write=3. */
    @publicInBinary val documentHighlightKindSchema: Schema[LspHandler.DocumentHighlightKind] =
        Schema.intSchema.transform[LspHandler.DocumentHighlightKind](n =>
            n match
                case 1 => LspHandler.DocumentHighlightKind.Text
                case 2 => LspHandler.DocumentHighlightKind.Read
                case 3 => LspHandler.DocumentHighlightKind.Write
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[TextDocumentSyncKind]: None=0, Incremental=1, Full=2. */
    @publicInBinary val textDocumentSyncKindSchema: Schema[LspHandler.TextDocumentSyncKind] =
        Schema.intSchema.transform[LspHandler.TextDocumentSyncKind](n =>
            n match
                case 0 => LspHandler.TextDocumentSyncKind.None
                case 1 => LspHandler.TextDocumentSyncKind.Incremental
                case 2 => LspHandler.TextDocumentSyncKind.Full
                case _ =>
                    throw TypeMismatchException(Seq.empty, "0|1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal
        )

    /** Schema[TextDocumentSaveReason]: Manual=1, AfterDelay=2, FocusOut=3. */
    @publicInBinary val textDocumentSaveReasonSchema: Schema[LspHandler.TextDocumentSaveReason] =
        Schema.intSchema.transform[LspHandler.TextDocumentSaveReason](n =>
            n match
                case 1 => LspHandler.TextDocumentSaveReason.Manual
                case 2 => LspHandler.TextDocumentSaveReason.AfterDelay
                case 3 => LspHandler.TextDocumentSaveReason.FocusOut
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[DiagnosticSeverity]: Error=1, Warning=2, Information=3, Hint=4. */
    @publicInBinary val diagnosticSeveritySchema: Schema[LspHandler.DiagnosticSeverity] =
        Schema.intSchema.transform[LspHandler.DiagnosticSeverity](n =>
            n match
                case 1 => LspHandler.DiagnosticSeverity.Error
                case 2 => LspHandler.DiagnosticSeverity.Warning
                case 3 => LspHandler.DiagnosticSeverity.Information
                case 4 => LspHandler.DiagnosticSeverity.Hint
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3|4", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[DiagnosticTag]: Unnecessary=1, Deprecated=2. */
    @publicInBinary val diagnosticTagSchema: Schema[LspHandler.DiagnosticTag] =
        Schema.intSchema.transform[LspHandler.DiagnosticTag](n =>
            n match
                case 1 => LspHandler.DiagnosticTag.Unnecessary
                case 2 => LspHandler.DiagnosticTag.Deprecated
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[SymbolKind]: File=1..TypeParameter=26 (all cases shift by +1 from ordinal). */
    @publicInBinary val symbolKindSchema: Schema[LspHandler.SymbolKind] =
        Schema.intSchema.transform[LspHandler.SymbolKind](n =>
            if n >= 1 && n <= 26 then LspHandler.SymbolKind.fromOrdinal(n - 1)
            else throw TypeMismatchException(Seq.empty, "1..26", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[SymbolTag]: Deprecated=1. */
    @publicInBinary val symbolTagSchema: Schema[LspHandler.SymbolTag] =
        Schema.intSchema.transform[LspHandler.SymbolTag](n =>
            n match
                case 1 => LspHandler.SymbolTag.Deprecated
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[CompletionItemKind]: Text=1..TypeParameter=25. */
    @publicInBinary val completionItemKindSchema: Schema[LspHandler.CompletionItemKind] =
        Schema.intSchema.transform[LspHandler.CompletionItemKind](n =>
            if n >= 1 && n <= 25 then LspHandler.CompletionItemKind.fromOrdinal(n - 1)
            else throw TypeMismatchException(Seq.empty, "1..25", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[CompletionItemTag]: Deprecated=1. */
    @publicInBinary val completionItemTagSchema: Schema[LspHandler.CompletionItemTag] =
        Schema.intSchema.transform[LspHandler.CompletionItemTag](n =>
            n match
                case 1 => LspHandler.CompletionItemTag.Deprecated
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[InsertTextFormat]: PlainText=1, Snippet=2. */
    @publicInBinary val insertTextFormatSchema: Schema[LspHandler.InsertTextFormat] =
        Schema.intSchema.transform[LspHandler.InsertTextFormat](n =>
            n match
                case 1 => LspHandler.InsertTextFormat.PlainText
                case 2 => LspHandler.InsertTextFormat.Snippet
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[InsertTextMode]: AsIs=1, AdjustIndentation=2. */
    @publicInBinary val insertTextModeSchema: Schema[LspHandler.InsertTextMode] =
        Schema.intSchema.transform[LspHandler.InsertTextMode](n =>
            n match
                case 1 => LspHandler.InsertTextMode.AsIs
                case 2 => LspHandler.InsertTextMode.AdjustIndentation
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[CompletionTriggerKind]: Invoked=1, TriggerCharacter=2, TriggerForIncompleteCompletions=3. */
    @publicInBinary val completionTriggerKindSchema: Schema[LspHandler.CompletionTriggerKind] =
        Schema.intSchema.transform[LspHandler.CompletionTriggerKind](n =>
            n match
                case 1 => LspHandler.CompletionTriggerKind.Invoked
                case 2 => LspHandler.CompletionTriggerKind.TriggerCharacter
                case 3 => LspHandler.CompletionTriggerKind.TriggerForIncompleteCompletions
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[SignatureHelpTriggerKind]: Invoked=1, TriggerCharacter=2, ContentChange=3. */
    @publicInBinary val signatureHelpTriggerKindSchema: Schema[LspHandler.SignatureHelpTriggerKind] =
        Schema.intSchema.transform[LspHandler.SignatureHelpTriggerKind](n =>
            n match
                case 1 => LspHandler.SignatureHelpTriggerKind.Invoked
                case 2 => LspHandler.SignatureHelpTriggerKind.TriggerCharacter
                case 3 => LspHandler.SignatureHelpTriggerKind.ContentChange
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[CodeActionTriggerKind]: Invoked=1, Automatic=2. */
    @publicInBinary val codeActionTriggerKindSchema: Schema[LspHandler.CodeActionTriggerKind] =
        Schema.intSchema.transform[LspHandler.CodeActionTriggerKind](n =>
            n match
                case 1 => LspHandler.CodeActionTriggerKind.Invoked
                case 2 => LspHandler.CodeActionTriggerKind.Automatic
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[InlayHintKind]: Type=1, Parameter=2. */
    @publicInBinary val inlayHintKindSchema: Schema[LspHandler.InlayHintKind] =
        Schema.intSchema.transform[LspHandler.InlayHintKind](n =>
            n match
                case 1 => LspHandler.InlayHintKind.Type
                case 2 => LspHandler.InlayHintKind.Parameter
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[NotebookCellKind]: Markup=1, Code=2. */
    @publicInBinary val notebookCellKindSchema: Schema[LspHandler.NotebookCellKind] =
        Schema.intSchema.transform[LspHandler.NotebookCellKind](n =>
            n match
                case 1 => LspHandler.NotebookCellKind.Markup
                case 2 => LspHandler.NotebookCellKind.Code
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[MessageType]: Error=1, Warning=2, Info=3, Log=4, Debug=5. */
    @publicInBinary val messageTypeSchema: Schema[LspHandler.MessageType] =
        Schema.intSchema.transform[LspHandler.MessageType](n =>
            n match
                case 1 => LspHandler.MessageType.Error
                case 2 => LspHandler.MessageType.Warning
                case 3 => LspHandler.MessageType.Info
                case 4 => LspHandler.MessageType.Log
                case 5 => LspHandler.MessageType.Debug
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3|4|5", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    /** Schema[FileChangeType]: Created=1, Changed=2, Deleted=3. */
    @publicInBinary val fileChangeTypeSchema: Schema[LspHandler.FileChangeType] =
        Schema.intSchema.transform[LspHandler.FileChangeType](n =>
            n match
                case 1 => LspHandler.FileChangeType.Created
                case 2 => LspHandler.FileChangeType.Changed
                case 3 => LspHandler.FileChangeType.Deleted
                case _ =>
                    throw TypeMismatchException(Seq.empty, "1|2|3", n.toString)(using Frame.internal)
        )(
            _.ordinal + 1
        )

    // MARK: -- String-keyed enum schemas

    /** Schema[MarkupKind]: PlainText="plaintext", Markdown="markdown". */
    @publicInBinary val markupKindSchema: Schema[LspHandler.MarkupKind] =
        Schema.stringSchema.transform[LspHandler.MarkupKind](s =>
            s match
                case "plaintext" => LspHandler.MarkupKind.PlainText
                case "markdown"  => LspHandler.MarkupKind.Markdown
                case _ =>
                    throw TypeMismatchException(Seq.empty, "plaintext|markdown", s)(using Frame.internal)
        )(k =>
            k match
                case LspHandler.MarkupKind.PlainText => "plaintext"
                case LspHandler.MarkupKind.Markdown  => "markdown"
        )

    /** Schema[ResourceOperationKind]: Create="create", Rename="rename", Delete="delete". */
    @publicInBinary val resourceOperationKindSchema: Schema[LspHandler.ResourceOperationKind] =
        Schema.stringSchema.transform[LspHandler.ResourceOperationKind](s =>
            s match
                case "create" => LspHandler.ResourceOperationKind.Create
                case "rename" => LspHandler.ResourceOperationKind.Rename
                case "delete" => LspHandler.ResourceOperationKind.Delete
                case _ =>
                    throw TypeMismatchException(Seq.empty, "create|rename|delete", s)(using Frame.internal)
        )(k =>
            k match
                case LspHandler.ResourceOperationKind.Create => "create"
                case LspHandler.ResourceOperationKind.Rename => "rename"
                case LspHandler.ResourceOperationKind.Delete => "delete"
        )

    /** Schema[MonikerKind]: Import="import", Export="export", Local="local". */
    @publicInBinary val monikerKindSchema: Schema[LspHandler.MonikerKind] =
        Schema.stringSchema.transform[LspHandler.MonikerKind](s =>
            s match
                case "import" => LspHandler.MonikerKind.Import
                case "export" => LspHandler.MonikerKind.Export
                case "local"  => LspHandler.MonikerKind.Local
                case _ =>
                    throw TypeMismatchException(Seq.empty, "import|export|local", s)(using Frame.internal)
        )(k =>
            k match
                case LspHandler.MonikerKind.Import => "import"
                case LspHandler.MonikerKind.Export => "export"
                case LspHandler.MonikerKind.Local  => "local"
        )

    /** Schema[UniquenessLevel]: Document="document", Project="project", Group="group", Scheme="scheme", Global="global". */
    @publicInBinary val uniquenessLevelSchema: Schema[LspHandler.UniquenessLevel] =
        Schema.stringSchema.transform[LspHandler.UniquenessLevel](s =>
            s match
                case "document" => LspHandler.UniquenessLevel.Document
                case "project"  => LspHandler.UniquenessLevel.Project
                case "group"    => LspHandler.UniquenessLevel.Group
                case "scheme"   => LspHandler.UniquenessLevel.Scheme
                case "global"   => LspHandler.UniquenessLevel.Global
                case _ =>
                    throw TypeMismatchException(Seq.empty, "document|project|group|scheme|global", s)(using Frame.internal)
        )(k =>
            k match
                case LspHandler.UniquenessLevel.Document => "document"
                case LspHandler.UniquenessLevel.Project  => "project"
                case LspHandler.UniquenessLevel.Group    => "group"
                case LspHandler.UniquenessLevel.Scheme   => "scheme"
                case LspHandler.UniquenessLevel.Global   => "global"
        )

    /** Schema[FileOperationPatternKind]: File="file", Folder="folder". */
    @publicInBinary val fileOperationPatternKindSchema: Schema[LspHandler.FileOperationPatternKind] =
        Schema.stringSchema.transform[LspHandler.FileOperationPatternKind](s =>
            s match
                case "file"   => LspHandler.FileOperationPatternKind.File
                case "folder" => LspHandler.FileOperationPatternKind.Folder
                case _ =>
                    throw TypeMismatchException(Seq.empty, "file|folder", s)(using Frame.internal)
        )(k =>
            k match
                case LspHandler.FileOperationPatternKind.File   => "file"
                case LspHandler.FileOperationPatternKind.Folder => "folder"
        )

    // MARK: -- Parameterized BooleanOr / StringOr schemas (Category C)

    /** Schema[BooleanOr[T]]: boolean JSON node -> Bool(v); object JSON node -> Options(schema.read(T)).
      *
      * Uses captureValue() to snapshot the next JSON token, then attempts to read it as boolean.
      * If that fails (the token is an object, not a boolean), falls back to reading via Schema[T].
      * The fromStructureValue override handles the Structure.Value path directly.
      */
    given [T: Schema]: Schema[LspHandler.BooleanOr[T]] =
        new Schema[LspHandler.BooleanOr[T]](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.BooleanOr[T], w: Codec.Writer): Unit =
                v match
                    case LspHandler.BooleanOr.Bool(b)    => w.boolean(b)
                    case LspHandler.BooleanOr.Options(t) => summon[Schema[T]].serializeWrite(t, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.BooleanOr[T] =
                val captured = reader.captureValue()
                try LspHandler.BooleanOr.Bool(captured.boolean())
                catch case _: Exception => LspHandler.BooleanOr.Options(summon[Schema[T]].serializeRead(captured))
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.BooleanOr[T]): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.BooleanOr[T], next: Any): LspHandler.BooleanOr[T] =
                next match
                    case b: LspHandler.BooleanOr[?] => b.asInstanceOf[LspHandler.BooleanOr[T]]
                    case _                          => value
            private lazy val _structure: Structure.Type =
                // Non-inline given: no implicit Tag[T] in scope; fall back to Tag[Any].
                Structure.Type.Open(Tag[Any])
            override def structure: Structure.Type = _structure
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.BooleanOr[T]] =
                sv match
                    case Structure.Value.Bool(b) => Result.Success(LspHandler.BooleanOr.Bool(b))
                    case other =>
                        summon[Schema[T]].fromStructureValue(other).map(t => LspHandler.BooleanOr.Options(t))

    /** Schema[StringOr[T]]: string JSON node -> Str(v); object JSON node -> Options(schema.read(T)).
      *
      * Uses captureValue() to snapshot the next JSON token, then attempts to read it as string.
      * If that fails (the token is an object, not a string), falls back to reading via Schema[T].
      * The fromStructureValue override handles the Structure.Value path directly.
      */
    given [T: Schema]: Schema[LspHandler.StringOr[T]] =
        new Schema[LspHandler.StringOr[T]](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.StringOr[T], w: Codec.Writer): Unit =
                v match
                    case LspHandler.StringOr.Str(s)     => w.string(s)
                    case LspHandler.StringOr.Options(t) => summon[Schema[T]].serializeWrite(t, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.StringOr[T] =
                val captured = reader.captureValue()
                try LspHandler.StringOr.Str(captured.string())
                catch case _: Exception => LspHandler.StringOr.Options(summon[Schema[T]].serializeRead(captured))
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.StringOr[T]): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.StringOr[T], next: Any): LspHandler.StringOr[T] =
                next match
                    case s: LspHandler.StringOr[?] => s.asInstanceOf[LspHandler.StringOr[T]]
                    case _                         => value
            private lazy val _structure: Structure.Type =
                // Non-inline given: no implicit Tag[T] in scope; fall back to Tag[Any].
                Structure.Type.Open(Tag[Any])
            override def structure: Structure.Type = _structure
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.StringOr[T]] =
                sv match
                    case Structure.Value.Str(s) => Result.Success(LspHandler.StringOr.Str(s))
                    case other =>
                        summon[Schema[T]].fromStructureValue(other).map(t => LspHandler.StringOr.Options(t))

    // MARK: -- LspCapabilities.Name string schema

    /** Schema[LspCapabilities.Name]: every case maps to its LSP 3.17 spec-canonical string. */
    @publicInBinary val lspCapabilityNameSchema: Schema[LspCapabilities.Name] =
        Schema.stringSchema.transform[LspCapabilities.Name](s =>
            s match
                case "completion"                       => LspCapabilities.Name.Completion
                case "hover"                            => LspCapabilities.Name.Hover
                case "signatureHelp"                    => LspCapabilities.Name.SignatureHelp
                case "declaration"                      => LspCapabilities.Name.Declaration
                case "definition"                       => LspCapabilities.Name.Definition
                case "typeDefinition"                   => LspCapabilities.Name.TypeDefinition
                case "implementation"                   => LspCapabilities.Name.Implementation
                case "references"                       => LspCapabilities.Name.References
                case "documentHighlight"                => LspCapabilities.Name.DocumentHighlight
                case "documentSymbol"                   => LspCapabilities.Name.DocumentSymbol
                case "codeAction"                       => LspCapabilities.Name.CodeAction
                case "codeLens"                         => LspCapabilities.Name.CodeLens
                case "documentLink"                     => LspCapabilities.Name.DocumentLink
                case "colorProvider"                    => LspCapabilities.Name.DocumentColor
                case "documentFormattingProvider"       => LspCapabilities.Name.Formatting
                case "documentRangeFormattingProvider"  => LspCapabilities.Name.RangeFormatting
                case "documentOnTypeFormattingProvider" => LspCapabilities.Name.OnTypeFormatting
                case "rename"                           => LspCapabilities.Name.Rename
                case "foldingRange"                     => LspCapabilities.Name.FoldingRange
                case "selectionRange"                   => LspCapabilities.Name.SelectionRange
                case "callHierarchy"                    => LspCapabilities.Name.CallHierarchy
                case "typeHierarchy"                    => LspCapabilities.Name.TypeHierarchy
                case "semanticTokens"                   => LspCapabilities.Name.SemanticTokens
                case "moniker"                          => LspCapabilities.Name.Moniker
                case "linkedEditingRange"               => LspCapabilities.Name.LinkedEditingRange
                case "inlayHint"                        => LspCapabilities.Name.InlayHint
                case "inlineValue"                      => LspCapabilities.Name.InlineValue
                case "diagnostic"                       => LspCapabilities.Name.Diagnostic
                case "notebookDocumentSync"             => LspCapabilities.Name.NotebookDocumentSync
                case "executeCommand"                   => LspCapabilities.Name.ExecuteCommand
                case "workspaceSymbol"                  => LspCapabilities.Name.WorkspaceSymbol
                case "workspaceFolders"                 => LspCapabilities.Name.WorkspaceFolders
                case "fileOperations"                   => LspCapabilities.Name.FileOperations
                case _ =>
                    throw TypeMismatchException(Seq.empty, "LSP capability name string", s)(using Frame.internal)
        )(n =>
            n match
                case LspCapabilities.Name.Completion           => "completion"
                case LspCapabilities.Name.Hover                => "hover"
                case LspCapabilities.Name.SignatureHelp        => "signatureHelp"
                case LspCapabilities.Name.Declaration          => "declaration"
                case LspCapabilities.Name.Definition           => "definition"
                case LspCapabilities.Name.TypeDefinition       => "typeDefinition"
                case LspCapabilities.Name.Implementation       => "implementation"
                case LspCapabilities.Name.References           => "references"
                case LspCapabilities.Name.DocumentHighlight    => "documentHighlight"
                case LspCapabilities.Name.DocumentSymbol       => "documentSymbol"
                case LspCapabilities.Name.CodeAction           => "codeAction"
                case LspCapabilities.Name.CodeLens             => "codeLens"
                case LspCapabilities.Name.DocumentLink         => "documentLink"
                case LspCapabilities.Name.DocumentColor        => "colorProvider"
                case LspCapabilities.Name.Formatting           => "documentFormattingProvider"
                case LspCapabilities.Name.RangeFormatting      => "documentRangeFormattingProvider"
                case LspCapabilities.Name.OnTypeFormatting     => "documentOnTypeFormattingProvider"
                case LspCapabilities.Name.Rename               => "rename"
                case LspCapabilities.Name.FoldingRange         => "foldingRange"
                case LspCapabilities.Name.SelectionRange       => "selectionRange"
                case LspCapabilities.Name.CallHierarchy        => "callHierarchy"
                case LspCapabilities.Name.TypeHierarchy        => "typeHierarchy"
                case LspCapabilities.Name.SemanticTokens       => "semanticTokens"
                case LspCapabilities.Name.Moniker              => "moniker"
                case LspCapabilities.Name.LinkedEditingRange   => "linkedEditingRange"
                case LspCapabilities.Name.InlayHint            => "inlayHint"
                case LspCapabilities.Name.InlineValue          => "inlineValue"
                case LspCapabilities.Name.Diagnostic           => "diagnostic"
                case LspCapabilities.Name.NotebookDocumentSync => "notebookDocumentSync"
                case LspCapabilities.Name.ExecuteCommand       => "executeCommand"
                case LspCapabilities.Name.WorkspaceSymbol      => "workspaceSymbol"
                case LspCapabilities.Name.WorkspaceFolders     => "workspaceFolders"
                case LspCapabilities.Name.FileOperations       => "fileOperations"
        )

end LspWireEnumSchemas
