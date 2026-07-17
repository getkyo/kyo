package kyo

/** Private wiring for [[EventLog]]'s prepare/append/reader internals, and the concrete
  * [[EventLog.StreamSelector]]/[[EventLog.EventIdPolicy]] witnesses built by their named
  * constructors. Kept in a separate file (rather than inside `EventLog.scala`) because
  * `EventLog.scala` is a materialized, byte-locked source: extension methods on
  * `EventLog.type` are the mechanism that lets this file add `private[kyo]` members
  * callable unqualified (`EventLog.mkReader(...)`, etc.) from that locked file without
  * editing it.
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

    /** Resolves `name`/`components`'s length-prefixed canonical stream id: the stream name
      * first, then one or more non-empty key components, each length-prefixed so punctuation
      * and multibyte text stay collision-safe. Backs both [[EventLog.StreamSelector.by]] and
      * [[EventLog.StreamSelector.canonical]].
      */
    private[kyo] def resolveKeyedStream(name: EventLog.StreamName, components: Chunk[String])(using
        Frame
    ): Result[EventLog.PreparationFailure, StreamId] =
        if components.isEmpty then
            Result.fail(EventLog.PreparationFailure(s"stream key for '${name.value}' has no components"))
        else if components.exists(_.isEmpty) then
            Result.fail(EventLog.PreparationFailure(s"stream key for '${name.value}' has an empty component"))
        else
            val canonical = (name.value +: components).map(c => s"${c.length}:$c").mkString("/")
            StreamId(canonical).mapFailure(e =>
                EventLog.PreparationFailure(s"cannot derive a stream id from '$canonical': ${e.getMessage()}")
            )

    /** Groups a batch into contiguous runs sharing the same resolved `streamId`, preserving
      * first-occurrence order across the whole batch: a stream id seen again later,
      * non-adjacently, starts a NEW run rather than merging with an earlier one, matching
      * Journal.append's per-call contiguous-offset-range semantics.
      */
    private[kyo] def groupContiguousByStream[A](commands: Chunk[EventLog[A]#Command]): Chunk[Chunk[EventLog[A]#Command]] =
        commands.foldLeft(Chunk.empty[Chunk[EventLog[A]#Command]]) { (acc, cmd) =>
            acc.lastMaybe match
                case Present(run) if run.head.streamId == cmd.streamId => acc.dropRight(1) :+ (run :+ cmd)
                case _                                                 => acc :+ Chunk(cmd)
        }

    /** Validates one contiguous run's directive-supplied `expectedOffset`: `Journal.append`
      * accepts exactly one `expected` value per call, checked before any event in that call is
      * appended, so only the run's HEAD command may carry a non-absent `expectedOffset`. A
      * non-head command's non-absent `expectedOffset` aborts `PreparationFailure` naming its
      * index within the run, rather than being silently dropped or silently checked against the
      * wrong offset.
      */
    private[kyo] def resolveRunExpectedOffset[A](run: Chunk[EventLog[A]#Command])(using
        Frame
    ): Result[EventLog.PreparationFailure, ExpectedOffset] =
        findNonHeadExpectedOffset(run, 1) match
            case Present(index) =>
                Result.fail(EventLog.PreparationFailure(
                    s"command at index $index in a contiguous run carries a non-absent expectedOffset; only the run's head command may set expectedOffset"
                ))
            case Absent =>
                Result.succeed(run.head.directive.expectedOffset.getOrElse(ExpectedOffset.Any))

    @scala.annotation.tailrec
    private def findNonHeadExpectedOffset[A](run: Chunk[EventLog[A]#Command], index: Int): Maybe[Int] =
        if index >= run.length then Absent
        else if run(index).directive.expectedOffset.isDefined then Present(index)
        else findNonHeadExpectedOffset(run, index + 1)
end EventLogSupport

extension (self: EventLog.type)

    /** Encodes `event` through `codecs.value`, resolves this command's final metadata (member-
      * produced `ev.metadata.values(event)`, right-biased-merged with `directive.metadataOverride`
      * when present), then resolves `streamId` and `eventId`: each is taken directly from the
      * directive's override when present (the EventDefinition's StreamSelector/EventIdPolicy is
      * NOT consulted at all for an overridden facet), or else resolved via
      * `ev.stream.resolve(event)` / `ev.eventId.next(event, streamId, ev.eventType, metadata)`.
      * Assembles the [[EventEnvelope]] `prepare` wraps in a `Command` alongside the resolved
      * `streamId`. Aborts `PreparationFailure` on stream, id, or codec resolution failure. No
      * Journal effect: prepare is pure staging.
      */
    private[kyo] def prepareEnvelope[A, E <: A](
        codecs: EventLog.Codecs[A],
        ev: EventLog.EventDefinition[A, E],
        event: E,
        directive: EventLog.AppendDirective
    )(using Frame): Result[EventLog.PreparationFailure, (StreamId, EventEnvelope)] =
        // Unsafe: EventLogCodecs.encodeValue's Sync suspension is a synchronous computation
        // with no real suspension point; evaluated inline so prepareEnvelope stays a plain
        // Result, matching the frozen EventLog.scala call site's
        // Abort.get(EventLog.prepareEnvelope(...)) shape (Abort.get takes a plain Result,
        // never an effectful one). ev.stream.resolve and ev.eventId.next carry only
        // Abort[PreparationFailure] in their row (no Sync), so they evaluate directly
        // through Abort.run(...).eval rather than the Sync.Unsafe bridge.
        given AllowUnsafe  = AllowUnsafe.embrace.danger
        val bytes          = Sync.Unsafe.evalOrThrow(EventLogCodecs.encodeValue(codecs.value, event))
        val memberMetadata = ev.metadata.values(event)
        val metadata = directive.metadataOverride match
            case Present(over) => EventMetadata(memberMetadata.values ++ over.values)
            case Absent        => memberMetadata
        val streamIdResult: Result[EventLog.PreparationFailure, StreamId] = directive.streamIdOverride match
            case Present(streamId) => Result.succeed(streamId)
            case Absent            => Abort.run[EventLog.PreparationFailure](ev.stream.resolve(event)).eval
        streamIdResult.flatMap { streamId =>
            val eventIdResult: Result[EventLog.PreparationFailure, EventId] = directive.eventIdOverride match
                case Present(eventId) => Result.succeed(eventId)
                case Absent =>
                    Abort.run[EventLog.PreparationFailure](ev.eventId.next(event, streamId, ev.eventType, metadata)).eval
            eventIdResult.map(eventId => (streamId, EventEnvelope(eventId, ev.eventType, bytes, metadata)))
        }
    end prepareEnvelope

    /** Groups the batch into contiguous runs by resolved `streamId` (first-occurrence order
      * preserved across the whole batch; a `replaceStreamId` override already baked into
      * `Command.streamId` at `prepare` time correctly starts a new run) and issues one
      * [[Journal.append]] per run, after validating that run's directive-supplied
      * `expectedOffset` (only the run's head command may carry a non-absent one,
      * [[EventLogSupport.resolveRunExpectedOffset]]). The returned `Chunk[AppendResult]` mirrors
      * every run's single result back across that run's original command positions, so the
      * outer shape always has one entry per input command, in original order, regardless of
      * grouping. Path-dependent `Command` (`EventLog[A]#Command`) is accepted structurally:
      * every concrete command is `this.Command` for the single log `appendAll` was called on,
      * which is always a subtype of the unbound projection.
      */
    private[kyo] def appendValidated[A](commands: Chunk[EventLog[A]#Command])(using
        Frame
    ): Chunk[AppendResult] < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure]) =
        val runs = EventLogSupport.groupContiguousByStream(commands)
        Kyo.foreach(runs) { run =>
            Abort.get(EventLogSupport.resolveRunExpectedOffset(run)).map { expected =>
                Journal.append(run.head.streamId, expected, run.map(_.envelope)).map(result => Chunk.fill(run.length)(result))
            }
        }.map(_.flattenChunk)
    end appendValidated

    /** Adapts a committed-frontier [[FileJournal.Reader]] into the typed, append-free
      * [[EventLog.Reader]]. Decodes each read record's payload through the reader's own bound
      * value codec (recovered via [[kyo.internal.BoundValueAccess.boundValueCodec]]) and builds
      * each record's [[JournalEntryRef]] from the reader's bound `journalId`. Carries no P type
      * parameter.
      */
    private[kyo] def mkReader[A, S](fileReader: FileJournal.Reader[A, S])(using
        Frame
    ): EventLog.Reader[A, S] =
        // Unsafe: downcasts the FileJournal.Reader[A, S] to the narrower BoundValueAccess[A]
        // interface (unconstrained in S) to recover the bound value codec and journalId; sound
        // because FileJournalCore is the only Reader/Backend implementation the shipped engine
        // constructs (FileJournalCore.openReader/openSync/openAsync are the sole factories) and
        // it extends BoundValueAccess[A] for the same A this reader is parameterized on.
        val core       = fileReader.asInstanceOf[kyo.internal.BoundValueAccess[A]]
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

// The five StreamSelector/EventIdPolicy witness classes live in EventLog.scala, not here:
// StreamSelector and EventIdPolicy are sealed, and Scala requires every direct subtype of a
// sealed trait to be defined in the same source file as the trait itself.
