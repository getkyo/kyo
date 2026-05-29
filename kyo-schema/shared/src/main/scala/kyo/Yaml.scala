package kyo

import java.nio.charset.StandardCharsets

final class Yaml(writerConfig: Yaml.WriterConfig = Yaml.WriterConfig.Default) extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.YamlWriter(writerConfig)
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.YamlReader(input)
end Yaml

/** Primary entry point for YAML 1.2 serialization, parsing, and visiting.
  *
  * YAML decoding is event-first: the parser drives a [[Yaml.Visitor]] and does not require constructing a YAML node tree. The schema
  * decoder uses that same event stream and adapts it to kyo-schema's existing codec reader machinery.
  *
  * @see
  *   [[kyo.Json]] for JSON serialization
  * @see
  *   [[kyo.Schema]] for type-driven serialization
  */
object Yaml:
    val DefaultMaxDepth: Int          = Json.DefaultMaxDepth
    val DefaultMaxCollectionSize: Int = Json.DefaultMaxCollectionSize

    given Yaml = Yaml()

    /** YAML language version used for schema-level scalar resolution.
      *
      * The parser accepts the YAML syntax supported by this module independently of this setting. `SpecVersion` controls how plain scalars
      * and standard scalar tags are interpreted while adapting YAML events into kyo-schema readers, and how the writer decides which
      * strings are ambiguous enough to quote.
      *
      * `Yaml12` is the default because YAML 1.2 Core schema avoids legacy implicit booleans such as `NO`, `yes`, `on`, and `off`. `Yaml11`
      * is available for systems that still use YAML 1.1 resolution, including legacy booleans, leading-zero octal integers, binary
      * integers, sexagesimal numbers, and underscores inside numeric scalars.
      */
    enum SpecVersion derives CanEqual:
        case Yaml12, Yaml11
    end SpecVersion

    /** Zero-based selector for one YAML document within a stream.
      *
      * YAML streams can contain multiple documents separated by `---` or `...`. `DocumentIndex` lets callers select one of those
      * documents while keeping that selection distinct from parser limits such as `maxDepth`. The first document is
      * `DocumentIndex(0)`, the second is `DocumentIndex(1)`, and so on.
      */
    opaque type DocumentIndex = Int

    object DocumentIndex:
        def apply(value: Int): DocumentIndex = value
    end DocumentIndex

    extension (index: DocumentIndex) def value: Int = index

    /** Decoding settings for YAML input.
      *
      * `ReaderConfig` keeps parser limits and stream selection in one value so callers can choose a document from a multi-document stream
      * without losing access to safety limits. `yamlVersion` selects scalar resolution rules for schema decoding. `maxDepth` limits nested
      * codec reads, and `maxCollectionSize` limits collection entries in the same way as [[Json]] decoding. `documentIndex` is empty for
      * single-document decoding and set to `DocumentIndex(n)` to decode the zero-based nth document from a stream. The default
      * configuration decodes exactly one YAML 1.2 document with the standard limits.
      */
    case class ReaderConfig(
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize,
        documentIndex: Maybe[DocumentIndex] = Absent,
        yamlVersion: SpecVersion = SpecVersion.Yaml12
    ) derives CanEqual

    object ReaderConfig:
        val Default: ReaderConfig = ReaderConfig()
        given ReaderConfig        = Default
    end ReaderConfig

    /** Encoding settings for YAML output.
      *
      * `WriterConfig` controls the YAML surface syntax produced by schema encoding without changing the value being written. The default
      * profile favors readable, round-trip-friendly block YAML: collections use indentation, ambiguous strings are quoted, and multiline
      * strings use block scalars.
      *
      * The config can also select flow output for compact documents, JSON-compatible flow output for speed-oriented processing, quote and
      * multiline scalar styles, document markers, indentation width, trailing newline behavior, special floating-point rendering, and the
      * YAML version used when deciding which strings are ambiguous. Profiles in the companion provide named starting points for common
      * tradeoffs: readability, compactness, smaller payloads, and fast downstream processing.
      *
      * IMPORTANT: `ScalarQuoting.WhenNeeded` can emit plain strings such as `true` or `0x3A` without quotes. Use the default
      * `QuoteAmbiguous` behavior when encoded YAML should decode back to strings under the YAML Core schema.
      */
    case class WriterConfig(
        collectionStyle: WriterConfig.CollectionStyle = WriterConfig.CollectionStyle.Block,
        sequenceMappingStyle: WriterConfig.SequenceMappingStyle = WriterConfig.SequenceMappingStyle.Indented,
        indent: Int = 2,
        scalarQuoting: WriterConfig.ScalarQuoting = WriterConfig.ScalarQuoting.QuoteAmbiguous,
        quoteStyle: WriterConfig.QuoteStyle = WriterConfig.QuoteStyle.Double,
        multilineStyle: WriterConfig.MultilineStyle = WriterConfig.MultilineStyle.Literal,
        chomping: WriterConfig.Chomping = WriterConfig.Chomping.Preserve,
        documentMarkers: WriterConfig.DocumentMarkers = WriterConfig.DocumentMarkers.None,
        trailingNewline: Boolean = true,
        specialFloatStyle: WriterConfig.SpecialFloatStyle = WriterConfig.SpecialFloatStyle.YamlCore,
        yamlVersion: SpecVersion = SpecVersion.Yaml12
    ) derives CanEqual

    object WriterConfig:
        enum CollectionStyle derives CanEqual:
            case Block, Flow, JsonCompatibleFlow
        end CollectionStyle

        enum SequenceMappingStyle derives CanEqual:
            case Compact, Indented
        end SequenceMappingStyle

        enum ScalarQuoting derives CanEqual:
            case WhenNeeded, QuoteAmbiguous, QuoteAllStrings
        end ScalarQuoting

        enum QuoteStyle derives CanEqual:
            case Double, Single
        end QuoteStyle

        enum MultilineStyle derives CanEqual:
            case Literal, Folded, DoubleQuoted
        end MultilineStyle

        enum Chomping derives CanEqual:
            case Clip, Strip, Keep, Preserve
        end Chomping

        enum DocumentMarkers derives CanEqual:
            case None, Start, StartAndEnd
        end DocumentMarkers

        enum SpecialFloatStyle derives CanEqual:
            case YamlCore, QuotedJsonCompatible
        end SpecialFloatStyle

        val Readable: WriterConfig = WriterConfig()

        val Compact: WriterConfig =
            WriterConfig(sequenceMappingStyle = SequenceMappingStyle.Compact)

        val Small: WriterConfig =
            WriterConfig(
                collectionStyle = CollectionStyle.Flow,
                sequenceMappingStyle = SequenceMappingStyle.Compact,
                multilineStyle = MultilineStyle.DoubleQuoted,
                trailingNewline = false
            )

        val Fast: WriterConfig =
            WriterConfig(
                collectionStyle = CollectionStyle.JsonCompatibleFlow,
                sequenceMappingStyle = SequenceMappingStyle.Compact,
                scalarQuoting = ScalarQuoting.QuoteAllStrings,
                multilineStyle = MultilineStyle.DoubleQuoted,
                specialFloatStyle = SpecialFloatStyle.QuotedJsonCompatible,
                trailingNewline = false
            )

        val Default: WriterConfig = Readable
        given WriterConfig        = Default
    end WriterConfig

    /** Source position in the YAML stream. */
    case class Mark(index: Int, line: Int, column: Int) derives CanEqual

    /** Node metadata carried by collections. */
    case class Meta(anchor: Maybe[String], tag: Maybe[String], mark: Mark) derives CanEqual

    /** Scalar style as written in the YAML stream. */
    enum ScalarStyle derives CanEqual:
        case Plain, SingleQuoted, DoubleQuoted, Literal, Folded
    end ScalarStyle

    /** Metadata carried by scalar nodes. */
    case class ScalarMeta(anchor: Maybe[String], tag: Maybe[String], style: ScalarStyle, mark: Mark) derives CanEqual

    /** Optional YAML node tree built only by [[parse]] and [[parseAll]]. */
    enum Node derives CanEqual:
        case Mapping(entries: Chunk[(Node, Node)], meta: Meta)
        case Sequence(elements: Chunk[Node], meta: Meta)
        case Scalar(value: String, meta: ScalarMeta)
        case Alias(name: String, mark: Mark)
    end Node

    /** Event consumer for YAML parsing.
      *
      * The parser passes caller-owned context through every callback. Returning [[Result.Failure]] stops parsing immediately and returns
      * that error to the caller.
      */
    trait Visitor[Ctx, Err, A]:
        def streamStart(context: Ctx, mark: Mark): Result[Err, Ctx]
        def documentStart(context: Ctx, mark: Mark): Result[Err, Ctx]
        def mappingStart(context: Ctx, meta: Meta): Result[Err, Ctx]
        def sequenceStart(context: Ctx, meta: Meta): Result[Err, Ctx]
        def scalar(context: Ctx, value: String, meta: ScalarMeta): Result[Err, Ctx]
        def alias(context: Ctx, name: String, mark: Mark): Result[Err, Ctx]
        def nodeEnd(context: Ctx, mark: Mark): Result[Err, Ctx]
        def documentEnd(context: Ctx, mark: Mark): Result[Err, Ctx]
        def streamEnd(context: Ctx, mark: Mark): Result[Err, A]
    end Visitor

    /** Visits a YAML stream without constructing a YAML DOM. */
    def visit[Ctx, Err, A](input: String, context: Ctx)(visitor: Visitor[Ctx, Err, A])(using Frame): Result[Err | DecodeException, A] =
        internal.YamlParser(input).visit(context)(visitor)

    /** Visits one document from a YAML stream without constructing a YAML DOM. */
    def visit[Ctx, Err, A](
        input: String,
        documentIndex: DocumentIndex,
        context: Ctx
    )(visitor: Visitor[Ctx, Err, A])(using Frame): Result[Err | DecodeException, A] =
        selectDocument(input, documentIndex).flatMap(doc => visit(doc, context)(visitor))
    end visit

    /** Parses a single YAML document into an optional DOM node tree. */
    def parse(input: String)(using Frame): Result[DecodeException, Node] =
        internal.YamlParser(input).visit(())(NodeBuilder())

    /** Parses one document from a YAML stream into an optional DOM node tree. */
    def parse(input: String, documentIndex: DocumentIndex)(using Frame): Result[DecodeException, Node] =
        selectDocument(input, documentIndex).flatMap(parse)
    end parse

    /** Parses an explicit YAML document stream into node trees. */
    def parseAll(input: String)(using Frame): Result[DecodeException, Chunk[Node]] =
        parseEach(input)(parse)

    /** Encodes a value of type A as YAML. */
    inline def encode[A](value: A)(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): String =
        val w = internal.YamlWriter(writerConfig)
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A as YAML using explicit writer configuration. */
    inline def encode[A](value: A, writerConfig: WriterConfig)(using schema: Schema[A], frame: Frame): String =
        val w = internal.YamlWriter(writerConfig)
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A as UTF-8 YAML bytes. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): Span[Byte] =
        val w = internal.YamlWriter(writerConfig)
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Encodes a value of type A as UTF-8 YAML bytes using explicit writer configuration. */
    inline def encodeBytes[A](value: A, writerConfig: WriterConfig)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = internal.YamlWriter(writerConfig)
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Decodes a single YAML document into a value of type A. */
    def decode[A](
        input: String
    )(using yaml: Yaml, schema: Schema[A], config: ReaderConfig, frame: Frame): Result[DecodeException, A] =
        decode(input, config)
    end decode

    /** Decodes a single YAML document into a value of type A with explicit limits. */
    def decode[A](
        input: String,
        maxDepth: Int,
        maxCollectionSize: Int
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode(input, ReaderConfig(maxDepth, maxCollectionSize))
    end decode

    /** Decodes YAML into a value of type A using explicit configuration. */
    def decode[A](
        input: String,
        config: ReaderConfig
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        config.documentIndex match
            case Present(index) =>
                selectDocument(input, index).flatMap(doc => decode[A](doc, config.copy(documentIndex = Absent)))
            case Absent =>
                decodeBytes(Span.from(input.getBytes(StandardCharsets.UTF_8)), config)
        end match
    end decode

    /** Decodes UTF-8 YAML bytes into a value of type A. */
    def decodeBytes[A](
        input: Span[Byte]
    )(using yaml: Yaml, schema: Schema[A], config: ReaderConfig, frame: Frame): Result[DecodeException, A] =
        decodeBytes(input, config)
    end decodeBytes

    /** Decodes UTF-8 YAML bytes into a value of type A with explicit limits. */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decodeBytes(input, ReaderConfig(maxDepth, maxCollectionSize))
    end decodeBytes

    /** Decodes UTF-8 YAML bytes into a value of type A using explicit configuration. */
    def decodeBytes[A](
        input: Span[Byte],
        config: ReaderConfig
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        config.documentIndex match
            case Present(index) =>
                decode[A](String(input.toArray, StandardCharsets.UTF_8), config.copy(documentIndex = Maybe(index)))
            case Absent =>
                decodePreparedBytes(input, config.maxDepth, config.maxCollectionSize, config.yamlVersion)
        end match
    end decodeBytes

    private def decodePreparedBytes[A](
        input: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int,
        yamlVersion: SpecVersion
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = internal.YamlReader(input, yamlVersion)
            reader.resetLimits(maxDepth, maxCollectionSize)
            schema.readFrom(reader)
        }
    end decodePreparedBytes

    /** Decodes one document from a YAML stream into a value of type A. */
    def decode[A](
        input: String,
        documentIndex: DocumentIndex
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, documentIndex, DefaultMaxDepth, DefaultMaxCollectionSize)
    end decode

    /** Decodes one document from a YAML stream into a value of type A with explicit limits. */
    def decode[A](
        input: String,
        documentIndex: DocumentIndex,
        maxDepth: Int,
        maxCollectionSize: Int
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode[A](input, ReaderConfig(maxDepth, maxCollectionSize, Maybe(documentIndex)))
    end decode

    /** Decodes one document from UTF-8 YAML bytes into a value of type A. */
    def decodeBytes[A](
        input: Span[Byte],
        documentIndex: DocumentIndex
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decodeBytes[A](input, documentIndex, DefaultMaxDepth, DefaultMaxCollectionSize)
    end decodeBytes

    /** Decodes one document from UTF-8 YAML bytes into a value of type A with explicit limits. */
    def decodeBytes[A](
        input: Span[Byte],
        documentIndex: DocumentIndex,
        maxDepth: Int,
        maxCollectionSize: Int
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decodeBytes[A](input, ReaderConfig(maxDepth, maxCollectionSize, Maybe(documentIndex)))
    end decodeBytes

    /** Decodes every document in an explicit YAML stream. */
    def decodeAll[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, Chunk[A]] =
        parseEach(input)(doc => decode[A](doc, maxDepth, maxCollectionSize))
    end decodeAll

    private def parseEach[A](input: String)(parseOne: String => Result[DecodeException, A])(using
        Frame
    ): Result[DecodeException, Chunk[A]] =
        val docs = splitDocuments(input)
        def loop(remaining: List[String], acc: Chunk[A]): Result[DecodeException, Chunk[A]] =
            remaining match
                case Nil => Result.succeed(acc)
                case doc :: tail =>
                    parseOne(doc) match
                        case Result.Success(value) => loop(tail, acc :+ value)
                        case Result.Failure(e)     => Result.fail(e)
                        case Result.Panic(e)       => Result.panic(e)
        loop(docs.toList, Chunk.empty)
    end parseEach

    private def selectDocument(input: String, documentIndex: DocumentIndex)(using Frame): Result[DecodeException, String] =
        val docs = splitDocuments(input)
        if documentIndex >= 0 && documentIndex < docs.size then
            Result.succeed(docs(documentIndex))
        else
            Result.fail(ParseException(
                Yaml(),
                "",
                s"YAML document index $documentIndex is out of range; found ${docs.size} document(s)",
                Nil,
                0
            ))
        end if
    end selectDocument

    private def splitDocuments(input: String): Chunk[String] =
        internal.YamlDocuments.split(input)
    end splitDocuments

    sealed private trait NodeFrame
    final private class MappingFrame(val meta: Meta, var entries: Chunk[(Node, Node)], var pendingKey: Maybe[Node]) extends NodeFrame
    final private class SequenceFrame(val meta: Meta, var elements: Chunk[Node])                                    extends NodeFrame

    final private class NodeBuilder(using Frame) extends Visitor[Unit, DecodeException, Node]:
        private var root: Maybe[Node]      = Absent
        private var stack: List[NodeFrame] = Nil

        def streamStart(context: Unit, mark: Mark): Result[DecodeException, Unit]   = Result.unit
        def documentStart(context: Unit, mark: Mark): Result[DecodeException, Unit] = Result.unit
        def documentEnd(context: Unit, mark: Mark): Result[DecodeException, Unit]   = Result.unit

        def mappingStart(context: Unit, meta: Meta): Result[DecodeException, Unit] =
            stack = MappingFrame(meta, Chunk.empty, Absent) :: stack
            Result.unit

        def sequenceStart(context: Unit, meta: Meta): Result[DecodeException, Unit] =
            stack = SequenceFrame(meta, Chunk.empty) :: stack
            Result.unit

        def scalar(context: Unit, value: String, meta: ScalarMeta): Result[DecodeException, Unit] =
            addNode(Node.Scalar(value, meta))

        def alias(context: Unit, name: String, mark: Mark): Result[DecodeException, Unit] =
            addNode(Node.Alias(name, mark))

        def nodeEnd(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            stack match
                case (f: MappingFrame) :: rest =>
                    stack = rest
                    addNode(Node.Mapping(f.entries, f.meta))
                case (f: SequenceFrame) :: rest =>
                    stack = rest
                    addNode(Node.Sequence(f.elements, f.meta))
                case Nil =>
                    Result.fail(ParseException(Yaml(), "", "Unexpected YAML node end", Nil, mark.index))
            end match
        end nodeEnd

        def streamEnd(context: Unit, mark: Mark): Result[DecodeException, Node] =
            root match
                case Present(node) => Result.succeed(node)
                case Absent        => Result.fail(ParseException(Yaml(), "", "Expected a YAML document", Nil, mark.index))
        end streamEnd

        private def addNode(node: Node): Result[DecodeException, Unit] =
            stack match
                case (f: MappingFrame) :: _ =>
                    f.pendingKey match
                        case Present(key) =>
                            f.entries = f.entries :+ (key -> node)
                            f.pendingKey = Absent
                        case Absent =>
                            f.pendingKey = Maybe(node)
                    end match
                    Result.unit
                case (f: SequenceFrame) :: _ =>
                    f.elements = f.elements :+ node
                    Result.unit
                case Nil =>
                    root = Maybe(node)
                    Result.unit
            end match
        end addNode
    end NodeBuilder

end Yaml
