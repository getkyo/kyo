package kyo

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.annotation.targetName

/** YAML codec instance for codec-polymorphic schema APIs.
  *
  * The companion object provides the usual high-level helpers for YAML strings and UTF-8 bytes. A `Yaml` instance is useful when code works
  * through the generic [[Codec]] or [[Schema.encode]] / [[Schema.decode]] APIs and needs to carry writer configuration in the contextual
  * codec value.
  *
  * Reader configuration is supplied by the companion decode helpers. Codec-generic reads use the direct YAML reader with YAML 1.2 scalar
  * resolution and the standard safety limits.
  */
final class Yaml(writerConfig: Yaml.WriterConfig = Yaml.WriterConfig.Default) extends Codec:
    /** Creates a YAML writer using this codec instance's writer configuration. */
    def newWriter(): Codec.Writer = kyo.internal.YamlWriter(writerConfig)

    /** Creates a direct YAML reader over UTF-8 input bytes. */
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.YamlReader(input)
end Yaml

/** Primary entry point for YAML 1.2 serialization, parsing, and visiting.
  *
  * YAML parsing is event-first: [[Yaml.Events]] exposes stream events without requiring construction of a YAML node tree. Schema decoding
  * uses a direct YAML [[Codec.Reader]] path, so ordinary decode does not require a YAML DOM, a whole-document event tape, or a JSON bridge.
  *
  * @see
  *   [[kyo.Json]] for JSON serialization
  * @see
  *   [[kyo.Schema]] for type-driven serialization
  */
object Yaml:
    /** Default maximum nesting depth for YAML schema decoding. */
    val DefaultMaxDepth: Int = Json.DefaultMaxDepth

    /** Default maximum number of entries in a decoded YAML collection or mapping. */
    val DefaultMaxCollectionSize: Int = Json.DefaultMaxCollectionSize

    /** Default YAML codec instance for generic schema encoding and decoding. */
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
        /** YAML 1.2 Core scalar resolution. */
        case Yaml12

        /** YAML 1.1 legacy scalar resolution for older YAML-consuming systems. */
        case Yaml11
    end SpecVersion

    /** Zero-based selector for one YAML document within a stream.
      *
      * YAML streams can contain multiple documents separated by `---` or `...`. `DocumentIndex` lets callers select one of those
      * documents while keeping that selection distinct from parser limits such as `maxDepth`. The first document is
      * `DocumentIndex(0)`, the second is `DocumentIndex(1)`, and so on.
      */
    opaque type DocumentIndex = Int

    /** Constructors and accessors for [[DocumentIndex]] values.
      *
      * `DocumentIndex` is intentionally an opaque type so APIs that select a YAML document cannot accidentally be confused with parser
      * depth or collection-size limits.
      */
    object DocumentIndex:
        /** Creates a zero-based document selector. */
        def apply(value: Int): DocumentIndex = value
    end DocumentIndex

    extension (index: DocumentIndex)
        /** Returns the zero-based integer value of this document selector. */
        def value: Int = index

    /** YAML anchor name.
      *
      * Anchors name a node so aliases can refer to it later in the same YAML document. `Anchor` is an opaque string type to keep anchor
      * metadata distinct from ordinary strings while remaining allocation-free.
      */
    opaque type Anchor = String

    /** Constructors and extractors for [[Anchor]] values. */
    object Anchor:
        /** Creates an anchor from its YAML source name without the leading `&`. */
        def apply(value: String): Anchor = value

        /** Extracts the raw YAML anchor name. */
        def unapply(anchor: Anchor): Some[String] = Some(anchor)

        given CanEqual[Anchor, Anchor] = CanEqual.derived
    end Anchor

    extension (anchor: Anchor)
        /** Returns the raw YAML anchor name without the leading `&`. */
        @targetName("anchorValue")
        def value: String = anchor
    end extension

    /** YAML tag handle.
      *
      * Tags annotate a YAML node with application-specific or standard YAML type information. `Tag` is an opaque string type to keep tag
      * metadata distinct from ordinary strings while remaining allocation-free.
      */
    opaque type Tag = String

    /** Constructors and extractors for [[Tag]] values. */
    object Tag:
        /** Creates a tag from its YAML source spelling, such as `!local`, `!!str`, or `tag:yaml.org,2002:str`. */
        def apply(value: String): Tag = value

        /** Extracts the raw YAML tag spelling. */
        def unapply(tag: Tag): Some[String] = Some(tag)

        given CanEqual[Tag, Tag] = CanEqual.derived
    end Tag

    extension (tag: Tag)
        /** Returns the raw YAML tag spelling. */
        @targetName("tagValue")
        def value: String = tag
    end extension

    /** Decoding settings for YAML input.
      *
      * `ReaderConfig` keeps parser limits and stream selection in one value so callers can choose a document from a multi-document stream
      * without losing access to safety limits. `yamlVersion` selects scalar resolution rules for schema decoding. `maxDepth` limits nested
      * codec reads, and `maxCollectionSize` limits collection entries in the same way as [[Json]] decoding. `documentIndex` is empty for
      * single-document decoding and set to `DocumentIndex(n)` to decode the zero-based nth document from a stream. `documentMode` controls
      * how single-value decoding handles streams when no `documentIndex` is selected. `MergeTopLevelMappings` concatenates non-empty stream
      * documents as fragments of one top-level mapping, which can decode case classes and wrapped ADT variants whose fields are split across
      * documents. A present `documentIndex` always takes precedence over `documentMode`. The default configuration decodes exactly one YAML
      * 1.2 document with the standard limits.
      */
    case class ReaderConfig(
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize,
        documentIndex: Maybe[DocumentIndex] = Absent,
        yamlVersion: SpecVersion = SpecVersion.Yaml12,
        documentMode: ReaderConfig.DocumentMode = ReaderConfig.DocumentMode.SingleDocument
    ) derives CanEqual

    /** Reader configuration defaults and stream handling modes. */
    object ReaderConfig:
        /** How a single schema decode handles a YAML stream when no explicit document index is selected.
          *
          * The normal mode treats multi-document streams as an error for single-value decoding. Merge mode exists for configuration shapes
          * where a top-level case class, sealed trait wrapper, or Scala 3 enum variant is deliberately split across YAML document
          * separators.
          */
        enum DocumentMode derives CanEqual:
            /** Requires the input to contain exactly one YAML document. */
            case SingleDocument

            /** Treats each non-empty document as a fragment of one top-level mapping. */
            case MergeTopLevelMappings
        end DocumentMode

        /** Default YAML reader configuration: YAML 1.2, one document, and standard safety limits. */
        val Default: ReaderConfig = ReaderConfig()

        /** Contextual default reader configuration used by the single-argument decode helpers. */
        given ReaderConfig = Default
    end ReaderConfig

    /** Encoding settings for YAML output.
      *
      * `WriterConfig` controls the YAML surface syntax produced by schema encoding without changing the value being written. The default
      * profile favors readable, round-trip-friendly block YAML: collections use indentation, ambiguous strings are quoted, and multiline
      * strings use block scalars.
      *
      * The config can also select flow output for compact documents, JSON-like flow output for speed-oriented processing, quote and
      * multiline scalar styles, document markers, indentation width, trailing newline behavior, special floating-point rendering, and the
      * YAML version used when deciding which strings are ambiguous. YAML-specific values such as non-finite floats may still use YAML
      * syntax and tags. Profiles in the companion provide named starting points for common tradeoffs: readability, compactness, smaller
      * payloads, and fast downstream processing.
      *
      * The companion `Yaml.encode` and `Yaml.encodeBytes` helpers use a contextual `WriterConfig`, while generic
      * `Schema.encodeString[Yaml]` and codec-polymorphic paths use the contextual `Yaml` instance's writer configuration.
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

    /** Writer configuration profiles and option enums. */
    object WriterConfig:
        /** Collection layout used for mappings and sequences.
          *
          * Block style favors human editing. Flow style favors compactness. JSON-compatible flow style is useful when downstream
          * processors are optimized for JSON-like YAML.
          */
        enum CollectionStyle derives CanEqual:
            /** Writes indented block-style YAML collections. */
            case Block

            /** Writes YAML flow-style collections such as `[a, b]` and `{k: v}`. */
            case Flow

            /** Writes flow collections using JSON-compatible punctuation and scalar quoting. */
            case JsonCompatibleFlow
        end CollectionStyle

        /** Layout used when a sequence element is itself a mapping.
          *
          * This setting only affects block-style sequences whose elements are mappings. Compact output starts the first field immediately
          * after `-`; indented output places every field under the sequence marker.
          */
        enum SequenceMappingStyle derives CanEqual:
            /** Writes the first mapping field on the same line as the sequence marker when possible. */
            case Compact

            /** Writes sequence mapping fields on indented child lines. */
            case Indented
        end SequenceMappingStyle

        /** Policy for quoting string scalars.
          *
          * Quoting has correctness implications because YAML decoders can implicitly resolve plain strings as booleans, nulls, integers,
          * floats, or legacy YAML 1.1 values. The default quotes ambiguous strings so schema round trips preserve Scala `String` values.
          */
        enum ScalarQuoting derives CanEqual:
            /** Quotes only strings that cannot be emitted as valid plain YAML scalars. */
            case WhenNeeded

            /** Quotes strings that could be implicitly resolved as non-string YAML scalars. */
            case QuoteAmbiguous

            /** Quotes every string scalar. */
            case QuoteAllStrings
        end ScalarQuoting

        /** Quote style used when a string scalar is quoted.
          *
          * Double quotes support YAML escape sequences and are the default. Single quotes are visually quieter for ordinary text and escape
          * embedded single quotes by doubling them.
          */
        enum QuoteStyle derives CanEqual:
            /** Uses double-quoted YAML strings with escape sequences. */
            case Double

            /** Uses single-quoted YAML strings with doubled single quote escaping. */
            case Single
        end QuoteStyle

        /** Encoding style used for strings containing line breaks.
          *
          * Literal and folded styles emit YAML block scalars. Double-quoted style keeps multiline values inside ordinary scalar syntax and
          * is used by the compact JSON-compatible writer profile.
          */
        enum MultilineStyle derives CanEqual:
            /** Uses literal block scalars, preserving line breaks. */
            case Literal

            /** Uses folded block scalars, folding ordinary line breaks to spaces. */
            case Folded

            /** Uses double-quoted scalars with escaped line breaks. */
            case DoubleQuoted
        end MultilineStyle

        /** Block scalar chomping behavior for trailing line breaks.
          *
          * YAML block scalars distinguish the final line break and any extra trailing line breaks from the main content. Preserve mode
          * chooses the YAML chomping indicator that round trips the exact Scala string.
          */
        enum Chomping derives CanEqual:
            /** Clips the final line break in YAML block scalar form. */
            case Clip

            /** Strips the final line break in YAML block scalar form. */
            case Strip

            /** Keeps trailing line breaks in YAML block scalar form. */
            case Keep

            /** Selects the chomping mode needed to preserve the exact Scala string. */
            case Preserve
        end Chomping

        /** Document marker policy for encoded YAML output.
          *
          * Markers are optional for ordinary single-document YAML, but they can make streams explicit and are sometimes required by tools
          * that concatenate YAML documents.
          */
        enum DocumentMarkers derives CanEqual:
            /** Emits no explicit document markers. */
            case None

            /** Emits an explicit document-start marker. */
            case Start

            /** Emits explicit document-start and document-end markers. */
            case StartAndEnd
        end DocumentMarkers

        /** Rendering style for non-finite floating-point values.
          *
          * YAML Core has native spellings for NaN and infinities. Tagged output is useful with the JSON-compatible flow profile because the
          * scalar text remains quoted while the YAML tag preserves the numeric meaning for this decoder.
          */
        enum SpecialFloatStyle derives CanEqual:
            /** Emits YAML Core special floats such as `.nan` and `.inf`. */
            case YamlCore

            /** Emits tagged YAML Core special floats to preserve JSON-compatible flow output. */
            case TaggedYamlCore
        end SpecialFloatStyle

        /** Readability-oriented profile and the default writer configuration. */
        val Readable: WriterConfig = WriterConfig()

        /** Compact block-style profile that keeps sequence mappings tighter. */
        val Compact: WriterConfig =
            WriterConfig(sequenceMappingStyle = SequenceMappingStyle.Compact)

        /** Size-oriented profile using flow collections and no trailing newline. */
        val Small: WriterConfig =
            WriterConfig(
                collectionStyle = CollectionStyle.Flow,
                sequenceMappingStyle = SequenceMappingStyle.Compact,
                multilineStyle = MultilineStyle.DoubleQuoted,
                trailingNewline = false
            )

        /** Processing-speed-oriented profile using JSON-compatible flow output. */
        val Fast: WriterConfig =
            WriterConfig(
                collectionStyle = CollectionStyle.JsonCompatibleFlow,
                sequenceMappingStyle = SequenceMappingStyle.Compact,
                scalarQuoting = ScalarQuoting.QuoteAllStrings,
                multilineStyle = MultilineStyle.DoubleQuoted,
                specialFloatStyle = SpecialFloatStyle.TaggedYamlCore,
                trailingNewline = false
            )

        /** Concrete default writer profile. */
        val Default: WriterConfig = Readable

        /** Contextual default writer configuration used by single-argument encode helpers. */
        given WriterConfig = Default
    end WriterConfig

    /** Source position in the YAML stream.
      *
      * Marks are attached to YAML events and parser metadata so callers can report application-level errors at the same source position
      * where the YAML parser found a value. The raw `index` is the character offset into the decoded input string; `line` and `column` are
      * intended for display in diagnostics.
      */
    case class Mark(index: Int, line: Int, column: Int) derives CanEqual

    /** Node metadata carried by collection nodes.
      *
      * YAML anchors and tags are exposed as metadata instead of being interpreted by the event parser. Schema decoding honors the standard
      * scalar tags it understands, while unknown and local tags remain available to event handlers and DOM-style parsing as metadata.
      */
    case class Meta(anchor: Maybe[Anchor], tag: Maybe[Tag], mark: Mark) derives CanEqual

    /** Scalar style as written in the YAML stream.
      *
      * The style records YAML surface syntax without changing the scalar value reported to event handlers. It lets callers distinguish plain,
      * quoted, literal, and folded content when they need formatting-aware behavior.
      */
    enum ScalarStyle derives CanEqual:
        /** Plain scalar without quote or block indicators. */
        case Plain

        /** Single-quoted scalar. */
        case SingleQuoted

        /** Double-quoted scalar. */
        case DoubleQuoted

        /** Literal block scalar introduced with `|`. */
        case Literal

        /** Folded block scalar introduced with `>`. */
        case Folded
    end ScalarStyle

    /** Metadata carried by scalar nodes.
      *
      * Scalar metadata includes the same anchor, tag, and mark information as collection metadata, plus the scalar's source style. Callers
      * can use the tag and style to implement custom scalar handling without forcing the parser to build a node tree.
      */
    case class ScalarMeta(anchor: Maybe[Anchor], tag: Maybe[Tag], style: ScalarStyle, mark: Mark) derives CanEqual

    /** YAML event protocol for parser, renderer, and tooling pipelines.
      *
      * `Events` is the YAML-specific middle layer between raw parsing and schema values. It preserves document boundaries, collection
      * starts and ends, scalars, aliases, anchors, tags, scalar styles, and source marks without requiring callers to build a [[Node]].
      * Use it for linters, format-aware transforms, anchor/alias audits, and event-to-event processing. Use [[Yaml.decode]] and
      * [[Yaml.encode]] for ordinary schema-value decoding and encoding.
      */
    object Events:

        /** Collection kind for typed collection-end events. */
        enum CollectionKind derives CanEqual:
            /** A YAML mapping collection. */
            case Mapping

            /** A YAML sequence collection. */
            case Sequence
        end CollectionKind

        /** One event in a YAML stream. */
        enum Event derives CanEqual:
            /** Start of the YAML stream. */
            case StreamStart(mark: Mark)

            /** Start of a YAML document. */
            case DocumentStart(mark: Mark)

            /** Start of a YAML mapping, with optional known size. */
            case MappingStart(meta: Meta, size: Maybe[Int] = Absent)

            /** Start of a YAML sequence, with optional known size. */
            case SequenceStart(meta: Meta, size: Maybe[Int] = Absent)

            /** Scalar value and its source metadata. */
            case Scalar(value: String, meta: ScalarMeta)

            /** Alias reference to an anchor declared elsewhere in the document. */
            case Alias(name: Anchor, mark: Mark)

            /** End of a YAML mapping or sequence. */
            case CollectionEnd(kind: CollectionKind, mark: Mark)

            /** End of a YAML document. */
            case DocumentEnd(mark: Mark)

            /** End of the YAML stream. */
            case StreamEnd(mark: Mark)
        end Event

        /** Event handler that threads caller-owned context through a YAML event stream. */
        trait Handler[Ctx, Err]:
            /** Handles one YAML event and returns the next context or a typed error. */
            def event(context: Ctx, event: Event): Result[Err, Ctx]
        end Handler

        /** Event middleware that wraps a downstream handler.
          *
          * Processors can inspect, rewrite, reject, or pass through YAML events. They compose left-to-right with `andThen`, allowing parser
          * events to flow through reusable tooling before they reach a terminal handler such as a collector or renderer.
          */
        trait Processor[Err]:
            self =>

            /** Wraps a downstream handler with this processor. */
            def apply[Ctx](downstream: Handler[Ctx, Err]): Handler[Ctx, Err]

            /** Sends this processor's output to the downstream handler. */
            final def andThen[Ctx](downstream: Handler[Ctx, Err]): Handler[Ctx, Err] =
                self(downstream)

            /** Composes this processor with another processor. */
            final def andThen(next: Processor[Err]): Processor[Err] =
                new Processor[Err]:
                    def apply[Ctx](downstream: Handler[Ctx, Err]): Handler[Ctx, Err] =
                        self(next(downstream))
                end new
            end andThen
        end Processor

        /** Processor constructors for common event transformations. */
        object Processor:
            /** Rewrites scalar values and metadata while passing all other events through unchanged. */
            def mapScalars[Err](
                f: (String, ScalarMeta) => Result[Err, (String, ScalarMeta)]
            ): Processor[Err] =
                new Processor[Err]:
                    def apply[Ctx](downstream: Handler[Ctx, Err]): Handler[Ctx, Err] =
                        new Handler[Ctx, Err]:
                            def event(context: Ctx, event: Event): Result[Err, Ctx] =
                                event match
                                    case Event.Scalar(value, meta) =>
                                        f(value, meta).flatMap { case (nextValue, nextMeta) =>
                                            downstream.event(context, Event.Scalar(nextValue, nextMeta))
                                        }
                                    case other =>
                                        downstream.event(context, other)
                        end new
                    end apply
                end new
            end mapScalars
        end Processor

        /** Terminal event handler that renders YAML events to a YAML string.
          *
          * A renderer is useful when parser events flow through one or more [[Processor]] instances and need to become YAML again without
          * first building a [[Node]]. It uses [[WriterConfig]] for layout, quoting, multiline strings, document markers, and YAML version
          * decisions. Create a fresh renderer for each output, pass it as the terminal handler, then read [[resultString]] after event
          * processing completes.
          */
        final class Renderer private (private val inner: internal.YamlEvents.Renderer) extends Handler[Unit, DecodeException]:

            /** Handles one YAML event for rendering. */
            def event(context: Unit, event: Event): Result[DecodeException, Unit] =
                inner.event(context, event)

            /** Returns the rendered YAML string, finalizing document markers if configured. */
            def resultString: String =
                inner.resultString
        end Renderer

        /** Renderer constructors. */
        object Renderer:
            /** Creates a YAML event renderer using the contextual writer configuration. */
            def apply()(using config: WriterConfig): Renderer =
                apply(config)

            /** Creates a YAML event renderer using the provided writer configuration. */
            @targetName("applyWithConfig")
            def apply(config: WriterConfig): Renderer =
                new Renderer(internal.YamlEvents.Renderer(config))
        end Renderer

        /** Visits a YAML stream as events without constructing a YAML DOM. */
        def visit[Ctx, Err](
            input: String,
            context: Ctx
        )(handler: Handler[Ctx, Err])(using Frame): Result[Err | DecodeException, Ctx] =
            internal.YamlParser(input).visitEvents(context)(handler)
        end visit

        /** Visits one document from a YAML stream as events without constructing a YAML DOM. */
        def visit[Ctx, Err](
            input: String,
            documentIndex: DocumentIndex,
            context: Ctx
        )(handler: Handler[Ctx, Err])(using Frame): Result[Err | DecodeException, Ctx] =
            selectDocument(input, documentIndex).flatMap(doc => visit(doc, context)(handler))
        end visit

        /** Writes a schema value as YAML events into a handler.
          *
          * This is the write-side counterpart to [[visit]]. It lets YAML-specific tooling inspect or transform schema output before it is
          * rendered, collected, or otherwise consumed. The contextual [[WriterConfig]] controls scalar style metadata in the emitted events,
          * matching the single-argument [[Yaml.encode]] behavior.
          */
        def write[A, Ctx, Err](
            value: A,
            context: Ctx
        )(handler: Handler[Ctx, Err])(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): Result[Err, Ctx] =
            val writer = internal.YamlEvents.EventWriter(context, handler, writerConfig)
            schema.writeTo(value, writer)
            writer.resultContext
        end write
    end Events

    /** YAML node tree built only by [[parse]] and [[parseAll]].
      *
      * Ordinary visiting and schema decoding are event-first and do not require this tree. Use `Node` when a caller explicitly needs to
      * inspect or transform a YAML document as data while preserving anchors, tags, scalar styles, and source marks.
      */
    enum Node derives CanEqual:
        /** Mapping node containing key-value node pairs and collection metadata. */
        case Mapping(entries: Chunk[(Node, Node)], meta: Meta)

        /** Sequence node containing element nodes and collection metadata. */
        case Sequence(elements: Chunk[Node], meta: Meta)

        /** Scalar node containing the raw scalar text and scalar metadata. */
        case Scalar(value: String, meta: ScalarMeta)

        /** Alias node referencing a previously declared anchor by name. */
        case Alias(name: Anchor, mark: Mark)
    end Node

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
                if !internal.YamlDocuments.requiresSplit(input) then
                    decodePreparedString(
                        input,
                        config.maxDepth,
                        config.maxCollectionSize,
                        config.yamlVersion
                    )
                else
                    val docs = splitDocuments(input)
                    config.documentMode match
                        case ReaderConfig.DocumentMode.MergeTopLevelMappings =>
                            decodePreparedString(
                                internal.YamlDocuments.mergeTopLevelMappings(docs),
                                config.maxDepth,
                                config.maxCollectionSize,
                                config.yamlVersion
                            )
                        case ReaderConfig.DocumentMode.SingleDocument =>
                            if docs.size == 1 then
                                decodePreparedString(docs(0), config.maxDepth, config.maxCollectionSize, config.yamlVersion)
                            else if docs.isEmpty && input.trim.isEmpty then
                                decodePreparedString(input, config.maxDepth, config.maxCollectionSize, config.yamlVersion)
                            else
                                Result.fail(ParseException(
                                    Yaml(),
                                    "",
                                    "Unexpected content after YAML document end",
                                    Nil,
                                    0
                                ))
                            end if
                    end match
                end if
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
        decode[A](String(input.toArray, StandardCharsets.UTF_8), config)
    end decodeBytes

    private def decodePreparedString[A](
        input: String,
        maxDepth: Int,
        maxCollectionSize: Int,
        yamlVersion: SpecVersion
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = internal.YamlReader(input, yamlVersion)
            reader.resetLimits(maxDepth, maxCollectionSize)
            schema.readFrom(reader)
        }
    end decodePreparedString

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
        input: String
    )(using yaml: Yaml, schema: Schema[A], config: ReaderConfig, frame: Frame): Result[DecodeException, Chunk[A]] =
        decodeAll(input, config)
    end decodeAll

    /** Decodes every document in an explicit YAML stream with explicit limits. */
    def decodeAll[A](
        input: String,
        maxDepth: Int,
        maxCollectionSize: Int
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, Chunk[A]] =
        decodeAll(input, ReaderConfig(maxDepth, maxCollectionSize))
    end decodeAll

    /** Decodes every document in an explicit YAML stream using explicit configuration. */
    def decodeAll[A](
        input: String,
        config: ReaderConfig
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, Chunk[A]] =
        parseEach(input)(doc => decode[A](doc, config.copy(documentIndex = Absent)))
    end decodeAll

    private def parseEach[A](input: String)(parseOne: String => Result[DecodeException, A])(using
        Frame
    ): Result[DecodeException, Chunk[A]] =
        val docs = splitDocuments(input)

        @tailrec def loop(index: Int, acc: Chunk[A]): Result[DecodeException, Chunk[A]] =
            if index >= docs.size then Result.succeed(acc)
            else
                parseOne(docs(index)) match
                    case Result.Success(value) => loop(index + 1, acc :+ value)
                    case Result.Failure(e)     => Result.fail(e)
                    case Result.Panic(e)       => Result.panic(e)
            end if
        end loop

        loop(0, Chunk.empty)
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

    final private class NodeBuilder(using Frame) extends internal.YamlVisitor[Unit, DecodeException, Node]:
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

        def alias(context: Unit, name: Anchor, mark: Mark): Result[DecodeException, Unit] =
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
