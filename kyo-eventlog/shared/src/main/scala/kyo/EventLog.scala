package kyo

/** Typed, backend-free program facade over the raw `Journal` ArrowEffect for domain event
  * type `A`. Constructed from an [[EventLog.Codecs]] and a [[JournalId]] via
  * [[EventLog.init]]; captures no backend. Every operation is an ordinary `Journal` program
  * run inside `Journal.run(backend)(program)`.
  *
  * `append`, `prepare`, and `appendAll` hide member evidence and `Frame` plumbing behind an
  * [[Event.Definition]] given, so a union/enum/sealed-trait concrete event appends
  * with `log.append(event)` and no visible membership witness.
  *
  * @tparam A the domain event type (union, enum, or sealed trait)
  */
final class EventLog[A] private (
    val journalId: JournalId,
    codecs: EventLog.Codecs[A]
):

    /** A prepared, log-bound event ready to append or batch. Path-dependent on THIS log so a
      * command prepared against `log1` cannot be appended through `log2`. Carries the
      * per-command `streamId` resolved by `Definition.stream.resolve` at `prepare` time,
      * so `appendAll` groups by it without re-resolving.
      */
    final class Command private[EventLog] (
        private[kyo] val streamId: Event.StreamId,
        private[kyo] val envelope: Event.Pending,
        private[kyo] val directive: EventLog.AppendDirective
    )

    /** Appends one concrete event. The `Event.Definition[A, E]` given supplies the event type,
      * stream, id policy, and metadata for `E`; the caller writes `log.append(event)`.
      */
    def append[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: Event.Definition[A, E],
        frame: Frame
    ): AppendResult < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        prepare(event, directive).flatMap(cmd => appendAll(cmd)).map(_.head)

    /** Prepares one concrete event into a log-bound [[Command]] without appending, so several
      * prepared commands can be widened and batched together.
      */
    def prepare[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: Event.Definition[A, E],
        frame: Frame
    ): Command < (Sync & Abort[EventLog.PreparationFailure]) =
        // Encodes via EventLogCodecs.encodeValue(codecs.value, event) (codecs.value is the
        // data-only descriptor; the interpreter performs the transform), resolves the real
        // per-member stream via ev.stream.resolve(event), the real event id via
        // ev.eventId.next(event, streamId, ev.eventType, metadata), and real metadata via
        // ev.metadata.values(event); assembles Event.Pending and wraps it, with the
        // resolved streamId, in a Command. Aborts PreparationFailure on stream/id resolution
        // failure. No Journal effect: prepare is pure staging.
        Abort.get(EventLog.prepareEnvelope(codecs, ev, event, directive)).map((streamId, envelope) =>
            new Command(streamId, envelope, directive)
        )

    /** Appends a nonempty batch atomically. Varargs are nonempty by construction (first is
      * required); `Chunk` batching shares the same validator.
      */
    def appendAll(first: Command, rest: Command*)(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        val commands = first +: Chunk.from(rest)
        // Groups commands into contiguous runs by resolved streamId (first-occurrence order
        // preserved across the whole batch) and issues one Journal.append per run,
        // never a single journalId-derived stream for the whole batch. Shared with any Chunk
        // overload through the single private validateBatch.
        EventLog.appendValidated(commands)
    end appendAll

    /** Backend-free typed read-only lane. Produces an [[EventLog.Reader]] that reads committed
      * records and cannot append.
      */
    def read(
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int
    )(using Frame): Chunk[Event.Record[A]] < (Journal & Abort[JournalReadFailure]) =
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
                    // Event.Committed carries no logical ref: build it from this log's
                    // journalId (the EventLog[A] field) plus the record's streamId and
                    // offset. JournalEntryRef(journalId, streamId, offset) is the same
                    // shape JournalEntryRef.parse resolves back from a URI.
                    val ref = JournalEntryRef(journalId, rec.streamId, rec.offset)
                    Event.Record(ref, rec.id, rec.eventType, rec.metadata, a)
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
        def read(streamId: Event.StreamId, from: Event.StreamOffset, maxCount: Int)(using
            Frame
        ): Chunk[Event.Record[A]] < (S & Abort[JournalReadFailure])
        def streamInfo(streamId: Event.StreamId)(using Frame): StreamInfo < (S & Abort[JournalStreamInfoFailure])
    end Reader

    /** Builds a typed reader over a FileJournal SWMR reader. Carries no P type parameter:
      * FileJournal.Reader[A, S] carries no profile identity.
      */
    def reader[A, S](reader: FileJournal.Reader[A, S])(using Frame): Reader[A, S] < Sync =
        // Adapts the file reader's committed-frontier read into the typed Reader,
        // decoding through the reader's bound codecs; no writer lock, no mutation.
        Sync.defer(EventLog.mkReader(reader))

    /** Produces metadata for a concrete event. A real data carrier (not a marker): the values
      * function is invoked at `prepare` time via `ev.metadata.values(event)`.
      */
    final case class Metadata[E](values: E => Event.Metadata) derives CanEqual
    object Metadata:
        def empty[E]: Metadata[E]                        = Metadata(_ => Event.Metadata.empty)
        def from[E](f: E => Event.Metadata): Metadata[E] = Metadata(f)

        /** Builds Metadata[E] from AttributeBinding[E] values (AttributeKey#from/const/option),
          * folding each event's produced attributes into one Event.Metadata at prepare time. The
          * closure captures the Frame supplied at this call site: Metadata[E]'s own `values`
          * field carries no per-call Frame parameter.
          */
        def of[E](attrs: Event.AttributeBinding[E]*)(using frame: Frame): Metadata[E] =
            Metadata(event =>
                attrs.foldLeft(Event.Metadata.empty) { (acc, binding) =>
                    binding(event) match
                        case Present(attr) => acc.put(attr)(using frame)
                        case Absent        => acc
                }
            )
    end Metadata

    /** Directive carrying independent, composable-by-construction override facets. `expected`
      * is the single canonical expected-offset spelling (`withExpected` is removed).
      * `replaceStreamId`/`replaceEventId` replace the Definition's StreamSelector/
      * IdPolicy outright for that command: the strategy is not consulted at all when the
      * facet is overridden. `withMetadata` right-biased-merges over member-produced metadata:
      * member keys survive except where the directive's keys collide, where the directive's
      * value wins. Each factory sets exactly one facet starting from `default`; combining
      * facets in a single directive is not supported through these factories.
      */
    sealed trait AppendDirective derives CanEqual:
        private[kyo] def expectedOffset: Maybe[ExpectedOffset]
        private[kyo] def streamIdOverride: Maybe[Event.StreamId]
        private[kyo] def eventIdOverride: Maybe[Event.Id]
        private[kyo] def metadataOverride: Maybe[Event.Metadata]
    end AppendDirective

    object AppendDirective:
        final private[kyo] case class Impl(
            expectedOffset: Maybe[ExpectedOffset],
            streamIdOverride: Maybe[Event.StreamId],
            eventIdOverride: Maybe[Event.Id],
            metadataOverride: Maybe[Event.Metadata]
        ) extends AppendDirective

        val default: AppendDirective                                   = Impl(Absent, Absent, Absent, Absent)
        def expected(expected: ExpectedOffset): AppendDirective        = Impl(Present(expected), Absent, Absent, Absent)
        def replaceStreamId(streamId: Event.StreamId): AppendDirective = Impl(Absent, Present(streamId), Absent, Absent)
        def replaceEventId(eventId: Event.Id): AppendDirective         = Impl(Absent, Absent, Present(eventId), Absent)
        def withMetadata(metadata: Event.Metadata): AppendDirective    = Impl(Absent, Absent, Absent, Present(metadata))
    end AppendDirective

    /** Typed preparation failure, distinct from Journal op failures. */
    final case class PreparationFailure(reason: String)(using Frame) extends KyoException

    // prepareEnvelope, appendValidated, mkReader, and deriveEventType are private[kyo] over the
    // Journal/Event.Pending surface, defined in EventLogSupport.scala. appendValidated holds the
    // one batch validator shared by varargs and any Chunk overload, and resolves each contiguous
    // run's directive-supplied expectedOffset (only the run's head command may carry a
    // non-absent one). The StreamSelector/IdPolicy/AppendDirective witness classes
    // (ConstantStreamSelector, KeyedStreamSelector, GeneratedEventIdPolicy,
    // DeterministicEventIdPolicy, CallerSuppliedEventIdPolicy, AppendDirective.Impl) are defined
    // in JournalEvent.scala (alongside sealed trait Event and object Event), not this file,
    // because StreamSelector and IdPolicy are sealed and nested under object Event: every direct
    // subtype of a sealed trait must live in the same source file as the trait, and Scala
    // requires a companion object's nested members to be declared in one physical file.
end EventLog
