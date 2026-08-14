package kyo

import java.util.Locale

/** Values shared by drag sources, drop targets, and drag event handlers.
  *
  * A drag carries typed items and an operation, while a drop target describes the media, file,
  * and directory values it accepts. [[Drag.Accept.accepts]] evaluates rules that can be decided
  * from one item. Item counts and operation compatibility are evaluated by the event layer, where
  * the complete transfer and requested operation are available.
  *
  * Exact media types and target patterns are validated and normalized before they enter domain
  * values. Targets can accept an exact media type or a wildcard for every subtype of a type.
  *
  * @see [[Drag.Accept]] for per-item acceptance
  * @see [[Drag.Move]] for collection move descriptions
  * @see [[Drag.Decision]] for acceptance results
  */
object Drag:

    // --- Public values ---

    /** Canonical exact media type used by drag transfer values.
      *
      * Values contain one concrete RFC-token-like main type and subtype separated by `/`. Parsing
      * removes only surrounding HTTP optional whitespace (space and horizontal tab) and applies locale-independent lowercase normalization.
      * Other controls and Unicode whitespace are rejected by token validation.
      * Wildcards, parameters, non-ASCII characters, and additional separators are rejected.
      *
      * The opaque representation is the canonical string itself, so carrying a media type adds no
      * wrapper allocation.
      *
      * @see [[MediaType.parse]] for validated construction
      * @see [[MediaTypePattern]] for target acceptance patterns
      */
    opaque type MediaType = String

    object MediaType:

        /** Parses and normalizes an exact media type. */
        def parse(value: String): Maybe[MediaType] =
            if value == null then Absent
            else
                val trimmed = trimOptionalWhitespace(value)
                if isExactMediaType(trimmed) then Present(trimmed.toLowerCase(Locale.ROOT))
                else Absent
            end if
        end parse

        extension (self: MediaType)
            /** Returns the canonical media type string. */
            def render: String = self

        /** Scalar string schema that validates and normalizes decoded media types. */
        given Schema[MediaType] = Schema.init[MediaType](
            writeFn = (value, writer) => writer.string(value.render),
            readFn = reader =>
                val raw = reader.string()
                kyo.internal.constructedOrThrow(
                    parse(raw).toResult(Result.fail(s"invalid media type: $raw")),
                    "Drag.MediaType"
                )(using reader.frame)
            ,
            structure = Structure.Type.Primitive(
                Structure.PrimitiveKind.String,
                Tag[MediaType].asInstanceOf[Tag[Any]]
            )
        )

        given CanEqual[MediaType, MediaType] = CanEqual.derived

        /** Object-shaped schema for maps whose keys are validated exact media types. */
        given mapSchema[V](using valueSchema0: => Schema[V]): Schema[Map[MediaType, V]] =
            lazy val valueSchema = valueSchema0
            Schema.init[Map[MediaType, V]](
                writeFn = (value, writer) =>
                    writer.mapStart(value.size)
                    value.iterator.zipWithIndex.foreach { case ((mediaType, item), index) =>
                        writer.field(mediaType.render, index)
                        valueSchema.serializeWrite(item, writer)
                    }
                    writer.mapEnd()
                ,
                readFn = reader =>
                    discard(reader.mapStart())
                    val builder = Map.newBuilder[MediaType, V]
                    val seen    = scala.collection.mutable.Set.empty[MediaType]
                    var count   = 1
                    while reader.hasNextEntry() do
                        reader.checkCollectionSize(count)
                        val raw = reader.field()
                        val mediaType = kyo.internal.constructedOrThrow(
                            parse(raw).toResult(Result.fail(s"invalid media type: $raw")),
                            "Drag.MediaType"
                        )(using reader.frame)
                        if seen.contains(mediaType) then
                            kyo.internal.constructedOrThrow(
                                Result.fail(s"duplicate canonical media type: ${mediaType.render}"),
                                "Drag.MediaType"
                            )(using reader.frame)
                        end if
                        seen += mediaType
                        builder += mediaType -> valueSchema.serializeRead(reader)
                        count += 1
                    end while
                    reader.mapEnd()
                    builder.result()
                ,
                absentDefaultValue = Maybe(Map.empty[MediaType, V]),
                structure = Structure.Type.Mapping(
                    "Map",
                    Tag[Any],
                    summon[Schema[MediaType]].structure,
                    valueSchema.structure
                )
            )
        end mapSchema

    end MediaType

    /** Canonical exact or main-type wildcard accepted by a drag target.
      *
      * Exact patterns share [[MediaType]] grammar. Wildcards have one concrete main token followed
      * by a slash and asterisk. Global wildcards and partial subtype wildcards are rejected. Parsing
      * removes only surrounding HTTP optional whitespace (space and horizontal tab) and applies locale-independent lowercase normalization.
      * Other controls and Unicode whitespace are rejected by token validation.
      *
      * The opaque representation is the canonical string itself. Matching compares that string
      * directly and does not allocate substrings or wrapper values.
      *
      * @see [[MediaTypePattern.parse]] for validated construction
      * @see [[MediaTypePattern.matches]] for exact and wildcard matching
      */
    opaque type MediaTypePattern = String

    object MediaTypePattern:

        /** Parses and normalizes an exact media type or concrete main-type wildcard. */
        def parse(value: String): Maybe[MediaTypePattern] =
            if value == null then Absent
            else
                val trimmed = trimOptionalWhitespace(value)
                if isMediaTypePattern(trimmed) then Present(trimmed.toLowerCase(Locale.ROOT))
                else Absent
            end if
        end parse

        /** Creates an exact pattern from an already validated media type. */
        def exact(value: MediaType): MediaTypePattern = value.render

        extension (self: MediaTypePattern)
            /** Returns the canonical pattern string. */
            def render: String = self

            /** Tests whether this exact or wildcard pattern accepts a media type. */
            def matches(value: MediaType): Boolean =
                val mediaType = value.render
                if self.endsWith("/*") then
                    val mainLength = self.length - 2
                    if mediaType.length <= mainLength || mediaType.charAt(mainLength) != '/' then false
                    else
                        var index = 0
                        while index < mainLength && self.charAt(index) == mediaType.charAt(index) do
                            index += 1
                        index == mainLength
                    end if
                else self == mediaType
                end if
            end matches
        end extension

        /** Scalar string schema that validates and normalizes decoded patterns. */
        given Schema[MediaTypePattern] = Schema.init[MediaTypePattern](
            writeFn = (value, writer) => writer.string(value.render),
            readFn = reader =>
                val raw = reader.string()
                kyo.internal.constructedOrThrow(
                    parse(raw).toResult(Result.fail(s"invalid media type pattern: $raw")),
                    "Drag.MediaTypePattern"
                )(using reader.frame)
            ,
            structure = Structure.Type.Primitive(
                Structure.PrimitiveKind.String,
                Tag[MediaTypePattern].asInstanceOf[Tag[Any]]
            )
        )

        given CanEqual[MediaTypePattern, MediaTypePattern] = CanEqual.derived

    end MediaTypePattern

    /** Operation requested for a drag transfer. */
    enum Operation derives CanEqual, Schema:
        case Copy, Move, Link
    end Operation

    /** Set of transfer operations supported by a source or target. */
    final case class AllowedOperations(values: Set[Operation]) derives CanEqual, Schema:
        /** Tests whether this set contains an operation. */
        def allows(operation: Operation): Boolean = values.contains(operation)
    end AllowedOperations

    object AllowedOperations:
        val none: AllowedOperations = AllowedOperations(Set.empty)
        val copy: AllowedOperations = AllowedOperations(Set(Operation.Copy))
        val move: AllowedOperations = AllowedOperations(Set(Operation.Move))
        val link: AllowedOperations = AllowedOperations(Set(Operation.Link))
        val all: AllowedOperations  = AllowedOperations(Set(Operation.Copy, Operation.Move, Operation.Link))
    end AllowedOperations

    /** Placement of dragged values relative to an anchor. */
    enum Position derives CanEqual, Schema:
        case Before, After, On, Inside
    end Position

    /** Axes along which a collection accepts pointer movement. */
    enum Orientation derives CanEqual, Schema:
        case Vertical, Horizontal, Both
    end Orientation

    /** Strategy used to select the active drop target. */
    enum Collision derives CanEqual, Schema:
        case PointerWithin, ClosestEdge, ClosestCenter, RectIntersection
    end Collision

    /** Visual representation displayed while dragging. */
    enum Preview derives CanEqual, Schema:
        case Native, Clone, Hidden
        case Label(value: String)
    end Preview

    /** Browser mechanisms allowed to activate a drag source.
      *
      * `Native` enables the platform's native HTML drag behavior, `Sensors` reserves activation for
      * pointer or keyboard sensors, and `Both` permits either mechanism. SVG elements never receive
      * the HTML `draggable` attribute, even when native activation is allowed.
      *
      * [[Drag.Source]] defaults to `Both`. Higher-level components that implement their own pointer
      * and keyboard sensors can select `Sensors` to avoid also enabling native HTML drag behavior.
      */
    enum Activation derives CanEqual, Schema:
        case Native, Sensors, Both
    end Activation

    /** Stable identifier of a collection participating in a move. */
    final case class Location(collection: String) derives CanEqual, Schema

    /** Pointer coordinates in the viewport. */
    final case class Point(x: Double, y: Double) derives CanEqual, Schema

    /** Browser file metadata with a validated exact media type and the token used to retrieve its content. */
    final case class FileMeta(
        token: String,
        name: String,
        mediaType: MediaType,
        size: ByteSize,
        lastModified: Instant
    ) derives CanEqual

    object FileMeta:
        given Schema[FileMeta] = DragSchemas.fileMeta
    end FileMeta

    /** One transferable value exposed by a browser drag. */
    enum Item derives CanEqual:
        case Text(representations: Map[MediaType, String])
        case Uri(value: String)
        case File(meta: FileMeta)
        case Directory(token: String, name: String)
    end Item

    object Item:
        given Schema[Item] = DragSchemas.item
    end Item

    /** Declarative configuration attached to a draggable UI element.
      *
      * `key` is the stable element identity exposed to drag events. `items` are the transferable
      * values made available by the source, and `operations` limits the operations it supports.
      * `label` supplies optional accessible metadata. When `handle` is true, activation begins only
      * from a descendant marked with [[kyo.UI.Ast.Interactive.dragHandle]], which emits the reserved
      * `data-kyo-drag-handle="true"` runtime marker. The marker is presentation-neutral and does not
      * make its element focusable. `preview` selects the browser representation used while dragging,
      * and `activation` selects the native and sensor mechanisms allowed to begin the drag.
      *
      * The complete value is serialized into the element's drag-source data attribute.
      */
    final case class Source(
        key: String,
        items: Chunk[Item],
        operations: AllowedOperations = AllowedOperations.move,
        label: Maybe[String] = Absent,
        handle: Boolean = false,
        preview: Preview = Preview.Clone,
        activation: Activation = Activation.Both
    ) derives CanEqual, Schema

    object Source:
        /** Creates a source for pure keyed reordering: the payload is the reserved sortable media
          * type carrying the key, so no application media type is involved. Pair with
          * [[Target.sortable]] on the receiving collection.
          */
        def sortable(key: String, label: Maybe[String] = Absent): Source =
            Source(key, Chunk(Item.Text(Map(sortableMediaType -> key))), label = label)
    end Source

    /** Declarative configuration attached to a drop-target UI element.
      *
      * `key` is the stable element identity exposed to drag events. `accepts` describes the item,
      * operation, count, size, and directory rules advertised by the target. `label` supplies
      * optional accessible metadata for browser runtimes and assistive interfaces.
      *
      * The complete value is serialized into the element's drop-target data attribute.
      */
    final case class Target(
        key: String,
        accepts: Accept,
        label: Maybe[String] = Absent
    ) derives CanEqual, Schema

    object Target:
        /** Creates a target for pure keyed reordering that accepts only the reserved sortable media
          * type. The target `key` names the collection: it is the value sort moves carry in
          * [[Location.collection]] for this container.
          */
        def sortable(collection: String, label: Maybe[String] = Absent): Target =
            Target(collection, Accept.types(MediaTypePattern.exact(sortableMediaType)), label)
    end Target

    /** Per-item rules declared by a drop target.
      *
      * An empty media type set accepts every valid exact MIME transfer representation. `maxItems`
      * is carried here for the event layer and is intentionally not applied by [[accepts]].
      */
    final case class Accept(
        mediaTypes: Set[MediaTypePattern] = Set.empty,
        operations: AllowedOperations = AllowedOperations.all,
        maxItems: Maybe[Int] = Absent,
        maxFileSize: Maybe[ByteSize] = Absent,
        directories: Boolean = false
    ) derives CanEqual:

        /** Evaluates the rules that can be determined from one transfer item. */
        def accepts(item: Item): Boolean = Drag.accepts(this, item)
    end Accept

    object Accept:
        given Schema[Accept] = DragSchemas.accept

        /** Creates acceptance rules for one or more validated media type patterns. */
        def types(first: MediaTypePattern, rest: MediaTypePattern*): Accept =
            Accept(mediaTypes = rest.toSet + first)
    end Accept

    /** Ordered movement of selected keys between collections. */
    final case class Move(
        keys: Chunk[String],
        source: Location,
        destination: Location,
        anchor: Maybe[String],
        position: Position,
        operation: Operation
    ) derives CanEqual, Schema

    /** Domain payload delivered to drag lifecycle handlers.
      *
      * The payload combines the session and transferred values with the selected operation,
      * optional source and target identities, pointer coordinates, keyboard modifiers, and the
      * resolved placement relative to a target. Fields unavailable for a lifecycle phase are
      * represented by [[kyo.Maybe]] rather than sentinel values.
      *
      * This handler value is assembled from validated wire protocol values and is not itself a
      * wire schema.
      */
    final case class Event(
        sessionId: String,
        items: Chunk[Item],
        operation: Operation,
        sourceKey: Maybe[String],
        targetKey: Maybe[String],
        point: Maybe[Point],
        modifiers: UI.Modifiers,
        position: Maybe[Position]
    ) derives CanEqual

    /** Domain payload delivered when a drag session ends.
      *
      * `event` contains the final normalized drag state. `canceled` is true when the session ended
      * without completing its selected operation. The spelling is part of the public handler API
      * and intentionally differs from the internal wire field where applicable.
      *
      * This handler value is assembled from validated wire protocol values and is not itself a
      * wire schema.
      */
    final case class End(event: Event, canceled: Boolean) derives CanEqual

    /** Reason a drag item or event was not accepted. */
    enum Rejection derives CanEqual, Schema:
        case IncompatibleType
        case IncompatibleOperation
        case TooManyItems
        case FileTooLarge(limit: ByteSize, actual: ByteSize)
        case DirectoryNotAccepted
        case Application(reason: String)
    end Rejection

    /** Result of applying drag acceptance rules. */
    enum Decision derives CanEqual, Schema:
        case Accept
        case Reject(rejection: Rejection)
    end Decision

    object Decision:
        /** Adapts a pure move reducer result into a decision: commits the updated value and accepts
          * on success, rejects with the typed rejection on failure. The commit effect runs only on
          * success.
          */
        def fromResult[A, S](result: Result[Rejection, A])(commit: A => Any < S)(using Frame): Decision < S =
            result.fold[Decision < S](
                value => commit(value).andThen(Decision.Accept: Decision),
                rejection => Decision.Reject(rejection),
                _ => Decision.Reject(Rejection.Application("The move could not be applied."))
            )
    end Decision

    // --- Internal matching ---

    private val sortableMediaType: MediaType = "application/x-kyo-sortable"

    private val uriMediaType: MediaType = "text/uri-list"

    private def accepts(accept: Accept, item: Item): Boolean =
        item match
            case Item.Text(representations) =>
                representations.nonEmpty && acceptsMediaTypes(accept.mediaTypes, representations.keySet)
            case Item.Uri(_) =>
                acceptsMediaTypes(accept.mediaTypes, Set(uriMediaType))
            case Item.File(meta) =>
                acceptsMediaTypes(accept.mediaTypes, Set(meta.mediaType)) &&
                (accept.maxFileSize match
                    case Present(limit) => meta.size <= limit
                    case Absent         => true)
            case Item.Directory(_, _) =>
                accept.directories
    end accepts

    private def acceptsMediaTypes(accepted: Set[MediaTypePattern], offered: Set[MediaType]): Boolean =
        offered.exists(actual => accepted.isEmpty || accepted.exists(pattern => MediaTypePattern.matches(pattern)(actual)))

    private def isTokenCharacter(value: Char): Boolean =
        (value >= 'a' && value <= 'z') ||
            (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') ||
            "!#$%&'*+-.^_`|~".contains(value)

    private def trimOptionalWhitespace(value: String): String =
        var start = 0
        var end   = value.length
        while start < end && isOptionalWhitespace(value.charAt(start)) do start += 1
        while end > start && isOptionalWhitespace(value.charAt(end - 1)) do end -= 1
        if start == 0 && end == value.length then value else value.substring(start, end)
    end trimOptionalWhitespace

    private def isOptionalWhitespace(value: Char): Boolean = value == ' ' || value == '\t'

    private def isExactMediaType(value: String): Boolean =
        val slash = value.indexOf('/')
        slash > 0 && slash == value.lastIndexOf('/') && slash < value.length - 1 &&
        isConcreteToken(value, 0, slash) && isConcreteToken(value, slash + 1, value.length)
    end isExactMediaType

    private def isMediaTypePattern(value: String): Boolean =
        if value.endsWith("/*") then isConcreteToken(value, 0, value.length - 2)
        else isExactMediaType(value)

    private def isConcreteToken(value: String, start: Int, end: Int): Boolean =
        var index = start
        while index < end && value.charAt(index) != '*' && isTokenCharacter(value.charAt(index)) do
            index += 1
        index == end && start < end
    end isConcreteToken

end Drag

private object DragSchemas:
    private given mediaTypeMap: Schema[Map[Drag.MediaType, String]] = Drag.MediaType.mapSchema[String]

    private given mediaTypePatternSet: Schema[Set[Drag.MediaTypePattern]] =
        Schema.setSchema(using summon[Schema[Drag.MediaTypePattern]])

    val fileMeta: Schema[Drag.FileMeta] = Schema.derived
    val item: Schema[Drag.Item]         = Schema.derived
    val accept: Schema[Drag.Accept]     = Schema.derived
end DragSchemas
