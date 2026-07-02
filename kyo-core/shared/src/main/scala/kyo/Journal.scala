package kyo

/** Capability for durable, append-only, optimistically-versioned event streams.
  *
  * A journal stores streams of immutable events. Each stream is a zero-indexed sequence: appends assign consecutive
  * [[StreamRevision]] values and are guarded by an [[ExpectedRevision]] check that is atomic with the write, giving optimistic
  * concurrency without locks. Reads return bounded slices in revision order, and [[Journal.streamInfo]] reports a stream's current
  * state.
  *
  * `Journal` is an opaque capability bundling the backend environment with the effects every operation needs: operations track only
  * `< Journal` in their row, while `Sync` and `Abort[JournalError]` ride inside and remain handleable through the bound. Providing a
  * backend with [[Journal.run]] discharges the capability and surfaces the remaining effects.
  *
  * The payload is raw bytes ([[Span]] of `Byte`): schemas, codecs, and domain event types belong to layers above this one. Backends are
  * pluggable through [[Journal.Backend]]; kyo-core ships an ephemeral in-memory backend for tests and local programs.
  *
  * @see
  *   [[kyo.Journal.Backend]] for the storage contract behind the capability
  * @see
  *   [[kyo.ExpectedRevision]] for the append concurrency model
  * @see
  *   [[kyo.JournalError]] for the failure model
  */
opaque type Journal <: Env[Journal.Backend] & Sync & Abort[JournalError] =
    Env[Journal.Backend] & Sync & Abort[JournalError]

object Journal:

    /** Storage contract behind the [[Journal]] capability.
      *
      * A backend provides atomic optimistic appends, bounded ordered reads, and stream inspection under `Sync` and
      * `Abort[JournalError]`. Implementations must satisfy the contract exercised by the shared backend test suite: consecutive
      * zero-based revision assignment, an expected-revision check atomic with the write, all-or-nothing batches, and empty reads (never
      * failures) for missing streams and out-of-range positions.
      *
      * @see
      *   [[kyo.Journal.run]] which installs a backend for a journal program
      */
    trait Backend:
        /** Appends a batch of events to a stream after checking the expected revision. */
        def append(
            streamId: StreamId,
            expected: ExpectedRevision,
            events: Chunk[EventEnvelope]
        ): AppendResult < (Sync & Abort[JournalError])

        /** Reads at most `maxCount` events from `from` in revision order. */
        def read(
            streamId: StreamId,
            from: StreamRevision,
            maxCount: Int
        ): Chunk[RecordedEvent] < (Sync & Abort[JournalError])

        /** Reports the stream's current state. */
        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError])
    end Backend

    object Backend:
        /** Creates a fresh ephemeral in-memory backend. Separate calls do not share streams. */
        def inMemory(using Frame): Backend < Sync =
            kyo.internal.InMemoryJournal.init
    end Backend

    /** Appends a batch of events to a stream after checking the expected revision.
      *
      * On success the batch is assigned consecutive revisions starting at the stream's next position. Fails with
      * [[JournalError.Conflict]] on an expectation mismatch and [[JournalError.EmptyAppend]] on an empty batch; in both cases the
      * stream is unchanged.
      */
    def append(
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    )(using Frame): AppendResult < Journal =
        Env.use[Backend](_.append(streamId, expected, events))

    /** Reads at most `maxCount` events from `from` in revision order.
      *
      * A missing stream, a non-positive `maxCount`, or a `from` at or past the event count returns an empty chunk.
      */
    def read(
        streamId: StreamId,
        from: StreamRevision,
        maxCount: Int
    )(using Frame): Chunk[RecordedEvent] < Journal =
        Env.use[Backend](_.read(streamId, from, maxCount))

    /** Reports the stream's current state: [[StreamInfo.Absent]] or [[StreamInfo.Existing]]. */
    def streamInfo(streamId: StreamId)(using Frame): StreamInfo < Journal =
        Env.use[Backend](_.streamInfo(streamId))

    /** Handles the [[Journal]] capability by installing a backend, leaving `Sync` and `Abort[JournalError]` for the caller. */
    def run[A, S](backend: Backend)(v: A < (Journal & S))(using Frame): A < (Sync & Abort[JournalError] & S) =
        Env.run(backend)(v)
end Journal
