package kyo.internal

import kyo.*

final private[kyo] class WireTransportAdapter(
    wire: WireTransport,
    framer: Framer,
    codec: JsonRpcCodec
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[JsonRpcError](codec.encode(env)).map {
            case Result.Success(structure) =>
                // flow-allow: RawJsonParser.encode converts Structure.Value to standard JSON-RPC wire bytes;
                // Json.encode[Structure.Value] uses kyo-schema format ({"Record":...}), not standard JSON.
                val jsonStr = RawJsonParser.encode(structure)
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
            // flow-allow: RawJsonParser.parse converts arbitrary JSON-RPC wire bytes into Structure.Value;
            // Json.decode[Structure.Value] uses the kyo-schema format and fails on standard JSON objects.
            RawJsonParser.parse(jsonStr) match
                case Result.Success(structureValue) => codec.decode(structureValue)
                case Result.Failure(_) =>
                    Sync.defer(JsonRpcEnvelope.Malformed(Absent, "json parse failed", Structure.Value.Str(jsonStr)))
                case Result.Panic(t) =>
                    Sync.defer(JsonRpcEnvelope.Malformed(Absent, s"json parse panic: ${t.getMessage}", Structure.Value.Null))
            end match
        }

    def close(using Frame): Unit < Async = wire.close
end WireTransportAdapter
