package kyo.internal

import java.net.URI
import java.util.Optional
import kyo.*
import org.eclipse.lsp4j as lsp4j
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

/** The lsp4j/mtags to neutral-ADT adapter and the pc params builders.
  *
  * Strictly internal: lsp4j and scala.meta.pc types appear only here, never in a
  * Request/Response/Envelope case or any public signature. The same adapter runs in the same-JVM
  * backend and in the worker host, so both produce identical neutral results for one buffer.
  */
private[kyo] object Wire:

    /** Builds the VirtualFileParams for the compile (diagnostics) op.
      *
      * `shouldReturnDiagnostics()` is overridden to `true`: the interface default is `false`, under
      * which `didChange` returns an empty list even for a buffer with errors, so the diagnostics op
      * must opt in to receive them.
      *
      * The URI is normalized to an absolute `file:` form: the pc ignores relative URIs (returns empty
      * results without error), so a bare filename must be made absolute before passing it.
      */
    def compileParams(uri: Compiler.Uri, text: String): VirtualFileParams =
        val absoluteUri = toAbsoluteUri(uri.asString)
        val textStr     = text
        new VirtualFileParams:
            def uri(): URI                                  = absoluteUri
            def text(): String                              = textStr
            def token(): scala.meta.pc.CancelToken          = NoopCancelToken
            override def shouldReturnDiagnostics(): Boolean = true
        end new
    end compileParams

    /** Builds the OffsetParams for the position-based ops (completions/hover/signatureHelp/symbol). */
    def offsetParams(uri: Compiler.Uri, text: String, offset: Int): OffsetParams =
        val absoluteUri = toAbsoluteUri(uri.asString)
        val textStr     = text
        val offsetInt   = offset
        new OffsetParams:
            def uri(): URI                         = absoluteUri
            def text(): String                     = textStr
            def offset(): Int                      = offsetInt
            def token(): scala.meta.pc.CancelToken = NoopCancelToken
        end new
    end offsetParams

    /** Converts a URI string to an absolute `file:` URI if it is not already absolute.
      *
      * The pc silently returns empty results for relative URIs. A bare filename like `"Main.scala"`
      * becomes `file:///Main.scala`; a string already carrying a scheme (e.g. `file:` or `untitled:`)
      * is parsed as-is.
      */
    private[kyo] def toAbsoluteUri(uriStr: String): URI =
        val raw = new URI(uriStr)
        if raw.isAbsolute then raw
        else new URI("file:///" + uriStr)
    end toAbsoluteUri

    /** Adapts the pc's diagnostics result. `text` is the compiled buffer, used to convert each
      * line/character range into a UTF-16 offset span.
      */
    def toDiagnostics(text: String, value: java.util.List[lsp4j.Diagnostic]): Chunk[Compiler.Diagnostic] =
        val index = new LineIndex(text)
        Chunk.from(value.asScala.map(d => toDiagnostic(index, d)).toSeq)

    /** Adapts the pc's completion result. */
    def toCompletions(value: lsp4j.CompletionList): Chunk[Compiler.Completion] =
        Chunk.from(value.getItems.asScala.map(toCompletion).toSeq)

    /** Adapts the pc's hover result against the queried buffer (Optional.empty -> Absent). */
    def toHover(text: String, value: Optional[scala.meta.pc.HoverSignature]): Maybe[Compiler.Hover] =
        value.toScala match
            case None    => Absent
            case Some(h) => Present(Compiler.Hover(hoverMarkdown(h), hoverSpan(new LineIndex(text), h)))

    /** Adapts the pc's signature-help result (no signatures -> Absent). */
    def toSignature(value: lsp4j.SignatureHelp): Maybe[Compiler.Signature] =
        Maybe(value).flatMap(v => Maybe(v.getSignatures)).flatMap(s => Maybe.fromOption(s.asScala.headOption)).map(toSignatureInfo)

    /** Adapts the pc's definition result for the queried buffer (no definition -> Absent).
      *
      * `result.symbol` is the fully-qualified symbol name and `result.locations` the def sites. The
      * neutral [[Compiler.SymbolInfo]] carries the simple name, the fully-qualified name (the seam a
      * caller resolves cross-file), the mapped kind, and the in-buffer definition span when a
      * location is in `uri`; otherwise `localDefinition` is Absent.
      */
    def toSymbol(uri: Compiler.Uri, text: String, result: scala.meta.pc.DefinitionResult): Maybe[Compiler.SymbolInfo] =
        val fullName = result.symbol
        if fullName == null || fullName.isEmpty then Absent
        else
            val index       = new LineIndex(text)
            val absoluteStr = toAbsoluteUri(uri.asString).toString
            val localSpan =
                result.locations.asScala
                    .find(loc => loc.getUri == absoluteStr)
                    .map(loc => uri -> rangeToSpan(index, loc.getRange))
            Present(
                Compiler.SymbolInfo(
                    name = simpleName(fullName),
                    fullName = fullName,
                    kind = symbolKind(fullName),
                    localDefinition = Maybe.fromOption(localSpan)
                )
            )
        end if
    end toSymbol

    private def toDiagnostic(index: LineIndex, d: lsp4j.Diagnostic): Compiler.Diagnostic =
        Compiler.Diagnostic(
            span = rangeToSpan(index, d.getRange),
            severity = severity(d.getSeverity),
            message = eitherText(d.getMessage),
            code = diagnosticCode(d.getCode)
        )

    private def toCompletion(i: lsp4j.CompletionItem): Compiler.Completion =
        Compiler.Completion(
            label = i.getLabel,
            kind = completionKind(i.getKind),
            detail = Maybe(i.getDetail),
            insertText = Maybe(i.getInsertText),
            documentation = Maybe(i.getDocumentation).map(eitherText)
        )

    private def toSignatureInfo(s: lsp4j.SignatureInformation): Compiler.Signature =
        Compiler.Signature(
            label = s.getLabel,
            params = Chunk.from(s.getParameters.asScala.map(p => Compiler.Signature.Param(paramLabel(p), Absent)).toSeq),
            activeParam = Maybe(s.getActiveParameter).map(_.intValue)
        )

    /** Reads the String side of an lsp4j `Either<String, MarkupContent>` (the form the pc emits for a
      * message or documentation), falling back to the MarkupContent value on the rare Right side.
      */
    private def eitherText(e: lsp4j.jsonrpc.messages.Either[String, lsp4j.MarkupContent]): String =
        if e == null then ""
        else if e.isLeft then e.getLeft
        else if e.isRight then e.getRight.getValue
        else ""

    /** Reads the parameter label, taking the String side of the lsp4j `Either<String, Tuple>` label and
      * rendering the (start, end) offset pair on the Tuple side.
      */
    private def paramLabel(p: lsp4j.ParameterInformation): String =
        val label = p.getLabel
        if label == null then ""
        else if label.isLeft then label.getLeft
        else if label.isRight then s"${label.getRight.getFirst}:${label.getRight.getSecond}"
        else ""
        end if
    end paramLabel

    /** Reads the diagnostic code as the String side of the lsp4j `Either<String, Integer>`, rendering
      * the numeric side as its decimal text when the pc emits an integer code.
      */
    private def diagnosticCode(code: lsp4j.jsonrpc.messages.Either[String, Integer]): Maybe[String] =
        if code == null then Absent
        else if code.isLeft then Maybe(code.getLeft)
        else if code.isRight then Maybe(code.getRight).map(_.toString)
        else Absent

    private def severity(s: lsp4j.DiagnosticSeverity): Compiler.Severity =
        if s == null then Compiler.Severity.Error
        else if s.eq(lsp4j.DiagnosticSeverity.Error) then Compiler.Severity.Error
        else if s.eq(lsp4j.DiagnosticSeverity.Warning) then Compiler.Severity.Warning
        else if s.eq(lsp4j.DiagnosticSeverity.Information) then Compiler.Severity.Info
        else if s.eq(lsp4j.DiagnosticSeverity.Hint) then Compiler.Severity.Hint
        else Compiler.Severity.Error

    private def completionKind(k: lsp4j.CompletionItemKind): Compiler.Completion.Kind =
        import lsp4j.CompletionItemKind as L
        import Compiler.Completion.Kind as K
        if k == null then K.Value
        else if k.eq(L.Method) || k.eq(L.Function) || k.eq(L.Constructor) then K.Method
        else if k.eq(L.Field) || k.eq(L.Property) then K.Field
        else if k.eq(L.Class) || k.eq(L.Struct) then K.Class
        else if k.eq(L.Interface) then K.Trait
        else if k.eq(L.Module) then K.Object
        else if k.eq(L.TypeParameter) || k.eq(L.Enum) then K.Type
        else if k.eq(L.Keyword) then K.Keyword
        // The remaining value-like lsp4j kinds (Variable, Value, Constant, EnumMember, Unit, Text,
        // Snippet, Color, File, Reference, Folder, Event, Operator) have no narrower neutral kind, so
        // mapping them to Value is the deliberate, total fallback rather than a silent catch-all.
        else K.Value
        end if
    end completionKind

    private def rangeToSpan(index: LineIndex, r: lsp4j.Range): Compiler.Span =
        if r == null then Compiler.Span(0, 0)
        else Compiler.Span(index.offsetOf(r.getStart), index.offsetOf(r.getEnd))

    /** Renders the hover contents to markdown, total over both sides of the lsp4j
      * `Either<List<Either<String, MarkedString>>, MarkupContent>`: the MarkupContent side yields its
      * value, the MarkedString-list side renders each element (a plain string, or a value fenced by its
      * language) joined by blank lines, and a null or empty contents yields the empty string.
      */
    private def hoverMarkdown(h: scala.meta.pc.HoverSignature): String =
        val contents = h.toLsp.getContents
        if contents == null then ""
        else if contents.isRight then markupValue(contents.getRight)
        else if contents.isLeft then contents.getLeft.asScala.map(markedString).filter(_.nonEmpty).mkString("\n\n")
        else ""
        end if
    end hoverMarkdown

    /** Reads a MarkupContent value, the empty string when the content or its value is null. */
    private def markupValue(m: lsp4j.MarkupContent): String =
        if m == null || m.getValue == null then "" else m.getValue

    /** Renders one hover content element: the plain String on the Left side, or the MarkedString value
      * fenced by its language (when present) on the Right side.
      */
    private def markedString(e: lsp4j.jsonrpc.messages.Either[String, lsp4j.MarkedString]): String =
        if e == null then ""
        else if e.isLeft then Maybe(e.getLeft).getOrElse("")
        else if e.isRight then
            val ms       = e.getRight
            val value    = if ms == null || ms.getValue == null then "" else ms.getValue
            val language = if ms == null then null else ms.getLanguage
            if language == null || language.isEmpty then value
            else s"```$language\n$value\n```"
        else ""

    private def hoverSpan(index: LineIndex, h: scala.meta.pc.HoverSignature): Maybe[Compiler.Span] =
        Maybe(h.toLsp.getRange).map(r => rangeToSpan(index, r))

    /** The simple (last-segment) name of a fully-qualified SemanticDB symbol such as
      * `scala/collection/Seq#`.
      */
    private def simpleName(fullName: String): String =
        val trimmed = fullName.stripSuffix("#").stripSuffix(".").stripSuffix("()")
        trimmed.substring(math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('.')) + 1)

    /** Maps the SemanticDB-style symbol suffix to the neutral kind: `()` is a method, `#` a type,
      * `.` a term, an unsuffixed package path a package.
      */
    private def symbolKind(fullName: String): Compiler.SymbolInfo.Kind =
        if fullName.endsWith("()") || fullName.endsWith("().") then Compiler.SymbolInfo.Kind.Method
        else if fullName.endsWith("#") then Compiler.SymbolInfo.Kind.Class
        else if fullName.endsWith(".") then Compiler.SymbolInfo.Kind.Val
        else Compiler.SymbolInfo.Kind.Package

    /** A never-cancelling token for params whose cancellation is driven by cancelling the underlying
      * future rather than through the token lever.
      */
    private object NoopCancelToken extends scala.meta.pc.CancelToken:
        def checkCanceled(): Unit = ()
        def onCancel(): java.util.concurrent.CompletionStage[java.lang.Boolean] =
            java.util.concurrent.CompletableFuture.completedFuture(java.lang.Boolean.FALSE)
    end NoopCancelToken

    /** Maps an lsp4j line/character position to a UTF-16 code-unit offset into the buffer.
      *
      * Precomputes the starting offset of each line once, so each position lookup is a single array
      * read plus the character column. A position past the last line clamps to the buffer length, and a
      * null position maps to offset 0.
      */
    final private class LineIndex(text: String):
        private val lineStarts: IArray[Int] =
            IArray.from(Iterator.single(0) ++ text.indices.iterator.filter(i => text.charAt(i) == '\n').map(_ + 1))

        def offsetOf(p: lsp4j.Position): Int =
            if p == null then 0
            else
                val line = math.min(math.max(p.getLine, 0), lineStarts.length - 1)
                math.min(lineStarts(line) + math.max(p.getCharacter, 0), text.length)
    end LineIndex
end Wire
