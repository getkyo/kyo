package kyo

import kyo.internal.ionbinary.IonBinaryReader
import kyo.internal.ionbinary.IonBinaryWriter

/** Amazon Ion Binary codec instance for codec-polymorphic schema APIs.
  *
  * Ion Binary is a self-describing binary encoding for Ion values. This entry point exposes the public binary codec surface.
  */
final class IonBinary extends Codec:
    def newWriter(): Codec.Writer = IonBinaryWriter()

    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        IonBinaryReader(input)

    override def mediaType: String = "application/vnd.amazon.ion"
end IonBinary

/** Primary entry point for Amazon Ion Binary serialization.
  *
  * Ion Binary is a self-describing binary encoding for Ion values: case classes become structs, collections become lists, maps become
  * structs, byte spans become blobs, and options or maybes become Ion nulls when absent, mirroring [[Ion]] text encoding but as compact
  * binary bytes rather than text. `encode`/`decode` work on `Span[Byte]`; `encodeBytes`/`decodeBytes` are aliases of the same
  * operations, named for symmetry with other byte-oriented codecs.
  *
  * The companion provides a default `given IonBinary` instance and default decode-limit values (`DefaultMaxDepth`,
  * `DefaultMaxCollectionSize`) shared with [[Ion]]; `decode`/`decodeBytes` accept explicit overrides of either limit per call.
  *
  * `IonBinary` is also reachable through [[Ion.Config]]: constructing an `Ion` instance with `Ion.Config(format = Ion.Format.Binary)`
  * routes `Ion`'s own encode/decode helpers through the same internal writer and reader this codec uses, so code that already depends on
  * the generic `Ion` codec value can switch to binary wire format without depending on `IonBinary` directly.
  *
  * @see
  *   [[kyo.Ion]] for the codec-polymorphic Ion entry point covering both text and binary format
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  */
object IonBinary:
    inline val DefaultMaxDepth = Ion.DefaultMaxDepth

    inline val DefaultMaxCollectionSize = Ion.DefaultMaxCollectionSize

    given IonBinary = IonBinary()

    /** Encodes a value of type A to Ion Binary bytes. */
    inline def encode[A](value: A)(using schema: Schema[A], ionBinary: IonBinary, frame: Frame): Span[Byte] =
        val w = ionBinary.newWriter()
        schema.writeTo(value, w)
        w.result()
    end encode

    /** Encodes a value of type A to Ion Binary bytes. Alias of [[encode]]. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], ionBinary: IonBinary, frame: Frame): Span[Byte] =
        encode[A](value)
    end encodeBytes

    /** Decodes Ion Binary bytes into a value of type A. */
    def decode[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ionBinary: IonBinary, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = ionBinary.newReader(input)
            reader.resetLimits(maxDepth, maxCollectionSize)
            schema.readFrom(reader)
        }
    end decode

    /** Decodes Ion Binary bytes into a value of type A. Alias of [[decode]]. */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ionBinary: IonBinary, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, maxDepth, maxCollectionSize)
    end decodeBytes

end IonBinary
