package kyo.internal.transport

import kyo.*

final private[kyo] class WireTransportAdapter(
    wire: JsonRpcWireTransport,
    framer: JsonRpcFramer,
    codec: Schema[JsonRpcEnvelope]
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        // Structure.encode is pure but throws a JsonRpcError for the unencodable cases (a Malformed
        // message, or a Lenient reserved-extras key); Abort.run reifies that into a Result so the
        // Success/Failure/Panic logging below is preserved.
        Abort.run[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using codec)).map {
            case Result.Success(structure) =>
                // Json.encode[Structure.Value] emits standard JSON-RPC wire bytes via the identity wire
                // shape (Record to object, Str to string, Integer/Decimal to number), so the universal
                // value tree serializes as plain JSON rather than a tagged kyo-schema encoding.
                val jsonStr = Json.encode(structure)
                val bytes   = Chunk.from(jsonStr.getBytes("UTF-8"))
                framer.frame(bytes).map(framed => wire.send(framed))
            case Result.Failure(err) =>
                Log.warn(s"kyo-jsonrpc: wire-transport encode failed: ${err.message}")
            case Result.Panic(t) =>
                Log.warn(s"kyo-jsonrpc: wire-transport encode panic: ${t.getMessage}")
        }

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
        framer.parse(wire.incoming).map { bytes =>
            val jsonStr = new String(bytes.toArray, "UTF-8")
            // Json.decode[Structure.Value] parses standard JSON-RPC wire bytes into the universal value
            // tree via the identity wire shape.
            Json.decode[Structure.Value](jsonStr) match
                case Result.Success(structureValue) =>
                    // Structure.decode never produces a Result.Failure for the envelope schema (a bad
                    // shape decodes to a Malformed envelope), but getOrElse keeps the seam total.
                    Sync.defer(
                        Structure.decode[JsonRpcEnvelope](structureValue)(using codec)
                            .getOrElse(JsonRpcMalformedMessage(Absent, "decode failed", structureValue))
                    )
                case Result.Failure(_) =>
                    Sync.defer(JsonRpcMalformedMessage(Absent, "json parse failed", Structure.Value.Str(jsonStr)))
                case Result.Panic(t) =>
                    Sync.defer(JsonRpcMalformedMessage(Absent, s"json parse panic: ${t.getMessage}", Structure.Value.Null))
            end match
        }

    def close(using Frame): Unit < Async = wire.close
end WireTransportAdapter
