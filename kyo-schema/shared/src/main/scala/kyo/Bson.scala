package kyo

import kyo.internal.bson.BsonReader
import kyo.internal.bson.BsonWriter

/** BSON codec instance for codec-polymorphic schema APIs.
  *
  * BSON is a document-oriented binary format. This codec therefore requires the top-level value to
  * be an object-shaped schema such as a case class or string-keyed map. Nested arrays, scalars,
  * binary values, UTC datetimes, and documents are supported inside that root document.
  *
  * The companion object provides direct encode and decode helpers that return and consume raw BSON
  * bytes. `Config` carries decode safety limits and is part of the public API, so future
  * BSON-specific options can be added without changing call sites.
  */
final class Bson(val config: Bson.Config = Bson.Config.Default) extends Codec:
    def newWriter(): Codec.Writer = BsonWriter(config)

    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        BsonReader(input, config)
    end newReader
end Bson

/** Primary entry point for BSON serialization.
  *
  * BSON is a document-oriented binary format, so the top-level value must be object-shaped: a case class or a string-keyed map. Nested
  * arrays, scalars, binary values, UTC datetimes, and documents are supported inside that root document. `encode`/`decode` work on
  * `Span[Byte]`; `encodeBytes`/`decodeBytes` are aliases of the same operations, named for symmetry with other byte-oriented codecs.
  *
  * A `BigInt` field encodes only within the signed 64-bit range (BSON's int64), unlike `BigDecimal`, which encodes losslessly up to 34
  * decimal digits through BSON's Decimal128. A `BigInt` value outside that range fails encoding with
  * [[SchemaNotSerializableException]] rather than truncating or corrupting the value.
  *
  * The companion provides a default `given Bson` instance backed by `Bson.Config.Default`, and default decode-limit values
  * (`DefaultMaxDepth`, `DefaultMaxCollectionSize`) shared with [[Json]]. `Config` carries `maxDepth` and `maxCollectionSize`, the
  * traversal limits enforced while parsing or decoding; every `encode`/`decode` overload either takes an explicit `Config`, explicit
  * `maxDepth`/`maxCollectionSize` values, or falls back to the contextual `Bson` instance's own configured limits.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Bson:
    inline val DefaultMaxDepth = Codec.DefaultMaxDepth

    inline val DefaultMaxCollectionSize = Codec.DefaultMaxCollectionSize

    /** Decode-limit configuration for BSON.
      *
      * @param maxDepth
      *   maximum nested document or array depth when parsing or traversing BSON
      * @param maxCollectionSize
      *   maximum element count in any single document or array
      */
    final case class Config(
        maxDepth: Int = Bson.DefaultMaxDepth,
        maxCollectionSize: Int = Bson.DefaultMaxCollectionSize
    ) derives CanEqual

    object Config:
        val Default: Config = Config()

        given Config = Default
    end Config

    given Bson = Bson()

    /** Encodes a value of type `A` to BSON bytes. */
    inline def encode[A](value: A)(using schema: Schema[A], bson: Bson, frame: Frame): Span[Byte] =
        val w = bson.newWriter()
        schema.writeTo(value, w)
        w.result()
    end encode

    /** Encodes a value of type `A` to BSON bytes using an explicit config. */
    inline def encode[A](value: A, config: Bson.Config)(using schema: Schema[A], frame: Frame): Span[Byte] =
        given Bson = Bson(config)
        encode[A](value)
    end encode

    /** Encodes a value of type `A` to BSON bytes. Alias of [[encode]]. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], bson: Bson, frame: Frame): Span[Byte] =
        encode[A](value)
    end encodeBytes

    /** Encodes a value of type `A` to BSON bytes using an explicit config. Alias of [[encode]]. */
    inline def encodeBytes[A](value: A, config: Bson.Config)(using schema: Schema[A], frame: Frame): Span[Byte] =
        encode[A](value, config)
    end encodeBytes

    /** Decodes BSON bytes into a value of type `A`. */
    def decode[A](
        input: Span[Byte]
    )(using bson: Bson, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, bson.config)
    end decode

    /** Decodes BSON bytes into a value of type `A` using an explicit config. */
    def decode[A](
        input: Span[Byte],
        config: Bson.Config
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = Bson(config).newReader(input)
            schema.readFrom(reader)
        }
    end decode

    /** Decodes BSON bytes into a value of type `A` with explicit traversal limits. */
    def decode[A](
        input: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using bson: Bson, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, bson.config.copy(maxDepth = maxDepth, maxCollectionSize = maxCollectionSize))
    end decode

    /** Decodes BSON bytes into a value of type `A`. Alias of [[decode]]. */
    def decodeBytes[A](
        input: Span[Byte]
    )(using bson: Bson, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input)
    end decodeBytes

    /** Decodes BSON bytes into a value of type `A` using an explicit config. Alias of [[decode]]. */
    def decodeBytes[A](
        input: Span[Byte],
        config: Bson.Config
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, config)
    end decodeBytes

    /** Decodes BSON bytes into a value of type `A` with explicit traversal limits. Alias of [[decode]]. */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using bson: Bson, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, maxDepth, maxCollectionSize)
    end decodeBytes

end Bson
