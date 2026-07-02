package kyo.internal

import kyo.*

/** Ephemeral in-memory [[Journal.Backend]]: an immutable stream map advanced by a compare-and-set loop.
  *
  * The expected-revision check and the append are one atomic state transition, so the backend satisfies the optimistic-concurrency
  * contract without locks. State is scoped to the value returned by `init`; separate `init` calls do not share streams.
  */
private[kyo] object InMemoryJournal:

    def init(using Frame): Journal.Backend < Sync =
        AtomicRef.init(State.empty).map(ref => new InMemoryJournal(ref))

    final case class State(streams: Map[StreamId, Chunk[RecordedEvent]])

    private object State:
        val empty: State = State(Map.empty)
end InMemoryJournal

final private class InMemoryJournal(state: AtomicRef[InMemoryJournal.State])(using Frame) extends Journal.Backend:
    import InMemoryJournal.*

    def append(
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    ): AppendResult < (Sync & Abort[JournalError]) =
        if events.isEmpty then Abort.fail(JournalError.EmptyAppend)
        else modify(current => appendToState(current, streamId, expected, events))

    def read(
        streamId: StreamId,
        from: StreamRevision,
        maxCount: Int
    ): Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
        state.use { current =>
            if maxCount <= 0 then Chunk.empty
            else
                val events = current.streams.getOrElse(streamId, Chunk.empty)
                if from.value >= events.length.toLong then Chunk.empty
                else events.drop(from.value.toInt).take(maxCount)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
        state.use(current => info(current.streams.getOrElse(streamId, Chunk.empty)))

    private def modify[A](operation: State => Result[JournalError, (State, A)]): A < (Sync & Abort[JournalError]) =
        state.get.map { current =>
            Abort.get(operation(current)).map { (next, value) =>
                state.compareAndSet(current, next).map {
                    case true  => value
                    case false => modify(operation)
                }
            }
        }

    private def appendToState(
        current: State,
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    ): Result[JournalError, (State, AppendResult)] =
        val currentEvents = current.streams.getOrElse(streamId, Chunk.empty)
        val currentInfo   = info(currentEvents)

        if !matches(expected, currentInfo) then Result.fail(JournalError.Conflict(streamId, expected, currentInfo))
        else
            val firstValue = currentEvents.length.toLong
            val recorded = Chunk.from(
                events.zipWithIndex.map { (event, index) =>
                    RecordedEvent(
                        streamId = streamId,
                        revision = StreamRevision.fromUnchecked(firstValue + index.toLong),
                        eventId = event.id,
                        eventType = event.eventType,
                        payload = event.payload,
                        metadata = event.metadata
                    )
                }
            )
            val updatedEvents = currentEvents ++ recorded
            val result = AppendResult(
                streamId = streamId,
                firstRevision = StreamRevision.fromUnchecked(firstValue),
                lastRevision = StreamRevision.fromUnchecked(firstValue + events.length.toLong - 1L),
                streamInfo = info(updatedEvents)
            )
            Result.succeed(current.copy(streams = current.streams.updated(streamId, updatedEvents)) -> result)
        end if
    end appendToState

    private def matches(expected: ExpectedRevision, actual: StreamInfo): Boolean =
        expected match
            case ExpectedRevision.Any =>
                true
            case ExpectedRevision.NoStream =>
                actual == StreamInfo.Absent
            case ExpectedRevision.Exact(revision) =>
                actual match
                    case StreamInfo.Existing(_, lastRevision) => lastRevision == revision
                    case StreamInfo.Absent                    => false

    private def info(events: Chunk[RecordedEvent]): StreamInfo =
        if events.isEmpty then StreamInfo.Absent
        else StreamInfo.Existing(events.length.toLong, StreamRevision.fromUnchecked(events.length.toLong - 1L))
end InMemoryJournal
