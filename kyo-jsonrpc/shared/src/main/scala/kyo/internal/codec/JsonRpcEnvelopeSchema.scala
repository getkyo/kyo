package kyo.internal.codec

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.annotation.nowarn
import scala.annotation.publicInBinary

/** Custom [[Schema]] instances for the JSON-RPC 2.0 envelope wire shape.
  *
  * The envelope is not a derivable shape: the four message kinds are distinguished by field
  * presence (no discriminator tag), the `"jsonrpc":"2.0"` version is a constant, non-standard
  * `extras` flatten to the top level, and at most one of `result`/`error` may appear. Those rules
  * live in [[toStructureValue]] (encode) and [[fromStructureValue]] (decode); every typed field is
  * still routed through a derived schema (`Structure.encode`/`Structure.decode` for the id and the
  * error). The streaming `serializeWrite`/`serializeRead` bridge through the universal
  * `Structure.Value` form, which is the only path the wire uses (`Json[Structure.Value]`).
  *
  *   - [[strict]]: emits `"jsonrpc":"2.0"`; ignores `extras`.
  *   - [[lenient]]: omits the version field; flattens `extras` and validates reserved keys.
  */
private[kyo] object JsonRpcEnvelopeSchema:

    val strict: Schema[JsonRpcEnvelope]  = envelopeSchema(emitVersion = true, handleExtras = false)
    val lenient: Schema[JsonRpcEnvelope] = envelopeSchema(emitVersion = false, handleExtras = true)

    private val reservedKeys: Set[String] = Set("id", "method", "params", "result", "error", "jsonrpc")

    @nowarn("msg=anonymous")
    private def envelopeSchema(emitVersion: Boolean, handleExtras: Boolean): Schema[JsonRpcEnvelope] =
        new Schema[JsonRpcEnvelope](Seq.empty):

            override private[kyo] def toStructureValue(env: JsonRpcEnvelope): Structure.Value =
                given Frame = Frame.internal
                env match
                    case JsonRpcRequest(id, method, params, extras) =>
                        val base = versionPrefix ++ Chunk("id" -> Structure.encode(id), "method" -> Structure.Value.Str(method))
                        withExtras(appendParams(base, params), extras)
                    case JsonRpcNotification(method, params, extras) =>
                        val base = versionPrefix :+ ("method" -> Structure.Value.Str(method))
                        withExtras(appendParams(base, params), extras)
                    case JsonRpcResponse(id, result, error, extras) =>
                        val base = versionPrefix :+ ("id" -> Structure.encode(id))
                        withExtras(appendPayload(base, result, error), extras)
                    case JsonRpcMalformedMessage(_, _, _) =>
                        throw JsonRpcInternalError(
                            JsonRpcInternalError.Operation.EncodeResponse,
                            new RuntimeException("cannot encode Malformed message")
                        )
                end match
            end toStructureValue

            override private[kyo] def fromStructureValue(sv: Structure.Value)(using Frame): Result[DecodeException, JsonRpcEnvelope] =
                Result.catching[DecodeException](decodeEnvelope(sv))

            @publicInBinary private[kyo] def serializeWrite(env: JsonRpcEnvelope, writer: Writer): Unit =
                Schema.writeStructureValue(writer, toStructureValue(env))

            @publicInBinary private[kyo] def serializeRead(reader: Reader): JsonRpcEnvelope =
                // Binary path: the Schema.serializeRead contract carries no Frame, so synthesize one here.
                decodeEnvelope(summon[Schema[Structure.Value]].serializeRead(reader))(using Frame.internal)

            @publicInBinary private[kyo] def getter(value: JsonRpcEnvelope): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: JsonRpcEnvelope, next: Any): JsonRpcEnvelope =
                next match
                    case e: JsonRpcEnvelope => e
                    case _                  => value

            // Open: the envelope is a dynamic wire union, not a fixed product/sum (see JsonRpcError/JsonRpcId).
            private lazy val _structure: Structure.Type = Structure.Type.Open(Tag[JsonRpcEnvelope].asInstanceOf[Tag[Any]])
            override def structure: Structure.Type      = _structure

            // --- encode helpers ---

            private def versionPrefix: Chunk[(String, Structure.Value)] =
                if emitVersion then Chunk("jsonrpc" -> Structure.Value.Str("2.0")) else Chunk.empty

            private def appendParams(
                base: Chunk[(String, Structure.Value)],
                params: Maybe[Structure.Value]
            ): Chunk[(String, Structure.Value)] =
                params match
                    case Present(p) => base :+ ("params" -> p)
                    case Absent     => base

            private def appendPayload(
                base: Chunk[(String, Structure.Value)],
                result: Maybe[Structure.Value],
                error: Maybe[JsonRpcError]
            )(using Frame): Chunk[(String, Structure.Value)] =
                (result, error) match
                    case (Present(r), Absent) => base :+ ("result" -> r)
                    case (Absent, Present(e)) => base :+ ("error"  -> Structure.encode(e))
                    case _                    => base :+ ("result" -> result.getOrElse(Structure.Value.Null))

            private def withExtras(
                base: Chunk[(String, Structure.Value)],
                extras: Maybe[Structure.Value]
            ): Structure.Value =
                if !handleExtras then Structure.Value.Record(base)
                else
                    extras match
                        case Absent =>
                            Structure.Value.Record(base)
                        case Present(Structure.Value.Record(extraFields)) =>
                            extraFields.iterator.map(_._1).find(reservedKeys.contains) match
                                case Some(key) =>
                                    throw JsonRpcInvalidRequestError(
                                        Structure.Value.Str(s"extras key '$key' is reserved"),
                                        Chunk.empty
                                    )(using Frame.internal)
                                case None =>
                                    Structure.Value.Record(base ++ extraFields)
                        case Present(Structure.Value.VariantCase(_, _)) | Present(Structure.Value.MapEntries(_)) =>
                            throw JsonRpcInvalidRequestError(
                                Structure.Value.Str("extras must be a Record or Null, not VariantCase/MapEntries"),
                                Chunk.empty
                            )(using Frame.internal)
                        case Present(other) =>
                            Structure.Value.Record(base :+ ("_extras" -> other))

            // --- decode (presence dispatch) ---

            private def decodeEnvelope(raw: Structure.Value)(using Frame): JsonRpcEnvelope =
                raw match
                    case Structure.Value.Record(fields) =>
                        def getStr(key: String): Maybe[String] =
                            Maybe.fromOption(fields.iterator.collectFirst { case (k, Structure.Value.Str(s)) if k == key => s })

                        def getVal(key: String): Maybe[Structure.Value] =
                            Maybe.fromOption(fields.iterator.collectFirst { case (k, v) if k == key => v }).flatMap:
                                case Structure.Value.Null => Absent
                                case v                    => Present(v)

                        def decodeId(v: Structure.Value): Maybe[JsonRpcId] =
                            v match
                                case Structure.Value.Null       => Absent
                                case Structure.Value.Integer(n) => Present(JsonRpcId(n))
                                case Structure.Value.Str(s)     => Present(JsonRpcId(s))
                                case _                          => Absent

                        val extras: Maybe[Structure.Value] =
                            if !handleExtras then Absent
                            else
                                val unknown = fields.filter((k, _) => !reservedKeys.contains(k))
                                if unknown.isEmpty then Absent else Present(Structure.Value.Record(unknown))

                        val methodOpt = getStr("method")
                        val idRaw     = Maybe.fromOption(fields.iterator.collectFirst { case (k, v) if k == "id" => v })
                        val resultOpt = getVal("result")
                        val errorOpt  = getVal("error")

                        val hasId     = idRaw.isDefined
                        val idMaybe   = idRaw.flatMap(decodeId)
                        val hasMethod = methodOpt.isDefined
                        val hasResult = resultOpt.isDefined
                        val hasError  = errorOpt.isDefined

                        val errorIsRecord = errorOpt.forall {
                            case Structure.Value.Record(_) => true
                            case _                         => false
                        }

                        if hasMethod && hasId && idMaybe.isDefined then
                            JsonRpcRequest(idMaybe.get, methodOpt.get, getVal("params"), extras)
                        else if hasMethod && (!hasId || idMaybe.isEmpty) then
                            JsonRpcNotification(methodOpt.get, getVal("params"), extras)
                        else if !hasMethod && hasId && idMaybe.isDefined && (hasResult || hasError) then
                            if hasResult && hasError then
                                JsonRpcMalformedMessage(idMaybe, "response has both result and error", raw)
                            else if hasError && !errorIsRecord then
                                JsonRpcMalformedMessage(idMaybe, "error field is not a Record", raw)
                            else
                                val decodedError = errorOpt.map: ev =>
                                    Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcInvalidRequestError(ev, Chunk.empty))
                                JsonRpcResponse(idMaybe.get, resultOpt, decodedError, extras)
                        else
                            JsonRpcMalformedMessage(idMaybe, "unclassifiable envelope", raw)
                        end if

                    case other =>
                        JsonRpcMalformedMessage(Absent, "expected a Record", other)
                end match
            end decodeEnvelope
    end envelopeSchema

end JsonRpcEnvelopeSchema
