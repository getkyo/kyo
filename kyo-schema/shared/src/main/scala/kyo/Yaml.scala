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
    def newWriter(): Codec.Writer = kyo.internal.yaml.YamlWriter(writerConfig)

    /** Creates a direct YAML reader over UTF-8 input bytes. */
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.yaml.YamlReader(input)
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
      * Tags annotate a YAML node with application-specific or standard YAML type information. `YamlTag` is an opaque string type to keep
      * YAML tag metadata distinct from ordinary strings and from [[kyo.Tag]] while remaining allocation-free.
      */
    opaque type YamlTag = String

    /** Constructors and extractors for [[YamlTag]] values. */
    object YamlTag:
        /** Creates a tag from its YAML source spelling, such as `!local`, `!!str`, or `tag:yaml.org,2002:str`. */
        def apply(value: String): YamlTag = value

        /** Extracts the raw YAML tag spelling. */
        def unapply(tag: YamlTag): Some[String] = Some(tag)

        given CanEqual[YamlTag, YamlTag] = CanEqual.derived
    end YamlTag

    extension (tag: YamlTag)
        /** Returns the raw YAML tag spelling. */
        @targetName("yamlTagValue")
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
    case class Meta(anchor: Maybe[Anchor], tag: Maybe[YamlTag], mark: Mark) derives CanEqual

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
    case class ScalarMeta(anchor: Maybe[Anchor], tag: Maybe[YamlTag], style: ScalarStyle, mark: Mark) derives CanEqual

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

        /** Event handler that threads caller-owned context through a YAML event stream.
          *
          * This method-based protocol is the allocation-light event surface used by parsers, renderers, and schema writers. Implement only
          * the callbacks relevant to a tool; callbacks return the context unchanged by default. Use [[EventHandler]] when pattern matching
          * on first-class [[Event]] values is more convenient than overriding individual callbacks.
          */
        trait Handler[Ctx, +Err]:
            /** Handles the start of a YAML stream. */
            def streamStart(context: Ctx, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the start of a YAML document. */
            def documentStart(context: Ctx, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the start of a YAML mapping. */
            def mappingStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the start of a YAML sequence. */
            def sequenceStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles a YAML scalar. */
            def scalar(context: Ctx, value: String, meta: ScalarMeta): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles an alias reference to an anchor declared elsewhere in the document. */
            def alias(context: Ctx, name: Anchor, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the end of a YAML mapping or sequence. */
            def collectionEnd(context: Ctx, kind: CollectionKind, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the end of a YAML document. */
            def documentEnd(context: Ctx, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles the end of a YAML stream. */
            def streamEnd(context: Ctx, mark: Mark): Result[Err, Ctx] =
                Result.succeed(context)

            /** Handles one first-class YAML event by dispatching to the method-based callbacks. */
            def event(context: Ctx, event: Event): Result[Err, Ctx] =
                event match
                    case Event.StreamStart(mark)         => streamStart(context, mark)
                    case Event.DocumentStart(mark)       => documentStart(context, mark)
                    case Event.MappingStart(meta, size)  => mappingStart(context, meta, size)
                    case Event.SequenceStart(meta, size) => sequenceStart(context, meta, size)
                    case Event.Scalar(value, meta)       => scalar(context, value, meta)
                    case Event.Alias(name, mark)         => alias(context, name, mark)
                    case Event.CollectionEnd(kind, mark) => collectionEnd(context, kind, mark)
                    case Event.DocumentEnd(mark)         => documentEnd(context, mark)
                    case Event.StreamEnd(mark)           => streamEnd(context, mark)
                end match
            end event
        end Handler

        /** Event handler specialization for tools that prefer first-class [[Event]] values.
          *
          * This trait extends the method-based [[Handler]] protocol, so it can be passed anywhere a handler is expected. Method callbacks
          * are converted to [[Event]] values at the edge. Use it for collectors, debuggers, replayers, and pattern-matching tools where
          * event allocation is intentional.
          */
        trait EventHandler[Ctx, Err] extends Handler[Ctx, Err]:
            /** Handles one YAML event and returns the next context or a typed error. */
            override def event(context: Ctx, event: Event): Result[Err, Ctx]

            final override def streamStart(context: Ctx, mark: Mark): Result[Err, Ctx] =
                event(context, Event.StreamStart(mark))

            final override def documentStart(context: Ctx, mark: Mark): Result[Err, Ctx] =
                event(context, Event.DocumentStart(mark))

            final override def mappingStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err, Ctx] =
                event(context, Event.MappingStart(meta, size))

            final override def sequenceStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err, Ctx] =
                event(context, Event.SequenceStart(meta, size))

            final override def scalar(context: Ctx, value: String, meta: ScalarMeta): Result[Err, Ctx] =
                event(context, Event.Scalar(value, meta))

            final override def alias(context: Ctx, name: Anchor, mark: Mark): Result[Err, Ctx] =
                event(context, Event.Alias(name, mark))

            final override def collectionEnd(context: Ctx, kind: CollectionKind, mark: Mark): Result[Err, Ctx] =
                event(context, Event.CollectionEnd(kind, mark))

            final override def documentEnd(context: Ctx, mark: Mark): Result[Err, Ctx] =
                event(context, Event.DocumentEnd(mark))

            final override def streamEnd(context: Ctx, mark: Mark): Result[Err, Ctx] =
                event(context, Event.StreamEnd(mark))
        end EventHandler

        /** Event middleware that wraps a downstream handler.
          *
          * Processors can inspect, rewrite, reject, or pass through YAML events. They compose left-to-right with `andThen`, allowing parser
          * events to flow through reusable tooling before they reach a terminal handler such as a collector or renderer.
          */
        trait Processor[Err]:
            self =>

            /** Wraps a downstream handler with this processor. */
            def apply[Ctx, Err2 >: Err](downstream: Handler[Ctx, Err2]): Handler[Ctx, Err2]

            /** Sends this processor's output to the downstream handler. */
            final def andThen[Ctx, Err2 >: Err](downstream: Handler[Ctx, Err2]): Handler[Ctx, Err2] =
                self(downstream)

            /** Composes this processor with another processor. */
            final def andThen[Err2](next: Processor[Err2]): Processor[Err | Err2] =
                new Processor[Err | Err2]:
                    def apply[Ctx, Err3 >: Err | Err2](downstream: Handler[Ctx, Err3]): Handler[Ctx, Err3] =
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
                    def apply[Ctx, Err2 >: Err](downstream: Handler[Ctx, Err2]): Handler[Ctx, Err2] =
                        new Handler[Ctx, Err2]:
                            override def streamStart(context: Ctx, mark: Mark): Result[Err2, Ctx] =
                                downstream.streamStart(context, mark)

                            override def documentStart(context: Ctx, mark: Mark): Result[Err2, Ctx] =
                                downstream.documentStart(context, mark)

                            override def mappingStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err2, Ctx] =
                                downstream.mappingStart(context, meta, size)

                            override def sequenceStart(context: Ctx, meta: Meta, size: Maybe[Int]): Result[Err2, Ctx] =
                                downstream.sequenceStart(context, meta, size)

                            override def scalar(context: Ctx, value: String, meta: ScalarMeta): Result[Err2, Ctx] =
                                f(value, meta).flatMap { case (nextValue, nextMeta) =>
                                    downstream.scalar(context, nextValue, nextMeta)
                                }

                            override def alias(context: Ctx, name: Anchor, mark: Mark): Result[Err2, Ctx] =
                                downstream.alias(context, name, mark)

                            override def collectionEnd(context: Ctx, kind: CollectionKind, mark: Mark): Result[Err2, Ctx] =
                                downstream.collectionEnd(context, kind, mark)

                            override def documentEnd(context: Ctx, mark: Mark): Result[Err2, Ctx] =
                                downstream.documentEnd(context, mark)

                            override def streamEnd(context: Ctx, mark: Mark): Result[Err2, Ctx] =
                                downstream.streamEnd(context, mark)
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
        final class Renderer private (private val inner: internal.yaml.YamlEvents.Renderer) extends Handler[Unit, DecodeException]:

            /** Handles the start of a YAML mapping. */
            override def mappingStart(context: Unit, meta: Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
                inner.mappingStart(context, meta, size)

            /** Handles the start of a YAML sequence. */
            override def sequenceStart(context: Unit, meta: Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
                inner.sequenceStart(context, meta, size)

            /** Handles a YAML scalar. */
            override def scalar(context: Unit, value: String, meta: ScalarMeta): Result[DecodeException, Unit] =
                inner.scalar(context, value, meta)

            /** Handles an alias reference. */
            override def alias(context: Unit, name: Anchor, mark: Mark): Result[DecodeException, Unit] =
                inner.alias(context, name, mark)

            /** Handles the end of a YAML mapping or sequence. */
            override def collectionEnd(context: Unit, kind: CollectionKind, mark: Mark): Result[DecodeException, Unit] =
                inner.collectionEnd(context, kind, mark)

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
                new Renderer(internal.yaml.YamlEvents.Renderer(config))
        end Renderer

        /** Visits a YAML stream as events without constructing a YAML DOM. */
        def visit[Ctx, Err](
            input: String,
            context: Ctx
        )(handler: Handler[Ctx, Err])(using Frame): Result[Err | DecodeException, Ctx] =
            internal.yaml.YamlParser(input).visitEvents(context)(handler)
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
            val writer = internal.yaml.YamlEvents.EventWriter(context, handler, writerConfig)
            schema.writeTo(value, writer)
            writer.resultContext
        end write
    end Events

    /** Configurable YAML event processing pipeline.
      *
      * A `Pipeline` is the opt-in orchestration API for YAML middleware. With no processors configured, schema decoding and encoding
      * delegate to [[Yaml.decode]] and [[Yaml.encode]] so ordinary reads and writes stay on the direct fast paths. With processors,
      * `visit`, `render`, and `write` route events through the configured middleware before reaching the terminal handler.
      *
      * Processor-backed `decode` reads transformed events into `Schema` through a direct YAML event reader. It does not render YAML text,
      * build a YAML DOM, or use the JSON reader bridge.
      *
      * Pipelines are immutable; methods such as [[reader]], [[writer]], and [[through]] return a new pipeline.
      */
    final class Pipeline[Err] private[Yaml] (
        private val readerConfig: ReaderConfig,
        private val writerConfig: WriterConfig,
        private val processor: Maybe[Events.Processor[Err]],
        private val cstTransform: Maybe[Cst.Document => Result[Err, Cst.Document]] = Absent,
        private val cstStreamTransform: Maybe[Cst.Stream => Result[Err, Cst.Stream]] = Absent
    ):

        /** Uses the supplied reader configuration for decode, parse, render, and visit source selection. */
        def reader(config: ReaderConfig): Pipeline[Err] =
            new Pipeline(config, writerConfig, processor, cstTransform, cstStreamTransform)
        end reader

        /** Uses the supplied writer configuration for encode, write, and render output. */
        def writer(config: WriterConfig): Pipeline[Err] =
            new Pipeline(readerConfig, config, processor, cstTransform, cstStreamTransform)
        end writer

        /** Adds an event processor to this pipeline. Processors run in the order they are added. */
        def through[Err2](next: Events.Processor[Err2]): Pipeline[Err | Err2] =
            val combined: Events.Processor[Err | Err2] =
                processor match
                    case Present(current) => current.andThen(next)
                    case Absent           => widenProcessor(next)
            new Pipeline(
                readerConfig,
                writerConfig,
                Maybe(combined),
                cstTransform.map(documentTransformWiden[Err, Err2]),
                cstStreamTransform.map(streamTransformWiden[Err, Err2])
            )
        end through

        /** Adds a per-document CST transform stage. When set, the String read terminals build a source-backed CST, apply this transform
          * (after any stream transform), emit events, and continue through event processors. The transform sees the document's comments and
          * trivia intact.
          */
        def throughCst[Err2](f: Cst.Document => Result[Err2, Cst.Document]): Pipeline[Err | Err2] =
            val widenedF: Cst.Document => Result[Err | Err2, Cst.Document] =
                document => f(document)
            val combined: Cst.Document => Result[Err | Err2, Cst.Document] =
                cstTransform match
                    case Present(existing) =>
                        val widenedExisting: Cst.Document => Result[Err | Err2, Cst.Document] =
                            document => existing(document)
                        document => widenedExisting(document).flatMap(widenedF)
                    case Absent => widenedF
            new Pipeline[Err | Err2](
                readerConfig,
                writerConfig,
                processor.map(widenProcessor[Err2, Err]),
                Maybe(combined),
                cstStreamTransform.map(streamTransformWiden[Err, Err2])
            )
        end throughCst

        /** Adds a whole-stream CST transform stage, applied before any per-document transform. */
        def throughCstStream[Err2](f: Cst.Stream => Result[Err2, Cst.Stream]): Pipeline[Err | Err2] =
            val widenedF: Cst.Stream => Result[Err | Err2, Cst.Stream] =
                stream => f(stream)
            val combined: Cst.Stream => Result[Err | Err2, Cst.Stream] =
                cstStreamTransform match
                    case Present(existing) =>
                        val widenedExisting: Cst.Stream => Result[Err | Err2, Cst.Stream] =
                            stream => existing(stream)
                        stream => widenedExisting(stream).flatMap(widenedF)
                    case Absent => widenedF
            new Pipeline[Err | Err2](
                readerConfig,
                writerConfig,
                processor.map(widenProcessor[Err2, Err]),
                cstTransform.map(documentTransformWiden[Err, Err2]),
                Maybe(combined)
            )
        end throughCstStream

        private def documentTransformWiden[E1, E2](
            f: Cst.Document => Result[E1, Cst.Document]
        ): Cst.Document => Result[E1 | E2, Cst.Document] =
            document => f(document)

        private def streamTransformWiden[E1, E2](
            f: Cst.Stream => Result[E1, Cst.Stream]
        ): Cst.Stream => Result[E1 | E2, Cst.Stream] =
            stream => f(stream)

        /** Decodes YAML into a schema value.
          *
          * Pipelines with no processors delegate to [[Yaml.decode]]. Processor-backed decode is implemented by the direct event reader
          * path, not by rendering the transformed events back to YAML text.
          */
        def decode[A](
            input: String
        )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[Err | DecodeException, A] =
            if cstStaged then stagedDocument(input).flatMap(decode[A](_))
            else
                processor match
                    case Absent => Yaml.decode[A](input, readerConfig)
                    case Present(current) =>
                        decodeSource(input).flatMap { source =>
                            internal.yaml.YamlEventScanner.collect(source, current).flatMap { events =>
                                Result.catching[DecodeException] {
                                    val reader = internal.yaml.YamlEventReader(events, readerConfig.yamlVersion)
                                    reader.resetLimits(readerConfig.maxDepth, readerConfig.maxCollectionSize)
                                    schema.readFrom(reader)
                                }
                            }
                        }
                end match
        end decode

        /** Decodes UTF-8 YAML bytes into a schema value. */
        def decodeBytes[A](
            input: Span[Byte]
        )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[Err | DecodeException, A] =
            decode[A](String(input.toArray, StandardCharsets.UTF_8))
        end decodeBytes

        /** Renders a YAML source after routing its events through this pipeline's processors. */
        def render(input: String)(using Frame): Result[Err | DecodeException, String] =
            if cstStaged then
                cstStreamTransform match
                    case Present(_) =>
                        stagedStream(input).map(stream => stream.render(using writerConfig))
                    case Absent =>
                        stagedDocument(input).map(doc => doc.render(using writerConfig))
            else
                val renderer = Events.Renderer(writerConfig)
                visit(input, ())(renderer).map(_ => renderer.resultString)
        end render

        /** Parses a YAML source into a [[Node]] after routing its events through this pipeline's processors. */
        def parse(input: String)(using Frame): Result[Err | DecodeException, Node] =
            if cstStaged then stagedDocument(input).flatMap(parse)
            else
                val builder = NodeBuilder()
                visit(input, ())(builder).flatMap(_ => builder.result)
        end parse

        /** Parses a YAML source into a CST after routing events through this pipeline's processors.
          *
          * Pipelines with no processors delegate to [[Yaml.cst]] after source selection, so source-backed CST rendering can return the
          * selected source unchanged until an edit is applied. Processor-backed CSTs are materialized from transformed events. They do not
          * preserve original source text or trivia because the transformed event stream, rather than the original bytes, is the source of
          * truth.
          */
        def cst(input: String)(using Frame): Result[Err | DecodeException, Cst.Document] =
            processor match
                case Absent =>
                    selectedSource(input).flatMap(Yaml.cst)
                case Present(current) =>
                    selectedSource(input).flatMap { source =>
                        internal.yaml.YamlEventScanner.collect(source, current).flatMap(Yaml.Cst.fromEvents)
                    }
            end match
        end cst

        /** Parses UTF-8 YAML bytes into a CST after routing events through this pipeline's processors.
          *
          * This is the byte-input counterpart to [[cst]]. With no processors, rendering keeps the decoded UTF-8 source backing. With
          * processors, the returned CST is rebuilt from transformed events and renders canonically.
          */
        def cstBytes(input: Span[Byte])(using Frame): Result[Err | DecodeException, Cst.Document] =
            cst(String(input.toArray, StandardCharsets.UTF_8))
        end cstBytes

        /** Parses a YAML stream into a CST stream after routing events through this pipeline's processors.
          *
          * Pipelines with no processors delegate to [[Yaml.cstAll]], so rendering can return the original stream unchanged until an edit is
          * applied. Processor-backed streams process each document and return canonical CST documents with no original stream source. Both
          * paths return every document in the stream; `cstAll` ignores [[ReaderConfig.documentIndex]] because document selection produces a
          * single document, which [[cst]] handles.
          */
        def cstAll(input: String)(using Frame): Result[Err | DecodeException, Cst.Stream] =
            processor match
                case Absent =>
                    Yaml.cstAll(input)
                case Present(current) =>
                    val docs = internal.yaml.YamlCstParser.documentBodies(input)

                    @tailrec def loop(index: Int, acc: Chunk[Cst.Document]): Result[Err | DecodeException, Chunk[Cst.Document]] =
                        if index >= docs.size then Result.succeed(acc)
                        else
                            internal.yaml.YamlEventScanner.collect(docs(index), current).flatMap(Yaml.Cst.fromEvents) match
                                case Result.Success(doc) => loop(index + 1, acc :+ doc)
                                case Result.Failure(e)   => Result.fail(e)
                                case Result.Panic(e)     => Result.panic(e)
                            end match
                    end loop

                    loop(0, Chunk.empty).map { documents =>
                        Cst.Stream(documents, Chunk.empty, Chunk.empty, streamSpan(documents), Absent)
                    }
            end match
        end cstAll

        /** Visits a YAML source after routing its events through this pipeline's processors. */
        def visit[Ctx, Err2](
            input: String,
            context: Ctx
        )(handler: Events.Handler[Ctx, Err2])(using Frame): Result[Err | Err2 | DecodeException, Ctx] =
            selectedSource(input).flatMap { source =>
                processor match
                    case Absent =>
                        Events.visit(source, context)(handler)
                    case Present(current) =>
                        Events.visit(source, context)(current.andThen(handler))
                end match
            }
        end visit

        /** Visits a CST document through this pipeline's processors and the supplied handler.
          *
          * With no processors the handler receives events directly from the document. With processors each event passes through the processor
          * chain before reaching the handler.
          */
        def visit[Ctx, Err2](
            document: Cst.Document,
            context: Ctx
        )(handler: Events.Handler[Ctx, Err2])(using Frame): Result[Err | Err2 | DecodeException, Ctx] =
            processor match
                case Absent           => internal.yaml.YamlCstBuilder.emitDocument(document, context)(handler)
                case Present(current) => internal.yaml.YamlCstBuilder.emitDocument(document, context)(current.andThen(handler))
            end match
        end visit

        /** Visits a CST stream through this pipeline's processors and the supplied handler.
          *
          * With no processors the handler receives events directly from the stream. With processors each event passes through the processor
          * chain before reaching the handler.
          */
        def visit[Ctx, Err2](
            stream: Cst.Stream,
            context: Ctx
        )(handler: Events.Handler[Ctx, Err2])(using Frame): Result[Err | Err2 | DecodeException, Ctx] =
            processor match
                case Absent           => internal.yaml.YamlCstBuilder.emitStream(stream, context)(handler)
                case Present(current) => internal.yaml.YamlCstBuilder.emitStream(stream, context)(current.andThen(handler))
            end match
        end visit

        /** Renders a CST document after routing its events through this pipeline's processors. */
        def render(document: Cst.Document)(using Frame): Result[Err | DecodeException, String] =
            val renderer = Events.Renderer(writerConfig)
            visit(document, ())(renderer).map(_ => renderer.resultString)
        end render

        /** Renders a CST stream after routing its events through this pipeline's processors.
          *
          * If the stream has an original source and no processors, that source is returned unchanged. Otherwise, each document is rendered
          * individually and joined with `---` separators. A single `visit(stream)(Renderer)` call would not insert `---` separators between
          * documents because the default `WriterConfig.documentMarkers` is `None`, so the per-document render-and-join approach mirrors
          * `YamlCstRenderer.stream`.
          */
        def render(stream: Cst.Stream)(using Frame): Result[Err | DecodeException, String] =
            stream.originalSource match
                case Present(source) if processor.isEmpty =>
                    Result.succeed(source)
                case _ =>
                    val multiDoc = stream.documents.size > 1
                    val childPipeline =
                        if multiDoc
                        then
                            new Pipeline(
                                readerConfig,
                                writerConfig.copy(documentMarkers = WriterConfig.DocumentMarkers.None),
                                processor,
                                cstTransform,
                                cstStreamTransform
                            )
                        else this

                    @tailrec def loop(
                        index: Int,
                        acc: StringBuilder
                    ): Result[Err | DecodeException, String] =
                        if index >= stream.documents.size then
                            if multiDoc && writerConfig.documentMarkers == WriterConfig.DocumentMarkers.StartAndEnd then
                                if acc.nonEmpty && acc.charAt(acc.length - 1) != '\n' then
                                    val _ = acc.append('\n')
                                val _ = acc.append("...\n")
                            end if
                            Result.succeed(acc.toString)
                        else
                            childPipeline.render(stream.documents(index)) match
                                case Result.Success(rendered) =>
                                    if multiDoc then
                                        if acc.nonEmpty && acc.charAt(acc.length - 1) != '\n' then
                                            val _ = acc.append('\n')
                                        val _ = acc.append("---\n")
                                    end if
                                    val _ = acc.append(rendered)
                                    loop(index + 1, acc)
                                case Result.Failure(e) => Result.fail(e)
                                case Result.Panic(e)   => Result.panic(e)
                            end match
                    end loop

                    loop(0, StringBuilder())
            end match
        end render

        /** Parses a CST document into a [[Node]] after routing its events through this pipeline's processors. */
        def parse(document: Cst.Document)(using Frame): Result[Err | DecodeException, Node] =
            val builder = NodeBuilder()
            visit(document, ())(builder).flatMap(_ => builder.result)
        end parse

        /** Parses every document of a CST stream into a [[Node]]. */
        def parseAll(stream: Cst.Stream)(using Frame): Result[Err | DecodeException, Chunk[Node]] =
            @tailrec def loop(index: Int, acc: Chunk[Node]): Result[Err | DecodeException, Chunk[Node]] =
                if index >= stream.documents.size then Result.succeed(acc)
                else
                    parse(stream.documents(index)) match
                        case Result.Success(node) => loop(index + 1, acc :+ node)
                        case Result.Failure(e)    => Result.fail(e)
                        case Result.Panic(e)      => Result.panic(e)
                    end match
            loop(0, Chunk.empty)
        end parseAll

        /** Decodes a CST document into a schema value.
          *
          * The document's events are routed through this pipeline's processors, then read by the schema. [[ReaderConfig.maxDepth]],
          * [[ReaderConfig.maxCollectionSize]], and [[ReaderConfig.yamlVersion]] apply; document selection does not, because a single
          * document has nothing to select. The `Cst.Stream` overload decodes one value from a multi-document stream.
          */
        def decode[A](document: Cst.Document)(using schema: Schema[A], frame: Frame): Result[Err | DecodeException, A] =
            visit(document, Chunk.empty[Events.Event])(cstEventCollector).flatMap { events =>
                Result.catching[DecodeException] {
                    val reader = internal.yaml.YamlEventReader(events, readerConfig.yamlVersion)
                    reader.resetLimits(readerConfig.maxDepth, readerConfig.maxCollectionSize)
                    schema.readFrom(reader)
                }
            }
        end decode

        /** Decodes a single value from a CST stream.
          *
          * The stream is first reduced to one document: a present [[ReaderConfig.documentIndex]] selects that document (failing when
          * out of range), otherwise [[ReaderConfig.documentMode]] decides, merging top-level mappings or requiring a single document.
          * The selected document is then decoded as in the `Cst.Document` overload, so the reader limits and version apply equally.
          */
        def decode[A](stream: Cst.Stream)(using schema: Schema[A], frame: Frame): Result[Err | DecodeException, A] =
            reduceStream(stream).flatMap(decode[A](_))
        end decode

        /** Decodes every document of a CST stream into schema values. */
        def decodeAll[A](stream: Cst.Stream)(using schema: Schema[A], frame: Frame): Result[Err | DecodeException, Chunk[A]] =
            @tailrec def loop(index: Int, acc: Chunk[A]): Result[Err | DecodeException, Chunk[A]] =
                if index >= stream.documents.size then Result.succeed(acc)
                else
                    decode[A](stream.documents(index)) match
                        case Result.Success(value) => loop(index + 1, acc :+ value)
                        case Result.Failure(e)     => Result.fail(e)
                        case Result.Panic(e)       => Result.panic(e)
                    end match
            loop(0, Chunk.empty)
        end decodeAll

        /** Encodes a schema value to YAML.
          *
          * Pipelines with no processors delegate to [[Yaml.encode]]. Processor-backed encode emits schema events through the configured
          * processors and renders the resulting YAML event stream.
          */
        def encode[A](value: A)(using schema: Schema[A], frame: Frame): Result[Err | DecodeException, String] =
            processor match
                case Absent =>
                    Result.succeed(Yaml.encode(value, writerConfig))
                case Present(_) =>
                    val renderer = Events.Renderer(writerConfig)
                    write(value, ())(renderer).map(_ => renderer.resultString)
            end match
        end encode

        /** Writes a schema value as YAML events after routing them through this pipeline's processors. */
        def write[A, Ctx, Err2](
            value: A,
            context: Ctx
        )(handler: Events.Handler[Ctx, Err2])(using schema: Schema[A], frame: Frame): Result[Err | Err2, Ctx] =
            processor match
                case Absent =>
                    Events.write(value, context)(handler)(using schema, writerConfig, frame)
                case Present(current) =>
                    Events.write(value, context)(current.andThen(handler))(using schema, writerConfig, frame)
            end match
        end write

        private def cstStaged: Boolean =
            cstTransform.isDefined || cstStreamTransform.isDefined

        private def applyDocumentTransform(document: Cst.Document)(using Frame): Result[Err | DecodeException, Cst.Document] =
            cstTransform match
                case Present(transform) => transform(document)
                case Absent             => Result.succeed(document)

        private def stagedDocument(input: String)(using Frame): Result[Err | DecodeException, Cst.Document] =
            cstStreamTransform match
                case Present(streamTransform) =>
                    Yaml.cstAll(input).flatMap(streamTransform).flatMap(reduceStream).flatMap(applyDocumentTransform)
                case Absent =>
                    selectedSource(input).flatMap(Yaml.cst).flatMap(applyDocumentTransform)

        private def stagedStream(input: String)(using Frame): Result[Err | DecodeException, Cst.Stream] =
            val base: Result[Err | DecodeException, Cst.Stream] =
                cstStreamTransform match
                    case Present(streamTransform) =>
                        Yaml.cstAll(input).flatMap(streamTransform).map(_.copy(originalSource = Absent))
                    case Absent => Yaml.cstAll(input)
            cstTransform match
                case Present(transform) =>
                    base.flatMap(stream =>
                        mapDocuments(stream.documents, 0, Chunk.empty, transform).map(docs =>
                            stream.copy(documents = docs, originalSource = Absent)
                        )
                    )
                case Absent => base
            end match
        end stagedStream

        @tailrec private def mapDocuments(
            documents: Chunk[Cst.Document],
            index: Int,
            acc: Chunk[Cst.Document],
            transform: Cst.Document => Result[Err, Cst.Document]
        ): Result[Err | DecodeException, Chunk[Cst.Document]] =
            if index >= documents.size then Result.succeed(acc)
            else
                transform(documents(index)) match
                    case Result.Success(doc) => mapDocuments(documents, index + 1, acc :+ doc, transform)
                    case Result.Failure(e)   => Result.fail(e)
                    case Result.Panic(e)     => Result.panic(e)
                end match
        end mapDocuments

        private def selectedSource(input: String)(using Frame): Result[DecodeException, String] =
            readerConfig.documentIndex match
                case Present(index) => selectDocument(input, index)
                case Absent         => Result.succeed(input)
        end selectedSource

        private def decodeSource(input: String)(using Frame): Result[DecodeException, String] =
            readerConfig.documentIndex match
                case Present(index) =>
                    selectDocument(input, index)
                case Absent if !internal.yaml.YamlDocuments.requiresSplit(input) =>
                    Result.succeed(input)
                case Absent =>
                    val docs = splitDocuments(input)
                    readerConfig.documentMode match
                        case ReaderConfig.DocumentMode.MergeTopLevelMappings =>
                            Result.succeed(internal.yaml.YamlDocuments.mergeTopLevelMappings(docs))
                        case ReaderConfig.DocumentMode.SingleDocument =>
                            if docs.size == 1 then Result.succeed(docs(0))
                            else if docs.isEmpty && input.trim.isEmpty then Result.succeed(input)
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
            end match
        end decodeSource

        // Buffers the full event sequence, including the StreamStart/StreamEnd boundaries, as YamlEventReader consumes them.
        private val cstEventCollector: Events.Handler[Chunk[Events.Event], Nothing] =
            new Events.EventHandler[Chunk[Events.Event], Nothing]:
                override def event(
                    context: Chunk[Events.Event],
                    event: Events.Event
                ): Result[Nothing, Chunk[Events.Event]] =
                    Result.succeed(context :+ event)

        private def reduceStream(stream: Cst.Stream)(using Frame): Result[DecodeException, Cst.Document] =
            readerConfig.documentIndex match
                case Present(index) =>
                    if index >= 0 && index < stream.documents.size then
                        Result.succeed(stream.documents(index))
                    else
                        Result.fail(ParseException(
                            Yaml(),
                            "",
                            s"YAML document index $index is out of range; found ${stream.documents.size} document(s)",
                            Nil,
                            0
                        ))
                    end if
                case Absent =>
                    readerConfig.documentMode match
                        case ReaderConfig.DocumentMode.MergeTopLevelMappings =>
                            Result.succeed(internal.yaml.YamlCstBuilder.mergeTopLevelMappings(stream))
                        case ReaderConfig.DocumentMode.SingleDocument =>
                            if stream.documents.size == 1 then Result.succeed(stream.documents(0))
                            // An empty stream (no documents, e.g. from blank input) reduces to an empty document, mirroring how the
                            // String path accepts blank input; decoding it then fails or succeeds per the target schema.
                            else if stream.documents.isEmpty then
                                Result.succeed(Cst.Document(Absent, Chunk.empty, Chunk.empty, stream.span, Absent))
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
            end match
        end reduceStream

        private def streamSpan(documents: Chunk[Cst.Document]): Cst.SourceSpan =
            if documents.isEmpty then
                val mark = Mark(0, 1, 1)
                Cst.SourceSpan(mark, mark)
            else
                Cst.SourceSpan(documents(0).span.start, documents(documents.size - 1).span.end)
        end streamSpan

    end Pipeline

    /** Default YAML pipeline using [[ReaderConfig.Default]] and [[WriterConfig.Default]]. */
    val pipeline: Pipeline[Nothing] =
        new Pipeline(ReaderConfig.Default, WriterConfig.Default, Absent)

    private def widenProcessor[Err1, Err2](processor: Events.Processor[Err2]): Events.Processor[Err1 | Err2] =
        new Events.Processor[Err1 | Err2]:
            def apply[Ctx, Err3 >: Err1 | Err2](downstream: Events.Handler[Ctx, Err3]): Events.Handler[Ctx, Err3] =
                processor(downstream)
        end new
    end widenProcessor

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

    /** Concrete syntax tree for YAML documents.
      *
      * `Cst` models YAML structure together with source spans and surface syntax metadata. It is intended for tools that need structural
      * paths, format-aware rendering, or later edit operations. Ordinary schema decoding and encoding continue to use the direct YAML
      * reader and writer paths.
      */
    object Cst:

        /** Source marks associated with a CST value.
          *
          * Collection roots parsed from source usually span the corresponding source region. Scalar and alias nodes may carry a zero-width
          * mark range when only the node start is available, especially for event-backed or canonical CST values.
          */
        case class SourceSpan(start: Mark, end: Mark) derives CanEqual

        /** Base type for CST-specific failures. */
        sealed trait Error extends Exception

        /** Raised when a CST edit cannot be applied. */
        case class EditException(message: String, path: Path, mark: Maybe[Mark]) extends Exception(message), Error

        /** Preserved non-structural YAML text. */
        case class Trivia(text: String, span: SourceSpan) derives CanEqual

        /** Source token captured by a CST node or entry. */
        case class Token(text: String, span: SourceSpan) derives CanEqual

        /** Scalar surface syntax. */
        enum ScalarSyntax derives CanEqual:
            /** Canonical syntax chosen by the CST renderer. */
            case Canonical

            /** Plain scalar syntax. */
            case Plain

            /** Single-quoted scalar syntax. */
            case SingleQuoted

            /** Double-quoted scalar syntax. */
            case DoubleQuoted

            /** Literal block scalar syntax. */
            case Literal

            /** Folded block scalar syntax. */
            case Folded
        end ScalarSyntax

        /** Mapping surface syntax. */
        enum MappingSyntax derives CanEqual:
            /** Canonical syntax chosen by the CST renderer. */
            case Canonical

            /** Block mapping syntax. */
            case Block

            /** Flow mapping syntax. */
            case Flow
        end MappingSyntax

        /** Sequence surface syntax. */
        enum SequenceSyntax derives CanEqual:
            /** Canonical syntax chosen by the CST renderer. */
            case Canonical

            /** Block sequence syntax. */
            case Block

            /** Flow sequence syntax. */
            case Flow
        end SequenceSyntax

        /** Alias surface syntax. */
        enum AliasSyntax derives CanEqual:
            /** Canonical `*anchor` alias syntax. */
            case Canonical
        end AliasSyntax

        /** Structural path to a YAML CST node.
          *
          * A path describes how to reach a node by YAML structure, not by character offset. [[Path.Segment.Key]] selects a mapping entry by
          * key text and [[Path.Segment.Index]] selects a sequence entry by zero-based position. The root path has no segments. Use
          * [[show]] for diagnostics and user-facing edit errors.
          */
        opaque type Path = Chunk[Path.Segment]

        /** Constructors and accessors for structural CST paths.
          *
          * Paths are opaque over `Chunk[Segment]` so callers can inspect segments without confusing a path with an arbitrary collection.
          * Appending with `/` keeps edit and lookup call sites compact while still preserving whether each step is a mapping key or a
          * sequence index.
          */
        object Path:
            /** One structural path step. */
            enum Segment derives CanEqual:
                /** Mapping key segment. */
                case Key(value: String)

                /** Sequence index segment. */
                case Index(value: Int)
            end Segment

            /** Root path with no segments. */
            val root: Path = Chunk.empty

            /** Creates a path from explicit segments. */
            def apply(segments: Chunk[Segment]): Path = segments

            given CanEqual[Path, Path] = CanEqual.derived

            extension (path: Path)
                /** Appends a mapping key segment. */
                @targetName("slashKey")
                def /(key: String): Path =
                    path :+ Segment.Key(key)

                /** Appends a sequence index segment. */
                @targetName("slashIndex")
                def /(index: Int): Path =
                    path :+ Segment.Index(index)

                /** Returns the path segments. */
                def segments: Chunk[Segment] =
                    path

                /** Renders the path in dotted form with bracketed indexes. */
                def show: String =
                    val builder = StringBuilder()

                    @tailrec def loop(index: Int): String =
                        if index >= path.size then builder.toString
                        else
                            path(index) match
                                case Segment.Key(value) =>
                                    if builder.length > 0 then
                                        val _ = builder.append('.')
                                    val _ = builder.append(value)
                                case Segment.Index(value) =>
                                    val _ = builder.append('[').append(value).append(']')
                            end match
                            loop(index + 1)
                        end if
                    end loop

                    loop(0)
                end show
            end extension
        end Path

        /** One YAML CST node.
          *
          * CST nodes carry the decoded structural value, source syntax metadata, and source marks. `originalSource` is reserved for CSTs
          * constructed by callers or future source-slice preserving parsers; the source parser currently preserves exact text at the document
          * or stream level, and edited documents render changed regions canonically. Trivia that belongs to entries or documents is kept
          * outside the node so structural editing can distinguish comments and whitespace from YAML values.
          */
        enum Node derives CanEqual:
            /** Mapping node with entry CSTs. */
            case Mapping(entries: Chunk[MappingEntry], syntax: MappingSyntax, meta: Meta, span: SourceSpan, originalSource: Maybe[String])

            /** Sequence node with element CSTs. */
            case Sequence(
                entries: Chunk[SequenceEntry],
                syntax: SequenceSyntax,
                meta: Meta,
                span: SourceSpan,
                originalSource: Maybe[String]
            )

            /** Scalar node with scalar text and syntax metadata. */
            case Scalar(value: String, syntax: ScalarSyntax, meta: ScalarMeta, span: SourceSpan, originalSource: Maybe[String])

            /** Alias node referencing an anchor. */
            case Alias(name: Anchor, syntax: AliasSyntax, span: SourceSpan, originalSource: Maybe[String])
        end Node

        /** Mapping key-value entry. */
        case class MappingEntry(
            key: Node,
            value: Node,
            span: SourceSpan,
            leadingTrivia: Chunk[Trivia] = Chunk.empty,
            trailingTrivia: Chunk[Trivia] = Chunk.empty
        ) derives CanEqual

        /** Sequence element entry. */
        case class SequenceEntry(
            value: Node,
            span: SourceSpan,
            leadingTrivia: Chunk[Trivia] = Chunk.empty,
            trailingTrivia: Chunk[Trivia] = Chunk.empty
        ) derives CanEqual

        /** One YAML document CST.
          *
          * A document has an optional root node because YAML streams can contain empty documents. `leadingTrivia` and `trailingTrivia`
          * reserve comments, blank lines, and whitespace that belong to the document rather than a particular node. `span` covers the
          * document's source extent when it came from text, or a synthetic extent for programmatically constructed documents.
          *
          * Rendering is source-preserving when `originalSource` is present: the original text is returned unchanged. Otherwise the document
          * is rendered canonically by emitting YAML events from the CST. The `events` method exposes that canonical event stream directly.
          */
        case class Document(
            root: Maybe[Node],
            leadingTrivia: Chunk[Trivia],
            trailingTrivia: Chunk[Trivia],
            span: SourceSpan,
            originalSource: Maybe[String]
        ) derives CanEqual:
            /** Returns this document's optional root node. */
            def node: Maybe[Node] =
                root

            /** Returns the original source used for source-preserving rendering. */
            def source: Maybe[String] =
                originalSource

            /** Renders this document to YAML.
              *
              * A source-backed document that has not been edited renders its original source unchanged, so `config` only affects documents
              * built from events or schema values, or documents with edits applied.
              */
            def render(using config: WriterConfig): String =
                internal.yaml.YamlCstRenderer.document(this)

            /** Emits this document as YAML events. */
            def events: Chunk[Events.Event] =
                internal.yaml.YamlCstBuilder.events(this)

            /** Replaces the node at `path`.
              *
              * Fails when a mapping segment along `path` is ambiguous because the mapping contains more than one entry with the same key.
              */
            def replace(path: Path, node: Node): Result[Error, Document] =
                internal.yaml.YamlCstEdits.replace(this, path, node)

            /** Inserts `node` at `path`.
              *
              * For a mapping key that does not yet exist the entry is appended. Inserting a mapping key that already exists fails rather
              * than overwriting it; use [[replace]] to change an existing value. Fails when a mapping segment along `path` is ambiguous.
              */
            def insert(path: Path, node: Node): Result[Error, Document] =
                internal.yaml.YamlCstEdits.insert(this, path, node)

            /** Removes the node at `path`. */
            def remove(path: Path): Result[Error, Document] =
                internal.yaml.YamlCstEdits.remove(this, path)
        end Document

        /** YAML stream CST containing zero or more documents.
          *
          * Stream-level trivia reserves content that belongs outside any individual document. When `originalSource` is present, rendering
          * returns that full source stream unchanged. When it is absent, each document is rendered canonically and multi-document streams
          * are separated with explicit `---` markers so the output remains parseable as a YAML stream.
          */
        case class Stream(
            documents: Chunk[Document],
            leadingTrivia: Chunk[Trivia],
            trailingTrivia: Chunk[Trivia],
            span: SourceSpan,
            originalSource: Maybe[String]
        ) derives CanEqual:
            /** Returns the original source used for source-preserving rendering. */
            def source: Maybe[String] =
                originalSource

            /** Renders this stream to YAML.
              *
              * A source-backed stream that has not been edited renders its original source unchanged, so `config` only affects streams
              * built from events or schema values, or streams with edits applied.
              */
            def render(using config: WriterConfig): String =
                internal.yaml.YamlCstRenderer.stream(this)
        end Stream

        /** Builds a CST document from YAML events. */
        def fromEvents(events: Chunk[Events.Event])(using Frame): Result[DecodeException, Document] =
            internal.yaml.YamlCstBuilder.fromEvents(events)

        /** Builds a CST document from a schema value. */
        def from[A](value: A)(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): Result[DecodeException, Document] =
            internal.yaml.YamlCstBuilder.fromValue(value)
    end Cst

    /** Parses a single YAML document into an optional DOM node tree. */
    def parse(input: String)(using Frame): Result[DecodeException, Node] =
        val builder = NodeBuilder()
        internal.yaml.YamlParser(input).visitEvents(())(builder).flatMap(_ => builder.result)
    end parse

    /** Parses one document from a YAML stream into an optional DOM node tree. */
    def parse(input: String, documentIndex: DocumentIndex)(using Frame): Result[DecodeException, Node] =
        selectDocument(input, documentIndex).flatMap(parse)
    end parse

    /** Parses an explicit YAML document stream into node trees. */
    def parseAll(input: String)(using Frame): Result[DecodeException, Chunk[Node]] =
        parseEach(input)(parse)

    /** Parses a single YAML document into a source-backed CST.
      *
      * The returned document keeps the original source text and source marks. Rendering an unchanged document returns that source without
      * re-emitting YAML events. Structural edits preserve collected comments and whitespace where supported, while changed regions render
      * canonically. Use CST parsing for format-aware tooling; ordinary schema decoding should use [[decode]].
      */
    def cst(input: String)(using Frame): Result[DecodeException, Cst.Document] =
        internal.yaml.YamlCstParser.document(input)

    /** Parses a YAML document stream into a source-backed CST stream.
      *
      * The stream retains the original input so an unchanged render can return it directly, including document separators and stream-level
      * trivia. Documents inside the stream expose the same structural edit API as [[cst]]. Use [[decodeAll]] for typed values and reserve CST
      * parsing for source-preserving YAML tools.
      */
    def cstAll(input: String)(using Frame): Result[DecodeException, Cst.Stream] =
        internal.yaml.YamlCstParser.stream(input)

    /** Encodes a value of type A as YAML. */
    inline def encode[A](value: A)(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): String =
        val w = Yaml(writerConfig).newWriter()
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A as YAML using explicit writer configuration. */
    inline def encode[A](value: A, writerConfig: WriterConfig)(using schema: Schema[A], frame: Frame): String =
        val w = Yaml(writerConfig).newWriter()
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A as UTF-8 YAML bytes. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], writerConfig: WriterConfig, frame: Frame): Span[Byte] =
        val w = Yaml(writerConfig).newWriter()
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Encodes a value of type A as UTF-8 YAML bytes using explicit writer configuration. */
    inline def encodeBytes[A](value: A, writerConfig: WriterConfig)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = Yaml(writerConfig).newWriter()
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
                if !internal.yaml.YamlDocuments.requiresSplit(input) then
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
                                internal.yaml.YamlDocuments.mergeTopLevelMappings(docs),
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
            val reader = internal.yaml.YamlReader(input, yamlVersion)
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
        internal.yaml.YamlDocuments.split(input)
    end splitDocuments

    sealed private trait NodeFrame
    final private class MappingFrame(val meta: Meta, var entries: Chunk[(Node, Node)], var pendingKey: Maybe[Node]) extends NodeFrame
    final private class SequenceFrame(val meta: Meta, var elements: Chunk[Node])                                    extends NodeFrame

    final private class NodeBuilder(using Frame) extends Events.Handler[Unit, DecodeException]:
        private var root: Maybe[Node]      = Absent
        private var stack: List[NodeFrame] = Nil
        private var lastMark: Mark         = Mark(0, 1, 1)

        def result: Result[DecodeException, Node] =
            root match
                case Present(node) if stack.isEmpty => Result.succeed(node)
                case Present(_)                     => Result.fail(ParseException(Yaml(), "", "Unclosed YAML node", Nil, lastMark.index))
                case Absent => Result.fail(ParseException(Yaml(), "", "Expected a YAML document", Nil, lastMark.index))
            end match
        end result

        override def streamStart(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end streamStart

        override def documentStart(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end documentStart

        override def documentEnd(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end documentEnd

        override def streamEnd(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end streamEnd

        override def mappingStart(context: Unit, meta: Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            stack = MappingFrame(meta, Chunk.empty, Absent) :: stack
            Result.unit
        end mappingStart

        override def sequenceStart(context: Unit, meta: Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            stack = SequenceFrame(meta, Chunk.empty) :: stack
            Result.unit
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: ScalarMeta): Result[DecodeException, Unit] =
            lastMark = meta.mark
            addNode(Node.Scalar(value, meta))

        override def alias(context: Unit, name: Anchor, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            addNode(Node.Alias(name, mark))

        override def collectionEnd(context: Unit, kind: Events.CollectionKind, mark: Mark): Result[DecodeException, Unit] =
            lastMark = mark
            stack match
                case (f: MappingFrame) :: rest =>
                    if kind != Events.CollectionKind.Mapping then
                        Result.fail(ParseException(Yaml(), "", "Unexpected YAML sequence end", Nil, mark.index))
                    else
                        stack = rest
                        addNode(Node.Mapping(f.entries, f.meta))
                    end if
                case (f: SequenceFrame) :: rest =>
                    if kind != Events.CollectionKind.Sequence then
                        Result.fail(ParseException(Yaml(), "", "Unexpected YAML mapping end", Nil, mark.index))
                    else
                        stack = rest
                        addNode(Node.Sequence(f.elements, f.meta))
                    end if
                case Nil =>
                    Result.fail(ParseException(Yaml(), "", "Unexpected YAML node end", Nil, mark.index))
            end match
        end collectionEnd

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
