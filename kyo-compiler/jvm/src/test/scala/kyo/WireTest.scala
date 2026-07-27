package kyo

import java.util.Arrays as JArrays
import java.util.Optional
import kyo.internal.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as LspEither
import org.eclipse.lsp4j as lsp4j
import scala.jdk.CollectionConverters.*

class WireTest extends kyo.test.Test[Any]:

    "every Request case round-trips through the AsMessage codec" in {
        val uri = Compiler.Uri("Main.scala")

        def roundTrip[T: Schema](value: T): T =
            MsgPack.decode[T](MsgPack.encode(value)).getOrThrow

        val cases: List[Request] = List(
            Request.Compile(uri, "object Main"),
            Request.Completions(uri, "object Main { val x = 1 }", 20),
            Request.Hover(uri, "object Main", 5),
            Request.SignatureHelp(uri, "def f(x: Int) = x", 10),
            Request.Symbol(uri, "class Foo", 6),
            Request.DidClose(uri)
        )
        cases.foreach(req => assert(roundTrip[Request](req) == req))
    }

    "every Response and Envelope case round-trips through the AsMessage codec" in {
        val uri = Compiler.Uri("Main.scala")

        def roundTrip[T: Schema](value: T): T =
            MsgPack.decode[T](MsgPack.encode(value)).getOrThrow

        val responses: List[Response] = List(
            Response.Diagnostics(Chunk(
                Compiler.Diagnostic(Compiler.Span(0, 5), Compiler.Severity.Error, "type mismatch", Present("E001"))
            )),
            Response.Completions(Chunk(
                Compiler.Completion("map", Compiler.Completion.Kind.Method, Present("def map"), Absent, Absent)
            )),
            Response.Hover(Present(Compiler.Hover("**Int**", Present(Compiler.Span(0, 3))))),
            Response.Hover(Absent),
            Response.Signature(Present(Compiler.Signature(
                "def f(x: Int): Unit",
                Chunk(Compiler.Signature.Param("x: Int", Absent)),
                Present(0)
            ))),
            Response.Symbol(Absent),
            Response.Closed,
            Response.Failed(CompilerWorkerReadyException("3.8.4", 30.seconds))
        )
        responses.foreach(resp => assert(roundTrip[Response](resp) == resp))

        val reqEnvelope = Envelope.Req(7, Request.Compile(uri, "object Main"))
        val decodedReq  = roundTrip[Envelope](reqEnvelope)
        assert(decodedReq == reqEnvelope)
        decodedReq match
            case Envelope.Req(id, _) => assert(id == 7)
            case _                   => assert(false)

        val respEnvelope = Envelope.Resp(7, Response.Closed)
        val decodedResp  = roundTrip[Envelope](respEnvelope)
        assert(decodedResp == respEnvelope)
        decodedResp match
            case Envelope.Resp(id, _) => assert(id == 7)
            case _                    => assert(false)
    }

    "a corrupt envelope decode fails, never a silent success" in {
        // MsgPack.decode returns a Result rather than throwing, so a corrupt or wrong-typed payload
        // surfaces as a non-Success rather than a contained throw.
        val truncatedBytes: Array[Byte] = Array[Byte](0, 1, 2)
        val truncatedResult             = MsgPack.decode[Envelope](Span.from(truncatedBytes))
        assert(!truncatedResult.isSuccess, s"truncated bytes must not decode as an Envelope; got $truncatedResult")

        val uri          = Compiler.Uri("x")
        val requestBytes = MsgPack.encode[Request](Request.Compile(uri, "")).toArray
        val unknownTag   = MsgPack.decode[Envelope](Span.from(requestBytes))
        assert(!unknownTag.isSuccess, s"a Request payload must not decode as an Envelope; got $unknownTag")
    }

    "LineIndex maps line/character positions to UTF-16 offsets, including CRLF and tab" in {
        // Text layout (UTF-16 offsets):
        //   offset: 0  1  2  3  4  5  6  7  8  9
        //   char:   a  b \r \n  c  d \n \t  e  f
        //   line 0 starts at 0  (ends at \n index 3, inclusive)
        //   line 1 starts at 4  (ends at \n index 6, inclusive)
        //   line 2 starts at 7  (no trailing newline, ends at EOF=10)
        //
        // The \r\n pair occupies 2 UTF-16 code units; the next line starts after the \n.
        val text = "ab\r\ncd\n\tef"

        def span(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Compiler.Span =
            val range = new lsp4j.Range(
                new lsp4j.Position(startLine, startChar),
                new lsp4j.Position(endLine, endChar)
            )
            val d = new lsp4j.Diagnostic()
            d.setRange(range)
            d.setMessage("x")
            val result = Wire.toDiagnostics(text, JArrays.asList(d))
            result.head.span
        end span

        // Line 0, char 0 -> offset 0; line 0, char 1 -> offset 1
        assert(span(0, 0, 0, 1) == Compiler.Span(0, 1))
        // \r is at offset 2 on line 0; \r\n ends line 0
        assert(span(0, 2, 0, 4) == Compiler.Span(2, 4))
        // Line 1 starts at offset 4 (after the \n at offset 3); (1,0)-(1,2) -> (4,6)
        assert(span(1, 0, 1, 2) == Compiler.Span(4, 6))
        // Empty position: start == end is legal; (1,1)-(1,1) -> (5,5)
        assert(span(1, 1, 1, 1) == Compiler.Span(5, 5))
        // Line 2 starts at offset 7; \t is at (2,0)=7, 'e' at (2,1)=8, 'f' at (2,2)=9
        assert(span(2, 0, 2, 1) == Compiler.Span(7, 8))
        assert(span(2, 1, 2, 2) == Compiler.Span(8, 9))
        // EOF clamp: character past end of line 2 clamps to text.length (10)
        assert(span(2, 99, 2, 99) == Compiler.Span(10, 10))
        // Out-of-range line clamps to last line
        assert(span(99, 0, 99, 1) == Compiler.Span(7, 8))

        // Empty-line text: "x\n\ny" has lines [0]=>"x\n", [1]=>"\n", [2]=>"y"
        //   lineStarts = [0, 2, 3]; empty line 1 has only the newline at offset 2
        val emptyLineText = "x\n\ny"
        def spanEmpty(sl: Int, sc: Int, el: Int, ec: Int): Compiler.Span =
            val range = new lsp4j.Range(new lsp4j.Position(sl, sc), new lsp4j.Position(el, ec))
            val d2    = new lsp4j.Diagnostic()
            d2.setRange(range)
            d2.setMessage("x")
            Wire.toDiagnostics(emptyLineText, JArrays.asList(d2)).head.span
        end spanEmpty

        // (0,0)-(0,1) -> (0,1), (1,0)-(1,0) -> (2,2) [empty line], (2,0)-(2,1) -> (3,4)
        assert(spanEmpty(0, 0, 0, 1) == Compiler.Span(0, 1))
        assert(spanEmpty(1, 0, 1, 0) == Compiler.Span(2, 2))
        assert(spanEmpty(2, 0, 2, 1) == Compiler.Span(3, 4))
    }

    "adapter maps null/missing lsp4j fields to Absent and full fields to expected neutral values" in {
        // Diagnostic with null code and null severity: code -> Absent, severity -> Error (default)
        val textA        = "val x: Int = true"
        val rangeA       = new lsp4j.Range(new lsp4j.Position(0, 4), new lsp4j.Position(0, 5))
        val diagNullCode = new lsp4j.Diagnostic()
        diagNullCode.setRange(rangeA)
        diagNullCode.setMessage("type mismatch")
        // code and severity left null (default constructor)
        val nullCodeResult = Wire.toDiagnostics(textA, JArrays.asList(diagNullCode))
        assert(nullCodeResult.size == 1)
        val nullCodeDiag = nullCodeResult.head
        assert(nullCodeDiag.code == Absent)
        assert(nullCodeDiag.severity == Compiler.Severity.Error)
        assert(nullCodeDiag.message == "type mismatch")
        assert(nullCodeDiag.span == Compiler.Span(4, 5))

        // Diagnostic with all fields populated: concrete equality on the full neutral value
        val rangeB   = new lsp4j.Range(new lsp4j.Position(0, 0), new lsp4j.Position(0, 3))
        val diagFull = new lsp4j.Diagnostic(rangeB, "found: Boolean", lsp4j.DiagnosticSeverity.Warning, "scalac")
        diagFull.setCode("E007")
        val fullDiagResult = Wire.toDiagnostics("val", JArrays.asList(diagFull))
        assert(fullDiagResult.size == 1)
        assert(
            fullDiagResult.head == Compiler.Diagnostic(
                span = Compiler.Span(0, 3),
                severity = Compiler.Severity.Warning,
                message = "found: Boolean",
                code = Present("E007")
            )
        )

        // CompletionItem with null detail and null documentation: both -> Absent, no throw
        val itemNullOptionals = new lsp4j.CompletionItem("map")
        itemNullOptionals.setKind(lsp4j.CompletionItemKind.Method)
        // detail, insertText, documentation all null by default
        val itemList         = new lsp4j.CompletionList(JArrays.asList(itemNullOptionals))
        val completionResult = Wire.toCompletions(itemList)
        assert(completionResult.size == 1)
        val completion = completionResult.head
        assert(completion.label == "map")
        assert(completion.kind == Compiler.Completion.Kind.Method)
        assert(completion.detail == Absent)
        assert(completion.insertText == Absent)
        assert(completion.documentation == Absent)

        // CompletionItem with all optional fields set: concrete equality
        val itemFull = new lsp4j.CompletionItem("filter")
        itemFull.setKind(lsp4j.CompletionItemKind.Method)
        itemFull.setDetail("def filter(p: A => Boolean): List[A]")
        itemFull.setInsertText("filter(${1:p})")
        itemFull.setDocumentation("Selects all elements satisfying a predicate.")
        val fullItemResult = Wire.toCompletions(new lsp4j.CompletionList(JArrays.asList(itemFull)))
        assert(fullItemResult.size == 1)
        assert(
            fullItemResult.head == Compiler.Completion(
                label = "filter",
                kind = Compiler.Completion.Kind.Method,
                detail = Present("def filter(p: A => Boolean): List[A]"),
                insertText = Present("filter(${1:p})"),
                documentation = Present("Selects all elements satisfying a predicate.")
            )
        )
    }

    "toHover adapter: Left-side contents (MarkedString list), Right-side MarkupContent, and empty Optional" in {
        // A Left-side hover carries a list of String/MarkedString entries; the renderer reads that list,
        // never the absent Right (MarkupContent) side. This stub returns one plain String plus one
        // MarkedString with a language tag.
        val plainElem  = LspEither.forLeft[String, lsp4j.MarkedString]("**Int**")
        val ms         = new lsp4j.MarkedString("scala", "val x: Int")
        val markedElem = LspEither.forRight[String, lsp4j.MarkedString](ms)
        val leftContents = LspEither.forLeft[java.util.List[LspEither[String, lsp4j.MarkedString]], lsp4j.MarkupContent](JArrays.asList(
            plainElem,
            markedElem
        ))
        val hoverWithLeft = new lsp4j.Hover(leftContents.getLeft)
        val leftStub = new scala.meta.pc.HoverSignature:
            def toLsp(): lsp4j.Hover                                    = hoverWithLeft
            def signature(): Optional[String]                           = Optional.empty()
            def getRange(): Optional[lsp4j.Range]                       = Optional.empty()
            def withRange(r: lsp4j.Range): scala.meta.pc.HoverSignature = this

        val leftResult = Wire.toHover("val x: Int", Optional.of(leftStub))
        leftResult match
            case Present(h) =>
                // plain String renders as-is; MarkedString with language renders fenced
                assert(h.markdown == "**Int**\n\n```scala\nval x: Int\n```")
                assert(h.span == Absent)
            case Absent => assert(false)
        end match

        // Right-side MarkupContent: getContents returns the MarkupContent side directly
        val mc             = new lsp4j.MarkupContent("markdown", "**String**")
        val hoverWithRight = new lsp4j.Hover(mc)
        val rightStub = new scala.meta.pc.HoverSignature:
            def toLsp(): lsp4j.Hover                                    = hoverWithRight
            def signature(): Optional[String]                           = Optional.empty()
            def getRange(): Optional[lsp4j.Range]                       = Optional.empty()
            def withRange(r: lsp4j.Range): scala.meta.pc.HoverSignature = this

        val rightResult = Wire.toHover("", Optional.of(rightStub))
        rightResult match
            case Present(h) =>
                assert(h.markdown == "**String**")
                assert(h.span == Absent)
            case Absent => assert(false)
        end match

        // Optional.empty -> Absent, no stub needed
        val emptyResult = Wire.toHover("", Optional.empty[scala.meta.pc.HoverSignature]())
        assert(emptyResult == Absent)
    }

    "toSignature adapter: null/empty signatures yields Absent; populated list yields expected Signature" in {
        // null SignatureHelp itself -> Absent
        assert(Wire.toSignature(null) == Absent)

        // empty signatures list -> Absent (no head element to adapt)
        val emptyHelp = new lsp4j.SignatureHelp(JArrays.asList(), null, null)
        assert(Wire.toSignature(emptyHelp) == Absent)

        // null signatures list via a foreign DefinitionResult-like stub that bypasses the bean validator:
        // a SignatureHelp whose signatures list is null must yield Absent, not a thrown exception
        val stubHelp = new lsp4j.SignatureHelp():
            override def getSignatures(): java.util.List[lsp4j.SignatureInformation] = null
        assert(Wire.toSignature(stubHelp) == Absent)

        // populated SignatureHelp: one SignatureInformation with one ParameterInformation
        val param           = new lsp4j.ParameterInformation("x: Int")
        val sig             = new lsp4j.SignatureInformation("def f(x: Int): Unit", null: String, JArrays.asList(param))
        val sigHelp         = new lsp4j.SignatureHelp(JArrays.asList(sig), null, null)
        val populatedResult = Wire.toSignature(sigHelp)
        populatedResult match
            case Present(s) =>
                assert(s.label == "def f(x: Int): Unit")
                assert(s.params.size == 1)
                assert(s.params.head.label == "x: Int")
                assert(s.params.head.documentation == Absent)
                assert(s.activeParam == Absent)
            case Absent => assert(false)
        end match
    }

    "toSymbol adapter: null/empty symbol yields Absent; populated with in-buffer location yields expected SymbolInfo" in {
        // A minimal DefinitionResult stub
        def defResult(sym: String, locs: java.util.List[lsp4j.Location]): scala.meta.pc.DefinitionResult =
            new scala.meta.pc.DefinitionResult:
                def symbol(): String                            = sym
                def locations(): java.util.List[lsp4j.Location] = locs

        val uri = Compiler.Uri("Main.scala")

        // null symbol -> Absent
        assert(Wire.toSymbol(uri, "", defResult(null, JArrays.asList())) == Absent)

        // empty symbol -> Absent
        assert(Wire.toSymbol(uri, "", defResult("", JArrays.asList())) == Absent)

        // symbol only, foreign-uri location -> localDefinition is Absent
        val foreignLoc    = new lsp4j.Location("Other.scala", new lsp4j.Range(new lsp4j.Position(0, 0), new lsp4j.Position(0, 3)))
        val foreignResult = Wire.toSymbol(uri, "class Foo", defResult("mypackage/Foo#", JArrays.asList(foreignLoc)))
        foreignResult match
            case Present(s) =>
                assert(s.name == "Foo")
                assert(s.fullName == "mypackage/Foo#")
                assert(s.kind == Compiler.SymbolInfo.Kind.Class)
                assert(s.localDefinition == Absent)
            case Absent => assert(false)
        end match

        // in-buffer location -> localDefinition carries (uri -> span)
        // text: "class Foo" (9 chars on line 0); location range (0,6)-(0,9) -> span (6,9)
        // The pc returns absolute file URIs in locations (relative URIs are silently ignored by the pc).
        val text        = "class Foo"
        val absUriStr   = Wire.toAbsoluteUri(uri.asString).toString
        val inBufLoc    = new lsp4j.Location(absUriStr, new lsp4j.Range(new lsp4j.Position(0, 6), new lsp4j.Position(0, 9)))
        val inBufResult = Wire.toSymbol(uri, text, defResult("mypackage/Foo#", JArrays.asList(inBufLoc)))
        inBufResult match
            case Present(s) =>
                assert(s.name == "Foo")
                assert(s.fullName == "mypackage/Foo#")
                assert(s.kind == Compiler.SymbolInfo.Kind.Class)
                s.localDefinition match
                    case Present((defUri, span)) =>
                        assert(defUri.asString == uri.asString)
                        assert(span == Compiler.Span(6, 9))
                    case Absent => assert(false)
                end match
            case Absent => assert(false)
        end match
    }

    "lsp4j completion kinds and diagnostic severities map onto the neutral kinds totally" in {
        def completionKind(k: lsp4j.CompletionItemKind): Compiler.Completion.Kind =
            val item = new lsp4j.CompletionItem("x")
            item.setKind(k)
            Wire.toCompletions(new lsp4j.CompletionList(JArrays.asList(item))).head.kind
        end completionKind

        // lsp4j's broader kinds collapse onto the narrower neutral kinds: Constructor/Method -> Method,
        // Property/Field -> Field, Struct -> Class, Enum -> Type.
        assert(completionKind(lsp4j.CompletionItemKind.Constructor) == Compiler.Completion.Kind.Method)
        assert(completionKind(lsp4j.CompletionItemKind.Method) == Compiler.Completion.Kind.Method)
        assert(completionKind(lsp4j.CompletionItemKind.Property) == Compiler.Completion.Kind.Field)
        assert(completionKind(lsp4j.CompletionItemKind.Field) == Compiler.Completion.Kind.Field)
        assert(completionKind(lsp4j.CompletionItemKind.Struct) == Compiler.Completion.Kind.Class)
        assert(completionKind(lsp4j.CompletionItemKind.Enum) == Compiler.Completion.Kind.Type)

        def severity(s: lsp4j.DiagnosticSeverity): Compiler.Severity =
            val d = new lsp4j.Diagnostic()
            d.setRange(new lsp4j.Range(new lsp4j.Position(0, 0), new lsp4j.Position(0, 1)))
            d.setMessage("x")
            d.setSeverity(s)
            Wire.toDiagnostics("x", JArrays.asList(d)).head.severity
        end severity

        // All four lsp4j severities map onto the four neutral severities.
        assert(severity(lsp4j.DiagnosticSeverity.Error) == Compiler.Severity.Error)
        assert(severity(lsp4j.DiagnosticSeverity.Warning) == Compiler.Severity.Warning)
        assert(severity(lsp4j.DiagnosticSeverity.Information) == Compiler.Severity.Info)
        assert(severity(lsp4j.DiagnosticSeverity.Hint) == Compiler.Severity.Hint)
    }

end WireTest
