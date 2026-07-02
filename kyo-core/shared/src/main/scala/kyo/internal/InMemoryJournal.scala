package kyo.internal

import kyo.*

private[kyo] object InMemoryJournal:
    def init(using Frame): Journal.Backend < Sync =
        Sync.defer(new Journal.Backend:
            def append(streamId: StreamId, expected: ExpectedRevision, events: Chunk[EventEnvelope])
                : AppendResult < (Sync & Abort[JournalError]) =
                Abort.fail(JournalError.StorageFailure("InMemoryJournal not yet implemented", Maybe.empty))
            def read(streamId: StreamId, from: StreamRevision, maxCount: Int)
                : Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
                Abort.fail(JournalError.StorageFailure("InMemoryJournal not yet implemented", Maybe.empty))
            def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
                Abort.fail(JournalError.StorageFailure("InMemoryJournal not yet implemented", Maybe.empty)))
end InMemoryJournal
