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
    def apply(value: String): Result[JournalError.InvalidIdentifier, StreamId] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("StreamId", value))
        else Result.succeed(value)

    extension (self: StreamId)
        /** The underlying string value. */
        def value: String = self

    given CanEqual[StreamId, StreamId] = CanEqual.derived
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
    def apply(value: String): Result[JournalError.InvalidIdentifier, EventId] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("EventId", value))
        else Result.succeed(value)

    extension (self: EventId)
        /** The underlying string value. */
        def value: String = self

    given CanEqual[EventId, EventId] = CanEqual.derived
end EventId

/** Type label of an event, used by consumers to select decoders.
  *
  * The journal stores payloads as raw bytes; the event type is the routing key a typed layer (kyo-eventlog) uses to pick the decoder.
  * The constructor validates that the label is non-empty and returns a `Result`.
  *
  * @see
  *   [[kyo.EventEnvelope]] which pairs this label with a raw payload
  */
opaque type EventType = String

object EventType:
    /** Creates a validated event type label, failing on an empty value. */
    def apply(value: String): Result[JournalError.InvalidIdentifier, EventType] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("EventType", value))
        else Result.succeed(value)

    extension (self: EventType)
        /** The underlying string value. */
        def value: String = self

    given CanEqual[EventType, EventType] = CanEqual.derived
end EventType

/** Zero-based position of an event within its stream.
  *
  * The first event of a stream is revision 0 and appends assign consecutive revisions. Valid values are in `[0, Long.MaxValue)`;
  * `Long.MaxValue` is excluded so the one-based [[StreamVersion]] view (`revision + 1`) cannot overflow. The constructor validates the
  * range and returns a `Result`.
  *
  * @see
  *   [[kyo.StreamVersion]] for the one-based count view derived from a revision
  */
opaque type StreamRevision = Long

object StreamRevision:
    /** The revision of the first event in any stream. */
    val first: StreamRevision = 0L

    /** Creates a validated revision, failing outside `[0, Long.MaxValue)`. */
    def apply(value: Long): Result[JournalError.InvalidIdentifier, StreamRevision] =
        if value < 0L || value == Long.MaxValue then
            Result.fail(JournalError.InvalidIdentifier("StreamRevision", value.toString))
        else Result.succeed(value)

    /** Constructs a revision from a value already known to be valid (an internal invariant, never user input). */
    private[kyo] inline def fromUnchecked(value: Long): StreamRevision = value

    extension (self: StreamRevision)
        /** The underlying position value. */
        def value: Long = self

    given CanEqual[StreamRevision, StreamRevision] = CanEqual.derived
end StreamRevision

/** One-based count view of a stream: the number of events, or equivalently the position after the last event.
  *
  * `StreamVersion.initial` (zero) is the version of an absent or empty stream; `StreamVersion.after(revision)` is the version of a
  * stream whose last event sits at `revision`. The constructor validates non-negativity and returns a `Result`.
  *
  * @see
  *   [[kyo.StreamRevision]] for the zero-based per-event position
  */
opaque type StreamVersion = Long

object StreamVersion:
    /** The version of an absent or empty stream. */
    val initial: StreamVersion = 0L

    /** Creates a validated version, failing on a negative value. */
    def apply(value: Long): Result[JournalError.InvalidIdentifier, StreamVersion] =
        if value < 0L then Result.fail(JournalError.InvalidIdentifier("StreamVersion", value.toString))
        else Result.succeed(value)

    /** The version of a stream whose last event sits at `revision`. */
    def after(revision: StreamRevision): StreamVersion =
        revision.value + 1L

    extension (self: StreamVersion)
        /** The underlying count value. */
        def value: Long = self

    given CanEqual[StreamVersion, StreamVersion] = CanEqual.derived
end StreamVersion

/** Optimistic concurrency expectation for an append.
  *
  * The check is atomic with the append: `Any` skips it, `NoStream` requires the stream to be absent, and `Exact(revision)` requires the
  * live last revision to equal `revision`. A mismatch fails the append with [[JournalError.Conflict]] carrying the observed
  * [[StreamInfo]], leaving the stream unchanged.
  *
  * @see
  *   [[kyo.Journal.append]] which takes this expectation
  */
enum ExpectedRevision derives CanEqual:
    case Any
    case NoStream
    case Exact(revision: StreamRevision)
end ExpectedRevision

/** Observed state of a stream: absent, or existing with an event count and last revision.
  *
  * Returned by [[kyo.Journal.streamInfo]] and carried inside [[JournalError.Conflict]] so a failed optimistic append reports what it
  * actually observed.
  */
enum StreamInfo derives CanEqual:
    case Absent
    case Existing(eventCount: Long, lastRevision: StreamRevision)

    /** Whether the stream has at least one event. */
    def exists: Boolean =
        this match
            case StreamInfo.Absent         => false
            case StreamInfo.Existing(_, _) => true
end StreamInfo
