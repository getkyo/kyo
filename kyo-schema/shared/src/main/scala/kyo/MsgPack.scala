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
  *     interops with any MsgPack decoder) or `FieldId` (compact XXH32 integers, like Protobuf). Dynamic `Map` keys and the
  *     `Result`/`Either`/tuple discriminator keys are always written as strings regardless of this setting, since they are not recoverable
  *     from a hash.
  *   - [[MsgPack.InstantEncoding]] selects how `Instant` is written: `Primitive` (default, lossless `[seconds, nanos]` array) or
  *     `Extension` (the spec-defined MessagePack timestamp extension, type -1). The reader auto-detects the wire shape, so bytes produced
  *     under either setting decode correctly regardless of the reader's config.
  *   - [[MsgPack.DurationEncoding]] selects how `Duration`/`FiniteDuration` are written: `Lossless` (default, `[seconds, nanos]` array,
  *     full range) or `Compat` (a string of total nanoseconds, wire-compatible with upickle/weePickle). Schemas for both
  *     `java.time.Duration` and `scala.concurrent.duration.{Duration, FiniteDuration}` are provided.
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

    override def mediaType: String = "application/msgpack"
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

        /** Write each schema field/variant name as its stable XXH32 `fieldId` integer. Compact, but not consumable without the
          * schema.
          */
        case FieldId
    end KeyEncoding

    /** Selects how `java.time.Instant` is encoded. */
    enum InstantEncoding derives CanEqual:
        /** Lossless primitive encoding: a 2-element `[seconds, nanos]` array. */
        case Primitive

        /** MessagePack reserved timestamp extension (type -1), the spec-defined cross-language form. */
        case Extension
    end InstantEncoding

    /** Selects how `java.time.Duration` and `scala.concurrent.duration.FiniteDuration` are encoded.
      *
      * MessagePack defines no Duration type and no cross-library convention exists, so this is a free choice:
      *   - [[Lossless]] keeps full `java.time.Duration` range (seconds beyond a `Long` of nanoseconds).
      *   - [[Compat]] matches upickle/weePickle, whose Duration wire form is a string of total nanoseconds.
      *
      * `scala.concurrent.duration.Duration` (the possibly-infinite abstract type) always uses the [[Compat]]
      * string form regardless of this setting, since `Inf`/`MinusInf`/`Undefined` have no lossless numeric
      * representation.
      */
    enum DurationEncoding derives CanEqual:
        /** Lossless 2-element `[seconds, nanos]` array, preserving the full `java.time.Duration` range. */
        case Lossless

        /** A MessagePack string of total nanoseconds, wire-compatible with upickle/weePickle. Limited to the
          * `Long` nanosecond range (about 292 years), matching those libraries.
          */
        case Compat
    end DurationEncoding

    /** Wire-shape configuration for the MsgPack codec.
      *
      * @param keyEncoding
      *   how schema field/variant names are written (default [[KeyEncoding.StringName]])
      * @param instantEncoding
      *   how `Instant` is written (default [[InstantEncoding.Primitive]])
      * @param durationEncoding
      *   how `Duration`/`FiniteDuration` is written (default [[DurationEncoding.Lossless]])
      */
    case class Config(
        keyEncoding: KeyEncoding = KeyEncoding.StringName,
        instantEncoding: InstantEncoding = InstantEncoding.Primitive,
        durationEncoding: DurationEncoding = DurationEncoding.Lossless
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
