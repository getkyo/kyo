package kyo.internal

import kyo.*

/** Ephemeral in-memory [[Journal.Backend]]: an immutable stream map advanced by a compare-and-set loop.
  *
  * The expected-offset check and the append are one atomic state transition, so the backend satisfies the optimistic-concurrency
  * contract without locks. State is scoped to the value returned by `init`; separate `init` calls do not share streams.
  */
private[kyo] object InMemoryJournal:

    def init(using Frame): Journal.Backend[Sync] < Sync =
        AtomicRef.init(State.empty).map(ref => new InMemoryJournal(ref))

    final case class State(streams: Map[StreamId, Chunk[RecordedEvent]])

    private object State:
        val empty: State = State(Map.empty)
end InMemoryJournal

final private class InMemoryJournal(state: AtomicRef[InMemoryJournal.State])(using Frame) extends Journal.Backend[Sync]:
    import InMemoryJournal.*

    def append(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope]
    ): AppendResult < (Sync & Abort[JournalAppendFailure]) =
        if events.isEmpty then Abort.fail(JournalEmptyAppendError())
        else modify(current => appendToState(current, streamId, expected, events))

    def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    ): Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
        state.use { current =>
            if maxCount <= 0 then Chunk.empty
            else
                val events = current.streams.getOrElse(streamId, Chunk.empty)
                if from.value >= events.length.toLong then Chunk.empty
                else events.drop(from.value.toInt).take(maxCount)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
        state.use(current => info(current.streams.getOrElse(streamId, Chunk.empty)))

    private def modify[A](operation: State => Result[JournalAppendFailure, (State, A)]): A < (Sync & Abort[JournalAppendFailure]) =
        Loop(()) { _ =>
            state.get.map { current =>
                Abort.get(operation(current)).map { (next, value) =>
                    state.compareAndSet(current, next).map {
                        case true  => Loop.done(value)
                        case false => Loop.continue
                    }
                }
            }
        }

    private def appendToState(
        current: State,
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope]
    ): Result[JournalAppendFailure, (State, AppendResult)] =
        val currentEvents = current.streams.getOrElse(streamId, Chunk.empty)
        val currentInfo   = info(currentEvents)

        if !matches(expected, currentInfo) then Result.fail(JournalConflictError(streamId, expected, currentInfo))
        else
            val firstValue = currentEvents.length.toLong
            val recorded = Chunk.from(
                events.zipWithIndex.map { (event, index) =>
                    RecordedEvent(
                        streamId = streamId,
                        offset = StreamOffset.fromUnchecked(firstValue + index.toLong),
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
                firstOffset = StreamOffset.fromUnchecked(firstValue),
                lastOffset = StreamOffset.fromUnchecked(firstValue + events.length.toLong - 1L),
                streamInfo = info(updatedEvents)
            )
            Result.succeed(current.copy(streams = current.streams.updated(streamId, updatedEvents)) -> result)
        end if
    end appendToState

    private def matches(expected: ExpectedOffset, actual: StreamInfo): Boolean =
        expected match
            case ExpectedOffset.Any =>
                true
            case ExpectedOffset.NoStream =>
                actual == StreamInfo.Absent
            case ExpectedOffset.Exact(offset) =>
                actual match
                    case StreamInfo.Existing(_, lastOffset) => lastOffset == offset
                    case StreamInfo.Absent                  => false

    private def info(events: Chunk[RecordedEvent]): StreamInfo =
        if events.isEmpty then StreamInfo.Absent
        else
            val lastOffset = StreamOffset.fromUnchecked(events.length.toLong - 1L)
            StreamInfo.Existing(StreamVersion.after(lastOffset), lastOffset)
end InMemoryJournal
