package kyo

/** Amazon Ion codec instance for codec-polymorphic schema APIs.
  *
  * The companion object provides high-level helpers for Ion text strings, UTF-8 text bytes, and Ion Binary bytes. An `Ion` instance is
  * useful when code works through the generic [[Codec]] or [[Schema.encode]] / [[Schema.decode]] APIs and needs to select Ion as the
  * contextual codec value.
  *
  * Text decoding accepts schema-shaped Ion text and treats Ion annotations as metadata. Text encoding emits plain Ion text by default and
  * can emit captured schema annotations when configured to do so.
  */
final class Ion(val config: Ion.Config = Ion.Config()) extends Codec:
    /** Creates an Ion writer for this instance's configured format. */
    def newWriter(): Codec.Writer =
        config.format match
            case Ion.Format.Text   => kyo.internal.IonWriter(config)
            case Ion.Format.Binary => kyo.internal.ionbinary.IonBinaryWriter(config)
    end newWriter

    /** Creates an Ion reader over this instance's configured format. */
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        val reader = config.format match
            case Ion.Format.Text   => kyo.internal.IonReader(input)
            case Ion.Format.Binary => IonBinary().newReader(input)
        reader.resetLimits(config.maxDepth, config.maxCollectionSize)
        reader
    end newReader
end Ion

/** Primary entry point for Amazon Ion serialization.
  *
  * Default helpers use Ion text. Case classes become structs, collections become lists, maps become structs, byte spans become blobs, and
  * options or maybes become Ion nulls when absent. Decoding accepts Ion text features that are useful for schema-shaped values, including
  * unquoted field names, comments, type annotations, typed nulls, blobs, symbols as strings, and long strings. Type annotations are
  * treated as Ion metadata and are not preserved in the decoded Scala value.
  *
  * Configured byte helpers can route to Ion Binary through [[Ion.Config]]. String helpers reject binary format because Ion Binary is not a
  * text encoding.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Ion:
    /** Selects the Ion wire format used by configured byte helpers and codec-polymorphic `Ion` instances. */
    enum Format derives CanEqual:
        case Text
        case Binary
    end Format

    /** Controls whether captured schema annotations are emitted as Ion type annotations. */
    enum AnnotationEmissionMode derives CanEqual:
        case Suppress
        case Emit
    end AnnotationEmissionMode

    /** Default maximum nesting depth for structs and lists in Ion decoding. */
    inline val DefaultMaxDepth = Json.DefaultMaxDepth

    /** Default maximum number of entries in any single collection or struct in Ion decoding. */
    inline val DefaultMaxCollectionSize = Json.DefaultMaxCollectionSize

    /** Wire-shape and decode-limit configuration for the Ion codec.
      *
      * @param format
      *   the Ion wire format to use
      * @param maxDepth
      *   maximum nesting depth for structs and lists when decoding through configured helpers
      * @param maxCollectionSize
      *   maximum number of entries in a single collection or struct when decoding through configured helpers
      * @param annotationEmissionMode
      *   controls whether Ion writers emit captured schema annotations as Ion type annotations
      */
    final case class Config(
        format: Ion.Format = Ion.Format.Text,
        maxDepth: Int = Ion.DefaultMaxDepth,
        maxCollectionSize: Int = Ion.DefaultMaxCollectionSize,
        annotationEmissionMode: Ion.AnnotationEmissionMode = Ion.AnnotationEmissionMode.Suppress
    ) derives CanEqual

    object Config:
        val Default: Config = Config()

        given Config = Default
    end Config

    given Ion = Ion()

    /** Generates an Ion Schema Language document for type `A`. */
    inline def ionSchema[A](config: IonSchema.Config = IonSchema.Config.Default)(using schema: Schema[A]): IonSchema =
        IonSchema.fromSchema(schema, config)
    end ionSchema

    /** Generates an Ion Schema Language document for type `A` and encodes it as ISL text. */
    inline def ionSchemaString[A](config: IonSchema.Config = IonSchema.Config.Default)(using schema: Schema[A]): String =
        IonSchema.encode(IonSchema.fromSchema(schema, config))
    end ionSchemaString

    /** Encodes a value of type A to an Ion text string.
      *
      * @param value
      *   the value to encode
      * @return
      *   the Ion text representation
      */
    inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String =
        encodeText[A](value)
    end encode

    /** Encodes a value of type A to raw UTF-8 Ion text bytes.
      *
      * @param value
      *   the value to encode
      * @return
      *   the Ion text bytes
      */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        encodeTextBytes[A](value)
    end encodeBytes

    /** Encodes a value of type A to an Ion text string. */
    inline def encodeText[A](value: A)(using schema: Schema[A], frame: Frame): String =
        val w = Ion(Config.Default).newWriter()
        schema.writeTo(value, w)
        w.resultString
    end encodeText

    /** Encodes a value of type A to raw UTF-8 Ion text bytes. */
    inline def encodeTextBytes[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = Ion(Config.Default).newWriter()
        schema.writeTo(value, w)
        w.result()
    end encodeTextBytes

    /** Encodes a value of type A according to a configured Ion format.
      *
      * Text format returns an Ion text string. Binary format raises a schema exception because arbitrary Ion Binary cannot be represented
      * as a string.
      */
    inline def encode[A](value: A, config: Ion.Config)(using schema: Schema[A], frame: Frame): String =
        config.format match
            case Format.Text =>
                val w = Ion(config.copy(format = Format.Text)).newWriter()
                schema.writeTo(value, w)
                w.resultString
            case Format.Binary => rejectBinaryStringEncode()
    end encode

    /** Encodes a value of type A according to a configured Ion format. */
    inline def encodeBytes[A](value: A, config: Ion.Config)(using schema: Schema[A], frame: Frame): Span[Byte] =
        config.format match
            case Format.Text =>
                val w = Ion(config.copy(format = Format.Text)).newWriter()
                schema.writeTo(value, w)
                w.result()
            case Format.Binary =>
                val w = Ion(config).newWriter()
                schema.writeTo(value, w)
                w.result()
    end encodeBytes

    /** Encodes a value of type A to Ion Binary bytes. */
    inline def encodeBinary[A](value: A)(using schema: Schema[A], ionBinary: IonBinary, frame: Frame): Span[Byte] =
        IonBinary.encode[A](value)
    end encodeBinary

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
        decodeText[A](input, maxDepth, maxCollectionSize)
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
        decodeTextBytes[A](input, maxDepth, maxCollectionSize)
    end decodeBytes

    /** Decodes an Ion text string into a value of type A. */
    def decodeText[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ion: Ion, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = Ion(Config.Default).newReader(Span.from(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        Codec.readFully[A](reader, maxDepth, maxCollectionSize)
    end decodeText

    /** Decodes raw UTF-8 Ion text bytes into a value of type A. */
    def decodeTextBytes[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using ion: Ion, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = Ion(Config.Default).newReader(input)
        Codec.readFully[A](reader, maxDepth, maxCollectionSize)
    end decodeTextBytes

    /** Decodes a configured Ion string input. Binary format requires byte input and returns a typed decode failure. */
    def decode[A](
        input: String,
        config: Ion.Config
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        config.format match
            case Format.Text =>
                given Ion = Ion(config.copy(format = Format.Text))
                decodeText[A](input, config.maxDepth, config.maxCollectionSize)
            case Format.Binary =>
                Result.fail(ParseException(
                    Ion(config),
                    input,
                    "a value; Ion Binary requires Span[Byte] input, use Ion.decodeBytes(bytes, config) or IonBinary.decode"
                ))
    end decode

    /** Decodes configured Ion bytes into a value of type A. */
    def decode[A](
        input: Span[Byte],
        config: Ion.Config
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        config.format match
            case Format.Text =>
                given Ion = Ion(config.copy(format = Format.Text))
                decodeTextBytes[A](input, config.maxDepth, config.maxCollectionSize)
            case Format.Binary =>
                given IonBinary = IonBinary()
                IonBinary.decode[A](input, config.maxDepth, config.maxCollectionSize)
    end decode

    /** Decodes configured Ion bytes into a value of type A. */
    def decodeBytes[A](
        input: Span[Byte],
        config: Ion.Config
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, config)
    end decodeBytes

    /** Decodes Ion Binary bytes into a value of type A. */
    def decodeBinary[A](
        input: Span[Byte],
        maxDepth: Int = IonBinary.DefaultMaxDepth,
        maxCollectionSize: Int = IonBinary.DefaultMaxCollectionSize
    )(using ionBinary: IonBinary, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        IonBinary.decode[A](input, maxDepth, maxCollectionSize)
    end decodeBinary

    private inline def rejectBinaryStringEncode[A]()(using Frame): A =
        throw SchemaNotSerializableException(
            "Ion Binary cannot be returned as String. Use Ion.encodeBytes, Ion.encodeBinary, or IonBinary.encode"
        )
    end rejectBinaryStringEncode

end Ion
