package kyo.internal

import kyo.*

private[kyo] object JsonRpcCodecImpl:

    val Strict2_0: JsonRpcCodec = new JsonRpcCodec:

        def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
            env match
                case JsonRpcEnvelope.Request(id, method, params, _) =>
                    Sync.defer:
                        val base = Chunk(
                            "jsonrpc" -> Structure.Value.Str("2.0"),
                            "id"      -> Structure.encode(id),
                            "method"  -> Structure.Value.Str(method)
                        )
                        val withParams = params match
                            case Present(p) => base :+ ("params" -> p)
                            case Absent     => base
                        Structure.Value.Record(withParams)

                case JsonRpcEnvelope.Notification(method, params, _) =>
                    Sync.defer:
                        val base = Chunk(
                            "jsonrpc" -> Structure.Value.Str("2.0"),
                            "method"  -> Structure.Value.Str(method)
                        )
                        val withParams = params match
                            case Present(p) => base :+ ("params" -> p)
                            case Absent     => base
                        Structure.Value.Record(withParams)

                case JsonRpcEnvelope.Response(id, result, error, _) =>
                    Sync.defer:
                        val base = Chunk(
                            "jsonrpc" -> Structure.Value.Str("2.0"),
                            "id"      -> Structure.encode(id)
                        )
                        val withPayload = (result, error) match
                            case (Present(r), Absent) => base :+ ("result" -> r)
                            case (Absent, Present(e)) => base :+ ("error"  -> Structure.encode(e))
                            case _ =>
                                base :+ ("result" -> result.getOrElse(Structure.Value.Null))
                        Structure.Value.Record(withPayload)

                case JsonRpcEnvelope.Malformed(_, _) =>
                    Abort.fail(JsonRpcError.internalError("cannot encode Malformed", Absent))

        def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync =
            Sync.defer:
                raw match
                    case Structure.Value.Record(fields) =>
                        val fieldMap = fields.iterator.toMap

                        def getStr(key: String): Maybe[String] =
                            Maybe.fromOption(fieldMap.get(key).collect { case Structure.Value.Str(s) => s })

                        def getValue(key: String): Maybe[Structure.Value] =
                            fieldMap.get(key) match
                                case Some(Structure.Value.Null) => Absent
                                case Some(v)                    => Present(v)
                                case None                       => Absent

                        def decodeId(v: Structure.Value): Maybe[JsonRpcId] =
                            v match
                                case Structure.Value.Null       => Absent
                                case Structure.Value.Integer(n) => Present(JsonRpcId.Num(n))
                                case Structure.Value.Str(s)     => Present(JsonRpcId.Str(s))
                                case _                          => Absent

                        val methodOpt = getStr("method")
                        val idRaw     = fieldMap.get("id")
                        val resultOpt = getValue("result")
                        val errorOpt  = getValue("error")

                        val hasId     = idRaw.isDefined
                        val idMaybe   = Maybe.fromOption(idRaw).flatMap(decodeId)
                        val hasMethod = methodOpt.isDefined
                        val hasResult = resultOpt.isDefined
                        val hasError  = errorOpt.isDefined

                        if hasMethod && hasId && idMaybe.isDefined then
                            val params = getValue("params")
                            JsonRpcEnvelope.Request(idMaybe.get, methodOpt.get, params, Absent)
                        else if hasMethod && (!hasId || idMaybe.isEmpty) then
                            val params = getValue("params")
                            JsonRpcEnvelope.Notification(methodOpt.get, params, Absent)
                        else if !hasMethod && hasId && idMaybe.isDefined && (hasResult || hasError) then
                            if hasResult && hasError then
                                JsonRpcEnvelope.Malformed("response has both result and error", raw)
                            else
                                val decodedError = errorOpt.map: ev =>
                                    Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)
                                JsonRpcEnvelope.Response(idMaybe.get, resultOpt, decodedError, Absent)
                        else
                            JsonRpcEnvelope.Malformed("unclassifiable envelope", raw)
                        end if

                    case other =>
                        JsonRpcEnvelope.Malformed("expected a Record", other)
        end decode

    end Strict2_0

    val Cdp: JsonRpcCodec = new JsonRpcCodec:

        def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
            env match
                case JsonRpcEnvelope.Request(id, method, params, extras) =>
                    val base = Chunk(
                        "id"     -> Structure.encode(id),
                        "method" -> Structure.Value.Str(method)
                    )
                    val withParams = params match
                        case Present(p) => base :+ ("params" -> p)
                        case Absent     => base
                    buildWithExtras(withParams, extras)

                case JsonRpcEnvelope.Notification(method, params, extras) =>
                    val base = Chunk("method" -> Structure.Value.Str(method))
                    val withParams = params match
                        case Present(p) => base :+ ("params" -> p)
                        case Absent     => base
                    buildWithExtras(withParams, extras)

                case JsonRpcEnvelope.Response(id, result, error, extras) =>
                    val base = Chunk("id" -> Structure.encode(id))
                    val withPayload = (result, error) match
                        case (Present(r), Absent) => base :+ ("result" -> r)
                        case (Absent, Present(e)) => base :+ ("error"  -> Structure.encode(e))
                        case _ =>
                            base :+ ("result" -> result.getOrElse(Structure.Value.Null))
                    buildWithExtras(withPayload, extras)

                case JsonRpcEnvelope.Malformed(_, _) =>
                    Abort.fail(JsonRpcError.internalError("cannot encode Malformed", Absent))

        private def buildWithExtras(
            base: Chunk[(String, Structure.Value)],
            extras: Maybe[Structure.Value]
        )(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
            extras match
                case Absent =>
                    Sync.defer(Structure.Value.Record(base))
                case Present(Structure.Value.Record(extraFields)) =>
                    val badKey = extraFields.iterator.map(_._1).find(JsonRpcCodec.cdpReservedKeys.contains)
                    badKey match
                        case Some(key) =>
                            Abort.fail(JsonRpcError.invalidRequest(s"extras key '$key' is reserved"))
                        case None =>
                            Sync.defer(Structure.Value.Record(base ++ extraFields))
                    end match
                case Present(Structure.Value.VariantCase(_, _)) =>
                    Abort.fail(JsonRpcError.invalidRequest("extras must be a Record or Null, not VariantCase/MapEntries"))
                case Present(Structure.Value.MapEntries(_)) =>
                    Abort.fail(JsonRpcError.invalidRequest("extras must be a Record or Null, not VariantCase/MapEntries"))
                case Present(other) =>
                    Sync.defer(Structure.Value.Record(base :+ ("_extras" -> other)))

        def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync =
            Sync.defer:
                raw match
                    case Structure.Value.Record(fields) =>
                        val known = JsonRpcCodec.cdpReservedKeys

                        def getStr(key: String): Maybe[String] =
                            Maybe.fromOption(fields.iterator.collectFirst { case (k, Structure.Value.Str(s)) if k == key => s })

                        def getVal(key: String): Maybe[Structure.Value] =
                            Maybe.fromOption(fields.iterator.collectFirst { case (k, v) if k == key => v }).flatMap:
                                case Structure.Value.Null => Absent
                                case v                    => Present(v)

                        def decodeId(v: Structure.Value): Maybe[JsonRpcId] =
                            v match
                                case Structure.Value.Null       => Absent
                                case Structure.Value.Integer(n) => Present(JsonRpcId.Num(n))
                                case Structure.Value.Str(s)     => Present(JsonRpcId.Str(s))
                                case _                          => Absent

                        val unknownFields = fields.filter((k, _) => !known.contains(k))
                        val extras: Maybe[Structure.Value] =
                            if unknownFields.isEmpty then Absent
                            else Present(Structure.Value.Record(unknownFields))

                        val methodOpt = getStr("method")
                        val idRaw     = Maybe.fromOption(fields.iterator.collectFirst { case (k, v) if k == "id" => v })
                        val resultOpt = getVal("result")
                        val errorOpt  = getVal("error")

                        val hasId     = idRaw.isDefined
                        val idMaybe   = idRaw.flatMap(decodeId)
                        val hasMethod = methodOpt.isDefined
                        val hasResult = resultOpt.isDefined
                        val hasError  = errorOpt.isDefined

                        if hasMethod && hasId && idMaybe.isDefined then
                            val params = getVal("params")
                            JsonRpcEnvelope.Request(idMaybe.get, methodOpt.get, params, extras)
                        else if hasMethod && (!hasId || idMaybe.isEmpty) then
                            val params = getVal("params")
                            JsonRpcEnvelope.Notification(methodOpt.get, params, extras)
                        else if !hasMethod && hasId && idMaybe.isDefined && (hasResult || hasError) then
                            val decodedError = errorOpt.map: ev =>
                                Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)
                            JsonRpcEnvelope.Response(idMaybe.get, resultOpt, decodedError, extras)
                        else
                            JsonRpcEnvelope.Malformed("unclassifiable envelope", raw)
                        end if

                    case other =>
                        JsonRpcEnvelope.Malformed("expected a Record", other)
        end decode

    end Cdp

end JsonRpcCodecImpl
