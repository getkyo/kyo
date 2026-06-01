package kyo.internal

import kyo.*

/** Tests for all numeric-keyed and string-keyed LSP 3.17 enum schemas (INV-051 / INV-052).
  *
  * Each enum encodes to its spec-mandated wire value (integer or string) and round-trips
  * correctly. The "segments is empty" assertion confirms each schema uses transform (not
  * derives Schema).
  */
class LspWireEnumSchemaTest extends Test:

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow
    private def roundtrip[A: Schema](value: A): A   = decode[A](encode[A](value))

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // ---- DocumentHighlightKind (INV-051) ----

    "DocumentHighlightKind.Text encodes to 1" in {
        assert(encode[LspHandler.DocumentHighlightKind](LspHandler.DocumentHighlightKind.Text) == "1")
    }
    "DocumentHighlightKind.Read encodes to 2" in {
        assert(encode[LspHandler.DocumentHighlightKind](LspHandler.DocumentHighlightKind.Read) == "2")
    }
    "DocumentHighlightKind.Write encodes to 3" in {
        assert(encode[LspHandler.DocumentHighlightKind](LspHandler.DocumentHighlightKind.Write) == "3")
    }
    "DocumentHighlightKind round-trips all cases" in {
        val cases = List(
            LspHandler.DocumentHighlightKind.Text,
            LspHandler.DocumentHighlightKind.Read,
            LspHandler.DocumentHighlightKind.Write
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }
    "DocumentHighlightKind schema uses transform" in {
        assert(summon[Schema[LspHandler.DocumentHighlightKind]].segments.isEmpty)
    }

    // ---- TextDocumentSyncKind (INV-051; None=0 is the special case) ----

    "TextDocumentSyncKind.None encodes to 0" in {
        assert(encode[LspHandler.TextDocumentSyncKind](LspHandler.TextDocumentSyncKind.None) == "0")
    }
    "TextDocumentSyncKind.Incremental encodes to 1" in {
        assert(encode[LspHandler.TextDocumentSyncKind](LspHandler.TextDocumentSyncKind.Incremental) == "1")
    }
    "TextDocumentSyncKind.Full encodes to 2" in {
        assert(encode[LspHandler.TextDocumentSyncKind](LspHandler.TextDocumentSyncKind.Full) == "2")
    }
    "TextDocumentSyncKind round-trips all cases" in {
        val cases = List(
            LspHandler.TextDocumentSyncKind.None,
            LspHandler.TextDocumentSyncKind.Incremental,
            LspHandler.TextDocumentSyncKind.Full
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- TextDocumentSaveReason (INV-051) ----

    "TextDocumentSaveReason.Manual encodes to 1" in {
        assert(encode[LspHandler.TextDocumentSaveReason](LspHandler.TextDocumentSaveReason.Manual) == "1")
    }
    "TextDocumentSaveReason.AfterDelay encodes to 2" in {
        assert(encode[LspHandler.TextDocumentSaveReason](LspHandler.TextDocumentSaveReason.AfterDelay) == "2")
    }
    "TextDocumentSaveReason.FocusOut encodes to 3" in {
        assert(encode[LspHandler.TextDocumentSaveReason](LspHandler.TextDocumentSaveReason.FocusOut) == "3")
    }
    "TextDocumentSaveReason round-trips all cases" in {
        val cases = List(
            LspHandler.TextDocumentSaveReason.Manual,
            LspHandler.TextDocumentSaveReason.AfterDelay,
            LspHandler.TextDocumentSaveReason.FocusOut
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- DiagnosticSeverity (INV-051) ----

    "DiagnosticSeverity.Error encodes to 1" in {
        assert(encode[LspHandler.DiagnosticSeverity](LspHandler.DiagnosticSeverity.Error) == "1")
    }
    "DiagnosticSeverity.Warning encodes to 2" in {
        assert(encode[LspHandler.DiagnosticSeverity](LspHandler.DiagnosticSeverity.Warning) == "2")
    }
    "DiagnosticSeverity.Information encodes to 3" in {
        assert(encode[LspHandler.DiagnosticSeverity](LspHandler.DiagnosticSeverity.Information) == "3")
    }
    "DiagnosticSeverity.Hint encodes to 4" in {
        assert(encode[LspHandler.DiagnosticSeverity](LspHandler.DiagnosticSeverity.Hint) == "4")
    }
    "DiagnosticSeverity round-trips all 4 cases" in {
        val cases = List(
            LspHandler.DiagnosticSeverity.Error,
            LspHandler.DiagnosticSeverity.Warning,
            LspHandler.DiagnosticSeverity.Information,
            LspHandler.DiagnosticSeverity.Hint
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- DiagnosticTag (INV-051) ----

    "DiagnosticTag.Unnecessary encodes to 1" in {
        assert(encode[LspHandler.DiagnosticTag](LspHandler.DiagnosticTag.Unnecessary) == "1")
    }
    "DiagnosticTag.Deprecated encodes to 2" in {
        assert(encode[LspHandler.DiagnosticTag](LspHandler.DiagnosticTag.Deprecated) == "2")
    }

    // ---- SymbolKind (INV-051; File=1..TypeParameter=26) ----

    "SymbolKind.File encodes to 1" in {
        assert(encode[LspHandler.SymbolKind](LspHandler.SymbolKind.File) == "1")
    }
    "SymbolKind.TypeParameter encodes to 26" in {
        assert(encode[LspHandler.SymbolKind](LspHandler.SymbolKind.TypeParameter) == "26")
    }
    "SymbolKind.Class encodes to 5" in {
        assert(encode[LspHandler.SymbolKind](LspHandler.SymbolKind.Class) == "5")
    }
    "SymbolKind round-trips all 26 cases" in {
        val cases = LspHandler.SymbolKind.values.toList
        assert(cases.size == 26)
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- CompletionItemKind (INV-051; Text=1..TypeParameter=25) ----

    "CompletionItemKind.Text encodes to 1" in {
        assert(encode[LspHandler.CompletionItemKind](LspHandler.CompletionItemKind.Text) == "1")
    }
    "CompletionItemKind.TypeParameter encodes to 25" in {
        assert(encode[LspHandler.CompletionItemKind](LspHandler.CompletionItemKind.TypeParameter) == "25")
    }
    "CompletionItemKind round-trips all 25 cases" in {
        val cases = LspHandler.CompletionItemKind.values.toList
        assert(cases.size == 25)
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- InsertTextFormat (INV-051) ----

    "InsertTextFormat.PlainText encodes to 1" in {
        assert(encode[LspHandler.InsertTextFormat](LspHandler.InsertTextFormat.PlainText) == "1")
    }
    "InsertTextFormat.Snippet encodes to 2" in {
        assert(encode[LspHandler.InsertTextFormat](LspHandler.InsertTextFormat.Snippet) == "2")
    }

    // ---- InsertTextMode (INV-051) ----

    "InsertTextMode.AsIs encodes to 1" in {
        assert(encode[LspHandler.InsertTextMode](LspHandler.InsertTextMode.AsIs) == "1")
    }
    "InsertTextMode.AdjustIndentation encodes to 2" in {
        assert(encode[LspHandler.InsertTextMode](LspHandler.InsertTextMode.AdjustIndentation) == "2")
    }

    // ---- CompletionTriggerKind (INV-051) ----

    "CompletionTriggerKind.Invoked encodes to 1" in {
        assert(encode[LspHandler.CompletionTriggerKind](LspHandler.CompletionTriggerKind.Invoked) == "1")
    }
    "CompletionTriggerKind.TriggerForIncompleteCompletions encodes to 3" in {
        assert(encode[LspHandler.CompletionTriggerKind](LspHandler.CompletionTriggerKind.TriggerForIncompleteCompletions) == "3")
    }

    // ---- SignatureHelpTriggerKind (INV-051) ----

    "SignatureHelpTriggerKind.Invoked encodes to 1" in {
        assert(encode[LspHandler.SignatureHelpTriggerKind](LspHandler.SignatureHelpTriggerKind.Invoked) == "1")
    }
    "SignatureHelpTriggerKind.ContentChange encodes to 3" in {
        assert(encode[LspHandler.SignatureHelpTriggerKind](LspHandler.SignatureHelpTriggerKind.ContentChange) == "3")
    }

    // ---- CodeActionTriggerKind (INV-051) ----

    "CodeActionTriggerKind.Invoked encodes to 1" in {
        assert(encode[LspHandler.CodeActionTriggerKind](LspHandler.CodeActionTriggerKind.Invoked) == "1")
    }
    "CodeActionTriggerKind.Automatic encodes to 2" in {
        assert(encode[LspHandler.CodeActionTriggerKind](LspHandler.CodeActionTriggerKind.Automatic) == "2")
    }

    // ---- InlayHintKind (INV-051) ----

    "InlayHintKind.Type encodes to 1" in {
        assert(encode[LspHandler.InlayHintKind](LspHandler.InlayHintKind.Type) == "1")
    }
    "InlayHintKind.Parameter encodes to 2" in {
        assert(encode[LspHandler.InlayHintKind](LspHandler.InlayHintKind.Parameter) == "2")
    }

    // ---- NotebookCellKind (INV-051) ----

    "NotebookCellKind.Markup encodes to 1" in {
        assert(encode[LspHandler.NotebookCellKind](LspHandler.NotebookCellKind.Markup) == "1")
    }
    "NotebookCellKind.Code encodes to 2" in {
        assert(encode[LspHandler.NotebookCellKind](LspHandler.NotebookCellKind.Code) == "2")
    }

    // ---- MessageType (INV-051) ----

    "MessageType.Error encodes to 1" in {
        assert(encode[LspHandler.MessageType](LspHandler.MessageType.Error) == "1")
    }
    "MessageType.Warning encodes to 2" in {
        assert(encode[LspHandler.MessageType](LspHandler.MessageType.Warning) == "2")
    }
    "MessageType.Info encodes to 3" in {
        assert(encode[LspHandler.MessageType](LspHandler.MessageType.Info) == "3")
    }
    "MessageType.Log encodes to 4" in {
        assert(encode[LspHandler.MessageType](LspHandler.MessageType.Log) == "4")
    }
    "MessageType.Debug encodes to 5" in {
        assert(encode[LspHandler.MessageType](LspHandler.MessageType.Debug) == "5")
    }
    "MessageType round-trips all 5 cases" in {
        val cases = LspHandler.MessageType.values.toList
        assert(cases.size == 5)
        assert(cases.forall(c => roundtrip(c) == c))
    }

    // ---- FileChangeType (INV-051) ----

    "FileChangeType.Created encodes to 1" in {
        assert(encode[LspHandler.FileChangeType](LspHandler.FileChangeType.Created) == "1")
    }
    "FileChangeType.Changed encodes to 2" in {
        assert(encode[LspHandler.FileChangeType](LspHandler.FileChangeType.Changed) == "2")
    }
    "FileChangeType.Deleted encodes to 3" in {
        assert(encode[LspHandler.FileChangeType](LspHandler.FileChangeType.Deleted) == "3")
    }

    // ---- String-keyed enum schemas (INV-052) ----

    "MarkupKind.PlainText encodes to \"plaintext\"" in {
        assert(encode[LspHandler.MarkupKind](LspHandler.MarkupKind.PlainText) == "\"plaintext\"")
    }
    "MarkupKind.Markdown encodes to \"markdown\"" in {
        assert(encode[LspHandler.MarkupKind](LspHandler.MarkupKind.Markdown) == "\"markdown\"")
    }
    "MarkupKind round-trips both cases" in {
        assert(roundtrip[LspHandler.MarkupKind](LspHandler.MarkupKind.PlainText) == LspHandler.MarkupKind.PlainText)
        assert(roundtrip[LspHandler.MarkupKind](LspHandler.MarkupKind.Markdown) == LspHandler.MarkupKind.Markdown)
    }
    "MarkupKind schema uses transform" in {
        assert(summon[Schema[LspHandler.MarkupKind]].segments.isEmpty)
    }

    "ResourceOperationKind.Create encodes to \"create\"" in {
        assert(encode[LspHandler.ResourceOperationKind](LspHandler.ResourceOperationKind.Create) == "\"create\"")
    }
    "ResourceOperationKind.Rename encodes to \"rename\"" in {
        assert(encode[LspHandler.ResourceOperationKind](LspHandler.ResourceOperationKind.Rename) == "\"rename\"")
    }
    "ResourceOperationKind.Delete encodes to \"delete\"" in {
        assert(encode[LspHandler.ResourceOperationKind](LspHandler.ResourceOperationKind.Delete) == "\"delete\"")
    }
    "ResourceOperationKind round-trips all 3 cases" in {
        val cases = List(
            LspHandler.ResourceOperationKind.Create,
            LspHandler.ResourceOperationKind.Rename,
            LspHandler.ResourceOperationKind.Delete
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }

    "MonikerKind.Import encodes to \"import\"" in {
        assert(encode[LspHandler.MonikerKind](LspHandler.MonikerKind.Import) == "\"import\"")
    }
    "MonikerKind.Export encodes to \"export\"" in {
        assert(encode[LspHandler.MonikerKind](LspHandler.MonikerKind.Export) == "\"export\"")
    }
    "MonikerKind.Local encodes to \"local\"" in {
        assert(encode[LspHandler.MonikerKind](LspHandler.MonikerKind.Local) == "\"local\"")
    }

    "UniquenessLevel.Document encodes to \"document\"" in {
        assert(encode[LspHandler.UniquenessLevel](LspHandler.UniquenessLevel.Document) == "\"document\"")
    }
    "UniquenessLevel.Global encodes to \"global\"" in {
        assert(encode[LspHandler.UniquenessLevel](LspHandler.UniquenessLevel.Global) == "\"global\"")
    }
    "UniquenessLevel round-trips all 5 cases" in {
        val cases = List(
            LspHandler.UniquenessLevel.Document,
            LspHandler.UniquenessLevel.Project,
            LspHandler.UniquenessLevel.Group,
            LspHandler.UniquenessLevel.Scheme,
            LspHandler.UniquenessLevel.Global
        )
        assert(cases.forall(c => roundtrip(c) == c))
    }

    "FileOperationPatternKind.File encodes to \"file\"" in {
        assert(encode[LspHandler.FileOperationPatternKind](LspHandler.FileOperationPatternKind.File) == "\"file\"")
    }
    "FileOperationPatternKind.Folder encodes to \"folder\"" in {
        assert(encode[LspHandler.FileOperationPatternKind](LspHandler.FileOperationPatternKind.Folder) == "\"folder\"")
    }
    "FileOperationPatternKind round-trips both cases" in {
        val cases = List(LspHandler.FileOperationPatternKind.File, LspHandler.FileOperationPatternKind.Folder)
        assert(cases.forall(c => roundtrip(c) == c))
    }
    "FileOperationPatternKind schema uses transform" in {
        assert(summon[Schema[LspHandler.FileOperationPatternKind]].segments.isEmpty)
    }

end LspWireEnumSchemaTest
