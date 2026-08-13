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

    /** Per-item rules declared by a drop target.
      *
      * An empty media type set accepts every non-empty transfer representation. `maxItems` is
      * carried here for the event layer and is intentionally not applied by [[accepts]].
      */
    final case class Accept(
        mediaTypes: Set[String] = Set.empty,
        operations: AllowedOperations = AllowedOperations.all,
        maxItems: Maybe[Int] = Absent,
        maxFileSize: Maybe[ByteSize] = Absent,
        directories: Boolean = false
    ) derives CanEqual, Schema:

        /** Evaluates the rules that can be determined from one transfer item. */
        def accepts(item: Item): Decision =
            item match
                case Item.Text(representations) =>
                    if representations.isEmpty || !acceptsMediaTypes(mediaTypes, representations.keySet) then
                        Decision.Reject(Rejection.IncompatibleType)
                    else Decision.Accept
                case Item.Uri(_) =>
                    if acceptsMediaTypes(mediaTypes, Set(uriMediaType)) then Decision.Accept
                    else Decision.Reject(Rejection.IncompatibleType)
                case Item.File(meta) =>
                    if !acceptsMediaTypes(mediaTypes, Set(meta.mediaType)) then
                        Decision.Reject(Rejection.IncompatibleType)
                    else
                        maxFileSize match
                            case Present(limit) if meta.size > limit =>
                                Decision.Reject(Rejection.FileTooLarge(limit, meta.size))
                            case _ => Decision.Accept
                case Item.Directory(_, _) =>
                    if directories then Decision.Accept
                    else Decision.Reject(Rejection.DirectoryNotAccepted)
        end accepts
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

    private val uriMediaType = "text/uri-list"

    private def normalizeMediaType(mediaType: String) =
        mediaType.trim.toLowerCase(Locale.ROOT)

    private def acceptsMediaTypes(accepted: Set[String], offered: Set[String]): Boolean =
        offered.nonEmpty &&
            (accepted.isEmpty || accepted.exists { configured =>
                val expected = normalizeMediaType(configured)
                offered.exists { transferred =>
                    val actual = normalizeMediaType(transferred)
                    expected == actual ||
                    (expected.endsWith("/*") && actual.startsWith(expected.dropRight(1)) && actual.length > expected.length - 1)
                }
            })
    end acceptsMediaTypes

end Drag
