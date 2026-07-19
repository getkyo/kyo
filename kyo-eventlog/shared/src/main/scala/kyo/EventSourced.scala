package kyo

/** J3 event-sourcing vocabulary over the typed EventLog and raw Journal.
  * Excludes Flow, Actor, Aeron, tenancy, upcasting, and Marten daemon integrations.
  */
object EventSourced:

    /** Decode/prepare adapter over an [[EventLog]], without a second byte-codec authority
      * (the EventLog.Codecs authority is reused). `decode` maps an already-decoded
      * [[Event.Record]] to the domain type: byte decoding and corruption handling happen
      * earlier in `EventLog.read` (surfaced as `JournalReadFailure`), so `decode`'s only
      * failure is a domain/schema-evolution mismatch raised as [[EventCodecError]].
      */
    trait EventCodec[A]:
        def prepare(log: EventLog[A])(event: A, directive: EventLog.AppendDirective)(using
            Frame
        ): log.Prepared < (Sync & Abort[EventCodecError] & Abort[EventLog.PreparationFailure])
        // decode is a domain mapping over an already-decoded Record, not a second byte-decode.
        def decode(record: Event.Record[A])(using Frame): A < Abort[EventCodecError]
    end EventCodec

    /** Adapter error, distinct from EventLog preparation and Journal op failures. */
    final case class EventCodecError(reason: String)(using Frame) extends KyoException

    /** Connects the typed EventLog and the J3 codec adapter without capturing a Journal
      * backend. Construct once with [[EventStore.init]].
      */
    final case class EventStore[A] private[kyo] (log: EventLog[A], codec: EventCodec[A]):

        /** Reads typed stream history through the raw Journal capability. */
        def read(streamId: Event.StreamId, from: Event.StreamOffset, maxCount: Int)(using
            Frame
        ): Chunk[Event.Record[A]] < (Journal & Abort[JournalReadFailure]) =
            log.read(streamId, from, maxCount)

        /** Prepares through EventLog and appends through Journal without manual evidence
          * plumbing.
          */
        def appendAll(events: Chunk[A], directive: EventLog.AppendDirective = EventLog.AppendDirective.default)(using
            Frame
        ): Chunk[AppendResult] < (Journal & Sync & Abort[EventCodecError] & Abort[EventLog.PreparationFailure] & Abort[
            JournalAppendFailure
        ]) =
            EventSourced.appendPrepared(this, events, directive)
    end EventStore

    object EventStore:
        /** Binds EventCodec[A] into the store so the whole program is handled by
          * Journal.run(backend)(program).
          */
        def init[A](log: EventLog[A], codec: EventCodec[A])(using Frame): EventStore[A] < Sync =
            Sync.defer(EventStore(log, codec))

        /** Constructs an [[EventStore.Builder]] fluent builder that collapses the codecs +
          * routes + optional event codec + store init steps into one expression, wrapping an
          * [[EventLog.Builder]].
          */
        def builder[A](journalId: JournalId): EventStore.Builder[A, Any] =
            new Builder[A, Any](EventLog.builder[A](journalId), Absent)

        /** Fluent builder for an [[EventStore]] of domain type `A`, wrapping an
          * [[EventLog.Builder]] (sharing its `Covered` phantom and its [[EventLog.RouteCoverage]]
          * completeness proof). `codecs`/`define`/`codec` are PURE; all effectful resolution
          * is deferred to [[Builder.build]].
          */
        final class Builder[A, Covered] private[kyo] (
            log: EventLog.Builder[A, Covered],
            codec: Maybe[EventCodec[A]]
        ):
            /** Delegates to the wrapped [[EventLog.Builder.codecs]]; optional, as on the wrapped
              * builder (skipping it applies the same defaults at `build`).
              */
            def codecs(binary: Codec = IonBinary(), json: Codec = Json(), metadata: Codec = IonBinary()): Builder[A, Covered] =
                new Builder[A, Covered](log.codecs(binary, json, metadata), codec)

            /** Delegates to the wrapped [[EventLog.Builder.define]], widening coverage to
              * `Covered & E`.
              */
            def define[E <: A](
                stream: Event.StreamSelector[E],
                eventId: Event.IdPolicy[E] = Event.IdPolicy.generated[E],
                metadata: EventLog.Metadata[E] = EventLog.Metadata.empty[E]
            )(using Schema[E], Tag[E], Frame): Builder[A, Covered & E] =
                new Builder[A, Covered & E](log.define[E](stream, eventId, metadata), codec)

            /** Supplies an explicit [[EventCodec]]; when omitted, `build` derives a default one
              * from the built log's baked routes.
              */
            def codec(codec: EventSourced.EventCodec[A]): Builder[A, Covered] =
                new Builder[A, Covered](log, Present(codec))

            /** Runs the wrapped [[EventLog.Builder.build]] to obtain the [[EventLog]]
              * (propagating its `Abort[EventLog.EventCodecConfigurationError]` codec-assembly
              * row), then [[EventStore.init]] with the supplied-or-derived [[EventCodec]].
              */
            def build(using
                EventLog.RouteCoverage[A, Covered],
                Schema[A],
                Frame
            ): EventStore[A] < (Sync & Abort[EventLog.EventCodecConfigurationError]) =
                log.build.map { builtLog =>
                    val eventCodec = codec.getOrElse(EventSourced.derivedEventCodec[A])
                    EventStore(builtLog, eventCodec)
                }
        end Builder
    end EventStore

    /** Default [[EventCodec]] derived from an [[EventLog]]'s baked routes when
      * [[EventStore.Builder.codec]] is omitted: `prepare` resolves the member's
      * [[Event.Definition]] dynamically by runtime class (via the log's routes) and `decode`
      * is the identity over the already-decoded `Event.Record`'s payload. The log to prepare
      * against is the `EventCodec.prepare` parameter, so this factory takes none.
      */
    private[kyo] def derivedEventCodec[A]: EventCodec[A] =
        new EventCodec[A]:
            def prepare(l: EventLog[A])(event: A, directive: EventLog.AppendDirective)(using
                Frame
            ): l.Prepared < (Sync & Abort[EventCodecError] & Abort[EventLog.PreparationFailure]) =
                l.prepareDynamic(event, directive)
            def decode(record: Event.Record[A])(using Frame): A < Abort[EventCodecError] =
                record.payload

    /** Decider authoring contract: decide a command against current state, evolve state by an
      * event. Failure rows are explicit.
      */
    trait Decider[Command, State, Event]:
        def initialState: State
        def decide(state: State, command: Command)(using Frame): Chunk[Event] < Abort[DecideFailure]
        def evolve(state: State, event: Event)(using Frame): State < Abort[EvolveFailure]
    end Decider

    final case class DecideFailure(reason: String)(using Frame) extends KyoException
    final case class EvolveFailure(reason: String)(using Frame) extends KyoException

    /** Self-contained J3 read, decode, evolve, decide, prepare, and append operation. Takes an
      * EventStore (no backend parameter, no call-site EventDefinition evidence).
      */
    def decide[Command, State, Event](
        store: EventStore[Event],
        streamId: Event.StreamId,
        command: Command,
        decider: Decider[Command, State, Event]
    )(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventCodecError] & Abort[DecideFailure] & Abort[EvolveFailure] & Abort[
        EventLog.PreparationFailure
    ] & Abort[JournalReadFailure] & Abort[JournalAppendFailure]) =
        // Reads full stream history, decodes each via store.codec.decode, folds through
        // decider.evolve from decider.initialState to current State, runs decider.decide(state,
        // command) to a Chunk[Event], and store.appendAll(events). One ordinary Journal program.
        EventSourced.runDecision(store, streamId, command, decider)

    /** J3 reader-consumption, projection, subscription-factory, and snapshot contracts
      * (registered components; zero integrations).
      */
    trait Subscription[A, S]
    trait Projection[A, State, S]
    trait Watchable[A, S]
    trait SnapshotStore[State, S]

    /** Bound for a single-page full-history read. A caller with genuinely unbounded history
      * pages explicitly through [[EventStore.read]]; `runDecision` reads one page large enough
      * to cover the whole stream in the common case.
      */
    private[kyo] val fullHistoryMaxCount: Int = Int.MaxValue

    /** Prepares each event through the codec and appends the resulting batch through the bound
      * EventLog. Empty input short-circuits to an empty result with no append.
      */
    private[kyo] def appendPrepared[A](
        store: EventStore[A],
        events: Chunk[A],
        directive: EventLog.AppendDirective
    )(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventCodecError] & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        if events.isEmpty then Chunk.empty
        else
            Kyo.foreach(events)(event => store.codec.prepare(store.log)(event, directive)).map { commands =>
                store.log.appendAll(commands.head, commands.tail*)
            }

    /** Reads the stream's full history, decodes and folds it into current state, decides the new
      * events against `command`, and appends them. One ordinary Journal program: no backend
      * capture, no call-site EventDefinition evidence.
      */
    private[kyo] def runDecision[Command, State, Event](
        store: EventStore[Event],
        streamId: Event.StreamId,
        command: Command,
        decider: Decider[Command, State, Event]
    )(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventCodecError] & Abort[DecideFailure] & Abort[EvolveFailure] & Abort[
        EventLog.PreparationFailure
    ] & Abort[JournalReadFailure] & Abort[JournalAppendFailure]) =
        for
            records <- store.read(streamId, Event.StreamOffset.first, EventSourced.fullHistoryMaxCount)
            events  <- Kyo.foreach(records)(record => store.codec.decode(record))
            state   <- Kyo.foldLeft(events)(decider.initialState)((state, event) => decider.evolve(state, event))
            decided <- decider.decide(state, command)
            results <- EventSourced.appendPrepared(store, decided, EventLog.AppendDirective.default)
        yield results
end EventSourced
