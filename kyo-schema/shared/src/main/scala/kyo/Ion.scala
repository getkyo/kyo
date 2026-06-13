package kyo

/** Amazon Ion text codec instance for codec-polymorphic schema APIs.
  *
  * The companion object provides high-level helpers for Ion text strings and UTF-8 bytes. An `Ion` instance is useful when code works
  * through the generic [[Codec]] or [[Schema.encode]] / [[Schema.decode]] APIs and needs to select Ion as the contextual codec value.
  *
  * Decoding accepts schema-shaped Ion text and treats Ion annotations as metadata. Encoding emits plain Ion text for the schema value,
  * without preserving or synthesizing annotations.
  */
final class Ion extends Codec:
    /** Creates an Ion text writer. */
    def newWriter(): Codec.Writer = kyo.internal.IonWriter()

    /** Creates an Ion text reader over UTF-8 input bytes. */
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.IonReader(input)
end Ion

/** Primary entry point for Amazon Ion text serialization.
  *
  * Encoding uses Ion text. Case classes become structs, collections become lists, maps become structs, byte spans become blobs, and
  * options or maybes become Ion nulls when absent. Decoding accepts Ion text features that are useful for schema-shaped values, including
  * unquoted field names, comments, type annotations, typed nulls, blobs, symbols as strings, and long strings. Type annotations are
  * treated as Ion metadata and are not preserved in the decoded Scala value.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Ion:
    /** Default maximum nesting depth for structs and lists in Ion decoding. */
    val DefaultMaxDepth: Int = Json.DefaultMaxDepth

    /** Default maximum number of entries in any single collection or struct in Ion decoding. */
    val DefaultMaxCollectionSize: Int = Json.DefaultMaxCollectionSize

    given Ion = Ion()

    /** Encodes a value of type A to an Ion text string.
      *
      * @param value
      *   the value to encode
      * @return
      *   the Ion text representation
      */
    inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String =
        val w = summon[Ion].newWriter()
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A to raw UTF-8 Ion text bytes.
      *
      * @param value
      *   the value to encode
      * @return
      *   the Ion text bytes
      */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = summon[Ion].newWriter()
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Decodes an Ion text string into a value of type A.
      *
      * @param input
      *   the Ion text string to decode
      * @param maxDepth
      *   maximum nesting depth for structs and lists
      * @param maxCollectionSize
      *   maximum number of entries in a single collection or struct
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ion: Ion, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = ion.newReader(Span.from(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decode

    /** Decodes raw UTF-8 Ion text bytes into a value of type A.
      *
      * @param input
      *   the raw UTF-8 Ion text bytes
      * @param maxDepth
      *   maximum nesting depth for structs and lists
      * @param maxCollectionSize
      *   maximum number of entries in a single collection or struct
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ion: Ion, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = ion.newReader(input)
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decodeBytes

end Ion
