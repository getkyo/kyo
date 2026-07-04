package kyo

/** Identifier of an event stream within a [[Journal]].
  *
  * A stream is an append-only, zero-indexed sequence of events sharing one identity, typically one entity or one logical log. The
  * constructor validates that the identifier is non-empty and returns a `Result`; there is no unchecked public construction.
  *
  * @see
  *   [[kyo.Journal.append]] and [[kyo.Journal.read]] which address streams by this identifier
  */
opaque type StreamId = String

object StreamId:
    /** Creates a validated stream identifier, failing on an empty value. */
    def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, StreamId] =
        if value.isEmpty then Result.fail(JournalInvalidIdentifierError("StreamId", value))
        else Result.succeed(value)

    extension (self: StreamId)
        /** The underlying string value. */
        def value: String = self

    inline given CanEqual[StreamId, StreamId] = CanEqual.derived
end StreamId

/** Identifier of a single event, assigned by the producer.
  *
  * Event identifiers travel with the event from [[EventEnvelope]] to [[RecordedEvent]] unchanged; the journal does not generate or
  * deduplicate them. The constructor validates that the identifier is non-empty and returns a `Result`.
  *
  * @see
  *   [[kyo.EventEnvelope]] which carries this identifier into an append
  */
opaque type EventId = String

object EventId:
    /** Creates a validated event identifier, failing on an empty value. */
    def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, EventId] =
        if value.isEmpty then Result.fail(JournalInvalidIdentifierError("EventId", value))
        else Result.succeed(value)

    extension (self: EventId)
        /** The underlying string value. */
        def value: String = self

    inline given CanEqual[EventId, EventId] = CanEqual.derived
end EventId

/** Type label of an event, used by consumers to select decoders.
  *
  * The routing label consumers use to select a decoder for the payload. The constructor validates that the label is non-empty and returns
  * a `Result`.
  *
  * @see
  *   [[kyo.EventEnvelope]] which pairs this label with a raw payload
  */
opaque type EventType = String

object EventType:
    /** Creates a validated event type label, failing on an empty value. */
    def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, EventType] =
        if value.isEmpty then Result.fail(JournalInvalidIdentifierError("EventType", value))
        else Result.succeed(value)

    extension (self: EventType)
        /** The underlying string value. */
        def value: String = self

    inline given CanEqual[EventType, EventType] = CanEqual.derived
end EventType

/** Zero-based position of an event within its stream.
  *
  * The first event of a stream is offset 0 and appends assign consecutive offsets. Valid values are in `[0, Long.MaxValue)`. Use
  * [[StreamVersion.after]] to convert an offset to a one-based count. The constructor validates the range and returns a `Result`.
  *
  * @see
  *   [[kyo.StreamVersion]] for the one-based count view derived from an offset
  */
opaque type StreamOffset = Long

object StreamOffset:
    /** The offset of the first event in any stream. */
    val first: StreamOffset = 0L

    /** Creates a validated offset, failing outside `[0, Long.MaxValue)`. */
    def apply(value: Long)(using Frame): Result[JournalInvalidIdentifierError, StreamOffset] =
        if value < 0L || value == Long.MaxValue then
            Result.fail(JournalInvalidIdentifierError("StreamOffset", value.toString))
        else Result.succeed(value)

    /** Constructs an offset from a value already known to be valid (an internal invariant, never user input). */
    private[kyo] inline def fromUnchecked(value: Long): StreamOffset = value

    extension (self: StreamOffset)
        /** The underlying position value. */
        def value: Long = self

    inline given CanEqual[StreamOffset, StreamOffset] = CanEqual.derived
end StreamOffset

/** One-based count view of a stream: the number of events, or equivalently the position after the last event.
  *
  * `StreamVersion.initial` (zero) is the version of an absent or empty stream; `StreamVersion.after(offset)` is the version of a stream
  * whose last event sits at `offset`. The constructor validates non-negativity and returns a `Result`.
  *
  * @see
  *   [[kyo.StreamOffset]] for the zero-based per-event position
  */
opaque type StreamVersion = Long

object StreamVersion:
    /** The version of an absent or empty stream. */
    val initial: StreamVersion = 0L

    /** Creates a validated version, failing on a negative value. */
    def apply(value: Long)(using Frame): Result[JournalInvalidIdentifierError, StreamVersion] =
        if value < 0L then Result.fail(JournalInvalidIdentifierError("StreamVersion", value.toString))
        else Result.succeed(value)

    /** The version of a stream whose last event sits at `offset`. */
    def after(offset: StreamOffset): StreamVersion =
        offset.value + 1L

    extension (self: StreamVersion)
        /** The underlying count value. */
        def value: Long = self

    inline given CanEqual[StreamVersion, StreamVersion] = CanEqual.derived
end StreamVersion

/** Optimistic concurrency expectation for an append.
  *
  * The check is atomic with the append: `Any` skips it, `NoStream` requires the stream to be absent, and `Exact(offset)` requires the
  * live last offset to equal `offset`. A mismatch fails the append with [[JournalConflictError]] carrying the observed [[StreamInfo]],
  * leaving the stream unchanged.
  *
  * @see
  *   [[kyo.Journal.append]] which takes this expectation
  */
enum ExpectedOffset derives CanEqual:
    case Any
    case NoStream
    case Exact(offset: StreamOffset)
end ExpectedOffset

/** Observed state of a stream: absent, or existing with an event count and last offset.
  *
  * Returned by [[kyo.Journal.streamInfo]] and carried inside [[JournalConflictError]] so a failed optimistic append reports what it
  * actually observed.
  */
enum StreamInfo derives CanEqual:
    case Absent
    case Existing(eventCount: Long, lastOffset: StreamOffset)

    /** Whether the stream has at least one event. */
    def exists: Boolean =
        this match
            case StreamInfo.Absent         => false
            case StreamInfo.Existing(_, _) => true
end StreamInfo

/** An event as submitted to [[kyo.Journal.append]]: producer-assigned identity, type label, raw payload, and metadata.
  *
  * The journal treats the payload as opaque bytes; typed encoding and decoding live above this layer. Note that `Span` equality via `==`
  * is reference-based: compare payload contents with `Span#is`, not by comparing envelopes with `==`.
  *
  * @see
  *   [[kyo.RecordedEvent]] for the stored form returned by reads
  */
final case class EventEnvelope(
    id: EventId,
    eventType: EventType,
    payload: Span[Byte],
    metadata: EventMetadata
) derives CanEqual

/** A stored event as returned by [[kyo.Journal.read]]: the envelope's data plus the stream identity and assigned offset.
  *
  * Note that `Span` equality via `==` is reference-based: compare payload contents with `Span#is`, not by comparing records with `==`.
  *
  * @see
  *   [[kyo.EventEnvelope]] for the submitted form
  */
final case class RecordedEvent(
    streamId: StreamId,
    offset: StreamOffset,
    eventId: EventId,
    eventType: EventType,
    payload: Span[Byte],
    metadata: EventMetadata
) derives CanEqual

/** Outcome of a successful append: the offset range assigned to the batch and the post-append stream state.
  *
  * @see
  *   [[kyo.Journal.append]] which returns this
  */
final case class AppendResult(
    streamId: StreamId,
    firstOffset: StreamOffset,
    lastOffset: StreamOffset,
    streamInfo: StreamInfo
) derives CanEqual
