package kyo

import java.nio.charset.StandardCharsets

/** Strategy for encoding event payloads in a journal backend.
  *
  * A codec is selected when a file backend is constructed and governs how every payload byte
  * stream is written to binary segments and transcoded when the segment format is JSONL. Two
  * built-in codecs are available through the companion:
  *
  *   - [[kyo.EventPayloadCodec.schema]] derives a typed codec from a `Schema[A]`. Binary
  *     segments store the payload in the selected binary `Codec` (Ion Binary by default); JSONL
  *     segments embed the payload as a JSON value by transcoding through the schema on write.
  *   - [[kyo.EventPayloadCodec.bytes]] is an identity codec for raw `Span[Byte]` payloads:
  *     binary segments store the bytes unchanged; JSONL segments base64-encode them.
  *
  * @see [[kyo.EventPayloadCodec.schema]] for schema-derived encoding
  * @see [[kyo.EventPayloadCodec.bytes]] for raw-byte identity encoding
  * @see [[kyo.EventLog]] for the typed event log that uses a schema-derived codec internally
  */
trait EventPayloadCodec:
    /** Transcodes a binary-encoded payload (bytes from the selected binary codec for schema
      * codecs, raw bytes for the identity codec) to a JSON-embeddable value string for the JSONL
      * segment format.
      *
      * For schema-derived codecs: decodes the binary bytes to the typed value, then encodes the
      * value as a JSON value string (e.g. `{"n":1}` or `"hello"`). The returned string is a
      * complete JSON value and is embedded literally in the JSONL record line. The binary decode
      * can fail if the stored bytes are malformed for the schema, so this method returns a
      * `Result`.
      *
      * For the identity codec: base64-encodes the raw bytes and wraps the result in a JSON string
      * literal (i.e. the returned string includes the surrounding double-quotes, e.g.
      * `"aGVsbG8="`). This always succeeds.
      */
    private[kyo] def encodeForJsonl(bytes: Span[Byte])(using Frame): Result[DecodeException, String]

    /** Reads a payload value from a [[Codec.Reader]] positioned at the payload field of a JSONL
      * record, and returns the binary form stored in [[DecodedRecord]].
      *
      * For schema-derived codecs: reads the JSON value using the schema, then re-encodes to
      * binary bytes so the returned `Span[Byte]` is in the same binary form as a binary-format
      * segment. This always returns the same binary form regardless of whether the segment is
      * Binary or JSONL.
      *
      * For the identity codec: reads a JSON string value, base64-decodes it, and returns the raw
      * bytes.
      *
      * Consumes exactly the payload value from `reader`. Callers must position `reader` at the
      * first token of the payload value before calling this method.
      */
    private[kyo] def decodeFromJsonl(reader: Codec.Reader)(using Frame): Result[DecodeException, Span[Byte]]

end EventPayloadCodec

object EventPayloadCodec:
    /** Schema-derived: binary = Ion Binary by default; JSONL = embedded `JSON(A)`. */
    def schema[A](using Schema[A], Frame): EventPayloadCodec = schema(IonBinary())

    /** Schema-derived with an explicit binary `Codec` (MsgPack, Ion Binary, BSON where legal, etc.). */
    def schema[A](binary: Codec)(using Schema[A], Frame): EventPayloadCodec = new SchemaPayloadCodec[A](binary)

    /** Identity over raw `Span[Byte]` payloads: binary = raw bytes, JSONL = base64 string. */
    val bytes: EventPayloadCodec = BytesPayloadCodec
end EventPayloadCodec

final private[kyo] class SchemaPayloadCodec[A](private val binary: Codec = IonBinary())(using private val schema: Schema[A])
    extends EventPayloadCodec:
    private val Utf8 = StandardCharsets.UTF_8

    private[kyo] def encode(value: A)(using Frame): Span[Byte] =
        val w = binary.newWriter()
        schema.writeTo(value, w)
        w.result()
    end encode

    private[kyo] def decode(bytes: Span[Byte])(using Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = binary.newReader(bytes)
            schema.readFrom(reader)
        }

    private[kyo] def encodeForJsonl(bytes: Span[Byte])(using Frame): Result[DecodeException, String] =
        decode(bytes).map { value =>
            val w = new Json().newWriter()
            schema.writeTo(value, w)
            new String(w.result().toArray, Utf8)
        }

    private[kyo] def decodeFromJsonl(reader: Codec.Reader)(using Frame): Result[DecodeException, Span[Byte]] =
        Result.catching[DecodeException](encode(schema.readFrom(reader)))

end SchemaPayloadCodec

private[kyo] object BytesPayloadCodec extends EventPayloadCodec:
    private val Utf8 = StandardCharsets.UTF_8

    private[kyo] def encode(bytes: Span[Byte]): Span[Byte]                          = bytes
    private[kyo] def decode(bytes: Span[Byte]): Result[DecodeException, Span[Byte]] = Result.succeed(bytes)

    private[kyo] def encodeForJsonl(bytes: Span[Byte])(using Frame): Result[DecodeException, String] =
        // Base64-encode the raw bytes and wrap in a JSON string literal (including the surrounding
        // double-quotes) so the value embeds correctly as a JSON string in the JSONL record line.
        Result.succeed("\"" + java.util.Base64.getEncoder.encodeToString(bytes.toArray) + "\"")

    private[kyo] def decodeFromJsonl(reader: Codec.Reader)(using Frame): Result[DecodeException, Span[Byte]] =
        // The payload is a JSON string value containing standard base64. Read the string, then
        // base64-decode to recover the raw bytes.
        try Result.succeed(Span.from(java.util.Base64.getDecoder.decode(reader.string())))
        catch
            case _: IllegalArgumentException =>
                Result.fail(TypeMismatchException(Seq.empty, "base64-encoded string value", "<invalid>")(
                    using Frame.internal
                ))

end BytesPayloadCodec
