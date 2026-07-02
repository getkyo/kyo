package kyo

/** Recoverable failures for [[Journal]] operations, used with `Abort[JournalError]`.
  *
  * Journal operations never throw for expected failure modes; they abort with one of these cases. `EmptyAppend`, `Conflict`, and
  * `InvalidIdentifier` mirror the optimistic-append model: an append must carry events, must match the expected stream revision, and must
  * use validated identifiers. `Corrupted` and `StorageFailure` exist for durable backends: `Corrupted` reports damaged stored data that a
  * backend refuses to repair silently, and `StorageFailure` wraps I/O failures from the underlying storage.
  *
  * @see
  *   [[kyo.Journal]] for the operations that abort with these errors
  * @see
  *   [[kyo.ExpectedRevision]] for the append concurrency model behind `Conflict`
  */
enum JournalError derives CanEqual:
    /** An append was attempted with an empty event chunk. */
    case EmptyAppend

    /** The expected revision did not match the live stream state; `actual` is the stream state observed at append time. */
    case Conflict(streamId: StreamId, expected: ExpectedRevision, actual: StreamInfo)

    /** A `StreamId`, `EventId`, `EventType`, `StreamRevision`, `StreamVersion`, or `MetadataKey` value failed validation. */
    case InvalidIdentifier(kind: String, value: String)

    /** A durable backend observed damaged stored data away from the write tail and refuses to repair it. */
    case Corrupted(streamId: Maybe[StreamId], detail: String)

    /** An I/O failure from a durable backend's underlying storage. */
    case StorageFailure(detail: String, cause: Maybe[Throwable])
end JournalError
