package kyo

import java.util.Locale

/** Values shared by drag sources, drop targets, and drag event handlers.
  *
  * A drag carries typed items and an operation, while a drop target describes the media, file,
  * and directory values it accepts. [[Drag.Accept.accepts]] evaluates rules that can be decided
  * from one item. Item counts and operation compatibility are evaluated by the event layer, where
  * the complete transfer and requested operation are available.
  *
  * MIME media types are compared after trimming and locale-independent lowercasing. Targets can
  * accept an exact media type or a wildcard for every subtype of a type.
  *
  * @see [[Drag.Accept]] for per-item acceptance
  * @see [[Drag.Move]] for collection move descriptions
  * @see [[Drag.Decision]] for acceptance results
  */
object Drag:

    // --- Public values ---

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

    /** Browser file metadata and the token used to retrieve its content. */
    final case class FileMeta(
        token: String,
        name: String,
        mediaType: String,
        size: ByteSize,
        lastModified: Instant
    ) derives CanEqual, Schema

    /** One transferable value exposed by a browser drag. */
    enum Item derives CanEqual, Schema:
        case Text(representations: Map[String, String])
        case Uri(value: String)
        case File(meta: FileMeta)
        case Directory(token: String, name: String)
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

    /** Per-item rules declared by a drop target.
      *
      * An empty media type set accepts every valid exact MIME transfer representation. `maxItems`
      * is carried here for the event layer and is intentionally not applied by [[accepts]].
      */
    final case class Accept(
        mediaTypes: Set[String] = Set.empty,
        operations: AllowedOperations = AllowedOperations.all,
        maxItems: Maybe[Int] = Absent,
        maxFileSize: Maybe[ByteSize] = Absent,
        directories: Boolean = false
    ) derives CanEqual, Schema:

        /** Evaluates the rules that can be determined from one transfer item. */
        def accepts(item: Item): Boolean = Drag.accepts(this, item)
    end Accept

    object Accept:
        /** Creates acceptance rules for one or more normalized MIME media types. */
        def types(first: String, rest: String*): Accept =
            Accept(mediaTypes = (first +: rest).iterator.map(normalizeMediaType).toSet)
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

    // --- Internal matching ---

    private val uriMediaType = "text/uri-list"

    final private case class ExactMediaType(mainType: String, subType: String) derives CanEqual

    private enum MediaTypePattern derives CanEqual:
        case Exact(value: ExactMediaType)
        case Wildcard(mainType: String)
    end MediaTypePattern

    private def normalizeMediaType(mediaType: String) =
        mediaType.trim.toLowerCase(Locale.ROOT)

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

    private def acceptsMediaTypes(accepted: Set[String], offered: Set[String]): Boolean =
        offered.exists { transferred =>
            parseExactMediaType(transferred) match
                case Present(actual) =>
                    accepted.isEmpty || accepted.exists { configured =>
                        parseMediaTypePattern(configured) match
                            case Present(MediaTypePattern.Exact(expected))    => expected == actual
                            case Present(MediaTypePattern.Wildcard(mainType)) => mainType == actual.mainType
                            case Absent                                       => false
                    }
                case Absent => false
        }
    end acceptsMediaTypes

    private def parseExactMediaType(mediaType: String): Maybe[ExactMediaType] =
        parseExactNormalized(normalizeMediaType(mediaType))

    private def parseMediaTypePattern(mediaType: String): Maybe[MediaTypePattern] =
        val normalized = normalizeMediaType(mediaType)
        if normalized.endsWith("/*") then
            val mainType = normalized.dropRight(2)
            if isConcreteToken(mainType) then Present(MediaTypePattern.Wildcard(mainType))
            else Absent
        else
            parseExactNormalized(normalized).map(MediaTypePattern.Exact(_))
        end if
    end parseMediaTypePattern

    private def parseExactNormalized(mediaType: String): Maybe[ExactMediaType] =
        val slash = mediaType.indexOf('/')
        if slash > 0 && slash == mediaType.lastIndexOf('/') && slash < mediaType.length - 1 then
            val mainType = mediaType.substring(0, slash)
            val subType  = mediaType.substring(slash + 1)
            if isConcreteToken(mainType) && isConcreteToken(subType) then Present(ExactMediaType(mainType, subType))
            else Absent
        else Absent
        end if
    end parseExactNormalized

    private def isConcreteToken(value: String): Boolean =
        value.nonEmpty && !value.contains('*') && value.forall(isTokenCharacter)

    private def isTokenCharacter(value: Char): Boolean =
        (value >= 'a' && value <= 'z') ||
            (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') ||
            "!#$%&'*+-.^_`|~".contains(value)

end Drag
