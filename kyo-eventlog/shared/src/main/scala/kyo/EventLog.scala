package kyo

/** Typed, backend-free program facade over the raw `Journal` ArrowEffect for domain event
  * type `A`. Constructed from an [[EventLog.Codecs]] and a [[JournalId]] via
  * [[EventLog.init]]; captures no backend. Every operation is an ordinary `Journal` program
  * run inside `Journal.run(backend)(program)`.
  *
  * `append`, `prepare`, and `appendAll` hide member evidence and `Frame` plumbing behind an
  * [[EventLog.EventDefinition]] given, so a union/enum/sealed-trait concrete event appends
  * with `log.append(event)` and no visible membership witness.
  *
  * @tparam A the domain event type (union, enum, or sealed trait)
  */
final class EventLog[A] private (
    val journalId: JournalId,
    codecs: EventLog.Codecs[A]
):

    /** A prepared, log-bound event ready to append or batch. Path-dependent on THIS log so a
      * command prepared against `log1` cannot be appended through `log2`.
      */
    final class Command private[EventLog] (
        private[kyo] val envelope: EventEnvelope,
        private[kyo] val directive: EventLog.AppendDirective
    )

    /** Appends one concrete event. The `EventDefinition[A, E]` given supplies the event type,
      * stream, id policy, and metadata for `E`; the caller writes `log.append(event)`.
      */
    def append[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: EventLog.EventDefinition[A, E],
        frame: Frame
    ): AppendResult < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        prepare(event, directive).flatMap(cmd => appendAll(cmd)).map(_.head)

    /** Prepares one concrete event into a log-bound [[Command]] without appending, so several
      * prepared commands can be widened and batched together.
      */
    def prepare[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: EventLog.EventDefinition[A, E],
        frame: Frame
    ): Command < (Sync & Abort[EventLog.PreparationFailure]) =
        // Encodes via EventLogCodecs.encodeValue(codecs.value, event) (codecs.value is the
        // data-only descriptor; the interpreter performs the transform), resolves stream via
        // ev.stream, id via ev.eventId, metadata via ev.metadata; assembles EventEnvelope and
        // wraps it in a Command. Aborts PreparationFailure on stream/id/metadata resolution
        // failure. No Journal effect: prepare is pure staging.
        Abort.get(EventLog.prepareEnvelope(codecs, ev, event, directive)).map(new Command(_, directive))

    /** Appends a nonempty batch atomically. Varargs are nonempty by construction (first is
      * required); `Chunk` batching shares the same validator.
      */
    def appendAll(first: Command, rest: Command*)(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        val commands = first +: Chunk.from(rest)
        // Groups by expected-offset directive, validates one-log membership (compile-time
        // via path-dependent Command), and issues Journal.append per stream group. Shared
        // with any Chunk overload through the single private validateBatch.
        EventLog.appendValidated(journalId, commands)
    end appendAll

    /** Backend-free typed read-only lane. Produces an [[EventLog.Reader]] that reads committed
      * records and cannot append.
      */
    def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    )(using Frame): Chunk[EventLog.Record[A]] < (Journal & Abort[JournalReadFailure]) =
        // EventLogCodecs.decodeValue(codecs.value, bytes) is itself effectful
        // (A < Abort[JournalReadFailure]) and already folds undecodable payload bytes into
        // JournalCorruptedError on that row (the interpreter's decode contract), so
        // the read composes it directly with map, never Abort.get (which lifts a Result, not
        // an already-effectful value). codecs.value is the data-only descriptor; the transform
        // is the interpreter, never a method on the descriptor. The row stays
        // Journal & Abort[JournalReadFailure] and never widens to a codec-specific error.
        Journal.read(streamId, from, maxCount).flatMap { records =>
            Kyo.foreach(records) { rec =>
                EventLogCodecs.decodeValue(codecs.value, rec.payload).map { a =>
                    // RecordedEvent carries no logical ref: build it from this log's
                    // journalId (the EventLog[A] field) plus the record's streamId and
                    // offset. JournalEntryRef(journalId, streamId, offset) is the same
                    // shape JournalEntryRef.parse resolves back from a URI.
                    val ref = JournalEntryRef(journalId, rec.streamId, rec.offset)
                    EventLog.Record(ref, rec.eventId, rec.eventType, rec.metadata, a)
                }
            }
        }
end EventLog

object EventLog:

    /** The single value+metadata codec authority. Aliased to the data-only authority in
      * EventLogCodecs so `EventLog.Codecs`, `EventLog.ValueCodec`, and `EventLog.MetadataCodec`
      * are the public lock spellings. The aliases are plain (not adapters) because the public
      * type IS the data-only descriptor shape; `val Codecs = EventLogCodecs` surfaces the
      * schema factory as `EventLog.Codecs.schema`.
      */
    type Codecs[A] = EventLogCodecs.Codecs[A]
    val Codecs = EventLogCodecs
    type ValueCodec[A] = EventLogCodecs.ValueCodec[A]
    val ValueCodec = EventLogCodecs.ValueCodec
    type MetadataCodec = EventLogCodecs.MetadataCodec
    val MetadataCodec = EventLogCodecs.MetadataCodec

    /** Constructs a backend-free typed facade. No backend is captured. */
    def init[A](codecs: Codecs[A], journalId: JournalId)(using Frame): EventLog[A] < Sync =
        Sync.defer(new EventLog[A](journalId, codecs))

    /** Typed read-only facade; append is unrepresentable. */
    trait Reader[A, S]:
        def read(streamId: StreamId, from: StreamOffset, maxCount: Int)(using
            Frame
        ): Chunk[Record[A]] < (S & Abort[JournalReadFailure])
        def streamInfo(streamId: StreamId)(using Frame): StreamInfo < (S & Abort[JournalStreamInfoFailure])
    end Reader

    /** Builds a typed reader over a FileJournal SWMR reader. */
    def reader[A, P <: FileJournal.Profile, S](reader: FileJournal.Reader[A, P, S])(using Frame): Reader[A, S] < Sync =
        // Adapts the file reader's committed-frontier read into the typed Reader,
        // decoding through the reader's bound codecs; no writer lock, no mutation.
        Sync.defer(EventLog.mkReader(reader))

    /** A decoded record with a logical reference. Replaces the old `Typed`. */
    final case class Record[A](
        ref: JournalEntryRef,
        eventId: EventId,
        eventType: EventType,
        metadata: EventMetadata,
        payload: A
    ) derives CanEqual

    /** Per-member evidence: event type, stream, id policy, and metadata for concrete `E <: A`.
      * Carries no codec slot.
      */
    final case class EventDefinition[A, E <: A](
        eventType: EventType,
        stream: StreamSelector[E],
        eventId: EventIdPolicy[E],
        metadata: Metadata[E]
    )

    object EventDefinition:
        /** Schema-derived member evidence. */
        def schema[A, E <: A](
            stream: StreamSelector[E],
            eventId: EventIdPolicy[E] = EventIdPolicy.generated[E],
            metadata: Metadata[E] = Metadata.empty[E]
        )(using Schema[E], Frame): EventDefinition[A, E] =
            // Derives eventType from Schema[E].structure.name (non-empty guaranteed by the
            // EventType smart constructor; a failing derivation is a compile-time absence).
            EventDefinition(EventLog.deriveEventType[E], stream, eventId, metadata)
    end EventDefinition

    /** Stream selection, id policy, and metadata evidence types. */
    trait StreamSelector[E]
    trait EventIdPolicy[E]
    object EventIdPolicy:
        def generated[E]: EventIdPolicy[E] = // monotonic opaque-token policy
            GeneratedEventIdPolicy.asInstanceOf[EventIdPolicy[E]]
    trait Metadata[E]
    object Metadata:
        def empty[E]: Metadata[E] = EmptyMetadata.asInstanceOf[Metadata[E]]

    /** Expected-offset directive. `expected(expected)` is the single canonical spelling;
      * `withExpected` is removed.
      */
    final case class AppendDirective private (expectedOffset: Maybe[ExpectedOffset])
    object AppendDirective:
        val default: AppendDirective                            = AppendDirective(Absent)
        def expected(expected: ExpectedOffset): AppendDirective = AppendDirective(Present(expected))

    /** Typed preparation failure, distinct from Journal op failures. */
    final case class PreparationFailure(reason: String)(using Frame) extends KyoException

    // prepareEnvelope, appendValidated, mkReader, deriveEventType, GeneratedEventIdPolicy,
    // and EmptyMetadata are private[kyo] over the Journal/EventEnvelope surface.
    // appendValidated holds the one batch validator shared by varargs and any Chunk
    // overload.
end EventLog
