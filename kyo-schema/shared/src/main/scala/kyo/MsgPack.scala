package kyo

/** A [[Codec]] for the MessagePack binary serialization format.
  *
  * MessagePack (https://msgpack.org) is a compact, self-describing binary format: every value carries a type tag on the wire, so a decoder
  * can walk arbitrary data without a schema. This places MsgPack between [[Protobuf]] (compact but schema-required) and [[Json]]
  * (self-describing but text). Because it is self-describing, [[kyo.internal.msgpack.MsgPackReader]] is a [[Codec.IntrospectingReader]],
  * so `Structure.Value` and open-shaped wire protocols (JSON-RPC style envelopes) round-trip through MsgPack.
  *
  * The wire shape is controlled by [[MsgPack.Config]]:
  *   - [[MsgPack.KeyEncoding]] selects how case-class field and sealed-variant names are written: `StringName` (default, self-describing,
  *     interops with any MsgPack decoder) or `FieldId` (compact MurmurHash3 integers, like Protobuf). Dynamic `Map` keys and the
  *     `Result`/`Either`/tuple discriminator keys are always written as strings regardless of this setting, since they are not recoverable
  *     from a hash.
  *   - [[MsgPack.TemporalEncoding]] selects how `Instant`/`Duration` are written: `Primitive` (default, lossless `[seconds, nanos]` array)
  *     or `Extension` (MessagePack timestamp extension for `Instant`, a Kyo extension for `Duration`). The reader auto-detects the wire
  *     shape, so bytes produced under either setting decode correctly regardless of the reader's config.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Protobuf]] for compact schema-required binary serialization
  */
final class MsgPack(val config: MsgPack.Config = MsgPack.Config.Default) extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.msgpack.MsgPackWriter(config)
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.msgpack.MsgPackReader(input.toArray, config)
end MsgPack

/** Entry point for MessagePack binary serialization.
  *
  * All encode methods are inline and require a `Schema[A]` and a `MsgPack` instance in scope (the default `given MsgPack` uses
  * [[Config.Default]]; construct a configured `MsgPack(...)` for non-default wire shapes).
  */
object MsgPack:

    given MsgPack = MsgPack()

    /** Selects how case-class field names and sealed-trait variant names are encoded as MessagePack map keys. */
    enum KeyEncoding derives CanEqual:
        /** Write each schema field/variant name as its UTF-8 string. Self-describing and interoperable with generic MessagePack tooling. */
        case StringName

        /** Write each schema field/variant name as its stable MurmurHash3 `fieldId` integer. Compact, but not consumable without the
          * schema.
          */
        case FieldId
    end KeyEncoding

    /** Selects how `java.time.Instant` and `java.time.Duration` are encoded. */
    enum TemporalEncoding derives CanEqual:
        /** Lossless primitive encoding: a 2-element `[seconds, nanos]` array. */
        case Primitive

        /** MessagePack timestamp extension (type -1) for `Instant`; a Kyo extension (type 1) for `Duration`. */
        case Extension
    end TemporalEncoding

    /** Wire-shape configuration for the MsgPack codec.
      *
      * @param keyEncoding
      *   how schema field/variant names are written (default [[KeyEncoding.StringName]])
      * @param temporalEncoding
      *   how `Instant`/`Duration` are written (default [[TemporalEncoding.Primitive]])
      */
    case class Config(
        keyEncoding: KeyEncoding = KeyEncoding.StringName,
        temporalEncoding: TemporalEncoding = TemporalEncoding.Primitive
    ) derives CanEqual

    object Config:
        val Default: Config = Config()

        given Config = Default
    end Config

    /** Encodes a value of type A to MessagePack binary bytes. */
    inline def encode[A](value: A)(using schema: Schema[A], msgpack: MsgPack, frame: Frame): Span[Byte] =
        val w = msgpack.newWriter()
        schema.writeTo(value, w)
        w.result()
    end encode

    /** Encodes a value of type A to MessagePack binary bytes. Alias of [[encode]] for naming symmetry with the other binary codecs. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], msgpack: MsgPack, frame: Frame): Span[Byte] =
        encode[A](value)

    /** Decodes MessagePack binary bytes into a value of type A.
      *
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using msgpack: MsgPack, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = msgpack.newReader(input)
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decode

    /** Decodes MessagePack binary bytes into a value of type A. Alias of [[decode]] for naming symmetry with the other binary codecs. */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using msgpack: MsgPack, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, maxDepth, maxCollectionSize)

end MsgPack
