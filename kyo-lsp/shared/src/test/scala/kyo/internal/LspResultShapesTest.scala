package kyo.internal

import kyo.*

/** Round-trip tests for every sealed result type.
  *
  * Each test encodes a representative value to JSON and decodes it back, asserting
  * structural equality. Uses fromStructureValue path (Json.decode) which is the primary
  * decode path for sealed unions.
  */
class LspResultShapesTest extends Test:

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow
    private def roundtrip[A: Schema](value: A): A   = decode[A](encode[A](value))

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    val pos0   = LspHandler.Position(0, 0)
    val range0 = LspHandler.Range(pos0, pos0)
    val loc0   = LspHandler.Location(LspHandler.LspDocument.Uri.fromWire("file:///test.scala"), range0)
    val link0 = LspHandler.LocationLink(
        originSelectionRange = Absent,
        targetUri = LspHandler.LspDocument.Uri.fromWire("file:///test.scala"),
        targetRange = range0,
        targetSelectionRange = range0
    )

    // ---- TextDocumentContentChangeEvent ----

    "TextDocumentContentChangeEvent.Full round-trips" in {
        val v: LspHandler.TextDocumentContentChangeEvent = LspHandler.TextDocumentContentChangeEvent.Full("hello world")
        assert(roundtrip(v) == v)
    }

    "TextDocumentContentChangeEvent.Incremental round-trips" in {
        val v: LspHandler.TextDocumentContentChangeEvent =
            LspHandler.TextDocumentContentChangeEvent.Incremental(range0, "updated text")
        assert(roundtrip(v) == v)
    }

    "TextDocumentContentChangeEvent.Incremental encodes with range field" in {
        val v    = LspHandler.TextDocumentContentChangeEvent.Incremental(range0, "x")
        val json = encode(v)
        assert(json.contains("range"))
        assert(json.contains("text"))
    }

    "TextDocumentContentChangeEvent.Full encodes without range field" in {
        val v    = LspHandler.TextDocumentContentChangeEvent.Full("x")
        val json = encode(v)
        assert(!json.contains("range"))
        assert(json.contains("text"))
    }

    // ---- ProgressToken ----

    "ProgressToken.IntToken round-trips" in {
        val v: LspHandler.ProgressToken = LspHandler.ProgressToken.IntToken(42)
        assert(roundtrip(v) == v)
    }

    "ProgressToken.StringToken round-trips" in {
        val v: LspHandler.ProgressToken = LspHandler.ProgressToken.StringToken("mytoken")
        assert(roundtrip(v) == v)
    }

    "ProgressToken.IntToken encodes as integer" in {
        val v: LspHandler.ProgressToken = LspHandler.ProgressToken.IntToken(7)
        assert(encode(v) == "7")
    }

    "ProgressToken.StringToken encodes as string" in {
        val v: LspHandler.ProgressToken = LspHandler.ProgressToken.StringToken("abc")
        assert(encode(v) == "\"abc\"")
    }

    // ---- WorkDoneProgressValue ----

    "WorkDoneProgressValue.Begin round-trips with all fields" in {
        val v: LspHandler.WorkDoneProgressValue = LspHandler.WorkDoneProgressValue.Begin(
            title = "Processing",
            cancellable = Present(true),
            message = Present("0%"),
            percentage = Present(0)
        )
        assert(roundtrip(v) == v)
    }

    "WorkDoneProgressValue.Report round-trips" in {
        val v: LspHandler.WorkDoneProgressValue = LspHandler.WorkDoneProgressValue.Report(
            message = Present("50%"),
            percentage = Present(50)
        )
        assert(roundtrip(v) == v)
    }

    "WorkDoneProgressValue.End round-trips" in {
        val v: LspHandler.WorkDoneProgressValue = LspHandler.WorkDoneProgressValue.End(Present("Done"))
        assert(roundtrip(v) == v)
    }

    "WorkDoneProgressValue.Begin encodes with kind=begin" in {
        val v: LspHandler.WorkDoneProgressValue = LspHandler.WorkDoneProgressValue.Begin("title")
        val json                                = encode(v)
        assert(json.contains("\"kind\":\"begin\""))
    }

    // ---- MarkedString ----

    "MarkedString.Plain round-trips" in {
        val v: LspHandler.MarkedString = LspHandler.MarkedString.Plain("hello")
        assert(roundtrip(v) == v)
    }

    "MarkedString.Code round-trips" in {
        val v: LspHandler.MarkedString = LspHandler.MarkedString.Code("scala", "val x = 1")
        assert(roundtrip(v) == v)
    }

    "MarkedString.Plain encodes as JSON string" in {
        val v: LspHandler.MarkedString = LspHandler.MarkedString.Plain("hello")
        assert(encode(v) == "\"hello\"")
    }

    // ---- HoverContents ----

    "HoverContents.Markup round-trips" in {
        val markup                      = LspHandler.MarkupContent(LspHandler.MarkupKind.Markdown, "**bold**")
        val v: LspHandler.HoverContents = LspHandler.HoverContents.Markup(markup)
        assert(roundtrip(v) == v)
    }

    "HoverContents.Strings round-trips with single plain string" in {
        val v: LspHandler.HoverContents = LspHandler.HoverContents.Strings(
            Chunk(LspHandler.MarkedString.Plain("info"))
        )
        assert(roundtrip(v) == v)
    }

    "HoverContents.Markup encodes as object with kind field" in {
        val markup                      = LspHandler.MarkupContent(LspHandler.MarkupKind.PlainText, "info")
        val v: LspHandler.HoverContents = LspHandler.HoverContents.Markup(markup)
        val json                        = encode(v)
        assert(json.contains("kind"))
    }

    // ---- DocumentDiagnosticReport ----

    "DocumentDiagnosticReport.Full round-trips" in {
        val diag = LspHandler.Diagnostic(
            range = range0,
            severity = Present(LspHandler.DiagnosticSeverity.Error),
            message = "error message"
        )
        val v: LspHandler.DocumentDiagnosticReport = LspHandler.DocumentDiagnosticReport.Full(
            items = Chunk(diag)
        )
        assert(roundtrip(v) == v)
    }

    "DocumentDiagnosticReport.Unchanged round-trips" in {
        val v: LspHandler.DocumentDiagnosticReport = LspHandler.DocumentDiagnosticReport.Unchanged(
            resultId = "result-1"
        )
        assert(roundtrip(v) == v)
    }

    "DocumentDiagnosticReport.Full encodes with kind=full" in {
        val v: LspHandler.DocumentDiagnosticReport = LspHandler.DocumentDiagnosticReport.Full(items = Chunk.empty)
        val json                                   = encode(v)
        assert(json.contains("\"kind\":\"full\""))
    }

    // ---- WorkspaceDocumentDiagnosticReport ----

    "WorkspaceDocumentDiagnosticReport.Full round-trips" in {
        val uri = LspHandler.LspDocument.Uri.fromWire("file:///test.scala")
        val v: LspHandler.WorkspaceDocumentDiagnosticReport = LspHandler.WorkspaceDocumentDiagnosticReport.Full(
            uri = uri,
            items = Chunk.empty
        )
        assert(roundtrip(v) == v)
    }

    "WorkspaceDocumentDiagnosticReport.Unchanged round-trips" in {
        val uri = LspHandler.LspDocument.Uri.fromWire("file:///test.scala")
        val v: LspHandler.WorkspaceDocumentDiagnosticReport = LspHandler.WorkspaceDocumentDiagnosticReport.Unchanged(
            uri = uri,
            resultId = "r1"
        )
        assert(roundtrip(v) == v)
    }

    // ---- DocumentSymbolResult ----

    "DocumentSymbolResult.Symbols round-trips empty" in {
        val v: LspHandler.DocumentSymbolResult = LspHandler.DocumentSymbolResult.Symbols(Chunk.empty)
        assert(roundtrip(v) == v)
    }

    "DocumentSymbolResult.Symbols round-trips with one entry" in {
        val ds = LspHandler.DocumentSymbol(
            name = "myClass",
            kind = LspHandler.SymbolKind.Class,
            range = range0,
            selectionRange = range0
        )
        val v: LspHandler.DocumentSymbolResult = LspHandler.DocumentSymbolResult.Symbols(Chunk(ds))
        assert(roundtrip(v) == v)
    }

    "DocumentSymbolResult.Information round-trips" in {
        val si = LspHandler.SymbolInformation(
            name = "myVar",
            kind = LspHandler.SymbolKind.Variable,
            location = loc0
        )
        val v: LspHandler.DocumentSymbolResult = LspHandler.DocumentSymbolResult.Information(Chunk(si))
        assert(roundtrip(v) == v)
    }

    // ---- WorkspaceSymbolLocation ----

    "WorkspaceSymbolLocation.WithRange round-trips" in {
        val uri                                   = LspHandler.LspDocument.Uri.fromWire("file:///test.scala")
        val v: LspHandler.WorkspaceSymbolLocation = LspHandler.WorkspaceSymbolLocation.WithRange(uri, range0)
        assert(roundtrip(v) == v)
    }

    "WorkspaceSymbolLocation.UriOnly round-trips" in {
        val uri                                   = LspHandler.LspDocument.Uri.fromWire("file:///test.scala")
        val v: LspHandler.WorkspaceSymbolLocation = LspHandler.WorkspaceSymbolLocation.UriOnly(uri)
        assert(roundtrip(v) == v)
    }

    // ---- CompletionResult ----

    "CompletionResult.Items round-trips empty" in {
        val v: LspHandler.CompletionResult = LspHandler.CompletionResult.Items(Chunk.empty)
        assert(roundtrip(v) == v)
    }

    "CompletionResult.Items round-trips with one item" in {
        val item                           = LspHandler.CompletionItem(label = "myMethod")
        val v: LspHandler.CompletionResult = LspHandler.CompletionResult.Items(Chunk(item))
        assert(roundtrip(v) == v)
    }

    "CompletionResult.List round-trips" in {
        val list = LspHandler.CompletionList(
            isIncomplete = false,
            items = Chunk(LspHandler.CompletionItem(label = "foo"))
        )
        val v: LspHandler.CompletionResult = LspHandler.CompletionResult.List(list)
        assert(roundtrip(v) == v)
    }

    "CompletionResult.Items encodes as JSON array" in {
        val v: LspHandler.CompletionResult = LspHandler.CompletionResult.Items(Chunk.empty)
        val json                           = encode(v)
        assert(json == "[]")
    }

    // ---- ParameterLabel ----

    "ParameterLabel.StringLabel round-trips" in {
        val v: LspHandler.ParameterLabel = LspHandler.ParameterLabel.StringLabel("param")
        assert(roundtrip(v) == v)
    }

    "ParameterLabel.RangeLabel round-trips" in {
        val v: LspHandler.ParameterLabel = LspHandler.ParameterLabel.RangeLabel(0, 5)
        assert(roundtrip(v) == v)
    }

    "ParameterLabel.StringLabel encodes as string" in {
        val v: LspHandler.ParameterLabel = LspHandler.ParameterLabel.StringLabel("p")
        assert(encode(v) == "\"p\"")
    }

    "ParameterLabel.RangeLabel encodes as array" in {
        val v: LspHandler.ParameterLabel = LspHandler.ParameterLabel.RangeLabel(2, 7)
        assert(encode(v) == "[2,7]")
    }

    // ---- CommandOrCodeAction ----

    "CommandOrCodeAction.Cmd round-trips" in {
        val cmd                               = LspHandler.Command(title = "Run", command = "run.action")
        val v: LspHandler.CommandOrCodeAction = LspHandler.CommandOrCodeAction.Cmd(cmd)
        assert(roundtrip(v) == v)
    }

    "CommandOrCodeAction.Action round-trips" in {
        val action = LspHandler.CodeAction(
            title = "Fix",
            isPreferred = Present(true)
        )
        val v: LspHandler.CommandOrCodeAction = LspHandler.CommandOrCodeAction.Action(action)
        assert(roundtrip(v) == v)
    }

    // ---- WorkspaceEditDocumentChange ----

    "WorkspaceEditDocumentChange.Create round-trips" in {
        val create = LspHandler.CreateFile(uri = LspHandler.LspDocument.Uri.fromWire("file:///new.scala"))
        val v: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Create(create)
        assert(roundtrip(v) == v)
    }

    "WorkspaceEditDocumentChange.Delete round-trips" in {
        val delete = LspHandler.DeleteFile(uri = LspHandler.LspDocument.Uri.fromWire("file:///old.scala"))
        val v: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Delete(delete)
        assert(roundtrip(v) == v)
    }

    "WorkspaceEditDocumentChange.Create encodes with kind=create" in {
        val create = LspHandler.CreateFile(uri = LspHandler.LspDocument.Uri.fromWire("file:///new.scala"))
        val v: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Create(create)
        val json                                      = encode(v)
        assert(json.contains("\"kind\":\"create\""))
    }

    // ---- PrepareRenameResult ----

    "PrepareRenameResult.JustRange round-trips" in {
        val v: LspHandler.PrepareRenameResult = LspHandler.PrepareRenameResult.JustRange(range0)
        assert(roundtrip(v) == v)
    }

    "PrepareRenameResult.RangeWithPlaceholder round-trips" in {
        val v: LspHandler.PrepareRenameResult = LspHandler.PrepareRenameResult.RangeWithPlaceholder(range0, "oldName")
        assert(roundtrip(v) == v)
    }

    "PrepareRenameResult.DefaultBehavior round-trips" in {
        val v: LspHandler.PrepareRenameResult = LspHandler.PrepareRenameResult.DefaultBehavior(true)
        assert(roundtrip(v) == v)
    }

    // ---- DefinitionResult ----

    "DefinitionResult.One round-trips" in {
        val v: LspHandler.DefinitionResult = LspHandler.DefinitionResult.One(loc0)
        assert(roundtrip(v) == v)
    }

    "DefinitionResult.Many round-trips empty" in {
        val v: LspHandler.DefinitionResult = LspHandler.DefinitionResult.Many(Chunk.empty)
        assert(roundtrip(v) == v)
    }

    "DefinitionResult.Many round-trips with items" in {
        val v: LspHandler.DefinitionResult = LspHandler.DefinitionResult.Many(Chunk(loc0))
        assert(roundtrip(v) == v)
    }

    "DefinitionResult.Links round-trips" in {
        val v: LspHandler.DefinitionResult = LspHandler.DefinitionResult.Links(Chunk(link0))
        assert(roundtrip(v) == v)
    }

    // ---- DeclarationResult ----

    "DeclarationResult.One round-trips" in {
        val v: LspHandler.DeclarationResult = LspHandler.DeclarationResult.One(loc0)
        assert(roundtrip(v) == v)
    }

    "DeclarationResult.Many round-trips" in {
        val v: LspHandler.DeclarationResult = LspHandler.DeclarationResult.Many(Chunk(loc0))
        assert(roundtrip(v) == v)
    }

    // ---- TypeDefinitionResult ----

    "TypeDefinitionResult.One round-trips" in {
        val v: LspHandler.TypeDefinitionResult = LspHandler.TypeDefinitionResult.One(loc0)
        assert(roundtrip(v) == v)
    }

    "TypeDefinitionResult.Links round-trips" in {
        val v: LspHandler.TypeDefinitionResult = LspHandler.TypeDefinitionResult.Links(Chunk(link0))
        assert(roundtrip(v) == v)
    }

    // ---- ImplementationResult ----

    "ImplementationResult.One round-trips" in {
        val v: LspHandler.ImplementationResult = LspHandler.ImplementationResult.One(loc0)
        assert(roundtrip(v) == v)
    }

    "ImplementationResult.Many round-trips" in {
        val v: LspHandler.ImplementationResult = LspHandler.ImplementationResult.Many(Chunk(loc0, loc0))
        assert(roundtrip(v) == v)
    }

    // ---- SemanticTokensResult ----

    "SemanticTokensResult.Full round-trips" in {
        val tokens                             = LspHandler.SemanticTokens(data = Chunk(1, 2, 3, 4, 5))
        val v: LspHandler.SemanticTokensResult = LspHandler.SemanticTokensResult.Full(tokens)
        assert(roundtrip(v) == v)
    }

    "SemanticTokensResult.Delta round-trips" in {
        val edit                               = LspHandler.SemanticTokensEdit(start = 0, deleteCount = 2, data = Chunk(1, 2))
        val delta                              = LspHandler.SemanticTokensDelta(edits = Chunk(edit))
        val v: LspHandler.SemanticTokensResult = LspHandler.SemanticTokensResult.Delta(delta)
        assert(roundtrip(v) == v)
    }

    "SemanticTokensResult.Full encodes with data field" in {
        val tokens                             = LspHandler.SemanticTokens(data = Chunk(1))
        val v: LspHandler.SemanticTokensResult = LspHandler.SemanticTokensResult.Full(tokens)
        val json                               = encode(v)
        assert(json.contains("data"))
        assert(!json.contains("edits"))
    }

    "SemanticTokensResult.Delta encodes with edits field" in {
        val delta                              = LspHandler.SemanticTokensDelta(edits = Chunk.empty)
        val v: LspHandler.SemanticTokensResult = LspHandler.SemanticTokensResult.Delta(delta)
        val json                               = encode(v)
        assert(json.contains("edits"))
    }

    // ---- InlayHintLabelPart ----

    "InlayHintLabelPart.StringPart round-trips" in {
        val v: LspHandler.InlayHintLabelPart = LspHandler.InlayHintLabelPart.StringPart("label")
        assert(roundtrip(v) == v)
    }

    "InlayHintLabelPart.StructuredPart round-trips" in {
        val v: LspHandler.InlayHintLabelPart = LspHandler.InlayHintLabelPart.StructuredPart(value = "x")
        assert(roundtrip(v) == v)
    }

    "InlayHintLabelPart.StringPart encodes as JSON string" in {
        val v: LspHandler.InlayHintLabelPart = LspHandler.InlayHintLabelPart.StringPart("hi")
        assert(encode(v) == "\"hi\"")
    }

    // ---- InlayHintLabel ----

    "InlayHintLabel.PlainString round-trips" in {
        val v: LspHandler.InlayHintLabel = LspHandler.InlayHintLabel.PlainString("label")
        assert(roundtrip(v) == v)
    }

    "InlayHintLabel.Parts round-trips" in {
        val v: LspHandler.InlayHintLabel = LspHandler.InlayHintLabel.Parts(
            Chunk(LspHandler.InlayHintLabelPart.StringPart("a"))
        )
        assert(roundtrip(v) == v)
    }

    "InlayHintLabel.PlainString encodes as JSON string" in {
        val v: LspHandler.InlayHintLabel = LspHandler.InlayHintLabel.PlainString("x")
        assert(encode(v) == "\"x\"")
    }

    // ---- InlineValue ----

    "InlineValue.Text round-trips" in {
        val v: LspHandler.InlineValue = LspHandler.InlineValue.Text(range0, "hello")
        assert(roundtrip(v) == v)
    }

    "InlineValue.VariableLookup round-trips" in {
        val v: LspHandler.InlineValue = LspHandler.InlineValue.VariableLookup(
            range0,
            Present("myVar"),
            caseSensitiveLookup = true
        )
        assert(roundtrip(v) == v)
    }

    "InlineValue.EvaluatableExpression round-trips" in {
        val v: LspHandler.InlineValue = LspHandler.InlineValue.EvaluatableExpression(range0, Present("x + 1"))
        assert(roundtrip(v) == v)
    }

    // ---- NotebookDocumentFilter ----

    "NotebookDocumentFilter.WithNotebookType round-trips" in {
        val v: LspHandler.NotebookDocumentFilter = LspHandler.NotebookDocumentFilter.WithNotebookType("jupyter")
        assert(roundtrip(v) == v)
    }

    "NotebookDocumentFilter.WithScheme round-trips" in {
        val v: LspHandler.NotebookDocumentFilter = LspHandler.NotebookDocumentFilter.WithScheme(scheme = "file")
        assert(roundtrip(v) == v)
    }

    "NotebookDocumentFilter.WithPattern round-trips" in {
        val v: LspHandler.NotebookDocumentFilter = LspHandler.NotebookDocumentFilter.WithPattern(pattern = "**/*.ipynb")
        assert(roundtrip(v) == v)
    }

    // ---- Registration ----

    "Registration without options round-trips" in {
        val v       = LspHandler.Registration("reg-1", "textDocument/completion")
        val encoded = encode(v)
        val back    = decode[LspHandler.Registration](encoded)
        assert(back.id == "reg-1")
        assert(back.method == "textDocument/completion")
        assert(back._rawRegisterOptions.isEmpty)
    }

    "Registration with options round-trips id and method" in {
        val v       = LspHandler.Registration("reg-2", "textDocument/hover", LspHandler.HoverOptions())
        val encoded = encode(v)
        val back    = decode[LspHandler.Registration](encoded)
        assert(back.id == "reg-2")
        assert(back.method == "textDocument/hover")
    }

end LspResultShapesTest
