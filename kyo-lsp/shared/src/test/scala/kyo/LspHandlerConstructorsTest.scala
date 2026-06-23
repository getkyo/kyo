package kyo

import kyo.LspHandler.*

/** Tests for the value-type smart constructors on `LspHandler.*` companions.
  *
  * Each assertion pins the factory's output to the equivalent hand-built case-class tree, so a
  * wiring mistake (wrong markup kind, wrong severity, wrong nesting) is caught.
  */
class LspHandlerConstructorsTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    val pos   = Position(1, 2)
    val pos2  = Position(3, 4)
    val range = Range(pos, pos2)
    val uriA  = LspDocument.Uri.parse("file:///a.scala").get
    val uriB  = LspDocument.Uri.parse("file:///b.scala").get

    // -------------------------------------------------------------------------
    // Core coordinates
    // -------------------------------------------------------------------------

    "Range.of builds from coordinates" in {
        assert(Range.of(1, 2, 3, 4) == Range(Position(1, 2), Position(3, 4)))
    }

    "Range.at collapses to a zero-width range" in {
        assert(Range.at(pos) == Range(pos, pos))
    }

    // -------------------------------------------------------------------------
    // Markup / hover
    // -------------------------------------------------------------------------

    "MarkupContent.plainText / markdown set the kind" in {
        assert(MarkupContent.plainText("x") == MarkupContent(MarkupKind.PlainText, "x"))
        assert(MarkupContent.markdown("**x**") == MarkupContent(MarkupKind.Markdown, "**x**"))
    }

    "Hover.plainText nests markup and defaults the range" in {
        assert(Hover.plainText("docs") == Hover(HoverContents.Markup(MarkupContent(MarkupKind.PlainText, "docs")), Absent))
    }

    "Hover.markdown carries an explicit range" in {
        assert(Hover.markdown("**d**", Present(range)) ==
            Hover(HoverContents.Markup(MarkupContent(MarkupKind.Markdown, "**d**")), Present(range)))
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    "Diagnostic severity presets" in {
        assert(Diagnostic.error(range, "e").severity == Present(DiagnosticSeverity.Error))
        assert(Diagnostic.warning(range, "w").severity == Present(DiagnosticSeverity.Warning))
        assert(Diagnostic.info(range, "i").severity == Present(DiagnosticSeverity.Information))
        assert(Diagnostic.hint(range, "h").severity == Present(DiagnosticSeverity.Hint))
        assert(Diagnostic.error(range, "e").message == "e")
        assert(Diagnostic.error(range, "e").range == range)
    }

    // -------------------------------------------------------------------------
    // Completion
    // -------------------------------------------------------------------------

    "CompletionItem.of carries label and optional kind" in {
        assert(CompletionItem.of("foldLeft") == CompletionItem(label = "foldLeft"))
        assert(CompletionItem.of("foldLeft", CompletionItemKind.Method).kind == Present(CompletionItemKind.Method))
    }

    "CompletionResult.of / items" in {
        assert(CompletionResult.of("a", "b") ==
            CompletionResult.Items(Chunk(CompletionItem(label = "a"), CompletionItem(label = "b"))))
        assert(CompletionResult.items(CompletionItem.of("a")) == CompletionResult.Items(Chunk(CompletionItem(label = "a"))))
    }

    // -------------------------------------------------------------------------
    // Location result unions
    // -------------------------------------------------------------------------

    "DefinitionResult.at / many / links" in {
        assert(DefinitionResult.at(uriA, range) == DefinitionResult.One(Location(uriA, range)))
        assert(DefinitionResult.many(Location(uriA, range)) ==
            DefinitionResult.Many(Chunk(Location(uriA, range))))
        val link = LocationLink(targetUri = uriA, targetRange = range, targetSelectionRange = range)
        assert(DefinitionResult.links(link) == DefinitionResult.Links(Chunk(link)))
    }

    "ImplementationResult.at mirrors the shared shape" in {
        assert(ImplementationResult.at(uriB, range) == ImplementationResult.One(Location(uriB, range)))
    }

    // -------------------------------------------------------------------------
    // Symbols, signatures, diagnostic reports
    // -------------------------------------------------------------------------

    "DocumentSymbol.of places the four required fields" in {
        val sym = DocumentSymbol.of("main", SymbolKind.Function, range, range)
        assert(sym == DocumentSymbol(name = "main", kind = SymbolKind.Function, range = range, selectionRange = range))
    }

    "DocumentSymbolResult.symbols" in {
        val sym = DocumentSymbol.of("main", SymbolKind.Function, range, range)
        assert(DocumentSymbolResult.symbols(sym) == DocumentSymbolResult.Symbols(Chunk(sym)))
    }

    "SignatureHelp.of / SignatureInformation.of / ParameterInformation.of" in {
        val param = ParameterInformation.of("x: Int")
        assert(param == ParameterInformation(ParameterLabel.StringLabel("x: Int")))
        val sig = SignatureInformation.of("def f(x: Int)", param)
        assert(sig == SignatureInformation(label = "def f(x: Int)", parameters = Chunk(param)))
        assert(SignatureHelp.of(sig) == SignatureHelp(signatures = Chunk(sig)))
    }

    "DocumentDiagnosticReport.full / unchanged" in {
        val diags = Chunk(Diagnostic.error(range, "e"))
        assert(DocumentDiagnosticReport.full(diags) == DocumentDiagnosticReport.Full(items = diags))
        assert(DocumentDiagnosticReport.unchanged("r1") == DocumentDiagnosticReport.Unchanged(resultId = "r1"))
    }

    // -------------------------------------------------------------------------
    // Code actions, edits, tokens, marked strings
    // -------------------------------------------------------------------------

    "CommandOrCodeAction.action / command" in {
        val action = CodeAction.of("Fix it", CodeActionKind.QuickFix)
        assert(CommandOrCodeAction.action(action) == CommandOrCodeAction.Action(action))
        val cmd = Command(title = "Run", command = "run")
        assert(CommandOrCodeAction.command(cmd) == CommandOrCodeAction.Cmd(cmd))
    }

    "CodeAction.quickFix presets the kind and carries the edit" in {
        val edit = WorkspaceEdit.changes(LspDocument.Uri.parse("file:///a.scala").get -> Chunk.empty)
        val ca   = CodeAction.quickFix("Apply", edit)
        assert(ca.kind == Present(CodeActionKind.QuickFix))
        assert(ca.edit == Present(edit))
    }

    "WorkspaceEdit.changes keys by URI string" in {
        val uri  = LspDocument.Uri.parse("file:///a.scala").get
        val edit = TextEdit(range, "x")
        assert(WorkspaceEdit.changes(uri -> Chunk(edit)).changes == Present(Map(uri.asString -> Chunk(edit))))
    }

    "SemanticTokensResult.full wraps raw data" in {
        assert(SemanticTokensResult.full(Chunk(0, 0, 1, 2, 3)) ==
            SemanticTokensResult.Full(SemanticTokens(data = Chunk(0, 0, 1, 2, 3))))
    }

    "MarkedString.plain / code" in {
        assert(MarkedString.plain("hi") == MarkedString.Plain("hi"))
        assert(MarkedString.code("scala", "val x = 1") == MarkedString.Code("scala", "val x = 1"))
    }

    // -------------------------------------------------------------------------
    // Folding ranges, semantic-token deltas, inlay hints
    // -------------------------------------------------------------------------

    "FoldingRange.of spans whole lines" in {
        assert(FoldingRange.of(3, 7) == FoldingRange(startLine = 3, endLine = 7))
    }

    "SemanticTokensResult.delta wraps raw edits" in {
        val edit = SemanticTokensEdit(start = 0, deleteCount = 2)
        assert(SemanticTokensResult.delta(Chunk(edit)) ==
            SemanticTokensResult.Delta(SemanticTokensDelta(edits = Chunk(edit))))
    }

    "InlayHint.of wraps a plain-string label" in {
        assert(InlayHint.of(pos, ": Int") == InlayHint(position = pos, label = InlayHintLabel.PlainString(": Int")))
    }

    // -------------------------------------------------------------------------
    // Capability toggles
    // -------------------------------------------------------------------------

    "BooleanOr.on / options" in {
        assert(BooleanOr.on == BooleanOr.Bool(true))
        assert(BooleanOr.options("x") == BooleanOr.Options("x"))
        // `on` is `BooleanOr[Nothing]`, so it slots into any typed capability field.
        val hover: Maybe[BooleanOr[HoverOptions]] = Present(BooleanOr.on)
        assert(hover == Present(BooleanOr.Bool(true)))
    }

end LspHandlerConstructorsTest
