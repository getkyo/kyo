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
    codecs: EventLog.Codecs[A],
    routes: Event.Routes[A]
):

    /** A prepared, log-bound event ready to append or batch. Path-dependent on THIS log so a
      * command prepared against `log1` cannot be appended through `log2`. Carries the
      * per-command `streamId` resolved by `Definition.stream.resolve` at `prepare` time,
      * so `appendAll` groups by it without re-resolving.
      */
    final class Prepared private[EventLog] (
        private[kyo] val streamId: Event.StreamId,
        private[kyo] val envelope: Event.New,
        private[kyo] val directive: EventLog.AppendDirective
    )

    /** Instance-level given resolving the [[Event.Definition]] for a statically-known member
      * `E <: A` from the baked routes, so a log built through [[EventLog.setup]] satisfies the
      * unchanged `append`/`prepare`/`appendAll` `(using Event.Definition[A, E], Frame)`
      * signatures from `import log.given` with no hand-written per-member `given`. On the
      * [[EventLog.init]] path the routes are empty and this given is not brought into scope
      * (the init path supplies its own hand-written `given Event.Definition`s instead); mixing
      * `import log.given` with hand-written givens on one log is a usage error.
      */
    given routed[E <: A](using Schema[E]): Event.Definition[A, E] =
        routes.resolve[E]

    /** Prepares a union-typed value whose leaf is not statically known, resolving its
      * [[Event.Definition]] from the baked routes by runtime class (the derived-`EventCodec`
      * path); aborts [[EventLog.PreparationFailure]] on an unrouted concrete type. Mirrors
      * `prepare`'s own body, differing only in resolving the definition dynamically.
      */
    private[kyo] def prepareDynamic(event: A, directive: EventLog.AppendDirective)(using
        Frame
    ): Prepared < (Sync & Abort[EventLog.PreparationFailure]) =
        routes.resolveDynamic(event).flatMap { definition =>
            // The dynamically-resolved definition carries the erased member type; the runtime
            // value is the concrete member, so it is prepared at the erased leaf through the
            // same prepareEnvelope the statically-typed prepare uses.
            val erased = definition.asInstanceOf[Event.Definition[A, A]]
            Abort.get(EventLog.prepareEnvelope(codecs, erased, event, directive)).map((streamId, envelope) =>
                new Prepared(streamId, envelope, directive)
            )
        }

    /** Appends one concrete event. The `Event.Definition[A, E]` given supplies the event type,
      * stream, id policy, and metadata for `E`; the caller writes `log.append(event)`.
      */
    def append[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: Event.Definition[A, E],
        frame: Frame
    ): AppendResult < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        prepare(event, directive).flatMap(cmd => appendAll(cmd)).map(_.head)

    /** Prepares one concrete event into a log-bound [[Prepared]] without appending, so several
      * prepared commands can be widened and batched together.
      */
    def prepare[E <: A](event: E, directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
        ev: Event.Definition[A, E],
        frame: Frame
    ): Prepared < (Sync & Abort[EventLog.PreparationFailure]) =
        // Encodes via EventLogCodecs.encodeValue(codecs.value, event) (codecs.value is the
        // data-only descriptor; the interpreter performs the transform), resolves the real
        // per-member stream via ev.stream.resolve(event), the real event id via
        // ev.eventId.next(event, streamId, ev.eventType, metadata), and real metadata via
        // ev.metadata.values(event); assembles Event.New and wraps it, with the
        // resolved streamId, in a Prepared. Aborts PreparationFailure on stream/id resolution
        // failure. No Journal effect: prepare is pure staging.
        Abort.get(EventLog.prepareEnvelope(codecs, ev, event, directive)).map((streamId, envelope) =>
            new Prepared(streamId, envelope, directive)
        )

    /** Appends a nonempty batch atomically. Varargs are nonempty by construction (first is
      * required); `Chunk` batching shares the same validator.
      */
    def appendAll(first: Prepared, rest: Prepared*)(using
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
                    // Event.Recorded carries no logical ref: build it from this log's
                    // journalId (the EventLog[A] field) plus the record's streamId and
                    // offset. JournalEntryRef(journalId, streamId, offset) is the same
                    // shape JournalEntryRef.parse resolves back from a URI.
                    val ref = JournalEntryRef(journalId, rec.streamId, rec.offset)
                    Event.Record(ref, rec.id, rec.eventType, rec.metadata, a)
                }
            }
        }
end EventLog

/** Low-priority union derivation for [[EventLog.RouteCoverage]]: a bare union `A = M1 | M2 |
  * ...` has no `Mirror.SumOf`, so its coverage is proven by the union-decomposition macro.
  * Kept at lower priority than `derivedSum` so an enum/sealed `A` (which HAS a `Mirror.SumOf`)
  * resolves through `derivedSum` unambiguously.
  */
private[kyo] trait RouteCoverageLowPriority:
    inline given derivedUnion[A, Covered]: EventLog.RouteCoverage[A, Covered] =
        ${ kyo.internal.RouteCoverageMacros.unionCoverageImpl[A, Covered] }
end RouteCoverageLowPriority

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

    /** The pre-existing schema-derived codec-configuration failure, given its documented home
      * under `EventLog` (the same `final case class EventCodecConfigurationError(reason:
      * String)(using Frame) extends KyoException` `EventLog.Codecs.schema`/`bytes` raise),
      * mirroring the `type Codecs[A] = EventLogCodecs.Codecs[A]` alias idiom, so
      * `EventLog.EventCodecConfigurationError` and `Setup.build`'s
      * `Abort[EventCodecConfigurationError]` row resolve.
      */
    type EventCodecConfigurationError = kyo.EventCodecConfigurationError

    /** Constructs a backend-free typed facade. No backend is captured. */
    def init[A](codecs: Codecs[A], journalId: JournalId)(using Frame): EventLog[A] < Sync =
        Sync.defer(new EventLog[A](journalId, codecs, Event.Routes.empty[A]))

    /** Constructs an [[EventLog.Setup]] fluent builder that centralizes the scattered
      * per-member `given Event.Definition`s into one expression and bakes them into the
      * produced log behind `import log.given`. The pre-existing [[EventLog.init]] constructor
      * is untouched and stays beside this (additive, not a replacement).
      */
    def setup[A](journalId: JournalId): EventLog.Setup[A, Any] =
        new Setup[A, Any](journalId, Absent, Event.Routes.empty[A])

    /** Fluent builder for an [[EventLog]] of domain type `A`. `codecs`/`define` are PURE (no
      * effect); all effectful resolution is deferred to [[Setup.build]]. `Covered` is the
      * phantom running intersection of routed member types, widened by each `define[E]` to
      * `Covered & E`, so [[RouteCoverage]] proves at `build` that every member of `A` is
      * routed.
      */
    final class Setup[A, Covered] private[kyo] (
        journalId: JournalId,
        codecChoice: Maybe[(Codec, Codec, Codec)],
        routes: Event.Routes[A]
    ):
        /** Stages the value binary/json and metadata codec choices; defaults match
          * [[EventLog.Codecs.schema]]. Pure: the fallible assembly is deferred to `build`.
          */
        def codecs(binary: Codec = IonBinary(), json: Codec = Json(), metadata: Codec = IonBinary()): Setup[A, Covered] =
            new Setup[A, Covered](journalId, Present((binary, json, metadata)), routes)

        /** Registers member `E`'s full [[Event.Definition]] (event type, stream selector, id
          * policy, metadata producer), widening the phantom coverage to `Covered & E`. The
          * parameter names mirror [[Event.Definition.schema]] one-to-one.
          */
        def define[E <: A](
            stream: Event.StreamSelector[E],
            eventId: Event.IdPolicy[E] = Event.IdPolicy.generated[E],
            metadata: EventLog.Metadata[E] = EventLog.Metadata.empty[E]
        )(using Schema[E], Tag[E], Frame): Setup[A, Covered & E] =
            val definition = Event.Definition.schema[A, E](stream, eventId, metadata)
            new Setup[A, Covered & E](journalId, codecChoice, routes.added[E](definition))
        end define

        /** Assembles the staged codec choices into an [[EventLog.Codecs]] (the same fallible
          * Schema-plus-Codec assembly [[EventLog.Codecs.schema]] performs, hence the
          * `Abort[EventCodecConfigurationError]` row) and produces the [[EventLog]] carrying
          * the baked routes. `RouteCoverage[A, Covered]` proves every member of `A` is routed.
          */
        def build(using EventLog.RouteCoverage[A, Covered], Schema[A], Frame): EventLog[A] < (Sync & Abort[EventCodecConfigurationError]) =
            val (binary, json, metadata) = codecChoice.getOrElse((IonBinary(), Json(), IonBinary()))
            val resolvedRoutes           = routes.withMatcher(summon[EventLog.RouteCoverage[A, Covered]].matcher)
            EventLog.Codecs.schema[A](binary, json, metadata).map(codecs => new EventLog[A](journalId, codecs, resolvedRoutes))
        end build
    end Setup

    /** Compile-time proof that every member of domain type `A` is present in the accumulated
      * `Covered` intersection of an [[EventLog.Setup]]. Required as a `using` parameter on
      * [[Setup.build]]. For each member `Mi` of `A`, `Covered <:< Mi` (an intersection is a
      * subtype of exactly its components), else compilation fails naming the unrouted `Mi`.
      * Users never write a `RouteCoverage` instance; they rely on the summoned given.
      */
    sealed trait RouteCoverage[A, Covered]:
        /** Maps a runtime value of `A` to its member's `Schema[Mi].structure.name`, the key
          * `Event.Routes` stores each member under. Derived from compile-time member
          * enumeration alongside the coverage proof and consumed only by
          * `Event.Routes#resolveDynamic`; carried here because `Setup.build`'s using clause
          * exposes this evidence as its sole member-aware parameter. Coverage checking (which
          * unrouted members are rejected, and whether at compile time or at prepare time) and
          * value dispatch (this matcher) are separate concerns delivered on one evidence.
          */
        private[kyo] def matcher: A => String
    end RouteCoverage

    object RouteCoverage extends RouteCoverageLowPriority:
        final private class Evidence[A, Covered](val matcher: A => String) extends RouteCoverage[A, Covered]

        /** Materializer for a proven-covered pair; called by `derivedSum`, `derivedUnion`,
          * and the union macro's spliced quote AFTER the coverage check passes, carrying the
          * value-dispatch `matcher`. Public because those inline/macro sites expand into
          * caller code; not a user-facing construction path (users never write a
          * `RouteCoverage` instance).
          */
        def unchecked[A, Covered](matcher: A => String): RouteCoverage[A, Covered] = new Evidence[A, Covered](matcher)

        /** Derives coverage for an enum or sealed trait `A` by iterating its `Mirror.SumOf`
          * element types, asserting `Covered <:< Mi` per member, and builds the dispatch
          * matcher from the same element types.
          */
        inline given derivedSum[A, Covered](using m: scala.deriving.Mirror.SumOf[A]): RouteCoverage[A, Covered] =
            RouteCoverage.checkMembers[Covered, m.MirroredElemTypes]()
            RouteCoverage.unchecked[A, Covered](value => RouteCoverage.matchMember[A, m.MirroredElemTypes](value))

        inline def checkMembers[Covered, Elems <: Tuple](): Unit =
            inline scala.compiletime.erasedValue[Elems] match
                case _: EmptyTuple => ()
                case _: (head *: tail) =>
                    val _ = scala.compiletime.summonInline[Covered <:< head]
                    RouteCoverage.checkMembers[Covered, tail]()

        /** Selects `value`'s member by concrete-type test over the enumerated element types
          * and yields that member's `Schema[Mi].structure.name`, the exact key
          * `EventLog.Setup.define` stores it under. `isInstanceOf` is total and
          * cross-platform; the `Mirror.SumOf` enumeration makes the empty case unreachable.
          */
        inline def matchMember[A, Elems <: Tuple](value: A): String =
            inline scala.compiletime.erasedValue[Elems] match
                case _: EmptyTuple =>
                    throw new IllegalStateException("unreachable: Mirror.SumOf enumerates every member of the domain type")
                case _: (head *: tail) =>
                    if value.isInstanceOf[head] then scala.compiletime.summonInline[Schema[head]].structure.name
                    else RouteCoverage.matchMember[A, tail](value)
    end RouteCoverage

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

    /** Immutable summary of an [[EventLog.migrate]] or [[EventLog.migrateWith]] call: one
      * [[MigrationReport.StreamSummary]] per stream named in the call's `streams` argument, in
      * the same order. No side channel; every observable outcome of a migrate call is carried
      * here.
      */
    final case class MigrationReport(streams: Chunk[MigrationReport.StreamSummary]) derives CanEqual
    object MigrationReport:
        /** One named stream's outcome: the number of events copied by THIS call (never a
          * cumulative or all-time count) and the target stream's last offset after the call
          * completes. `lastOffset` is `Absent` only when the target stream is genuinely empty
          * after the call, which happens exactly when the source stream itself has zero events
          * and the target had none beforehand; a stream with prior target-side data reports its
          * carried-over last offset even when `copied == 0`.
          */
        final case class StreamSummary(streamId: Event.StreamId, copied: Long, lastOffset: Maybe[Event.StreamOffset]) derives CanEqual
    end MigrationReport

    /** Copies every event of every stream named in `streams` from `source` to `target`,
      * preserving each event's identity (id, type, metadata) and raw payload bytes exactly: no
      * value decode, transform, or re-encode, so a source and target configured with different
      * on-disk profiles (for example a Binary-segment source and a JSONL-segment target) migrate
      * with no type obstacle, since both satisfy the identical raw [[Journal.Reader]]/
      * [[Journal.Backend]] contract regardless of their bound value type. Before each stream's
      * first append, reads `target`'s current [[StreamInfo]] and seeds the optimistic-append
      * expectation from it (`NoStream` for a genuinely absent target stream, `Exact` otherwise),
      * so a target that already carries data for a named stream is extended contiguously rather
      * than assumed empty. Carries no deduplication: calling `migrate` again over an unchanged
      * source and the same target re-appends the same events again, since a raw journal has no
      * idempotency key to detect a repeat.
      *
      * @see
      *   [[EventLog.migrateWith]] for the value-transforming rewrite variant
      * @see
      *   [[MigrationReport]] for the per-stream outcome this returns
      */
    def migrate[S1, S2](
        source: Journal.Reader[S1],
        target: Journal.Backend[S2],
        streams: Chunk[Event.StreamId]
    )(using
        Frame
    ): MigrationReport < (S1 & S2 & Abort[JournalReadFailure] & Abort[JournalAppendFailure] & Abort[JournalStreamInfoFailure]) =
        Kyo.foreach(streams)(streamId => EventLog.copyStreamRaw(source, target, streamId)).map(MigrationReport(_))

    /** Value-transforming rewrite-migrate: reads each stream named in `streams` from `source`
      * through its bound value codec, applies `transform` to each decoded value, re-encodes the
      * result through `targetCodecs`, and appends the re-encoded bytes to `target`. Each event's
      * id, type, and metadata carry over unchanged from `source`; only the payload changes
      * shape, from `X` to `Y`. Pays the migration cost once: the target's stored bytes are
      * genuinely `Y`, not a read-time view. Seeds each stream's optimistic-append expectation
      * from `target`'s current [[StreamInfo]], identically to [[EventLog.migrate]].
      *
      * @see
      *   [[EventLog.Reader]]'s `upcast` extension for the zero-cost read-time adapter variant
      * @see
      *   [[MigrationReport]] for the per-stream outcome this returns
      */
    def migrateWith[X, Y, S1, S2](
        source: EventLog.Reader[X, S1],
        target: Journal.Backend[S2],
        targetCodecs: EventLog.Codecs[Y],
        streams: Chunk[Event.StreamId],
        transform: X => Y < (S1 & S2)
    )(using
        Frame
    ): MigrationReport < (S1 & S2 & Sync & Abort[JournalReadFailure] & Abort[JournalAppendFailure] & Abort[JournalStreamInfoFailure]) =
        Kyo.foreach(streams)(streamId =>
            EventLog.copyStreamWith(source, target, targetCodecs, streamId, transform)
        ).map(MigrationReport(_))

    // prepareEnvelope, appendValidated, mkReader, deriveEventType, copyStreamRaw, and
    // copyStreamWith are private[kyo] over the Journal/Event.New surface, defined in
    // EventLogSupport.scala. appendValidated holds the one batch validator shared by varargs and
    // any Chunk overload, and resolves each contiguous run's directive-supplied expectedOffset
    // (only the run's head command may carry a non-absent one). copyStreamRaw and copyStreamWith
    // hold migrate's and migrateWith's shared per-stream read-page/append-page loop. The
    // StreamSelector/IdPolicy/AppendDirective witness classes
    // (ConstantStreamSelector, KeyedStreamSelector, GeneratedEventIdPolicy,
    // DeterministicEventIdPolicy, CallerSuppliedEventIdPolicy, AppendDirective.Impl) are defined
    // in JournalEvent.scala (alongside sealed trait Event and object Event), not this file,
    // because StreamSelector and IdPolicy are sealed and nested under object Event: every direct
    // subtype of a sealed trait must live in the same source file as the trait, and Scala
    // requires a companion object's nested members to be declared in one physical file.
end EventLog

/** Read-time value adapter over a typed reader: applies `f` to every decoded record without
  * touching stored bytes. Zero migration cost since the transform runs on each read; the append
  * lane is absent by type (there is no meaningful way to append a `Y` and store it as `X`), so
  * this returns a plain [[EventLog.Reader]], never a full [[EventLog]] facade or a
  * [[Journal.Backend]].
  *
  * @see
  *   [[EventLog.migrateWith]] for the variant that pays the cost once and rewrites the target
  */
extension [X, S](reader: EventLog.Reader[X, S])
    def upcast[Y](f: X => Y)(using Frame): EventLog.Reader[Y, S] =
        new EventLog.Reader[Y, S]:
            def read(streamId: Event.StreamId, from: Event.StreamOffset, maxCount: Int)(using
                Frame
            ): Chunk[Event.Record[Y]] < (S & Abort[JournalReadFailure]) =
                reader.read(streamId, from, maxCount).map(_.map(rec =>
                    Event.Record(rec.ref, rec.eventId, rec.eventType, rec.metadata, f(rec.payload))
                ))
            def streamInfo(streamId: Event.StreamId)(using Frame): StreamInfo < (S & Abort[JournalStreamInfoFailure]) =
                reader.streamInfo(streamId)
        end new
end extension
