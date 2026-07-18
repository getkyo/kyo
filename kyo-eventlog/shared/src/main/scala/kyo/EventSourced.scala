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
        ): log.Command < (Sync & Abort[EventCodecError] & Abort[EventLog.PreparationFailure])
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
    end EventStore

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
