package kyo.internal

import kyo.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary

/** Hand-rolled discriminator-key Schemas for `McpContent` and `McpHandler.ResourceContents`.
  *
  * Both schemas use the `"type"` field as the discriminator key. The `contentSchema` and
  * `resourceContentsSchema` vals are singletons (INV-013): every `summon[Schema[McpContent]]`
  * resolves to the same reference.
  *
  * The `annotations` field uses the noop pattern: the wire encoder omits the field when the
  * runtime value equals `McpContent.Annotations.noop`. The wire decoder restores `noop` when
  * the field is absent on the incoming payload.
  *
  * Precedent: `Schema[JsonRpcError]` at kyo-jsonrpc/.../JsonRpcError.scala:63-116.
  */
private[kyo] object McpContentSchema:

    @nowarn("msg=anonymous")
    val contentSchema: Schema[McpContent] = new Schema[McpContent](Seq.empty):

        @publicInBinary private[kyo] def serializeWrite(c: McpContent, w: Codec.Writer): Unit =
            c match
                case McpContent.Text(text, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 3 else 2
                    w.objectStart("McpContent.Text", fieldCount)
                    w.field("type", 1)
                    w.string("text")
                    w.field("text", 2)
                    w.string(text)
                    if emitAnn then
                        w.field("annotations", 3)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
                case McpContent.Image(data, mimeType, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 4 else 3
                    w.objectStart("McpContent.Image", fieldCount)
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
                case McpContent.Audio(data, mimeType, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 4 else 3
                    w.objectStart("McpContent.Audio", fieldCount)
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
                case McpContent.EmbeddedResource(resource, annotations) =>
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = if emitAnn then 3 else 2
                    w.objectStart("McpContent.EmbeddedResource", fieldCount)
                    w.field("type", 1)
                    w.string("resource")
                    w.field("resource", 2)
                    summon[Schema[McpHandler.ResourceContents]].serializeWrite(resource, w)
                    if emitAnn then
                        w.field("annotations", 3)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
                case McpContent.ResourceLink(uri, name, description, rlMimeType, annotations) =>
                    val emitDesc   = description.isDefined
                    val emitMime   = rlMimeType.isDefined
                    val emitAnn    = annotations != McpContent.Annotations.noop
                    val fieldCount = 3 + (if emitDesc then 1 else 0) + (if emitMime then 1 else 0) + (if emitAnn then 1 else 0)
                    w.objectStart("McpContent.ResourceLink", fieldCount)
                    w.field("type", 1)
                    w.string("resource_link")
                    w.field("uri", 2)
                    summon[Schema[McpResourceUri]].serializeWrite(uri, w)
                    w.field("name", 3)
                    w.string(name)
                    var fieldIdx = 4
                    if emitDesc then
                        w.field("description", fieldIdx)
                        w.string(description.get)
                        fieldIdx += 1
                    end if
                    if emitMime then
                        w.field("mimeType", fieldIdx)
                        w.string(rlMimeType.get.asString)
                        fieldIdx += 1
                    end if
                    if emitAnn then
                        w.field("annotations", fieldIdx)
                        summon[Schema[McpContent.Annotations]].serializeWrite(annotations, w)
                    w.objectEnd()
        end serializeWrite

        @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): McpContent =
            var typeTag: String                          = ""
            var text: String                             = ""
            var data: String                             = ""
            var mimeType: McpMimeType                    = McpMimeType.fromWire("")
            var resource: McpHandler.ResourceContents    = null
            var annotations: McpContent.Annotations      = McpContent.Annotations.noop
            var uri: McpResourceUri                      = McpResourceUri("")
            var name: String                             = ""
            var description: Maybe[String]               = Absent
            var resourceLinkMimeType: Maybe[McpMimeType] = Absent
            val n                                        = reader.objectStart()
            var i                                        = 0
            while i < n do
                reader.fieldParse()
                if reader.matchField("type".getBytes("UTF-8")) then
                    typeTag = reader.string()
                else if reader.matchField("text".getBytes("UTF-8")) then
                    text = reader.string()
                else if reader.matchField("data".getBytes("UTF-8")) then
                    data = reader.string()
                else if reader.matchField("mimeType".getBytes("UTF-8")) then
                    val mt = McpMimeType.fromWire(reader.string())
                    mimeType = mt
                    resourceLinkMimeType = Present(mt)
                else if reader.matchField("resource".getBytes("UTF-8")) then
                    resource = summon[Schema[McpHandler.ResourceContents]].serializeRead(reader)
                else if reader.matchField("annotations".getBytes("UTF-8")) then
                    annotations = summon[Schema[McpContent.Annotations]].serializeRead(reader)
                else if reader.matchField("uri".getBytes("UTF-8")) then
                    uri = summon[Schema[McpResourceUri]].serializeRead(reader)
                else if reader.matchField("name".getBytes("UTF-8")) then
                    name = reader.string()
                else if reader.matchField("description".getBytes("UTF-8")) then
                    description = Present(reader.string())
                else
                    reader.skip()
                end if
                i += 1
            end while
            reader.objectEnd()
            typeTag match
                case "text"          => McpContent.Text(text, annotations)
                case "image"         => McpContent.Image(data, mimeType, annotations)
                case "audio"         => McpContent.Audio(data, mimeType, annotations)
                case "resource"      => McpContent.EmbeddedResource(resource, annotations)
                case "resource_link" => McpContent.ResourceLink(uri, name, description, resourceLinkMimeType, annotations)
                case other =>
                    throw TypeMismatchException(Seq.empty, "text|image|audio|resource|resource_link", other)(using Frame.internal)
            end match
        end serializeRead

        @publicInBinary private[kyo] def getter(value: McpContent): Maybe[Any] = Maybe(value)
        @publicInBinary private[kyo] def setter(value: McpContent, next: Any): McpContent =
            next match
                case c: McpContent => c
                case _             => value

        override private[kyo] def fromStructureValue(sv: Structure.Value)(using
            Frame
        )
            : Result[DecodeException, McpContent] =
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
                                    annotationsResult.map(anns => McpContent.Text(t, anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("text"), "String", m.get("text").fold("absent")(_.toString)))
                        case Some(Structure.Value.Str("image")) =>
                            val dataOpt     = m.get("data").collect { case Structure.Value.Str(s) => s }
                            val mimeTypeOpt = m.get("mimeType").collect { case Structure.Value.Str(s) => s }
                            (dataOpt, mimeTypeOpt) match
                                case (Some(d), Some(mt)) =>
                                    annotationsResult.map(anns => McpContent.Image(d, McpMimeType.fromWire(mt), anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq.empty, "data+mimeType", "missing fields"))
                            end match
                        case Some(Structure.Value.Str("audio")) =>
                            val dataOpt     = m.get("data").collect { case Structure.Value.Str(s) => s }
                            val mimeTypeOpt = m.get("mimeType").collect { case Structure.Value.Str(s) => s }
                            (dataOpt, mimeTypeOpt) match
                                case (Some(d), Some(mt)) =>
                                    annotationsResult.map(anns => McpContent.Audio(d, McpMimeType.fromWire(mt), anns))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq.empty, "data+mimeType", "missing fields"))
                            end match
                        case Some(Structure.Value.Str("resource")) =>
                            m.get("resource") match
                                case Some(resSv) =>
                                    summon[Schema[McpHandler.ResourceContents]].fromStructureValue(resSv).flatMap { res =>
                                        annotationsResult.map(anns => McpContent.EmbeddedResource(res, anns))
                                    }
                                case scala.None =>
                                    Result.Failure(TypeMismatchException(Seq("resource"), "McpHandler.ResourceContents", "absent"))
                        case Some(Structure.Value.Str("resource_link")) =>
                            val uriResult = m.get("uri") match
                                case Some(uriSv) => summon[Schema[McpResourceUri]].fromStructureValue(uriSv)
                                case scala.None =>
                                    Result.Failure(TypeMismatchException(Seq("uri"), "McpResourceUri", "absent"))
                            val nameResult = m.get("name") match
                                case Some(Structure.Value.Str(n)) => Result.Success(n)
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("name"), "String", m.get("name").fold("absent")(_.toString)))
                            val descriptionResult: Result[DecodeException, Maybe[String]] = m.get("description") match
                                case Some(Structure.Value.Str(d))            => Result.Success(Present(d))
                                case Some(Structure.Value.Null) | scala.None => Result.Success(Absent)
                                case Some(other) =>
                                    Result.Failure(TypeMismatchException(Seq("description"), "String", other.toString))
                            val rlMimeTypeResult: Result[DecodeException, Maybe[McpMimeType]] = m.get("mimeType") match
                                case Some(Structure.Value.Str(mt))           => Result.Success(Present(McpMimeType.fromWire(mt)))
                                case Some(Structure.Value.Null) | scala.None => Result.Success(Absent)
                                case Some(other) =>
                                    Result.Failure(TypeMismatchException(Seq("mimeType"), "String", other.toString))
                            for
                                u    <- uriResult
                                n    <- nameResult
                                desc <- descriptionResult
                                mt   <- rlMimeTypeResult
                                anns <- annotationsResult
                            yield McpContent.ResourceLink(u, n, desc, mt, anns)
                            end for
                        case other =>
                            Result.Failure(TypeMismatchException(
                                Seq.empty,
                                "text|image|audio|resource|resource_link",
                                other.fold("absent")(_.toString)
                            ))
                    end match
                case _ =>
                    Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))
        end fromStructureValue

    end contentSchema

    @nowarn("msg=anonymous")
    val resourceContentsSchema: Schema[McpHandler.ResourceContents] = new Schema[McpHandler.ResourceContents](Seq.empty):

        @publicInBinary private[kyo] def serializeWrite(rc: McpHandler.ResourceContents, w: Codec.Writer): Unit =
            rc match
                case McpHandler.ResourceContents.Text(uri, mimeType, text) =>
                    val fieldCount = if mimeType.isDefined then 4 else 3
                    w.objectStart("McpHandler.ResourceContents.Text", fieldCount)
                    w.field("type", 1)
                    w.string("text")
                    w.field("uri", 2)
                    summon[Schema[McpResourceUri]].serializeWrite(uri, w)
                    mimeType.foreach { mt =>
                        w.field("mimeType", 3)
                        w.string(mt.asString)
                    }
                    w.field("text", if mimeType.isDefined then 4 else 3)
                    w.string(text)
                    w.objectEnd()
                case McpHandler.ResourceContents.Blob(uri, mimeType, blob) =>
                    val fieldCount = if mimeType.isDefined then 4 else 3
                    w.objectStart("McpHandler.ResourceContents.Blob", fieldCount)
                    w.field("type", 1)
                    w.string("blob")
                    w.field("uri", 2)
                    summon[Schema[McpResourceUri]].serializeWrite(uri, w)
                    mimeType.foreach { mt =>
                        w.field("mimeType", 3)
                        w.string(mt.asString)
                    }
                    w.field("blob", if mimeType.isDefined then 4 else 3)
                    w.string(blob)
                    w.objectEnd()
        end serializeWrite

        @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): McpHandler.ResourceContents =
            var typeTag: String              = ""
            var uri: McpResourceUri          = McpResourceUri("")
            var mimeType: Maybe[McpMimeType] = Absent
            var text: String                 = ""
            var blob: String                 = ""
            val n                            = reader.objectStart()
            var i                            = 0
            while i < n do
                reader.fieldParse()
                if reader.matchField("type".getBytes("UTF-8")) then
                    typeTag = reader.string()
                else if reader.matchField("uri".getBytes("UTF-8")) then
                    uri = summon[Schema[McpResourceUri]].serializeRead(reader)
                else if reader.matchField("mimeType".getBytes("UTF-8")) then
                    mimeType = Present(McpMimeType.fromWire(reader.string()))
                else if reader.matchField("text".getBytes("UTF-8")) then
                    text = reader.string()
                else if reader.matchField("blob".getBytes("UTF-8")) then
                    blob = reader.string()
                else
                    reader.skip()
                end if
                i += 1
            end while
            reader.objectEnd()
            typeTag match
                case "text" => McpHandler.ResourceContents.Text(uri, mimeType, text)
                case "blob" => McpHandler.ResourceContents.Blob(uri, mimeType, blob)
                case other =>
                    throw TypeMismatchException(Seq.empty, "text|blob", other)(using Frame.internal)
            end match
        end serializeRead

        @publicInBinary private[kyo] def getter(value: McpHandler.ResourceContents): Maybe[Any] = Maybe(value)
        @publicInBinary private[kyo] def setter(value: McpHandler.ResourceContents, next: Any): McpHandler.ResourceContents =
            next match
                case rc: McpHandler.ResourceContents => rc
                case _                               => value

        override private[kyo] def fromStructureValue(sv: Structure.Value)(using
            Frame
        )
            : Result[DecodeException, McpHandler.ResourceContents] =
            sv match
                case Structure.Value.Record(fields) =>
                    val m = fields.iterator.toMap
                    val uriResult = m.get("uri") match
                        case Some(uriSv) => summon[Schema[McpResourceUri]].fromStructureValue(uriSv)
                        case scala.None =>
                            Result.Failure(TypeMismatchException(Seq("uri"), "McpResourceUri", "absent"))
                    val mimeTypeResult: Result[DecodeException, Maybe[McpMimeType]] = m.get("mimeType") match
                        case Some(Structure.Value.Str(mt))           => Result.Success(Present(McpMimeType.fromWire(mt)))
                        case Some(Structure.Value.Null) | scala.None => Result.Success(Absent)
                        case Some(other) =>
                            Result.Failure(TypeMismatchException(Seq("mimeType"), "String", other.toString))
                    m.get("type") match
                        case Some(Structure.Value.Str("text")) =>
                            m.get("text") match
                                case Some(Structure.Value.Str(t)) =>
                                    for
                                        u  <- uriResult
                                        mt <- mimeTypeResult
                                    yield McpHandler.ResourceContents.Text(u, mt, t)
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("text"), "String", m.get("text").fold("absent")(_.toString)))
                        case Some(Structure.Value.Str("blob")) =>
                            m.get("blob") match
                                case Some(Structure.Value.Str(b)) =>
                                    for
                                        u  <- uriResult
                                        mt <- mimeTypeResult
                                    yield McpHandler.ResourceContents.Blob(u, mt, b)
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("blob"), "String", m.get("blob").fold("absent")(_.toString)))
                        case other =>
                            Result.Failure(TypeMismatchException(
                                Seq.empty,
                                "text|blob",
                                other.fold("absent")(_.toString)
                            ))
                    end match
                case _ =>
                    Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))
        end fromStructureValue

    end resourceContentsSchema

end McpContentSchema
