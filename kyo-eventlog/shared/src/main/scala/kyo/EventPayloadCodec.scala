package kyo

/** Strategy for encoding event payloads in a journal backend.
  *
  * A codec is selected when a file backend is constructed and governs how every payload byte
  * stream is written to binary segments and transcoded when the segment format is JSONL. Two
  * built-in codecs are available through the companion:
  *
  *   - [[kyo.EventPayloadCodec.schema]] derives a typed codec from a `Schema[A]`. Binary
  *     segments store the payload as MsgPack bytes; JSONL segments embed the payload as a JSON
  *     value by transcoding through the schema on write.
  *   - [[kyo.EventPayloadCodec.bytes]] is an identity codec for raw `Span[Byte]` payloads:
  *     binary segments store the bytes unchanged; JSONL segments base64-encode them.
  *
  * @see [[kyo.EventPayloadCodec.schema]] for schema-derived encoding
  * @see [[kyo.EventPayloadCodec.bytes]] for raw-byte identity encoding
  * @see [[kyo.EventLog]] for the typed event log that uses a schema-derived codec internally
  */
trait EventPayloadCodec

object EventPayloadCodec:
    /** Schema-derived: binary = `MsgPack(A)`; JSONL = embedded `JSON(A)`. */
    def schema[A](using Schema[A], Frame): EventPayloadCodec = new SchemaPayloadCodec[A]

    /** Identity over raw `Span[Byte]` payloads: binary = raw bytes, JSONL = base64 string. */
    val bytes: EventPayloadCodec = BytesPayloadCodec
end EventPayloadCodec

final private[kyo] class SchemaPayloadCodec[A](using private val schm: Schema[A]) extends EventPayloadCodec:
    private[kyo] def encode(value: A)(using Frame): Span[Byte]                          = schm.encode[MsgPack](value)
    private[kyo] def decode(bytes: Span[Byte])(using Frame): Result[DecodeException, A] = schm.decode[MsgPack](bytes)
end SchemaPayloadCodec

private[kyo] object BytesPayloadCodec extends EventPayloadCodec:
    private[kyo] def encode(bytes: Span[Byte]): Span[Byte]                          = bytes
    private[kyo] def decode(bytes: Span[Byte]): Result[DecodeException, Span[Byte]] = Result.succeed(bytes)
end BytesPayloadCodec
