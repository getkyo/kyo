package kyo.internal

import kyo.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary

/** Hand-rolled discriminator-key Schema for [[McpServer.SamplingContent]].
  *
  * Uses the `"type"` field as the discriminator with values `"text"` | `"image"` | `"audio"`.
  * EmbeddedResource and ResourceLink are not valid sampling content per MCP §3.10.
  */
private[kyo] object McpSamplingContentSchema:

    @nowarn("msg=anonymous")
    val schema: Schema[McpServer.SamplingContent] = new Schema[McpServer.SamplingContent](Seq.empty):

        @publicInBinary private[kyo] def serializeWrite(c: McpServer.SamplingContent, w: Codec.Writer): Unit =
            c match
                case McpServer.SamplingContent.Text(text, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 3 else 2
                    w.objectStart("SamplingContent.Text", fieldCount)
                    w.field("type", 1)
                    w.string("text")
                    w.field("text", 2)
                    w.string(text)
                    if emitAnn then
                        w.field("annotations", 3)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
                case McpServer.SamplingContent.Image(data, mimeType, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 4 else 3
                    w.objectStart("SamplingContent.Image", fieldCount)
                    w.field("type", 1)
                    w.string("image")
                    w.field("data", 2)
                    w.string(data)
                    w.field("mimeType", 3)
                    w.string(mimeType.asString)
                    if emitAnn then
                        w.field("annotations", 4)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
                case McpServer.SamplingContent.Audio(data, mimeType, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 4 else 3
                    w.objectStart("SamplingContent.Audio", fieldCount)
                    w.field("type", 1)
                    w.string("audio")
                    w.field("data", 2)
                    w.string(data)
                    w.field("mimeType", 3)
                    w.string(mimeType.asString)
                    if emitAnn then
                        w.field("annotations", 4)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
        end serializeWrite

        @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): McpServer.SamplingContent =
            var typeTag: String                     = ""
            var text: String                        = ""
            var data: String                        = ""
            var mimeType: McpMimeType               = McpMimeType.fromWire("")
            var annotations: McpContent.Annotations = McpContent.Annotations.noop
            discard(reader.objectStart())
            while reader.hasNextField() do
                reader.fieldParse()
                if reader.matchField("type".getBytes("UTF-8")) then
                    typeTag = reader.string()
                else if reader.matchField("text".getBytes("UTF-8")) then
                    text = reader.string()
                else if reader.matchField("data".getBytes("UTF-8")) then
                    data = reader.string()
                else if reader.matchField("mimeType".getBytes("UTF-8")) then
                    mimeType = McpMimeType.fromWire(reader.string())
                else if reader.matchField("annotations".getBytes("UTF-8")) then
                    annotations = summon[Schema[McpContent.Annotations]].serializeRead(reader)
                else
                    reader.skip()
                end if
            end while
            reader.objectEnd()
            typeTag match
                case "text"  => McpServer.SamplingContent.Text(text, annotations)
                case "image" => McpServer.SamplingContent.Image(data, mimeType, annotations)
                case "audio" => McpServer.SamplingContent.Audio(data, mimeType, annotations)
                case other =>
                    throw TypeMismatchException(Seq.empty, "text|image|audio", other)(using Frame.internal)
            end match
        end serializeRead

        @publicInBinary private[kyo] def getter(value: McpServer.SamplingContent): Maybe[Any] = Maybe(value)
        @publicInBinary private[kyo] def setter(value: McpServer.SamplingContent, next: Any): McpServer.SamplingContent =
            next match
                case c: McpServer.SamplingContent => c
                case _                            => value

        override private[kyo] def fromStructureValue(sv: Structure.Value)(using
            Frame
        )
            : Result[DecodeException, McpServer.SamplingContent] =
            sv match
                case Structure.Value.Record(fields) =>
                    val m = fields.iterator.toMap
                    val annotationsResult: Result[DecodeException, McpContent.Annotations] =
                        m.get("annotations") match
                            case Some(annSv) =>
                                summon[Schema[McpContent.Annotations]].fromStructureValue(annSv)
                            case scala.None => Result.Success(McpContent.Annotations.noop)
                    m.get("type") match
                        case Some(Structure.Value.Str("text")) =>
                            m.get("text") match
                                case Some(Structure.Value.Str(t)) =>
                                    annotationsResult.map(anns => McpServer.SamplingContent.Text(t, anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("text"), "String", m.get("text").fold("absent")(_.toString)))
                        case Some(Structure.Value.Str("image")) =>
                            val dataOpt     = m.get("data").collect { case Structure.Value.Str(s) => s }
                            val mimeTypeOpt = m.get("mimeType").collect { case Structure.Value.Str(s) => s }
                            (dataOpt, mimeTypeOpt) match
                                case (Some(d), Some(mt)) =>
                                    annotationsResult.map(anns => McpServer.SamplingContent.Image(d, McpMimeType.fromWire(mt), anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq.empty, "data+mimeType", "missing fields"))
                            end match
                        case Some(Structure.Value.Str("audio")) =>
                            val dataOpt     = m.get("data").collect { case Structure.Value.Str(s) => s }
                            val mimeTypeOpt = m.get("mimeType").collect { case Structure.Value.Str(s) => s }
                            (dataOpt, mimeTypeOpt) match
                                case (Some(d), Some(mt)) =>
                                    annotationsResult.map(anns => McpServer.SamplingContent.Audio(d, McpMimeType.fromWire(mt), anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq.empty, "data+mimeType", "missing fields"))
                            end match
                        case other =>
                            Result.Failure(TypeMismatchException(
                                Seq.empty,
                                "text|image|audio",
                                other.fold("absent")(_.toString)
                            ))
                    end match
                case _ =>
                    Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))
        end fromStructureValue

    end schema

end McpSamplingContentSchema
