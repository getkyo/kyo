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
      * command prepared against `log1` cannot be appended through `log2`. Carries the
      * per-command `streamId` resolved by `EventDefinition.stream.resolve` at `prepare` time,
      * so `appendAll` groups by it without re-resolving.
      */
    final class Command private[EventLog] (
        private[kyo] val streamId: StreamId,
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
        // data-only descriptor; the interpreter performs the transform), resolves the real
        // per-member stream via ev.stream.resolve(event), the real event id via
        // ev.eventId.next(event, streamId, ev.eventType, metadata), and real metadata via
        // ev.metadata.values(event); assembles EventEnvelope and wraps it, with the
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

    /** Builds a typed reader over a FileJournal SWMR reader. Carries no P type parameter:
      * FileJournal.Reader[A, S] carries no profile identity.
      */
    def reader[A, S](reader: FileJournal.Reader[A, S])(using Frame): Reader[A, S] < Sync =
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

    /** A validated, human-legible stream family name: the first component of every
      * `by`/`canonical`-derived stream id.
      */
    opaque type StreamName = String
    object StreamName:
        def apply(value: String)(using Frame): StreamName < Abort[EventLog.PreparationFailure] =
            if value.isEmpty then Abort.fail(EventLog.PreparationFailure("stream name must not be empty"))
            else value: StreamName

        extension (self: StreamName)
            /** The underlying stream-name string. */
            def value: String = self
    end StreamName

    /** Derives one or more non-empty key components from a concrete event, combined with a
      * [[StreamName]] by [[StreamSelector.canonical]] into a collision-safe stream id.
      */
    final case class StreamKey[E] private (components: E => Chunk[String]) derives CanEqual
    object StreamKey:
        // The primary constructor is private so the case class's own synthesized apply (which
        // would otherwise be public and identical in arity save for this using clause) is private
        // too, leaving this apply as the sole public constructor path. Constructs via `new` (not
        // a bare StreamKey(components) call) so this method does not recurse into itself: both
        // the private synthesized apply and this apply remain visible from inside the companion.
        def apply[E](components: E => Chunk[String])(using Frame): StreamKey[E] = new StreamKey(components)
    end StreamKey

    /** Resolves the target stream for one concrete event. A real behavioral contract:
      * every concrete event routes to its resolved stream, never a stubbed marker.
      */
    sealed trait StreamSelector[E] derives CanEqual:
        def resolve(event: E)(using Frame): StreamId < Abort[EventLog.PreparationFailure]

    object StreamSelector:
        /** Every event routes to the same fixed stream, regardless of its content. */
        def constant[E](streamId: StreamId): StreamSelector[E] =
            ConstantStreamSelector(streamId)

        /** Every event routes to `name`/`f(event)`'s length-prefixed canonical stream id. */
        def by[E](name: StreamName)(f: E => Chunk[String]): StreamSelector[E] =
            KeyedStreamSelector(name, f)

        /** Every event routes to `name`/`key.components(event)`'s length-prefixed canonical
          * stream id.
          */
        def canonical[E](name: StreamName, key: StreamKey[E]): StreamSelector[E] =
            KeyedStreamSelector(name, key.components)
    end StreamSelector

    /** Resolves the event id for one concrete event, given its resolved stream, type, and
      * metadata. A real behavioral contract. `generated` reads the shared monotonic
      * counter through the same internal Unsafe bridging pattern `prepareEnvelope` already
      * uses, so the row carries no `Sync`; `deterministic`/`callerSupplied` are pure over
      * their declared inputs.
      */
    sealed trait EventIdPolicy[E] derives CanEqual:
        def next(event: E, streamId: StreamId, eventType: EventType, metadata: EventMetadata)(using
            Frame
        )
            : EventId < Abort[EventLog.PreparationFailure]
    end EventIdPolicy

    object EventIdPolicy:
        /** Every event gets a fresh monotonic token from the shared in-process counter. */
        def generated[E]: EventIdPolicy[E] =
            GeneratedEventIdPolicy.asInstanceOf[EventIdPolicy[E]]

        /** Every event's id is `f(event, streamId, eventType, metadata)`, validated as an
          * [[EventId]]. Repeats for equal inputs and equal `f` output.
          */
        def deterministic[E](f: (E, StreamId, EventType, EventMetadata) => String)(using
            Frame
        ): EventIdPolicy[E] < Abort[EventLog.PreparationFailure] =
            DeterministicEventIdPolicy(f)

        /** Every event's id is `f(event)`, validated as an [[EventId]]. */
        def callerSupplied[E](f: E => String)(using Frame): EventIdPolicy[E] < Abort[EventLog.PreparationFailure] =
            CallerSuppliedEventIdPolicy(f)
    end EventIdPolicy

    /** Produces metadata for a concrete event. A real data carrier (not a marker): the values
      * function is invoked at `prepare` time via `ev.metadata.values(event)`.
      */
    final case class Metadata[E](values: E => EventMetadata) derives CanEqual
    object Metadata:
        def empty[E]: Metadata[E]                       = Metadata(_ => EventMetadata.empty)
        def from[E](f: E => EventMetadata): Metadata[E] = Metadata(f)

        /** Builds Metadata[E] from AttributeBinding[E] values (AttributeKey#from/const/option),
          * folding each event's produced attributes into one EventMetadata at prepare time. The
          * closure captures the Frame supplied at this call site: Metadata[E]'s own `values`
          * field carries no per-call Frame parameter.
          */
        def of[E](attrs: EventLog.AttributeBinding[E]*)(using frame: Frame): Metadata[E] =
            Metadata(event =>
                attrs.foldLeft(EventMetadata.empty) { (acc, binding) =>
                    binding(event) match
                        case Present(attr) => acc.put(attr)(using frame)
                        case Absent        => acc
                }
            )
    end Metadata

    /** Typed, Schema[A]-checked facade over EventMetadata's unchanged wire form. Names a stable
      * wire key; the key's Schema[A] encodes/decodes its attribute's value through the same
      * StructureValueWriter/StructureValueReader bridge EventMetadata.get/put use. A
      * heterogeneous type-map keyed by erased Scala type is rejected: it has no stable,
      * cross-platform, cross-version wire identity, and two attributes of the same type would
      * collide; keying on this explicit validated MetadataKey string is the wire-safe
      * alternative.
      */
    final case class AttributeKey[A](key: MetadataKey)(using val schema: Schema[A])

    /** One produced key-value pair for an event, consumed by EventMetadata.of/put. */
    final case class Attribute[A](key: AttributeKey[A], value: A)

    /** A per-member attribute-producer function: given the event, yield one Attribute or skip. */
    type AttributeBinding[E] = E => Maybe[Attribute[?]]

    extension [A](key: AttributeKey[A])
        /** Derives the attribute's value from the event. */
        def from[E](f: E => A): AttributeBinding[E] = event => Present(Attribute(key, f(event)))

        /** Produces a fixed attribute value regardless of the event. */
        def const[E](value: A): AttributeBinding[E] = _ => Present(Attribute(key, value))

        /** Produces the attribute only when `f` yields a present value. */
        def option[E](f: E => Maybe[A]): AttributeBinding[E] = event => f(event).map(v => Attribute(key, v))
    end extension

    /** Starter named-attribute vocabulary; users define their own AttributeKey vals alongside
      * these in their own vocabulary objects.
      */
    object Attributes:
        val CorrelationId: AttributeKey[String] =
            AttributeKey(MetadataKey("trace.correlation_id")(using Frame.internal).getOrThrow)
        val SourceSystem: AttributeKey[String] =
            AttributeKey(MetadataKey("source.system")(using Frame.internal).getOrThrow)
    end Attributes

    /** Directive carrying independent, composable-by-construction override facets. `expected`
      * is the single canonical expected-offset spelling (`withExpected` is removed).
      * `replaceStreamId`/`replaceEventId` replace the EventDefinition's StreamSelector/
      * EventIdPolicy outright for that command: the strategy is not consulted at all when the
      * facet is overridden. `withMetadata` right-biased-merges over member-produced metadata:
      * member keys survive except where the directive's keys collide, where the directive's
      * value wins. Each factory sets exactly one facet starting from `default`; combining
      * facets in a single directive is not supported through these factories.
      */
    sealed trait AppendDirective derives CanEqual:
        private[kyo] def expectedOffset: Maybe[ExpectedOffset]
        private[kyo] def streamIdOverride: Maybe[StreamId]
        private[kyo] def eventIdOverride: Maybe[EventId]
        private[kyo] def metadataOverride: Maybe[EventMetadata]
    end AppendDirective

    object AppendDirective:
        final private[kyo] case class Impl(
            expectedOffset: Maybe[ExpectedOffset],
            streamIdOverride: Maybe[StreamId],
            eventIdOverride: Maybe[EventId],
            metadataOverride: Maybe[EventMetadata]
        ) extends AppendDirective

        val default: AppendDirective                               = Impl(Absent, Absent, Absent, Absent)
        def expected(expected: ExpectedOffset): AppendDirective    = Impl(Present(expected), Absent, Absent, Absent)
        def replaceStreamId(streamId: StreamId): AppendDirective   = Impl(Absent, Present(streamId), Absent, Absent)
        def replaceEventId(eventId: EventId): AppendDirective      = Impl(Absent, Absent, Present(eventId), Absent)
        def withMetadata(metadata: EventMetadata): AppendDirective = Impl(Absent, Absent, Absent, Present(metadata))
    end AppendDirective

    /** Typed preparation failure, distinct from Journal op failures. */
    final case class PreparationFailure(reason: String)(using Frame) extends KyoException

    // prepareEnvelope, appendValidated, mkReader, and deriveEventType are private[kyo] over the
    // Journal/EventEnvelope surface, defined in EventLogSupport.scala. appendValidated holds the
    // one batch validator shared by varargs and any Chunk overload, and resolves each contiguous
    // run's directive-supplied expectedOffset (only the run's head command may carry a
    // non-absent one). The StreamSelector/EventIdPolicy/AppendDirective witness classes
    // (ConstantStreamSelector, KeyedStreamSelector, GeneratedEventIdPolicy,
    // DeterministicEventIdPolicy, CallerSuppliedEventIdPolicy, AppendDirective.Impl) are defined
    // in this file rather than EventLogSupport.scala, because StreamSelector, EventIdPolicy, and
    // AppendDirective are sealed: every direct subtype of a sealed trait must live in the same
    // source file as the trait.
end EventLog

/** Every event routes to the same fixed stream, regardless of its content. Built by
  * [[EventLog.StreamSelector.constant]].
  */
final private[kyo] case class ConstantStreamSelector[E](streamId: StreamId) extends EventLog.StreamSelector[E]:
    def resolve(event: E)(using Frame): StreamId < Abort[EventLog.PreparationFailure] = streamId

/** Every event routes to `name`/`components(event)`'s length-prefixed canonical stream id.
  * Built by [[EventLog.StreamSelector.by]] and [[EventLog.StreamSelector.canonical]].
  */
final private[kyo] case class KeyedStreamSelector[E](name: EventLog.StreamName, components: E => Chunk[String])
    extends EventLog.StreamSelector[E]:
    def resolve(event: E)(using Frame): StreamId < Abort[EventLog.PreparationFailure] =
        Abort.get(EventLogSupport.resolveKeyedStream(name, components(event)))
end KeyedStreamSelector

/** Every event gets a fresh monotonic token from the shared in-process counter, ignoring its
  * resolved stream, type, and metadata. Built by [[EventLog.EventIdPolicy.generated]].
  */
private[kyo] object GeneratedEventIdPolicy extends EventLog.EventIdPolicy[Any]:
    def next(event: Any, streamId: StreamId, eventType: EventType, metadata: EventMetadata)(using
        Frame
    ): EventId < Abort[EventLog.PreparationFailure] =
        // Unsafe: the counter increment is a synchronous computation with no real suspension
        // point; evaluated inline so this row carries no Sync, matching
        // deterministic/callerSupplied's pure rows.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow[EventId](EventLogSupport.freshEventId())
    end next
end GeneratedEventIdPolicy

/** Every event's id is `f(event, streamId, eventType, metadata)`, validated as an [[EventId]].
  * Repeats for equal inputs and equal `f` output. Built by
  * [[EventLog.EventIdPolicy.deterministic]].
  */
final private[kyo] case class DeterministicEventIdPolicy[E](f: (E, StreamId, EventType, EventMetadata) => String)
    extends EventLog.EventIdPolicy[E]:
    def next(event: E, streamId: StreamId, eventType: EventType, metadata: EventMetadata)(using
        Frame
    ): EventId < Abort[EventLog.PreparationFailure] =
        Abort.get(EventId(f(event, streamId, eventType, metadata)).mapFailure(e =>
            EventLog.PreparationFailure(s"deterministic event id policy produced an invalid id: ${e.getMessage()}")
        ))
end DeterministicEventIdPolicy

/** Every event's id is `f(event)`, validated as an [[EventId]]. Built by
  * [[EventLog.EventIdPolicy.callerSupplied]].
  */
final private[kyo] case class CallerSuppliedEventIdPolicy[E](f: E => String) extends EventLog.EventIdPolicy[E]:
    def next(event: E, streamId: StreamId, eventType: EventType, metadata: EventMetadata)(using
        Frame
    ): EventId < Abort[EventLog.PreparationFailure] =
        Abort.get(EventId(f(event)).mapFailure(e =>
            EventLog.PreparationFailure(s"caller-supplied event id is invalid: ${e.getMessage()}")
        ))
end CallerSuppliedEventIdPolicy
