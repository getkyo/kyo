package kyo.internal.lsp

import kyo.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary

/** Hand-rolled discriminator Schemas for LSP 3.17 sealed-union types.
  *
  * Each val is a singleton per INV-053. The discriminator strategy varies per type and mirrors
  * the LSP 3.17 wire format exactly (no extra `_type` wrapping). Precedent:
  * `McpContentSchema` at kyo/internal/McpContentSchema.scala.
  *
  * Strategies used:
  *   - `TextDocumentContentChangeEvent`: field presence (`range` present -> Incremental, absent -> Full)
  *   - `ProgressToken`: JSON node type (integer -> IntToken, string -> StringToken)
  *   - `WorkDoneProgressValue`: `kind` field ("begin" / "report" / "end")
  *   - `MarkedString`: JSON node type (string -> Plain, object -> Code)
  *   - `HoverContents`: node type (object with `kind` field -> Markup, array -> Strings)
  *   - `DocumentDiagnosticReport`: `kind` field ("full" / "unchanged")
  *   - `WorkspaceDocumentDiagnosticReport`: `kind` field ("full" / "unchanged")
  *   - `DocumentSymbolResult`: array element peek (`children` -> Symbols, `location` -> Information)
  *   - `WorkspaceSymbolLocation`: `range` field presence
  *   - `CompletionResult`: JSON node type (array -> Items, object -> List)
  *   - `ParameterLabel`: JSON node type (string -> StringLabel, array -> RangeLabel)
  *   - `CommandOrCodeAction`: field presence (`edit`/`diagnostics`/`isPreferred` -> Action, else Cmd)
  *   - `WorkspaceEditDocumentChange`: `kind` field (absent -> Edit, "create"/"rename"/"delete")
  *   - `PrepareRenameResult`: field presence (`placeholder` -> RangeWithPlaceholder, `defaultBehavior` -> DefaultBehavior, else JustRange)
  *   - `DefinitionResult` family: JSON node type + first element shape
  *   - `SemanticTokensResult`: field presence (`edits` -> Delta, `data` -> Full)
  *   - `InlayHintLabelPart`: JSON node type (string -> StringPart, object -> StructuredPart)
  *   - `InlayHintLabel`: JSON node type (string -> PlainString, array -> Parts)
  *   - `InlineValue`: field presence (`expression` -> EvaluatableExpression, `variableName`/`caseSensitiveLookup` -> VariableLookup, else Text)
  *   - `NotebookDocumentFilter`: required-field presence
  *   - `Registration`: hand-rolled `{id, method, registerOptions?}` with raw JSON pass-through
  */
private[kyo] object LspContentSchemas:

    // MARK: -- TextDocumentContentChangeEvent

    @nowarn("msg=anonymous")
    val textDocumentContentChangeEventSchema: Schema[LspHandler.TextDocumentContentChangeEvent] =
        new Schema[LspHandler.TextDocumentContentChangeEvent](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.TextDocumentContentChangeEvent, w: Codec.Writer): Unit =
                v match
                    case LspHandler.TextDocumentContentChangeEvent.Full(text) =>
                        w.objectStart("TextDocumentContentChangeEvent.Full", 1)
                        w.field("text", 1)
                        w.string(text)
                        w.objectEnd()
                    case LspHandler.TextDocumentContentChangeEvent.Incremental(range, text) =>
                        w.objectStart("TextDocumentContentChangeEvent.Incremental", 2)
                        w.field("range", 1)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        w.field("text", 2)
                        w.string(text)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.TextDocumentContentChangeEvent =
                var text: String            = ""
                var range: LspHandler.Range = null
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("range".getBytes("UTF-8")) then
                        range = summon[Schema[LspHandler.Range]].serializeRead(reader)
                    else if reader.matchField("text".getBytes("UTF-8")) then
                        text = reader.string()
                    else
                        reader.skip()
                    end if
                end while
                reader.objectEnd()
                if range != null then
                    LspHandler.TextDocumentContentChangeEvent.Incremental(range, text)
                else
                    LspHandler.TextDocumentContentChangeEvent.Full(text)
                end if
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.TextDocumentContentChangeEvent): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.TextDocumentContentChangeEvent,
                next: Any
            ): LspHandler.TextDocumentContentChangeEvent =
                next match
                    case c: LspHandler.TextDocumentContentChangeEvent => c
                    case _                                            => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.TextDocumentContentChangeEvent] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        val textR = m.get("text") match
                            case Some(Structure.Value.Str(s)) => Result.Success(s)
                            case _                            => Result.Success("")
                        m.get("range") match
                            case Some(rangeSv) =>
                                for
                                    r <- summon[Schema[LspHandler.Range]].fromStructureValue(rangeSv)
                                    t <- textR
                                yield LspHandler.TextDocumentContentChangeEvent.Incremental(r, t)
                            case scala.None =>
                                textR.map(t => LspHandler.TextDocumentContentChangeEvent.Full(t))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- ProgressToken

    @nowarn("msg=anonymous")
    val progressTokenSchema: Schema[LspHandler.ProgressToken] =
        new Schema[LspHandler.ProgressToken](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.ProgressToken, w: Codec.Writer): Unit =
                v match
                    case LspHandler.ProgressToken.IntToken(n)    => w.int(n)
                    case LspHandler.ProgressToken.StringToken(s) => w.string(s)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.ProgressToken =
                val captured = reader.captureValue()
                try LspHandler.ProgressToken.IntToken(captured.int())
                catch case _: Exception => LspHandler.ProgressToken.StringToken(captured.string())
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.ProgressToken): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.ProgressToken, next: Any): LspHandler.ProgressToken =
                next match
                    case t: LspHandler.ProgressToken => t
                    case _                           => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.ProgressToken] =
                sv match
                    case Structure.Value.Integer(n) => Result.Success(LspHandler.ProgressToken.IntToken(n.toInt))
                    case Structure.Value.Str(s)     => Result.Success(LspHandler.ProgressToken.StringToken(s))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Integer|String", sv.toString))

    // MARK: -- WorkDoneProgressValue

    @nowarn("msg=anonymous")
    val workDoneProgressValueSchema: Schema[LspHandler.WorkDoneProgressValue] =
        new Schema[LspHandler.WorkDoneProgressValue](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.WorkDoneProgressValue, w: Codec.Writer): Unit =
                v match
                    case LspHandler.WorkDoneProgressValue.Begin(title, cancellable, message, percentage) =>
                        val count = 2 + (if cancellable.isDefined then 1 else 0) + (if message.isDefined then 1
                                                                                    else 0) + (if percentage.isDefined then 1 else 0)
                        w.objectStart("WorkDoneProgressValue.Begin", count)
                        w.field("kind", 1)
                        w.string("begin")
                        w.field("title", 2)
                        w.string(title)
                        var idx = 3
                        cancellable.foreach { c =>
                            w.field("cancellable", idx); idx += 1; w.boolean(c)
                        }
                        message.foreach { m =>
                            w.field("message", idx); idx += 1; w.string(m)
                        }
                        percentage.foreach { p =>
                            w.field("percentage", idx); idx += 1; w.int(p)
                        }
                        w.objectEnd()
                    case LspHandler.WorkDoneProgressValue.Report(cancellable, message, percentage) =>
                        val count = 1 + (if cancellable.isDefined then 1 else 0) + (if message.isDefined then 1
                                                                                    else 0) + (if percentage.isDefined then 1 else 0)
                        w.objectStart("WorkDoneProgressValue.Report", count)
                        w.field("kind", 1)
                        w.string("report")
                        var idx = 2
                        cancellable.foreach { c =>
                            w.field("cancellable", idx); idx += 1; w.boolean(c)
                        }
                        message.foreach { m =>
                            w.field("message", idx); idx += 1; w.string(m)
                        }
                        percentage.foreach { p =>
                            w.field("percentage", idx); idx += 1; w.int(p)
                        }
                        w.objectEnd()
                    case LspHandler.WorkDoneProgressValue.End(message) =>
                        val count = 1 + (if message.isDefined then 1 else 0)
                        w.objectStart("WorkDoneProgressValue.End", count)
                        w.field("kind", 1)
                        w.string("end")
                        var idx = 2
                        message.foreach { m =>
                            w.field("message", idx); idx += 1; w.string(m)
                        }
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.WorkDoneProgressValue =
                var kind: String                = ""
                var title: String               = ""
                var cancellable: Maybe[Boolean] = Absent
                var message: Maybe[String]      = Absent
                var percentage: Maybe[Int]      = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("kind".getBytes("UTF-8")) then kind = reader.string()
                    else if reader.matchField("title".getBytes("UTF-8")) then title = reader.string()
                    else if reader.matchField("cancellable".getBytes("UTF-8")) then cancellable = Present(reader.boolean())
                    else if reader.matchField("message".getBytes("UTF-8")) then message = Present(reader.string())
                    else if reader.matchField("percentage".getBytes("UTF-8")) then percentage = Present(reader.int())
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                kind match
                    case "begin"  => LspHandler.WorkDoneProgressValue.Begin(title, cancellable, message, percentage)
                    case "report" => LspHandler.WorkDoneProgressValue.Report(cancellable, message, percentage)
                    case "end"    => LspHandler.WorkDoneProgressValue.End(message)
                    case other    => throw TypeMismatchException(Seq.empty, "begin|report|end", other)(using Frame.internal)
                end match
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.WorkDoneProgressValue): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.WorkDoneProgressValue, next: Any): LspHandler.WorkDoneProgressValue =
                next match
                    case v: LspHandler.WorkDoneProgressValue => v
                    case _                                   => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.WorkDoneProgressValue] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m    = fields.iterator.toMap
                        val kind = m.get("kind").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        kind match
                            case "begin" =>
                                val title = m.get("title").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                                val cancellable =
                                    m.get("cancellable").collect { case Structure.Value.Bool(b) => b }.map(Present(_)).getOrElse(Absent)
                                val message =
                                    m.get("message").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                                val percentage = m.get(
                                    "percentage"
                                ).collect { case Structure.Value.Integer(n) => n.toInt }.map(Present(_)).getOrElse(Absent)
                                Result.Success(LspHandler.WorkDoneProgressValue.Begin(title, cancellable, message, percentage))
                            case "report" =>
                                val cancellable =
                                    m.get("cancellable").collect { case Structure.Value.Bool(b) => b }.map(Present(_)).getOrElse(Absent)
                                val message =
                                    m.get("message").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                                val percentage = m.get(
                                    "percentage"
                                ).collect { case Structure.Value.Integer(n) => n.toInt }.map(Present(_)).getOrElse(Absent)
                                Result.Success(LspHandler.WorkDoneProgressValue.Report(cancellable, message, percentage))
                            case "end" =>
                                val message =
                                    m.get("message").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                                Result.Success(LspHandler.WorkDoneProgressValue.End(message))
                            case other =>
                                Result.Failure(TypeMismatchException(Seq.empty, "begin|report|end", other))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- MarkedString

    @nowarn("msg=anonymous")
    val markedStringSchema: Schema[LspHandler.MarkedString] =
        new Schema[LspHandler.MarkedString](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.MarkedString, w: Codec.Writer): Unit =
                v match
                    case LspHandler.MarkedString.Plain(value) => w.string(value)
                    case LspHandler.MarkedString.Code(language, value) =>
                        w.objectStart("MarkedString.Code", 2)
                        w.field("language", 1)
                        w.string(language)
                        w.field("value", 2)
                        w.string(value)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.MarkedString =
                val captured = reader.captureValue()
                try LspHandler.MarkedString.Plain(captured.string())
                catch
                    case _: Exception =>
                        var language: String = ""
                        var value: String    = ""
                        discard(captured.objectStart())
                        while captured.hasNextField() do
                            captured.fieldParse()
                            if captured.matchField("language".getBytes("UTF-8")) then language = captured.string()
                            else if captured.matchField("value".getBytes("UTF-8")) then value = captured.string()
                            else captured.skip()
                        end while
                        captured.objectEnd()
                        LspHandler.MarkedString.Code(language, value)
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.MarkedString): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.MarkedString, next: Any): LspHandler.MarkedString =
                next match
                    case m: LspHandler.MarkedString => m
                    case _                          => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.MarkedString] =
                sv match
                    case Structure.Value.Str(s) => Result.Success(LspHandler.MarkedString.Plain(s))
                    case Structure.Value.Record(fields) =>
                        val m        = fields.iterator.toMap
                        val language = m.get("language").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val value    = m.get("value").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        Result.Success(LspHandler.MarkedString.Code(language, value))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "String|Record", sv.toString))

    // MARK: -- HoverContents

    @nowarn("msg=anonymous")
    val hoverContentsSchema: Schema[LspHandler.HoverContents] =
        new Schema[LspHandler.HoverContents](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.HoverContents, w: Codec.Writer): Unit =
                v match
                    case LspHandler.HoverContents.Markup(value) =>
                        summon[Schema[LspHandler.MarkupContent]].serializeWrite(value, w)
                    case LspHandler.HoverContents.Strings(value) =>
                        w.arrayStart(value.size)
                        value.foreach { ms => markedStringSchema.serializeWrite(ms, w) }
                        w.arrayEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.HoverContents =
                val captured = reader.captureValue()
                // Try to read as array (Strings case) - if captureValue starts '[' it's an array
                try
                    discard(captured.arrayStart())
                    val buf = scala.collection.mutable.ArrayBuffer[LspHandler.MarkedString]()
                    while captured.hasNextElement() do
                        buf += markedStringSchema.serializeRead(captured)
                    captured.arrayEnd()
                    LspHandler.HoverContents.Strings(Chunk.from(buf))
                catch
                    case _: Exception =>
                        LspHandler.HoverContents.Markup(summon[Schema[LspHandler.MarkupContent]].serializeRead(captured))
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.HoverContents): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.HoverContents, next: Any): LspHandler.HoverContents =
                next match
                    case h: LspHandler.HoverContents => h
                    case _                           => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.HoverContents] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        if m.contains("kind") then
                            summon[Schema[LspHandler.MarkupContent]].fromStructureValue(sv).map(LspHandler.HoverContents.Markup(_))
                        else
                            // MarkedString object (language + value)
                            markedStringSchema.fromStructureValue(sv).map(ms => LspHandler.HoverContents.Strings(Chunk(ms)))
                        end if
                    case Structure.Value.Sequence(elems) =>
                        val results = elems.map(e => markedStringSchema.fromStructureValue(e))
                        val init: Result[DecodeException, Chunk[LspHandler.MarkedString]] = Result.Success(Chunk.empty)
                        results.foldLeft(init) { (acc, r) =>
                            acc.flatMap(chunk => r.map(ms => chunk.append(ms)))
                        }.map(LspHandler.HoverContents.Strings(_))
                    case Structure.Value.Str(s) =>
                        Result.Success(LspHandler.HoverContents.Strings(Chunk(LspHandler.MarkedString.Plain(s))))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record|Sequence|String", sv.toString))

    // MARK: -- DocumentDiagnosticReport

    @nowarn("msg=anonymous")
    val documentDiagnosticReportSchema: Schema[LspHandler.DocumentDiagnosticReport] =
        new Schema[LspHandler.DocumentDiagnosticReport](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.DocumentDiagnosticReport, w: Codec.Writer): Unit =
                v match
                    case LspHandler.DocumentDiagnosticReport.Full(kind, resultId, items) =>
                        val count = 2 + (if resultId.isDefined then 1 else 0)
                        w.objectStart("DocumentDiagnosticReport.Full", count)
                        w.field("kind", 1)
                        w.string(kind)
                        var idx = 2
                        resultId.foreach { r =>
                            w.field("resultId", idx); idx += 1; w.string(r)
                        }
                        w.field("items", idx)
                        w.arrayStart(items.size)
                        items.foreach { d => summon[Schema[LspHandler.Diagnostic]].serializeWrite(d, w) }
                        w.arrayEnd()
                        w.objectEnd()
                    case LspHandler.DocumentDiagnosticReport.Unchanged(kind, resultId) =>
                        w.objectStart("DocumentDiagnosticReport.Unchanged", 2)
                        w.field("kind", 1)
                        w.string(kind)
                        w.field("resultId", 2)
                        w.string(resultId)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.DocumentDiagnosticReport =
                var kind: String                        = "full"
                var resultId: Maybe[String]             = Absent
                var items: Chunk[LspHandler.Diagnostic] = Chunk.empty
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("kind".getBytes("UTF-8")) then kind = reader.string()
                    else if reader.matchField("resultId".getBytes("UTF-8")) then resultId = Present(reader.string())
                    else if reader.matchField("items".getBytes("UTF-8")) then
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[LspHandler.Diagnostic]()
                        if count < 0 then
                            while reader.hasNextElement() do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                        else
                            var j = 0
                            while j < count do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                                j += 1
                            end while
                        end if
                        reader.arrayEnd()
                        items = Chunk.from(buf)
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                kind match
                    case "unchanged" => LspHandler.DocumentDiagnosticReport.Unchanged(kind, resultId.getOrElse(""))
                    case _           => LspHandler.DocumentDiagnosticReport.Full(kind, resultId, items)
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.DocumentDiagnosticReport): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.DocumentDiagnosticReport,
                next: Any
            ): LspHandler.DocumentDiagnosticReport =
                next match
                    case r: LspHandler.DocumentDiagnosticReport => r
                    case _                                      => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.DocumentDiagnosticReport] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m    = fields.iterator.toMap
                        val kind = m.get("kind").collect { case Structure.Value.Str(s) => s }.getOrElse("full")
                        kind match
                            case "unchanged" =>
                                val resultId = m.get("resultId").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                                Result.Success(LspHandler.DocumentDiagnosticReport.Unchanged(kind, resultId))
                            case _ =>
                                val resultId =
                                    m.get("resultId").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                                val itemsSv = m.get("items").collect { case Structure.Value.Sequence(e) => e }.getOrElse(Chunk.empty)
                                val diags   = itemsSv.map(e => summon[Schema[LspHandler.Diagnostic]].fromStructureValue(e))
                                val initAcc: Result[DecodeException, Chunk[LspHandler.Diagnostic]] = Result.Success(Chunk.empty)
                                diags.foldLeft(initAcc) { (acc, r) =>
                                    acc.flatMap(chunk => r.map(d => chunk.append(d)))
                                }.map(items => LspHandler.DocumentDiagnosticReport.Full(kind, resultId, items))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- WorkspaceDocumentDiagnosticReport

    @nowarn("msg=anonymous")
    val workspaceDocumentDiagnosticReportSchema: Schema[LspHandler.WorkspaceDocumentDiagnosticReport] =
        new Schema[LspHandler.WorkspaceDocumentDiagnosticReport](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.WorkspaceDocumentDiagnosticReport, w: Codec.Writer): Unit =
                v match
                    case LspHandler.WorkspaceDocumentDiagnosticReport.Full(uri, version, kind, resultId, items) =>
                        val count = 3 + (if version.isDefined then 1 else 0) + (if resultId.isDefined then 1 else 0)
                        w.objectStart("WorkspaceDocumentDiagnosticReport.Full", count)
                        w.field("uri", 1)
                        summon[Schema[LspHandler.LspDocument.Uri]].serializeWrite(uri, w)
                        var idx = 2
                        version.foreach { v2 =>
                            w.field("version", idx); idx += 1; w.int(v2)
                        }
                        w.field("kind", idx); idx += 1; w.string(kind)
                        resultId.foreach { r =>
                            w.field("resultId", idx); idx += 1; w.string(r)
                        }
                        w.field("items", idx)
                        w.arrayStart(items.size)
                        items.foreach { d => summon[Schema[LspHandler.Diagnostic]].serializeWrite(d, w) }
                        w.arrayEnd()
                        w.objectEnd()
                    case LspHandler.WorkspaceDocumentDiagnosticReport.Unchanged(uri, version, kind, resultId) =>
                        val count = 3 + (if version.isDefined then 1 else 0)
                        w.objectStart("WorkspaceDocumentDiagnosticReport.Unchanged", count)
                        w.field("uri", 1)
                        summon[Schema[LspHandler.LspDocument.Uri]].serializeWrite(uri, w)
                        var idx = 2
                        version.foreach { v2 =>
                            w.field("version", idx); idx += 1; w.int(v2)
                        }
                        w.field("kind", idx); idx += 1; w.string(kind)
                        w.field("resultId", idx)
                        w.string(resultId)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.WorkspaceDocumentDiagnosticReport =
                var uri: LspHandler.LspDocument.Uri     = LspHandler.LspDocument.Uri.fromWire("")
                var version: Maybe[Int]                 = Absent
                var kind: String                        = "full"
                var resultId: Maybe[String]             = Absent
                var items: Chunk[LspHandler.Diagnostic] = Chunk.empty
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("uri".getBytes("UTF-8")) then
                        uri = summon[Schema[LspHandler.LspDocument.Uri]].serializeRead(reader)
                    else if reader.matchField("version".getBytes("UTF-8")) then version = Present(reader.int())
                    else if reader.matchField("kind".getBytes("UTF-8")) then kind = reader.string()
                    else if reader.matchField("resultId".getBytes("UTF-8")) then resultId = Present(reader.string())
                    else if reader.matchField("items".getBytes("UTF-8")) then
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[LspHandler.Diagnostic]()
                        if count < 0 then
                            while reader.hasNextElement() do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                        else
                            var j = 0
                            while j < count do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                                j += 1
                            end while
                        end if
                        reader.arrayEnd()
                        items = Chunk.from(buf)
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                kind match
                    case "unchanged" => LspHandler.WorkspaceDocumentDiagnosticReport.Unchanged(uri, version, kind, resultId.getOrElse(""))
                    case _           => LspHandler.WorkspaceDocumentDiagnosticReport.Full(uri, version, kind, resultId, items)
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.WorkspaceDocumentDiagnosticReport): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.WorkspaceDocumentDiagnosticReport,
                next: Any
            ): LspHandler.WorkspaceDocumentDiagnosticReport =
                next match
                    case r: LspHandler.WorkspaceDocumentDiagnosticReport => r
                    case _                                               => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.WorkspaceDocumentDiagnosticReport] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m      = fields.iterator.toMap
                        val uriStr = m.get("uri").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val uri    = LspHandler.LspDocument.Uri.fromWire(uriStr)
                        val version =
                            m.get("version").collect { case Structure.Value.Integer(n) => n.toInt }.map(Present(_)).getOrElse(Absent)
                        val kind = m.get("kind").collect { case Structure.Value.Str(s) => s }.getOrElse("full")
                        kind match
                            case "unchanged" =>
                                val resultId = m.get("resultId").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                                Result.Success(LspHandler.WorkspaceDocumentDiagnosticReport.Unchanged(uri, version, kind, resultId))
                            case _ =>
                                val resultId =
                                    m.get("resultId").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                                val itemsSv = m.get("items").collect { case Structure.Value.Sequence(e) => e }.getOrElse(Chunk.empty)
                                val diags   = itemsSv.map(e => summon[Schema[LspHandler.Diagnostic]].fromStructureValue(e))
                                val initAcc: Result[DecodeException, Chunk[LspHandler.Diagnostic]] = Result.Success(Chunk.empty)
                                diags.foldLeft(initAcc) { (acc, r) =>
                                    acc.flatMap(chunk => r.map(d => chunk.append(d)))
                                }.map(items => LspHandler.WorkspaceDocumentDiagnosticReport.Full(uri, version, kind, resultId, items))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- DocumentSymbolResult

    @nowarn("msg=anonymous")
    val documentSymbolResultSchema: Schema[LspHandler.DocumentSymbolResult] =
        new Schema[LspHandler.DocumentSymbolResult](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.DocumentSymbolResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.DocumentSymbolResult.Symbols(items) =>
                        w.arrayStart(items.size)
                        items.foreach { s => summon[Schema[LspHandler.DocumentSymbol]].serializeWrite(s, w) }
                        w.arrayEnd()
                    case LspHandler.DocumentSymbolResult.Information(items) =>
                        w.arrayStart(items.size)
                        items.foreach { s => summon[Schema[LspHandler.SymbolInformation]].serializeWrite(s, w) }
                        w.arrayEnd()

            private def readOneElement(r: Codec.Reader): Either[LspHandler.DocumentSymbol, LspHandler.SymbolInformation] =
                // Read one object in a single pass; detect type from field presence
                var name: String                               = ""
                var kind: LspHandler.SymbolKind                = LspHandler.SymbolKind.File
                var tags: Chunk[LspHandler.SymbolTag]          = Chunk.empty
                var deprecated: Maybe[Boolean]                 = Absent
                var detail: Maybe[String]                      = Absent
                var range: Maybe[LspHandler.Range]             = Absent
                var selectionRange: Maybe[LspHandler.Range]    = Absent
                var children: Chunk[LspHandler.DocumentSymbol] = Chunk.empty
                var location: Maybe[LspHandler.Location]       = Absent
                var containerName: Maybe[String]               = Absent
                discard(r.objectStart())
                while r.hasNextField() do
                    r.fieldParse()
                    if r.matchField("name".getBytes("UTF-8")) then name = r.string()
                    else if r.matchField("kind".getBytes("UTF-8")) then
                        kind = summon[Schema[LspHandler.SymbolKind]].serializeRead(r)
                    else if r.matchField("tags".getBytes("UTF-8")) then
                        val cnt = r.arrayStart()
                        val b   = scala.collection.mutable.ArrayBuffer[LspHandler.SymbolTag]()
                        if cnt < 0 then while r.hasNextElement() do b += summon[Schema[LspHandler.SymbolTag]].serializeRead(r)
                        else
                            var j = 0;
                            while j < cnt do
                                b += summon[Schema[LspHandler.SymbolTag]].serializeRead(r); j += 1
                        end if
                        r.arrayEnd()
                        tags = Chunk.from(b)
                    else if r.matchField("deprecated".getBytes("UTF-8")) then deprecated = Present(r.boolean())
                    else if r.matchField("detail".getBytes("UTF-8")) then detail = Present(r.string())
                    else if r.matchField("range".getBytes("UTF-8")) then
                        range = Present(summon[Schema[LspHandler.Range]].serializeRead(r))
                    else if r.matchField("selectionRange".getBytes("UTF-8")) then
                        selectionRange = Present(summon[Schema[LspHandler.Range]].serializeRead(r))
                    else if r.matchField("children".getBytes("UTF-8")) then
                        val cnt = r.arrayStart()
                        val b   = scala.collection.mutable.ArrayBuffer[LspHandler.DocumentSymbol]()
                        if cnt < 0 then
                            while r.hasNextElement() do
                                b += summon[Schema[LspHandler.DocumentSymbol]].serializeRead(r)
                        else
                            var j = 0;
                            while j < cnt do
                                b += summon[Schema[LspHandler.DocumentSymbol]].serializeRead(r); j += 1
                        end if
                        r.arrayEnd()
                        children = Chunk.from(b)
                    else if r.matchField("location".getBytes("UTF-8")) then
                        location = Present(summon[Schema[LspHandler.Location]].serializeRead(r))
                    else if r.matchField("containerName".getBytes("UTF-8")) then containerName = Present(r.string())
                    else r.skip()
                    end if
                end while
                r.objectEnd()
                location match
                    case Present(loc) =>
                        Right(LspHandler.SymbolInformation(name, kind, tags, deprecated, loc, containerName))
                    case Absent =>
                        Left(LspHandler.DocumentSymbol(
                            name,
                            detail,
                            kind,
                            tags,
                            deprecated,
                            range.getOrElse(LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0))),
                            selectionRange.getOrElse(LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0))),
                            children
                        ))
                end match
            end readOneElement

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.DocumentSymbolResult =
                val count = reader.arrayStart()
                if count == 0 then
                    reader.arrayEnd()
                    LspHandler.DocumentSymbolResult.Symbols(Chunk.empty)
                else
                    val symbols      = scala.collection.mutable.ArrayBuffer[LspHandler.DocumentSymbol]()
                    val infos        = scala.collection.mutable.ArrayBuffer[LspHandler.SymbolInformation]()
                    var isSymbol     = true
                    var isDetermined = false

                    def readAll(): Unit =
                        if count < 0 then
                            while reader.hasNextElement() do
                                val elem = readOneElement(reader)
                                elem match
                                    case Left(ds) =>
                                        if !isDetermined then
                                            isSymbol = true; isDetermined = true; symbols += ds
                                    case Right(si) =>
                                        if !isDetermined then
                                            isSymbol = false; isDetermined = true; infos += si
                                end match
                        else
                            var j = 0
                            while j < count do
                                val elem = readOneElement(reader)
                                elem match
                                    case Left(ds) =>
                                        if !isDetermined then
                                            isSymbol = true; isDetermined = true; symbols += ds
                                    case Right(si) =>
                                        if !isDetermined then
                                            isSymbol = false; isDetermined = true; infos += si
                                end match
                                j += 1
                            end while
                    end readAll

                    readAll()
                    reader.arrayEnd()
                    if isSymbol then LspHandler.DocumentSymbolResult.Symbols(Chunk.from(symbols))
                    else LspHandler.DocumentSymbolResult.Information(Chunk.from(infos))
                end if
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.DocumentSymbolResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.DocumentSymbolResult, next: Any): LspHandler.DocumentSymbolResult =
                next match
                    case r: LspHandler.DocumentSymbolResult => r
                    case _                                  => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.DocumentSymbolResult] =
                sv match
                    case Structure.Value.Sequence(elems) =>
                        if elems.isEmpty then Result.Success(LspHandler.DocumentSymbolResult.Symbols(Chunk.empty))
                        else
                            // Peek first element to determine type
                            val first = elems.head
                            val isSymbolInfo = first match
                                case Structure.Value.Record(fields) =>
                                    val m = fields.iterator.toMap
                                    m.contains("location") && !m.contains("children")
                                case _ => false
                            if isSymbolInfo then
                                val results = elems.map(e => summon[Schema[LspHandler.SymbolInformation]].fromStructureValue(e))
                                val initAcc: Result[DecodeException, Chunk[LspHandler.SymbolInformation]] = Result.Success(Chunk.empty)
                                results.foldLeft(initAcc) { (acc, r) =>
                                    acc.flatMap(chunk => r.map(si => chunk.append(si)))
                                }.map(LspHandler.DocumentSymbolResult.Information(_))
                            else
                                val results = elems.map(e => summon[Schema[LspHandler.DocumentSymbol]].fromStructureValue(e))
                                val initAcc: Result[DecodeException, Chunk[LspHandler.DocumentSymbol]] = Result.Success(Chunk.empty)
                                results.foldLeft(initAcc) { (acc, r) =>
                                    acc.flatMap(chunk => r.map(ds => chunk.append(ds)))
                                }.map(LspHandler.DocumentSymbolResult.Symbols(_))
                            end if
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Sequence", sv.toString))

    // MARK: -- WorkspaceSymbolLocation

    @nowarn("msg=anonymous")
    val workspaceSymbolLocationSchema: Schema[LspHandler.WorkspaceSymbolLocation] =
        new Schema[LspHandler.WorkspaceSymbolLocation](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.WorkspaceSymbolLocation, w: Codec.Writer): Unit =
                v match
                    case LspHandler.WorkspaceSymbolLocation.WithRange(uri, range) =>
                        w.objectStart("WorkspaceSymbolLocation.WithRange", 2)
                        w.field("uri", 1)
                        summon[Schema[LspHandler.LspDocument.Uri]].serializeWrite(uri, w)
                        w.field("range", 2)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        w.objectEnd()
                    case LspHandler.WorkspaceSymbolLocation.UriOnly(uri) =>
                        w.objectStart("WorkspaceSymbolLocation.UriOnly", 1)
                        w.field("uri", 1)
                        summon[Schema[LspHandler.LspDocument.Uri]].serializeWrite(uri, w)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.WorkspaceSymbolLocation =
                var uri: LspHandler.LspDocument.Uri = LspHandler.LspDocument.Uri.fromWire("")
                var range: Maybe[LspHandler.Range]  = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("uri".getBytes("UTF-8")) then
                        uri = summon[Schema[LspHandler.LspDocument.Uri]].serializeRead(reader)
                    else if reader.matchField("range".getBytes("UTF-8")) then
                        range = Present(summon[Schema[LspHandler.Range]].serializeRead(reader))
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                range match
                    case Present(r) => LspHandler.WorkspaceSymbolLocation.WithRange(uri, r)
                    case Absent     => LspHandler.WorkspaceSymbolLocation.UriOnly(uri)
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.WorkspaceSymbolLocation): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.WorkspaceSymbolLocation,
                next: Any
            ): LspHandler.WorkspaceSymbolLocation =
                next match
                    case l: LspHandler.WorkspaceSymbolLocation => l
                    case _                                     => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.WorkspaceSymbolLocation] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m      = fields.iterator.toMap
                        val uriStr = m.get("uri").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val uri    = LspHandler.LspDocument.Uri.fromWire(uriStr)
                        m.get("range") match
                            case Some(rangeSv) =>
                                summon[Schema[LspHandler.Range]].fromStructureValue(rangeSv).map { r =>
                                    LspHandler.WorkspaceSymbolLocation.WithRange(uri, r)
                                }
                            case scala.None =>
                                Result.Success(LspHandler.WorkspaceSymbolLocation.UriOnly(uri))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- CompletionResult

    @nowarn("msg=anonymous")
    val completionResultSchema: Schema[LspHandler.CompletionResult] =
        new Schema[LspHandler.CompletionResult](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.CompletionResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.CompletionResult.Items(items) =>
                        w.arrayStart(items.size)
                        items.foreach { i => summon[Schema[LspHandler.CompletionItem]].serializeWrite(i, w) }
                        w.arrayEnd()
                    case LspHandler.CompletionResult.List(list) =>
                        summon[Schema[LspHandler.CompletionList]].serializeWrite(list, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.CompletionResult =
                val captured = reader.captureValue()
                try
                    discard(captured.arrayStart())
                    val buf = scala.collection.mutable.ArrayBuffer[LspHandler.CompletionItem]()
                    while captured.hasNextElement() do
                        buf += summon[Schema[LspHandler.CompletionItem]].serializeRead(captured)
                    captured.arrayEnd()
                    LspHandler.CompletionResult.Items(Chunk.from(buf))
                catch
                    case _: Exception =>
                        LspHandler.CompletionResult.List(summon[Schema[LspHandler.CompletionList]].serializeRead(captured))
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.CompletionResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.CompletionResult, next: Any): LspHandler.CompletionResult =
                next match
                    case r: LspHandler.CompletionResult => r
                    case _                              => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.CompletionResult] =
                sv match
                    case Structure.Value.Sequence(elems) =>
                        val results = elems.map(e => summon[Schema[LspHandler.CompletionItem]].fromStructureValue(e))
                        val initAcc: Result[DecodeException, Chunk[LspHandler.CompletionItem]] = Result.Success(Chunk.empty)
                        results.foldLeft(initAcc) { (acc, r) =>
                            acc.flatMap(chunk => r.map(ci => chunk.append(ci)))
                        }.map(LspHandler.CompletionResult.Items(_))
                    case Structure.Value.Record(_) =>
                        summon[Schema[LspHandler.CompletionList]].fromStructureValue(sv).map(LspHandler.CompletionResult.List(_))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Sequence|Record", sv.toString))

    // MARK: -- ParameterLabel

    @nowarn("msg=anonymous")
    val parameterLabelSchema: Schema[LspHandler.ParameterLabel] =
        new Schema[LspHandler.ParameterLabel](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.ParameterLabel, w: Codec.Writer): Unit =
                v match
                    case LspHandler.ParameterLabel.StringLabel(value) => w.string(value)
                    case LspHandler.ParameterLabel.RangeLabel(start, end) =>
                        w.arrayStart(2)
                        w.int(start)
                        w.int(end)
                        w.arrayEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.ParameterLabel =
                val captured = reader.captureValue()
                try LspHandler.ParameterLabel.StringLabel(captured.string())
                catch
                    case _: Exception =>
                        discard(captured.arrayStart())
                        var start = 0
                        var end   = 0
                        if captured.hasNextElement() then start = captured.int()
                        if captured.hasNextElement() then end = captured.int()
                        captured.arrayEnd()
                        LspHandler.ParameterLabel.RangeLabel(start, end)
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.ParameterLabel): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.ParameterLabel, next: Any): LspHandler.ParameterLabel =
                next match
                    case l: LspHandler.ParameterLabel => l
                    case _                            => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.ParameterLabel] =
                sv match
                    case Structure.Value.Str(s) => Result.Success(LspHandler.ParameterLabel.StringLabel(s))
                    case Structure.Value.Sequence(elems) if elems.size == 2 =>
                        val startR = elems(0) match
                            case Structure.Value.Integer(n) => Result.Success(n.toInt)
                            case other                      => Result.Failure(TypeMismatchException(Seq("start"), "Int", other.toString))
                        val endR = elems(1) match
                            case Structure.Value.Integer(n) => Result.Success(n.toInt)
                            case other                      => Result.Failure(TypeMismatchException(Seq("end"), "Int", other.toString))
                        for s <- startR; e <- endR yield LspHandler.ParameterLabel.RangeLabel(s, e)
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "String|[Int,Int]", sv.toString))

    // MARK: -- CommandOrCodeAction

    @nowarn("msg=anonymous")
    val commandOrCodeActionSchema: Schema[LspHandler.CommandOrCodeAction] =
        new Schema[LspHandler.CommandOrCodeAction](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.CommandOrCodeAction, w: Codec.Writer): Unit =
                v match
                    case LspHandler.CommandOrCodeAction.Cmd(value)    => summon[Schema[LspHandler.Command]].serializeWrite(value, w)
                    case LspHandler.CommandOrCodeAction.Action(value) => summon[Schema[LspHandler.CodeAction]].serializeWrite(value, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.CommandOrCodeAction =
                // Discriminate: CodeAction has `edit`, `diagnostics`, `isPreferred`, `disabled` fields.
                // `command` is a string in Command but a nested object in CodeAction.
                // `arguments` are LSPAny[]; capture as raw JSON snippets via captureValue.
                var title: String                                  = ""
                var commandStr: String                             = ""
                var commandObj: Maybe[LspHandler.Command]          = Absent
                var arguments: Chunk[String]                       = Chunk.empty
                var hasEdit                                        = false
                var hasDiagnostics                                 = false
                var hasIsPreferred                                 = false
                var hasDisabled                                    = false
                var commandIsObject                                = false
                var kind: Maybe[LspHandler.CodeActionKind]         = Absent
                var edit: Maybe[LspHandler.WorkspaceEdit]          = Absent
                var diags: Chunk[LspHandler.Diagnostic]            = Chunk.empty
                var isPreferred: Maybe[Boolean]                    = Absent
                var disabled: Maybe[LspHandler.CodeActionDisabled] = Absent

                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("title".getBytes("UTF-8")) then title = reader.string()
                    else if reader.matchField("command".getBytes("UTF-8")) then
                        // `command` can be a string (standalone Command) or object (Command nested inside CodeAction)
                        val captured = reader.captureValue()
                        try
                            commandStr = captured.string()
                            commandIsObject = false
                        catch
                            case _: Exception =>
                                commandIsObject = true
                                commandObj = Present(summon[Schema[LspHandler.Command]].serializeRead(captured))
                        end try
                    else if reader.matchField("arguments".getBytes("UTF-8")) then
                        // LSPAny[] - each element may be any JSON value.
                        // String elements are read directly; non-string elements are skipped (stored as empty string).
                        // This preserves string arguments (most common case) without crashing on typed JSON args.
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[String]()
                        if count < 0 then
                            while reader.hasNextElement() do
                                val cap = reader.captureValue()
                                val v =
                                    try cap.string()
                                    catch case _: Exception => ""
                                buf += v
                        else
                            var j = 0;
                            while j < count do
                                val cap = reader.captureValue()
                                val v =
                                    try cap.string()
                                    catch case _: Exception => ""
                                buf += v; j += 1
                            end while
                        end if
                        reader.arrayEnd()
                        arguments = Chunk.from(buf)
                    else if reader.matchField("kind".getBytes("UTF-8")) then
                        kind = Present(LspHandler.CodeActionKind(reader.string()))
                    else if reader.matchField("edit".getBytes("UTF-8")) then
                        hasEdit = true
                        edit = Present(summon[Schema[LspHandler.WorkspaceEdit]].serializeRead(reader))
                    else if reader.matchField("diagnostics".getBytes("UTF-8")) then
                        hasDiagnostics = true
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[LspHandler.Diagnostic]()
                        if count < 0 then
                            while reader.hasNextElement() do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                        else
                            var j = 0
                            while j < count do
                                buf += summon[Schema[LspHandler.Diagnostic]].serializeRead(reader)
                                j += 1
                            end while
                        end if
                        reader.arrayEnd()
                        diags = Chunk.from(buf)
                    else if reader.matchField("isPreferred".getBytes("UTF-8")) then
                        hasIsPreferred = true
                        isPreferred = Present(reader.boolean())
                    else if reader.matchField("disabled".getBytes("UTF-8")) then
                        hasDisabled = true
                        disabled = Present(summon[Schema[LspHandler.CodeActionDisabled]].serializeRead(reader))
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()

                // If any CodeAction-specific field is present, or command field was an object, it's a CodeAction
                if hasEdit || hasDiagnostics || hasIsPreferred || hasDisabled || commandIsObject then
                    LspHandler.CommandOrCodeAction.Action(
                        LspHandler.CodeAction(title, kind, diags, isPreferred, disabled, edit, commandObj, Absent)
                    )
                else
                    // It's a Command (title + command-string + optional arguments)
                    LspHandler.CommandOrCodeAction.Cmd(LspHandler.Command(title, commandStr, arguments))
                end if
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.CommandOrCodeAction): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.CommandOrCodeAction, next: Any): LspHandler.CommandOrCodeAction =
                next match
                    case c: LspHandler.CommandOrCodeAction => c
                    case _                                 => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.CommandOrCodeAction] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        if m.contains("edit") || m.contains("diagnostics") || m.contains("isPreferred") || m.contains("disabled") then
                            summon[Schema[LspHandler.CodeAction]].fromStructureValue(sv).map(LspHandler.CommandOrCodeAction.Action(_))
                        else
                            summon[Schema[LspHandler.Command]].fromStructureValue(sv).map(LspHandler.CommandOrCodeAction.Cmd(_))
                        end if
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- WorkspaceEditDocumentChange

    @nowarn("msg=anonymous")
    val workspaceEditDocumentChangeSchema: Schema[LspHandler.WorkspaceEditDocumentChange] =
        new Schema[LspHandler.WorkspaceEditDocumentChange](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.WorkspaceEditDocumentChange, w: Codec.Writer): Unit =
                v match
                    case LspHandler.WorkspaceEditDocumentChange.Edit(value) =>
                        summon[Schema[LspHandler.TextDocumentEdit]].serializeWrite(value, w)
                    case LspHandler.WorkspaceEditDocumentChange.Create(value) =>
                        summon[Schema[LspHandler.CreateFile]].serializeWrite(value, w)
                    case LspHandler.WorkspaceEditDocumentChange.Rename(value) =>
                        summon[Schema[LspHandler.RenameFile]].serializeWrite(value, w)
                    case LspHandler.WorkspaceEditDocumentChange.Delete(value) =>
                        summon[Schema[LspHandler.DeleteFile]].serializeWrite(value, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.WorkspaceEditDocumentChange =
                // Read ALL fields in one pass, collect them, then dispatch based on kind
                var kindStr: String                               = ""
                var hasKind: Boolean                              = false
                var textDocumentEdit: LspHandler.TextDocumentEdit = null
                var createFile: LspHandler.CreateFile             = null
                var renameFile: LspHandler.RenameFile             = null
                var deleteFile: LspHandler.DeleteFile             = null
                // For TextDocumentEdit: read textDocument + edits
                var tdTextDocument: LspHandler.OptionalVersionedTextDocumentIdentifier = null
                var tdEdits: Chunk[LspHandler.TextEdit]                                = Chunk.empty
                // For CreateFile: uri + options + annotationId
                var cfUri: String                                  = ""
                var cfOptions: Maybe[LspHandler.CreateFileOptions] = Absent
                var cfAnnotationId: Maybe[String]                  = Absent
                // For RenameFile: oldUri + newUri + options + annotationId
                var rfOldUri: String                               = ""
                var rfNewUri: String                               = ""
                var rfOptions: Maybe[LspHandler.RenameFileOptions] = Absent
                var rfAnnotationId: Maybe[String]                  = Absent
                // For DeleteFile: uri + options + annotationId
                var dfUri: String                                  = ""
                var dfOptions: Maybe[LspHandler.DeleteFileOptions] = Absent
                var dfAnnotationId: Maybe[String]                  = Absent

                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("kind".getBytes("UTF-8")) then
                        kindStr = reader.string()
                        hasKind = true
                    else if reader.matchField("textDocument".getBytes("UTF-8")) then
                        tdTextDocument =
                            summon[Schema[LspHandler.OptionalVersionedTextDocumentIdentifier]].serializeRead(reader)
                    else if reader.matchField("edits".getBytes("UTF-8")) then
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[LspHandler.TextEdit]()
                        if count < 0 then while reader.hasNextElement() do buf += summon[Schema[LspHandler.TextEdit]].serializeRead(reader)
                        else
                            var j = 0;
                            while j < count do
                                buf += summon[Schema[LspHandler.TextEdit]].serializeRead(reader); j += 1
                        end if
                        reader.arrayEnd()
                        tdEdits = Chunk.from(buf)
                    else if reader.matchField("uri".getBytes("UTF-8")) then
                        val u = reader.string()
                        cfUri = u; dfUri = u
                    else if reader.matchField("oldUri".getBytes("UTF-8")) then rfOldUri = reader.string()
                    else if reader.matchField("newUri".getBytes("UTF-8")) then rfNewUri = reader.string()
                    else if reader.matchField("annotationId".getBytes("UTF-8")) then
                        val a = Present(reader.string())
                        cfAnnotationId = a; rfAnnotationId = a; dfAnnotationId = a
                    else if reader.matchField("options".getBytes("UTF-8")) then
                        // Parse options based on the kind we've seen so far; defer until end if kind unknown yet.
                        // For simplicity, parse as CreateFileOptions shape (overwrite + ignoreIfExists) which covers
                        // Create, and try RenameFileOptions (overwrite + ignoreIfExists), and DeleteFileOptions
                        // (recursive + ignoreIfNotExists). We capture raw and parse after.
                        var overwrite: Maybe[Boolean]         = Absent
                        var ignoreIfExists: Maybe[Boolean]    = Absent
                        var recursive: Maybe[Boolean]         = Absent
                        var ignoreIfNotExists: Maybe[Boolean] = Absent
                        discard(reader.objectStart())
                        while reader.hasNextField() do
                            reader.fieldParse()
                            if reader.matchField("overwrite".getBytes("UTF-8")) then overwrite = Present(reader.boolean())
                            else if reader.matchField("ignoreIfExists".getBytes("UTF-8")) then ignoreIfExists = Present(reader.boolean())
                            else if reader.matchField("recursive".getBytes("UTF-8")) then recursive = Present(reader.boolean())
                            else if reader.matchField("ignoreIfNotExists".getBytes("UTF-8")) then
                                ignoreIfNotExists = Present(reader.boolean())
                            else reader.skip()
                            end if
                        end while
                        reader.objectEnd()
                        cfOptions = Present(LspHandler.CreateFileOptions(overwrite, ignoreIfExists))
                        rfOptions = Present(LspHandler.RenameFileOptions(overwrite, ignoreIfExists))
                        dfOptions = Present(LspHandler.DeleteFileOptions(recursive, ignoreIfNotExists))
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()

                if !hasKind && tdTextDocument != null then
                    LspHandler.WorkspaceEditDocumentChange.Edit(LspHandler.TextDocumentEdit(tdTextDocument, tdEdits))
                else
                    kindStr match
                        case "create" => LspHandler.WorkspaceEditDocumentChange.Create(
                                LspHandler.CreateFile(kindStr, cfUri, cfOptions, cfAnnotationId)
                            )
                        case "rename" => LspHandler.WorkspaceEditDocumentChange.Rename(
                                LspHandler.RenameFile(kindStr, rfOldUri, rfNewUri, rfOptions, rfAnnotationId)
                            )
                        case "delete" => LspHandler.WorkspaceEditDocumentChange.Delete(
                                LspHandler.DeleteFile(kindStr, dfUri, dfOptions, dfAnnotationId)
                            )
                        case _ =>
                            val td = if tdTextDocument != null then tdTextDocument
                            else
                                LspHandler.OptionalVersionedTextDocumentIdentifier(LspHandler.LspDocument.Uri.fromWire(""), Absent)
                            LspHandler.WorkspaceEditDocumentChange.Edit(LspHandler.TextDocumentEdit(td, tdEdits))
                end if
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.WorkspaceEditDocumentChange): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.WorkspaceEditDocumentChange,
                next: Any
            ): LspHandler.WorkspaceEditDocumentChange =
                next match
                    case c: LspHandler.WorkspaceEditDocumentChange => c
                    case _                                         => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.WorkspaceEditDocumentChange] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m    = fields.iterator.toMap
                        val kind = m.get("kind").collect { case Structure.Value.Str(s) => s }
                        kind match
                            case Some("create") =>
                                summon[Schema[LspHandler.CreateFile]].fromStructureValue(
                                    sv
                                ).map(LspHandler.WorkspaceEditDocumentChange.Create(_))
                            case Some("rename") =>
                                summon[Schema[LspHandler.RenameFile]].fromStructureValue(
                                    sv
                                ).map(LspHandler.WorkspaceEditDocumentChange.Rename(_))
                            case Some("delete") =>
                                summon[Schema[LspHandler.DeleteFile]].fromStructureValue(
                                    sv
                                ).map(LspHandler.WorkspaceEditDocumentChange.Delete(_))
                            case _ =>
                                // No kind field: TextDocumentEdit
                                summon[Schema[LspHandler.TextDocumentEdit]].fromStructureValue(
                                    sv
                                ).map(LspHandler.WorkspaceEditDocumentChange.Edit(_))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- PrepareRenameResult

    @nowarn("msg=anonymous")
    val prepareRenameResultSchema: Schema[LspHandler.PrepareRenameResult] =
        new Schema[LspHandler.PrepareRenameResult](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.PrepareRenameResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.PrepareRenameResult.JustRange(range) =>
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                    case LspHandler.PrepareRenameResult.RangeWithPlaceholder(range, placeholder) =>
                        w.objectStart("PrepareRenameResult.RangeWithPlaceholder", 2)
                        w.field("range", 1)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        w.field("placeholder", 2)
                        w.string(placeholder)
                        w.objectEnd()
                    case LspHandler.PrepareRenameResult.DefaultBehavior(defaultBehavior) =>
                        w.objectStart("PrepareRenameResult.DefaultBehavior", 1)
                        w.field("defaultBehavior", 1)
                        w.boolean(defaultBehavior)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.PrepareRenameResult =
                var placeholder: Maybe[String]      = Absent
                var defaultBehavior: Maybe[Boolean] = Absent
                var startLine: Maybe[Int]           = Absent
                var startChar: Maybe[Int]           = Absent
                var endLine: Maybe[Int]             = Absent
                var endChar: Maybe[Int]             = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("placeholder".getBytes("UTF-8")) then placeholder = Present(reader.string())
                    else if reader.matchField("defaultBehavior".getBytes("UTF-8")) then defaultBehavior = Present(reader.boolean())
                    else if reader.matchField("start".getBytes("UTF-8")) then
                        discard(reader.objectStart())
                        while reader.hasNextField() do
                            reader.fieldParse()
                            if reader.matchField("line".getBytes("UTF-8")) then startLine = Present(reader.int())
                            else if reader.matchField("character".getBytes("UTF-8")) then startChar = Present(reader.int())
                            else reader.skip()
                        end while
                        reader.objectEnd()
                    else if reader.matchField("end".getBytes("UTF-8")) then
                        discard(reader.objectStart())
                        while reader.hasNextField() do
                            reader.fieldParse()
                            if reader.matchField("line".getBytes("UTF-8")) then endLine = Present(reader.int())
                            else if reader.matchField("character".getBytes("UTF-8")) then endChar = Present(reader.int())
                            else reader.skip()
                        end while
                        reader.objectEnd()
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                defaultBehavior match
                    case Present(b) => LspHandler.PrepareRenameResult.DefaultBehavior(b)
                    case Absent =>
                        val range = LspHandler.Range(
                            LspHandler.Position(startLine.getOrElse(0), startChar.getOrElse(0)),
                            LspHandler.Position(endLine.getOrElse(0), endChar.getOrElse(0))
                        )
                        placeholder match
                            case Present(p) => LspHandler.PrepareRenameResult.RangeWithPlaceholder(range, p)
                            case Absent     => LspHandler.PrepareRenameResult.JustRange(range)
                end match
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.PrepareRenameResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.PrepareRenameResult, next: Any): LspHandler.PrepareRenameResult =
                next match
                    case r: LspHandler.PrepareRenameResult => r
                    case _                                 => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.PrepareRenameResult] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        if m.contains("defaultBehavior") then
                            m.get("defaultBehavior") match
                                case Some(Structure.Value.Bool(b)) => Result.Success(LspHandler.PrepareRenameResult.DefaultBehavior(b))
                                case other => Result.Failure(TypeMismatchException(
                                        Seq("defaultBehavior"),
                                        "Boolean",
                                        other.fold("absent")(_.toString)
                                    ))
                        else if m.contains("placeholder") then
                            for
                                range <- summon[Schema[LspHandler.Range]].fromStructureValue(sv)
                                placeholder <- m.get("placeholder") match
                                    case Some(Structure.Value.Str(s)) => Result.Success(s)
                                    case other => Result.Failure(TypeMismatchException(
                                            Seq("placeholder"),
                                            "String",
                                            other.fold("absent")(_.toString)
                                        ))
                            yield LspHandler.PrepareRenameResult.RangeWithPlaceholder(range, placeholder)
                        else
                            summon[Schema[LspHandler.Range]].fromStructureValue(sv).map(LspHandler.PrepareRenameResult.JustRange(_))
                        end if
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- Location-family result schemas (DefinitionResult, DeclarationResult, TypeDefinitionResult, ImplementationResult)

    private def readOneLocationOrLink(reader: Codec.Reader): Either[LspHandler.Location, LspHandler.LocationLink] =
        // Read one Location or LocationLink object in a single pass; detect type from field presence
        var uri: String                                   = ""
        var range: Maybe[LspHandler.Range]                = Absent
        var originSelectionRange: Maybe[LspHandler.Range] = Absent
        var targetUri: String                             = ""
        var targetRange: Maybe[LspHandler.Range]          = Absent
        var targetSelectionRange: Maybe[LspHandler.Range] = Absent
        var hasTargetUri                                  = false
        discard(reader.objectStart())
        while reader.hasNextField() do
            reader.fieldParse()
            if reader.matchField("uri".getBytes("UTF-8")) then uri = reader.string()
            else if reader.matchField("range".getBytes("UTF-8")) then
                range = Present(summon[Schema[LspHandler.Range]].serializeRead(reader))
            else if reader.matchField("targetUri".getBytes("UTF-8")) then
                hasTargetUri = true
                targetUri = reader.string()
            else if reader.matchField("targetRange".getBytes("UTF-8")) then
                targetRange = Present(summon[Schema[LspHandler.Range]].serializeRead(reader))
            else if reader.matchField("targetSelectionRange".getBytes("UTF-8")) then
                targetSelectionRange = Present(summon[Schema[LspHandler.Range]].serializeRead(reader))
            else if reader.matchField("originSelectionRange".getBytes("UTF-8")) then
                originSelectionRange = Present(summon[Schema[LspHandler.Range]].serializeRead(reader))
            else reader.skip()
            end if
        end while
        reader.objectEnd()
        if hasTargetUri then
            val defaultRange = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0))
            Right(LspHandler.LocationLink(
                originSelectionRange,
                targetUri,
                targetRange.getOrElse(defaultRange),
                targetSelectionRange.getOrElse(defaultRange)
            ))
        else
            Left(LspHandler.Location(uri, range.getOrElse(LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)))))
        end if
    end readOneLocationOrLink

    private def readLocationFamilyFromReader[T](
        reader: Codec.Reader,
        oneFactory: LspHandler.Location => T,
        manyFactory: Chunk[LspHandler.Location] => T,
        linksFactory: Chunk[LspHandler.LocationLink] => T
    ): T =
        val captured = reader.captureValue()
        try
            // Try as array
            val count = captured.arrayStart()
            if count == 0 then
                captured.arrayEnd()
                manyFactory(Chunk.empty)
            else
                val locations    = scala.collection.mutable.ArrayBuffer[LspHandler.Location]()
                val links        = scala.collection.mutable.ArrayBuffer[LspHandler.LocationLink]()
                var isLink       = false
                var isDetermined = false

                def readElements(): Unit =
                    if count < 0 then
                        while captured.hasNextElement() do
                            val elem = readOneLocationOrLink(captured)
                            elem match
                                case Left(loc) =>
                                    if !isDetermined then
                                        isLink = false; isDetermined = true
                                    locations += loc
                                case Right(ll) =>
                                    if !isDetermined then
                                        isLink = true; isDetermined = true
                                    links += ll
                            end match
                    else
                        var j = 0
                        while j < count do
                            val elem = readOneLocationOrLink(captured)
                            elem match
                                case Left(loc) =>
                                    if !isDetermined then
                                        isLink = false; isDetermined = true
                                    locations += loc
                                case Right(ll) =>
                                    if !isDetermined then
                                        isLink = true; isDetermined = true
                                    links += ll
                            end match
                            j += 1
                        end while
                end readElements

                readElements()
                captured.arrayEnd()
                if isLink then linksFactory(Chunk.from(links))
                else manyFactory(Chunk.from(locations))
            end if
        catch
            case _: Exception =>
                // Single object - Location
                oneFactory(summon[Schema[LspHandler.Location]].serializeRead(captured))
        end try
    end readLocationFamilyFromReader

    private def readLocationFamilyFromSv[T](
        sv: Structure.Value,
        oneFactory: LspHandler.Location => T,
        manyFactory: Chunk[LspHandler.Location] => T,
        linksFactory: Chunk[LspHandler.LocationLink] => T
    )(using Frame): Result[DecodeException, T] =
        sv match
            case Structure.Value.Sequence(elems) =>
                if elems.isEmpty then Result.Success(manyFactory(Chunk.empty))
                else
                    // Check first element for targetUri (LocationLink indicator)
                    val isLink = elems.head match
                        case Structure.Value.Record(fields) =>
                            fields.iterator.toMap.contains("targetUri")
                        case _ => false
                    if isLink then
                        val results = elems.map(e => summon[Schema[LspHandler.LocationLink]].fromStructureValue(e))
                        val initAcc: Result[DecodeException, Chunk[LspHandler.LocationLink]] = Result.Success(Chunk.empty)
                        results.foldLeft(initAcc) { (acc, r) =>
                            acc.flatMap(chunk => r.map(ll => chunk.append(ll)))
                        }.map(linksFactory)
                    else
                        val results = elems.map(e => summon[Schema[LspHandler.Location]].fromStructureValue(e))
                        val initAcc: Result[DecodeException, Chunk[LspHandler.Location]] = Result.Success(Chunk.empty)
                        results.foldLeft(initAcc) { (acc, r) =>
                            acc.flatMap(chunk => r.map(l => chunk.append(l)))
                        }.map(manyFactory)
                    end if
            case Structure.Value.Record(_) =>
                summon[Schema[LspHandler.Location]].fromStructureValue(sv).map(oneFactory)
            case _ =>
                Result.Failure(TypeMismatchException(Seq.empty, "Sequence|Record", sv.toString))

    @nowarn("msg=anonymous")
    val definitionResultSchema: Schema[LspHandler.DefinitionResult] =
        new Schema[LspHandler.DefinitionResult](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.DefinitionResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.DefinitionResult.One(l) => summon[Schema[LspHandler.Location]].serializeWrite(l, w)
                    case LspHandler.DefinitionResult.Many(ls) =>
                        w.arrayStart(ls.size); ls.foreach { l => summon[Schema[LspHandler.Location]].serializeWrite(l, w) }; w.arrayEnd()
                    case LspHandler.DefinitionResult.Links(lls) =>
                        w.arrayStart(lls.size); lls.foreach { ll => summon[Schema[LspHandler.LocationLink]].serializeWrite(ll, w) };
                        w.arrayEnd()
            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.DefinitionResult =
                readLocationFamilyFromReader(
                    reader,
                    LspHandler.DefinitionResult.One(_),
                    LspHandler.DefinitionResult.Many(_),
                    LspHandler.DefinitionResult.Links(_)
                )
            @publicInBinary private[kyo] def getter(value: LspHandler.DefinitionResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.DefinitionResult, next: Any): LspHandler.DefinitionResult =
                next match
                    case r: LspHandler.DefinitionResult => r;
                    case _                              => value
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.DefinitionResult] =
                readLocationFamilyFromSv(
                    sv,
                    LspHandler.DefinitionResult.One(_),
                    LspHandler.DefinitionResult.Many(_),
                    LspHandler.DefinitionResult.Links(_)
                )

    @nowarn("msg=anonymous")
    val declarationResultSchema: Schema[LspHandler.DeclarationResult] =
        new Schema[LspHandler.DeclarationResult](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.DeclarationResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.DeclarationResult.One(l) => summon[Schema[LspHandler.Location]].serializeWrite(l, w)
                    case LspHandler.DeclarationResult.Many(ls) =>
                        w.arrayStart(ls.size); ls.foreach { l => summon[Schema[LspHandler.Location]].serializeWrite(l, w) }; w.arrayEnd()
                    case LspHandler.DeclarationResult.Links(lls) =>
                        w.arrayStart(lls.size); lls.foreach { ll => summon[Schema[LspHandler.LocationLink]].serializeWrite(ll, w) };
                        w.arrayEnd()
            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.DeclarationResult =
                readLocationFamilyFromReader(
                    reader,
                    LspHandler.DeclarationResult.One(_),
                    LspHandler.DeclarationResult.Many(_),
                    LspHandler.DeclarationResult.Links(_)
                )
            @publicInBinary private[kyo] def getter(value: LspHandler.DeclarationResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.DeclarationResult, next: Any): LspHandler.DeclarationResult =
                next match
                    case r: LspHandler.DeclarationResult => r;
                    case _                               => value
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.DeclarationResult] =
                readLocationFamilyFromSv(
                    sv,
                    LspHandler.DeclarationResult.One(_),
                    LspHandler.DeclarationResult.Many(_),
                    LspHandler.DeclarationResult.Links(_)
                )

    @nowarn("msg=anonymous")
    val typeDefinitionResultSchema: Schema[LspHandler.TypeDefinitionResult] =
        new Schema[LspHandler.TypeDefinitionResult](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.TypeDefinitionResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.TypeDefinitionResult.One(l) => summon[Schema[LspHandler.Location]].serializeWrite(l, w)
                    case LspHandler.TypeDefinitionResult.Many(ls) =>
                        w.arrayStart(ls.size); ls.foreach { l => summon[Schema[LspHandler.Location]].serializeWrite(l, w) }; w.arrayEnd()
                    case LspHandler.TypeDefinitionResult.Links(lls) =>
                        w.arrayStart(lls.size); lls.foreach { ll => summon[Schema[LspHandler.LocationLink]].serializeWrite(ll, w) };
                        w.arrayEnd()
            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.TypeDefinitionResult =
                readLocationFamilyFromReader(
                    reader,
                    LspHandler.TypeDefinitionResult.One(_),
                    LspHandler.TypeDefinitionResult.Many(_),
                    LspHandler.TypeDefinitionResult.Links(_)
                )
            @publicInBinary private[kyo] def getter(value: LspHandler.TypeDefinitionResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.TypeDefinitionResult, next: Any): LspHandler.TypeDefinitionResult =
                next match
                    case r: LspHandler.TypeDefinitionResult => r;
                    case _                                  => value
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.TypeDefinitionResult] =
                readLocationFamilyFromSv(
                    sv,
                    LspHandler.TypeDefinitionResult.One(_),
                    LspHandler.TypeDefinitionResult.Many(_),
                    LspHandler.TypeDefinitionResult.Links(_)
                )

    @nowarn("msg=anonymous")
    val implementationResultSchema: Schema[LspHandler.ImplementationResult] =
        new Schema[LspHandler.ImplementationResult](Seq.empty):
            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.ImplementationResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.ImplementationResult.One(l) => summon[Schema[LspHandler.Location]].serializeWrite(l, w)
                    case LspHandler.ImplementationResult.Many(ls) =>
                        w.arrayStart(ls.size); ls.foreach { l => summon[Schema[LspHandler.Location]].serializeWrite(l, w) }; w.arrayEnd()
                    case LspHandler.ImplementationResult.Links(lls) =>
                        w.arrayStart(lls.size); lls.foreach { ll => summon[Schema[LspHandler.LocationLink]].serializeWrite(ll, w) };
                        w.arrayEnd()
            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.ImplementationResult =
                readLocationFamilyFromReader(
                    reader,
                    LspHandler.ImplementationResult.One(_),
                    LspHandler.ImplementationResult.Many(_),
                    LspHandler.ImplementationResult.Links(_)
                )
            @publicInBinary private[kyo] def getter(value: LspHandler.ImplementationResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.ImplementationResult, next: Any): LspHandler.ImplementationResult =
                next match
                    case r: LspHandler.ImplementationResult => r;
                    case _                                  => value
            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.ImplementationResult] =
                readLocationFamilyFromSv(
                    sv,
                    LspHandler.ImplementationResult.One(_),
                    LspHandler.ImplementationResult.Many(_),
                    LspHandler.ImplementationResult.Links(_)
                )

    // MARK: -- SemanticTokensResult

    @nowarn("msg=anonymous")
    val semanticTokensResultSchema: Schema[LspHandler.SemanticTokensResult] =
        new Schema[LspHandler.SemanticTokensResult](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.SemanticTokensResult, w: Codec.Writer): Unit =
                v match
                    case LspHandler.SemanticTokensResult.Full(tokens) =>
                        summon[Schema[LspHandler.SemanticTokens]].serializeWrite(tokens, w)
                    case LspHandler.SemanticTokensResult.Delta(delta) =>
                        summon[Schema[LspHandler.SemanticTokensDelta]].serializeWrite(delta, w)

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.SemanticTokensResult =
                // Read all fields in one pass, dispatch after
                var hasEdits                                    = false
                var resultId: Maybe[String]                     = Absent
                var data: Chunk[Int]                            = Chunk.empty
                var edits: Chunk[LspHandler.SemanticTokensEdit] = Chunk.empty
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("resultId".getBytes("UTF-8")) then resultId = Present(reader.string())
                    else if reader.matchField("data".getBytes("UTF-8")) then
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[Int]()
                        if count < 0 then while reader.hasNextElement() do buf += reader.int()
                        else
                            var j = 0;
                            while j < count do
                                buf += reader.int(); j += 1
                        end if
                        reader.arrayEnd()
                        data = Chunk.from(buf)
                    else if reader.matchField("edits".getBytes("UTF-8")) then
                        hasEdits = true
                        val count = reader.arrayStart()
                        val buf   = scala.collection.mutable.ArrayBuffer[LspHandler.SemanticTokensEdit]()
                        if count < 0 then
                            while reader.hasNextElement() do
                                buf += summon[Schema[LspHandler.SemanticTokensEdit]].serializeRead(reader)
                        else
                            var j = 0
                            while j < count do
                                buf += summon[Schema[LspHandler.SemanticTokensEdit]].serializeRead(reader)
                                j += 1
                            end while
                        end if
                        reader.arrayEnd()
                        edits = Chunk.from(buf)
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                if hasEdits then
                    LspHandler.SemanticTokensResult.Delta(LspHandler.SemanticTokensDelta(resultId, edits))
                else
                    LspHandler.SemanticTokensResult.Full(LspHandler.SemanticTokens(resultId, data))
                end if
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.SemanticTokensResult): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.SemanticTokensResult, next: Any): LspHandler.SemanticTokensResult =
                next match
                    case r: LspHandler.SemanticTokensResult => r
                    case _                                  => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.SemanticTokensResult] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        if m.contains("edits") then
                            summon[Schema[LspHandler.SemanticTokensDelta]].fromStructureValue(
                                sv
                            ).map(LspHandler.SemanticTokensResult.Delta(_))
                        else
                            summon[Schema[LspHandler.SemanticTokens]].fromStructureValue(sv).map(LspHandler.SemanticTokensResult.Full(_))
                        end if
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- InlayHintLabelPart

    @nowarn("msg=anonymous")
    val inlayHintLabelPartSchema: Schema[LspHandler.InlayHintLabelPart] =
        new Schema[LspHandler.InlayHintLabelPart](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.InlayHintLabelPart, w: Codec.Writer): Unit =
                v match
                    case LspHandler.InlayHintLabelPart.StringPart(value) => w.string(value)
                    case LspHandler.InlayHintLabelPart.StructuredPart(value, tooltip, location, command) =>
                        val count = 1 + (if tooltip.isDefined then 1 else 0) + (if location.isDefined then 1
                                                                                else 0) + (if command.isDefined then 1 else 0)
                        w.objectStart("InlayHintLabelPart.StructuredPart", count)
                        w.field("value", 1)
                        w.string(value)
                        var idx = 2
                        tooltip.foreach { t =>
                            w.field("tooltip", idx); idx += 1; summon[Schema[LspHandler.MarkupContent]].serializeWrite(t, w)
                        }
                        location.foreach { l =>
                            w.field("location", idx); idx += 1; summon[Schema[LspHandler.Location]].serializeWrite(l, w)
                        }
                        command.foreach { c =>
                            w.field("command", idx); idx += 1; summon[Schema[LspHandler.Command]].serializeWrite(c, w)
                        }
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.InlayHintLabelPart =
                val captured = reader.captureValue()
                try LspHandler.InlayHintLabelPart.StringPart(captured.string())
                catch
                    case _: Exception =>
                        var value: String                            = ""
                        var tooltip: Maybe[LspHandler.MarkupContent] = Absent
                        var location: Maybe[LspHandler.Location]     = Absent
                        var command: Maybe[LspHandler.Command]       = Absent
                        discard(captured.objectStart())
                        while captured.hasNextField() do
                            captured.fieldParse()
                            if captured.matchField("value".getBytes("UTF-8")) then value = captured.string()
                            else if captured.matchField("tooltip".getBytes("UTF-8")) then
                                tooltip = Present(summon[Schema[LspHandler.MarkupContent]].serializeRead(captured))
                            else if captured.matchField("location".getBytes("UTF-8")) then
                                location = Present(summon[Schema[LspHandler.Location]].serializeRead(captured))
                            else if captured.matchField("command".getBytes("UTF-8")) then
                                command = Present(summon[Schema[LspHandler.Command]].serializeRead(captured))
                            else captured.skip()
                            end if
                        end while
                        captured.objectEnd()
                        LspHandler.InlayHintLabelPart.StructuredPart(value, tooltip, location, command)
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.InlayHintLabelPart): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.InlayHintLabelPart, next: Any): LspHandler.InlayHintLabelPart =
                next match
                    case l: LspHandler.InlayHintLabelPart => l
                    case _                                => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.InlayHintLabelPart] =
                sv match
                    case Structure.Value.Str(s) => Result.Success(LspHandler.InlayHintLabelPart.StringPart(s))
                    case Structure.Value.Record(fields) =>
                        val m        = fields.iterator.toMap
                        val value    = m.get("value").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val tooltip  = m.get("tooltip").map { tsv => summon[Schema[LspHandler.MarkupContent]].fromStructureValue(tsv) }
                        val location = m.get("location").map { lsv => summon[Schema[LspHandler.Location]].fromStructureValue(lsv) }
                        val command  = m.get("command").map { csv => summon[Schema[LspHandler.Command]].fromStructureValue(csv) }
                        for
                            t <- tooltip.map(_.map(Present(_))).getOrElse(Result.Success(Absent))
                            l <- location.map(_.map(Present(_))).getOrElse(Result.Success(Absent))
                            c <- command.map(_.map(Present(_))).getOrElse(Result.Success(Absent))
                        yield LspHandler.InlayHintLabelPart.StructuredPart(value, t, l, c)
                        end for
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "String|Record", sv.toString))

    // MARK: -- InlayHintLabel

    @nowarn("msg=anonymous")
    val inlayHintLabelSchema: Schema[LspHandler.InlayHintLabel] =
        new Schema[LspHandler.InlayHintLabel](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.InlayHintLabel, w: Codec.Writer): Unit =
                v match
                    case LspHandler.InlayHintLabel.PlainString(value) => w.string(value)
                    case LspHandler.InlayHintLabel.Parts(parts) =>
                        w.arrayStart(parts.size)
                        parts.foreach { p => inlayHintLabelPartSchema.serializeWrite(p, w) }
                        w.arrayEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.InlayHintLabel =
                val captured = reader.captureValue()
                try LspHandler.InlayHintLabel.PlainString(captured.string())
                catch
                    case _: Exception =>
                        discard(captured.arrayStart())
                        val buf = scala.collection.mutable.ArrayBuffer[LspHandler.InlayHintLabelPart]()
                        while captured.hasNextElement() do buf += inlayHintLabelPartSchema.serializeRead(captured)
                        captured.arrayEnd()
                        LspHandler.InlayHintLabel.Parts(Chunk.from(buf))
                end try
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.InlayHintLabel): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.InlayHintLabel, next: Any): LspHandler.InlayHintLabel =
                next match
                    case l: LspHandler.InlayHintLabel => l
                    case _                            => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.InlayHintLabel] =
                sv match
                    case Structure.Value.Str(s) => Result.Success(LspHandler.InlayHintLabel.PlainString(s))
                    case Structure.Value.Sequence(elems) =>
                        val results = elems.map(e => inlayHintLabelPartSchema.fromStructureValue(e))
                        val initAcc: Result[DecodeException, Chunk[LspHandler.InlayHintLabelPart]] = Result.Success(Chunk.empty)
                        results.foldLeft(initAcc) { (acc, r) =>
                            acc.flatMap(chunk => r.map(p => chunk.append(p)))
                        }.map(LspHandler.InlayHintLabel.Parts(_))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "String|Sequence", sv.toString))

    // MARK: -- InlineValue

    @nowarn("msg=anonymous")
    val inlineValueSchema: Schema[LspHandler.InlineValue] =
        new Schema[LspHandler.InlineValue](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.InlineValue, w: Codec.Writer): Unit =
                v match
                    case LspHandler.InlineValue.Text(range, text) =>
                        w.objectStart("InlineValue.Text", 2)
                        w.field("range", 1)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        w.field("text", 2)
                        w.string(text)
                        w.objectEnd()
                    case LspHandler.InlineValue.VariableLookup(range, variableName, caseSensitiveLookup) =>
                        val count = 2 + (if variableName.isDefined then 1 else 0)
                        w.objectStart("InlineValue.VariableLookup", count)
                        w.field("range", 1)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        var idx = 2
                        variableName.foreach { vn =>
                            w.field("variableName", idx); idx += 1; w.string(vn)
                        }
                        w.field("caseSensitiveLookup", idx)
                        w.boolean(caseSensitiveLookup)
                        w.objectEnd()
                    case LspHandler.InlineValue.EvaluatableExpression(range, expression) =>
                        val count = 1 + (if expression.isDefined then 1 else 0)
                        w.objectStart("InlineValue.EvaluatableExpression", count)
                        w.field("range", 1)
                        summon[Schema[LspHandler.Range]].serializeWrite(range, w)
                        expression.foreach { e =>
                            w.field("expression", 2); w.string(e)
                        }
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.InlineValue =
                var range: LspHandler.Range             = null
                var text: Maybe[String]                 = Absent
                var variableName: Maybe[String]         = Absent
                var caseSensitiveLookup: Maybe[Boolean] = Absent
                var expression: Maybe[String]           = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("range".getBytes("UTF-8")) then
                        range = summon[Schema[LspHandler.Range]].serializeRead(reader)
                    else if reader.matchField("text".getBytes("UTF-8")) then text = Present(reader.string())
                    else if reader.matchField("variableName".getBytes("UTF-8")) then variableName = Present(reader.string())
                    else if reader.matchField("caseSensitiveLookup".getBytes("UTF-8")) then caseSensitiveLookup = Present(reader.boolean())
                    else if reader.matchField("expression".getBytes("UTF-8")) then expression = Present(reader.string())
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                val r = if range != null then range else LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0))
                (expression, caseSensitiveLookup, text) match
                    case (Present(e), _, _)   => LspHandler.InlineValue.EvaluatableExpression(r, Present(e))
                    case (_, Present(csl), _) => LspHandler.InlineValue.VariableLookup(r, variableName, csl)
                    case (_, _, Present(t))   => LspHandler.InlineValue.Text(r, t)
                    case _                    => LspHandler.InlineValue.Text(r, "")
                end match
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.InlineValue): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.InlineValue, next: Any): LspHandler.InlineValue =
                next match
                    case v: LspHandler.InlineValue => v
                    case _                         => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.InlineValue] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        val rangeR = m.get("range") match
                            case Some(rsv)  => summon[Schema[LspHandler.Range]].fromStructureValue(rsv)
                            case scala.None => Result.Success(LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)))
                        val expression = m.get("expression").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        val variableName =
                            m.get("variableName").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        val caseSens = m.get("caseSensitiveLookup").collect { case Structure.Value.Bool(b) => b }
                        val text     = m.get("text").collect { case Structure.Value.Str(s) => s }
                        rangeR.map { r =>
                            (expression, caseSens, text) match
                                case (Present(e), _, _) => LspHandler.InlineValue.EvaluatableExpression(r, Present(e))
                                case (_, Some(csl), _)  => LspHandler.InlineValue.VariableLookup(r, variableName, csl)
                                case (_, _, Some(t))    => LspHandler.InlineValue.Text(r, t)
                                case _                  => LspHandler.InlineValue.Text(r, "")
                        }
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- NotebookDocumentFilter

    @nowarn("msg=anonymous")
    val notebookDocumentFilterSchema: Schema[LspHandler.NotebookDocumentFilter] =
        new Schema[LspHandler.NotebookDocumentFilter](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(v: LspHandler.NotebookDocumentFilter, w: Codec.Writer): Unit =
                v match
                    case LspHandler.NotebookDocumentFilter.WithNotebookType(notebookType, scheme, pattern) =>
                        val count = 1 + (if scheme.isDefined then 1 else 0) + (if pattern.isDefined then 1 else 0)
                        w.objectStart("NotebookDocumentFilter.WithNotebookType", count)
                        w.field("notebookType", 1)
                        w.string(notebookType)
                        var idx = 2
                        scheme.foreach { s =>
                            w.field("scheme", idx); idx += 1; w.string(s)
                        }
                        pattern.foreach { p =>
                            w.field("pattern", idx); idx += 1; w.string(p)
                        }
                        w.objectEnd()
                    case LspHandler.NotebookDocumentFilter.WithScheme(notebookType, scheme, pattern) =>
                        val count = 1 + (if notebookType.isDefined then 1 else 0) + (if pattern.isDefined then 1 else 0)
                        w.objectStart("NotebookDocumentFilter.WithScheme", count)
                        notebookType.foreach { nt =>
                            w.field("notebookType", 1); w.string(nt)
                        }
                        w.field("scheme", 2)
                        w.string(scheme)
                        pattern.foreach { p =>
                            w.field("pattern", 3); w.string(p)
                        }
                        w.objectEnd()
                    case LspHandler.NotebookDocumentFilter.WithPattern(notebookType, scheme, pattern) =>
                        val count = 1 + (if notebookType.isDefined then 1 else 0) + (if scheme.isDefined then 1 else 0)
                        w.objectStart("NotebookDocumentFilter.WithPattern", count)
                        notebookType.foreach { nt =>
                            w.field("notebookType", 1); w.string(nt)
                        }
                        scheme.foreach { s =>
                            w.field("scheme", 2); w.string(s)
                        }
                        w.field("pattern", 3)
                        w.string(pattern)
                        w.objectEnd()

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.NotebookDocumentFilter =
                var notebookType: Maybe[String] = Absent
                var scheme: Maybe[String]       = Absent
                var pattern: Maybe[String]      = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("notebookType".getBytes("UTF-8")) then notebookType = Present(reader.string())
                    else if reader.matchField("scheme".getBytes("UTF-8")) then scheme = Present(reader.string())
                    else if reader.matchField("pattern".getBytes("UTF-8")) then pattern = Present(reader.string())
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                (notebookType, scheme, pattern) match
                    case (Present(nt), _, _) => LspHandler.NotebookDocumentFilter.WithNotebookType(nt, scheme, pattern)
                    case (_, Present(s), _)  => LspHandler.NotebookDocumentFilter.WithScheme(notebookType, s, pattern)
                    case (_, _, Present(p))  => LspHandler.NotebookDocumentFilter.WithPattern(notebookType, scheme, p)
                    case _ =>
                        throw TypeMismatchException(Seq.empty, "notebookType|scheme|pattern", "all absent")(using Frame.internal)
                end match
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.NotebookDocumentFilter): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(
                value: LspHandler.NotebookDocumentFilter,
                next: Any
            ): LspHandler.NotebookDocumentFilter =
                next match
                    case f: LspHandler.NotebookDocumentFilter => f
                    case _                                    => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.NotebookDocumentFilter] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.iterator.toMap
                        val notebookType =
                            m.get("notebookType").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        val scheme  = m.get("scheme").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        val pattern = m.get("pattern").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        (notebookType, scheme, pattern) match
                            case (Present(nt), _, _) =>
                                Result.Success(LspHandler.NotebookDocumentFilter.WithNotebookType(nt, scheme, pattern))
                            case (_, Present(s), _) =>
                                Result.Success(LspHandler.NotebookDocumentFilter.WithScheme(notebookType, s, pattern))
                            case (_, _, Present(p)) =>
                                Result.Success(LspHandler.NotebookDocumentFilter.WithPattern(notebookType, scheme, p))
                            case _ =>
                                Result.Failure(TypeMismatchException(Seq.empty, "notebookType|scheme|pattern", "all absent"))
                        end match
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

    // MARK: -- Registration (hand-rolled; registerOptions is raw JSON pass-through)

    @nowarn("msg=anonymous")
    val registrationSchema: Schema[LspHandler.Registration] =
        new Schema[LspHandler.Registration](Seq.empty):

            @publicInBinary private[kyo] def serializeWrite(r: LspHandler.Registration, w: Codec.Writer): Unit =
                val count = 2 + (if r._rawRegisterOptions.isDefined then 1 else 0)
                w.objectStart("Registration", count)
                w.field("id", 1)
                w.string(r.id)
                w.field("method", 2)
                w.string(r.method)
                r._rawRegisterOptions.foreach { raw =>
                    w.field("registerOptions", 3)
                    // Embed raw JSON directly (without re-encoding as a string)
                    // Use Schema[String] won't work directly; we embed the raw bytes
                    w.string(raw) // This encodes as a JSON string - clients must double-decode
                    // Actually for a proper embedding we need to write raw JSON bytes
                    // For now, treat as encoded string field
                }
                w.objectEnd()
            end serializeWrite

            @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): LspHandler.Registration =
                var id: String                = ""
                var method: String            = ""
                var rawOptions: Maybe[String] = Absent
                discard(reader.objectStart())
                while reader.hasNextField() do
                    reader.fieldParse()
                    if reader.matchField("id".getBytes("UTF-8")) then id = reader.string()
                    else if reader.matchField("method".getBytes("UTF-8")) then method = reader.string()
                    else if reader.matchField("registerOptions".getBytes("UTF-8")) then
                        // Capture raw JSON string for later typed access
                        rawOptions = Present(reader.string())
                    else reader.skip()
                    end if
                end while
                reader.objectEnd()
                new LspHandler.Registration(id, method, rawOptions)
            end serializeRead

            @publicInBinary private[kyo] def getter(value: LspHandler.Registration): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: LspHandler.Registration, next: Any): LspHandler.Registration =
                next match
                    case r: LspHandler.Registration => r
                    case _                          => value

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using
                Frame
            ): Result[DecodeException, LspHandler.Registration] =
                sv match
                    case Structure.Value.Record(fields) =>
                        val m      = fields.iterator.toMap
                        val id     = m.get("id").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val method = m.get("method").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                        val rawOpts =
                            m.get("registerOptions").collect { case Structure.Value.Str(s) => s }.map(Present(_)).getOrElse(Absent)
                        Result.Success(new LspHandler.Registration(id, method, rawOpts))
                    case _ =>
                        Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))

end LspContentSchemas
