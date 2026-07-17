package kyo

/** Private wiring for [[EventLog]]'s prepare/append/reader internals. Kept in a separate file
  * (rather than inside `EventLog.scala`) because `EventLog.scala` is a materialized, byte-locked
  * source: extension methods on `EventLog.type` are the mechanism that lets this file add
  * `private[kyo]` members callable unqualified (`EventLog.mkReader(...)`, etc.) from that locked
  * file without editing it.
  */
private[kyo] object EventLogSupport:

    // Unsafe: a module-level monotonic in-process counter (Category B companion init, no
    // construction site); initialized once outside any effect.
    private val eventIdSeq: AtomicLong.Unsafe = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)

    private[kyo] def freshEventId()(using Frame): EventId < Sync =
        Sync.defer {
            // Unsafe: increments the shared monotonic counter; the increment itself cannot fail.
            val n = eventIdSeq.incrementAndGet()(using AllowUnsafe.embrace.danger)
            EventId(n.toString)(using Frame.internal) match
                case Result.Success(id) => id
                case Result.Failure(_)  => throw new IllegalStateException(s"generated event id was empty for counter value $n")
        }

    /** Derives the single logical stream every append and read on a log resolves to: `journalId`'s
      * own route-segment value re-validated as a [[StreamId]]. `EventLog.EventDefinition.stream`
      * (a [[EventLog.StreamSelector]]) is not yet consulted for per-member routing; see
      * `prepareEnvelope`'s scaladoc.
      */
    private[kyo] def streamIdFor(journalId: JournalId)(using Frame): Result[EventLog.PreparationFailure, StreamId] =
        StreamId(journalId.value).mapFailure(e =>
            EventLog.PreparationFailure(s"cannot derive a stream id from journal id '${journalId.value}': ${e.getMessage()}")
        )
end EventLogSupport

extension (self: EventLog.type)

    /** Encodes `event` through `codecs.value`, resolves a fresh generated event id (the only
      * [[EventLog.EventIdPolicy]] this phase constructs is [[GeneratedEventIdPolicy]]) and empty
      * metadata (the only [[EventLog.Metadata]] this phase constructs is [[EmptyMetadata]]), and
      * assembles the [[EventEnvelope]] `prepare` wraps in a `Command`. `ev.stream` is not yet
      * consulted: `Command` carries no per-command stream identity field (only `envelope` and
      * `directive`), so member-level stream ROUTING is out of this phase's reachable scope; every
      * append resolves to the single stream `appendValidated` derives from the log's `journalId`.
      */
    private[kyo] def prepareEnvelope[A, E <: A](
        codecs: EventLog.Codecs[A],
        ev: EventLog.EventDefinition[A, E],
        event: E,
        directive: EventLog.AppendDirective
    )(using Frame): Result[EventLog.PreparationFailure, EventEnvelope] =
        // Unsafe: EventLogCodecs.encodeValue's and freshEventId's Sync suspension are synchronous
        // computations with no real suspension point; evaluated inline so prepareEnvelope stays a
        // plain Result, matching the frozen EventLog.scala call site's
        // Abort.get(EventLog.prepareEnvelope(...)) shape (Abort.get takes a plain Result, never an
        // effectful one).
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val bytes         = Sync.Unsafe.evalOrThrow(EventLogCodecs.encodeValue(codecs.value, event))
        val eventId       = Sync.Unsafe.evalOrThrow(EventLogSupport.freshEventId())
        Result.succeed(EventEnvelope(eventId, ev.eventType, bytes, EventMetadata.empty))
    end prepareEnvelope

    /** Groups nothing (this phase resolves every command in a batch onto the single stream
      * `journalId` derives) and issues one [[Journal.append]] per command, so the returned
      * `Chunk[AppendResult]` carries one result per command in order. Path-dependent `Command`
      * (`EventLog[A]#Command`) is accepted structurally: every concrete command is `this.Command`
      * for the single log `appendAll` was called on, which is always a subtype of the unbound
      * projection.
      */
    private[kyo] def appendValidated[A](journalId: JournalId, commands: Chunk[EventLog[A]#Command])(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        Abort.get(EventLogSupport.streamIdFor(journalId)).map { streamId =>
            Kyo.foreach(commands) { cmd =>
                val expected = cmd.directive.expectedOffset.getOrElse(ExpectedOffset.Any)
                Journal.append(streamId, expected, Chunk(cmd.envelope))
            }
        }

    /** Adapts a committed-frontier [[FileJournal.Reader]] into the typed, append-free
      * [[EventLog.Reader]]. Decodes each read record's payload through the reader's own bound
      * value codec (recovered via [[kyo.internal.FileJournalCore.boundValueCodec]]) and builds each
      * record's [[JournalEntryRef]] from the reader's bound `journalId`.
      */
    private[kyo] def mkReader[A, P <: FileJournal.Profile, S](fileReader: FileJournal.Reader[A, P, S])(using
        Frame
    ): EventLog.Reader[A, S] =
        // Unsafe: downcasts the phantom FileJournal.Reader[A,P,S] to the narrower BoundCodecs[A]
        // interface (unconstrained in P/S) to recover the bound value codec and journalId; sound
        // because FileJournalCore is the only Reader/Backend implementation the shipped engine
        // constructs (FileJournalCore.openReader/openSync/openAsync are the sole factories) and it
        // extends BoundCodecs[A] for the same A this reader is parameterized on.
        val core       = fileReader.asInstanceOf[kyo.internal.BoundCodecs[A]]
        val valueCodec = core.boundValueCodec
        val journalId  = core.journalId
        new EventLog.Reader[A, S]:
            def read(streamId: StreamId, from: StreamOffset, maxCount: Int)(using
                Frame
            ): Chunk[EventLog.Record[A]] < (S & Abort[JournalReadFailure]) =
                // EventLogCodecs.decodeValue is itself effectful (A < Abort[JournalReadFailure])
                // and already folds undecodable payload bytes into JournalCorruptedError on that
                // row, so this composes it directly with map, mirroring EventLog.read's own
                // decode composition.
                fileReader.read(streamId, from, maxCount).flatMap { records =>
                    Kyo.foreach(records) { rec =>
                        EventLogCodecs.decodeValue(valueCodec, rec.payload).map { a =>
                            val ref = JournalEntryRef(journalId, rec.streamId, rec.offset)
                            EventLog.Record(ref, rec.eventId, rec.eventType, rec.metadata, a)
                        }
                    }
                }
            def streamInfo(streamId: StreamId)(using Frame): StreamInfo < (S & Abort[JournalStreamInfoFailure]) =
                fileReader.streamInfo(streamId)
        end new
    end mkReader

    /** Derives an [[EventType]] from `Schema[E]`'s top-level structure name. The name is
      * guaranteed non-empty by construction (a derived `Schema` always carries a non-empty
      * structural name), so a validation failure here is a defect, not a reachable user input.
      */
    private[kyo] def deriveEventType[E](using schema: Schema[E], frame: Frame): EventType =
        EventType(schema.structure.name) match
            case Result.Success(et) => et
            case Result.Failure(_) =>
                throw new IllegalStateException(s"schema-derived event type name was empty for ${schema.structure.name}")
end extension

/** The only [[EventLog.EventIdPolicy]] this phase constructs: every generated id is a fresh
  * monotonic token from [[EventLogSupport]]'s counter. `EventIdPolicy[E]` carries no members, so
  * this witness is a marker only; the actual generation happens in `prepareEnvelope`.
  */
private[kyo] object GeneratedEventIdPolicy extends EventLog.EventIdPolicy[Any]

/** The only [[EventLog.Metadata]] this phase constructs: every prepared envelope carries
  * [[EventMetadata.empty]]. `Metadata[E]` carries no members, so this witness is a marker only.
  */
private[kyo] object EmptyMetadata extends EventLog.Metadata[Any]
