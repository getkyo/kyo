package kyo.internal

import kyo.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary

/** Hand-rolled discriminator-key Schema for `McpHandler.CompletionRef`.
  *
  * Uses `"type"` as the discriminator key. `Prompt` encodes as
  * `{"type":"ref/prompt","name":"..."}` and `Resource` encodes as
  * `{"type":"ref/resource","uri":"..."}`.
  *
  * The schema is a singleton: every `summon[Schema[McpHandler.CompletionRef]]`
  * resolves to the same reference via the given in `McpHandler.CompletionRef`'s companion.
  */
private[kyo] object McpCompletionRefSchema:

    @nowarn("msg=anonymous")
    val schema: Schema[McpHandler.CompletionRef] = new Schema[McpHandler.CompletionRef](Seq.empty):

        @publicInBinary private[kyo] def serializeWrite(ref: McpHandler.CompletionRef, w: Codec.Writer): Unit =
            ref match
                case McpHandler.CompletionRef.Prompt(name) =>
                    w.objectStart("McpHandler.CompletionRef.Prompt", 2)
                    w.field("type", 1)
                    w.string("ref/prompt")
                    w.field("name", 2)
                    w.string(name)
                    w.objectEnd()
                case McpHandler.CompletionRef.Resource(uri) =>
                    w.objectStart("McpHandler.CompletionRef.Resource", 2)
                    w.field("type", 1)
                    w.string("ref/resource")
                    w.field("uri", 2)
                    summon[Schema[McpResourceUri]].serializeWrite(uri, w)
                    w.objectEnd()
        end serializeWrite

        @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): McpHandler.CompletionRef =
            var typeTag: String     = ""
            var name: String        = ""
            var uri: McpResourceUri = McpResourceUri("")
            val n                   = reader.objectStart()
            var i                   = 0
            while i < n do
                reader.fieldParse()
                if reader.matchField("type".getBytes("UTF-8")) then
                    typeTag = reader.string()
                else if reader.matchField("name".getBytes("UTF-8")) then
                    name = reader.string()
                else if reader.matchField("uri".getBytes("UTF-8")) then
                    uri = summon[Schema[McpResourceUri]].serializeRead(reader)
                else
                    reader.skip()
                end if
                i += 1
            end while
            reader.objectEnd()
            typeTag match
                case "ref/prompt"   => McpHandler.CompletionRef.Prompt(name)
                case "ref/resource" => McpHandler.CompletionRef.Resource(uri)
                case other =>
                    throw TypeMismatchException(Seq.empty, "ref/prompt|ref/resource", other)(using Frame.internal)
            end match
        end serializeRead

        @publicInBinary private[kyo] def getter(value: McpHandler.CompletionRef): Maybe[Any] = Maybe(value)
        @publicInBinary private[kyo] def setter(value: McpHandler.CompletionRef, next: Any): McpHandler.CompletionRef =
            next match
                case r: McpHandler.CompletionRef => r
                case _                           => value

        override private[kyo] def fromStructureValue(sv: Structure.Value)(using
            Frame
        ): Result[DecodeException, McpHandler.CompletionRef] =
            sv match
                case Structure.Value.Record(fields) =>
                    val m = fields.iterator.toMap
                    m.get("type") match
                        case Some(Structure.Value.Str("ref/prompt")) =>
                            m.get("name") match
                                case Some(Structure.Value.Str(n)) =>
                                    Result.Success(McpHandler.CompletionRef.Prompt(n))
                                case _ =>
                                    Result.Failure(TypeMismatchException(Seq("name"), "String", m.get("name").fold("absent")(_.toString)))
                        case Some(Structure.Value.Str("ref/resource")) =>
                            m.get("uri") match
                                case Some(uriSv) =>
                                    summon[Schema[McpResourceUri]].fromStructureValue(uriSv).map { u =>
                                        McpHandler.CompletionRef.Resource(u)
                                    }
                                case scala.None =>
                                    Result.Failure(TypeMismatchException(Seq("uri"), "McpResourceUri", "absent"))
                        case other =>
                            Result.Failure(TypeMismatchException(
                                Seq.empty,
                                "ref/prompt|ref/resource",
                                other.fold("absent")(_.toString)
                            ))
                    end match
                case _ =>
                    Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))
        end fromStructureValue

    end schema

end McpCompletionRefSchema
