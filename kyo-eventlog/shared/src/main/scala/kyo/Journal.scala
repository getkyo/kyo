package kyo

import kyo.kernel.*

/** Capability for durable, append-only, optimistically-versioned event streams.
  *
  * A journal stores streams of immutable events. Each stream is a zero-indexed sequence: appends assign consecutive [[StreamOffset]]
  * values and are guarded by an [[ExpectedOffset]] check that is atomic with the write, giving optimistic concurrency without locks.
  * Reads return bounded slices in offset order, and [[Journal.streamInfo]] reports a stream's current state.
  *
  * `Journal` is an [[kyo.kernel.ArrowEffect]]: operations suspend a reified op and carry their own reachable failures as
  * `Abort[<per-op trait>]` beside `< Journal` in the row. Installing a backend with [[Journal.run]] discharges [[Journal]] and adds the
  * backend's own effect `S`, leaving each op's `Abort[<trait>]` on the residual; an in-memory backend runs under `Sync` while a future
  * asynchronous backend runs under `Async`.
  *
  * A program mixing several operations accumulates their per-op `Abort` traits in its row; `Abort.run[JournalError](...)` handles them all
  * in one shot through the sealed [[JournalError]] base (the same call-site catch-all documented on [[Journal.run]]).
  *
  * The payload is raw bytes ([[Span]] of `Byte`): schemas, codecs, and domain event types belong to layers above this one. Backends are
  * pluggable through [[Journal.Backend]]; kyo-eventlog ships an ephemeral in-memory backend for tests and local programs.
  *
  * @see
  *   [[kyo.Journal.Backend]] for the storage contract behind the capability
  * @see
  *   [[kyo.ExpectedOffset]] for the append concurrency model
  * @see
  *   [[kyo.JournalError]] for the failure model
  */
sealed trait Journal extends ArrowEffect[[A] =>> Journal.Op[A], Id]

object Journal:

    /** Reified journal operations dispatched by [[Journal.run]] to the installed backend; each resumes with a `Result` carrying the
      * operation's per-op failure trait, which the public op lifts back to `Abort` on its own row.
      */
    private[kyo] enum Op[A]:
        case Append(streamId: StreamId, expected: ExpectedOffset, events: Chunk[EventEnvelope])
            extends Op[Result[JournalAppendFailure, AppendResult]]
        case Read(streamId: StreamId, from: StreamOffset, maxCount: Int) extends Op[Result[JournalReadFailure, Chunk[RecordedEvent]]]
        case StreamInfo(streamId: StreamId)                              extends Op[Result[JournalStreamInfoFailure, kyo.StreamInfo]]
    end Op

    /** Read-only view of a journal: read and stream inspection with no append. A read-only file open
      * skips the writer lock and sees only committed data (single-writer, multiple-reader semantics).
      *
      * Consumed directly by subscription and projection layers, and extended by [[Backend]] which adds
      * `append`. Effect-polymorphic in `S`: a sync reader runs under `Sync`, an async reader under
      * `Async`. Factory methods live on [[Reader.file]] and [[Reader.fileAsync]] in each platform tree's
      * `FileJournalBackend.scala`.
      *
      * @tparam S
      *   the effect the reader's operations run under
      * @see
      *   [[Backend]] for the read-write storage contract
      */
    trait Reader[S]:
        /** Reads at most `maxCount` events from `from` in offset order. */
        def read(
            streamId: StreamId,
            from: StreamOffset,
            maxCount: Int
        ): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure])

        /** Reports the stream's current state. */
        def streamInfo(streamId: StreamId): StreamInfo < (S & Abort[JournalStreamInfoFailure])
    end Reader

    /** Storage contract behind the [[Journal]] capability.
      *
      * A backend provides atomic optimistic appends, bounded ordered reads, and stream inspection under its own effect `S`. Each method's
      * row names its precise failure set: `append` an [[JournalAppendFailure]], `read` a [[JournalReadFailure]], `streamInfo` a
      * [[JournalStreamInfoFailure]] (all three sub-traits of [[JournalError]]). Implementations must satisfy the contract exercised by
      * [[kyo.JournalBackendTest]]: consecutive zero-based offset assignment, an expected-offset check atomic with the write,
      * all-or-nothing batches, and empty reads (never failures) for missing streams and out-of-range positions.
      *
      * The methods take no `(using Frame)`: a backend captures its `Frame` at construction (as the in-memory backend does via a
      * `(using Frame)` class parameter), and custom-backend authors must do the same so failures carry a frame for error attribution.
      *
      * @tparam S
      *   the effect the backend's operations run under; the residual of [[Journal.run]] follows it
      * @see
      *   [[kyo.Journal.run]] which installs a backend for a journal program
      */
    trait Backend[S] extends Reader[S]:
        /** Appends a batch of events to a stream after checking the expected offset. */
        def append(
            streamId: StreamId,
            expected: ExpectedOffset,
            events: Chunk[EventEnvelope]
        ): AppendResult < (S & Abort[JournalAppendFailure])
    end Backend

    object Reader:
        /** Opens a typed read-only SWMR file reader over the committed frontier of `dir`.
          * No writer lock, no mutation, no reader-side tail repair.
          */
        def file[A](dir: Path, configuration: FileJournal.Configuration[A])(using
            Frame
        ): FileJournal.Reader[A, Sync] < (Sync & Scope & Abort[JournalStorageError]) =
            // Adapts the shipped FileJournalCore SWMR reader to the typed FileJournal.Reader,
            // binding the Configuration's codecs, over the live reader factory. Scope-managed
            // open; JournalStorageError on a bad layout.
            kyo.internal.FileJournalCore.openReader(dir, configuration)
    end Reader

    object Backend:
        /** Creates a fresh ephemeral in-memory backend. Separate calls do not share streams. */
        def inMemory(using Frame): Backend[Sync] < Sync =
            kyo.internal.InMemoryJournal.init

        /** Opens a typed Sync file-backed journal over `dir` using `configuration` (Binary or
          * JSONL). Scope-managed; group-commit + atomic-move + kill-at-every-step recovery are
          * preserved from the shipped engine.
          */
        def file[A](dir: Path, configuration: FileJournal.Configuration[A])(using
            Frame
        ): FileJournal.Backend[A, Sync] < (Sync & Scope & Abort[JournalStorageError]) =
            // openSync is a private[kyo] adapter on FileJournalCore, a sibling to openReader,
            // that binds `configuration` (codecs) onto the core Sync engine.
            kyo.internal.FileJournalCore.openSync(dir, configuration)

        /** Opens a typed Async file-backed journal; shares semantics with [[file]] but drives IO
          * off the event loop (Node), never blocking it.
          */
        def fileAsync[A](dir: Path, configuration: FileJournal.Configuration[A])(using
            Frame
        ): FileJournal.Backend[A, Async] < (Sync & Scope & Abort[JournalStorageError]) =
            // openAsync is a private[kyo] adapter on FileJournalCore, a sibling to openSync and
            // openReader, that binds `configuration` (codecs) onto the core Async engine.
            kyo.internal.FileJournalCore.openAsync(dir, configuration)
    end Backend

    /** Appends a batch of events to a stream after checking the expected offset.
      *
      * On success the batch is assigned consecutive offsets starting at the stream's next position. Fails on this operation's own row with
      * [[JournalConflictError]] on an expectation mismatch or [[JournalEmptyAppendError]] on an empty batch; in both cases the stream is
      * unchanged. A durable backend may additionally raise [[JournalCorruptedError]] or [[JournalStorageError]] (both [[JournalAppendFailure]]).
      */
    inline def append(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope]
    )(using inline frame: Frame): AppendResult < (Journal & Abort[JournalAppendFailure]) =
        ArrowEffect.suspend(Tag[Journal], Op.Append(streamId, expected, events)).map(r => Abort.get(r))

    /** Reads at most `maxCount` events from `from` in offset order. The in-memory backend never fails (a missing stream, a non-positive
      * `maxCount`, or a `from` at or past the event count returns an empty chunk); a durable backend may raise [[JournalCorruptedError]] or
      * [[JournalStorageError]] (both [[JournalReadFailure]]), which is why the row carries `Abort[JournalReadFailure]`.
      */
    inline def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    )(using inline frame: Frame): Chunk[RecordedEvent] < (Journal & Abort[JournalReadFailure]) =
        ArrowEffect.suspend(Tag[Journal], Op.Read(streamId, from, maxCount)).map(r => Abort.get(r))

    /** Reports the stream's current state: [[StreamInfo.Absent]] or [[StreamInfo.Existing]]. The in-memory backend never fails; a durable
      * backend may raise [[JournalCorruptedError]] or [[JournalStorageError]] (both [[JournalStreamInfoFailure]]), carried on the row.
      */
    inline def streamInfo(streamId: StreamId)(using inline frame: Frame): StreamInfo < (Journal & Abort[JournalStreamInfoFailure]) =
        ArrowEffect.suspend(Tag[Journal], Op.StreamInfo(streamId)).map(r => Abort.get(r))

    /** Handles the [[Journal]] capability by installing a backend, discharging [[Journal]] and adding the backend's own effect `S`. The
      * per-op `Abort[<trait>]` failures introduced by the operations ride in the caller's `S2` and are left UNTOUCHED, so the residual is
      * `A < (S & S2)` with no umbrella `Abort[JournalError]` on the row. To catch any journal failure at a call site, wrap the program in
      * `Abort.run[JournalError](...)`: it recovers every op trait at once because each extends the sealed [[JournalError]] base.
      *
      * @tparam S
      *   the installed backend's own effect; it flows onto the residual (`Sync` for the in-memory backend, `Async` for a future async backend)
      * @tparam A
      *   the result type of the journal program
      * @tparam S2
      *   the caller's own effects (including any op `Abort[<trait>]` the program introduced), carried through unchanged
      */
    def run[S, A, S2](backend: Backend[S])(v: A < (Journal & S2))(using Frame): A < (S & S2) =
        ArrowEffect.handleLoop(Tag[Journal], v) {
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Append(sid, exp, evs) =>
                            Abort.run[JournalAppendFailure](backend.append(sid, exp, evs)).map(r => Loop.continue(cont(r)))
                        case Op.Read(sid, from, max) =>
                            Abort.run[JournalReadFailure](backend.read(sid, from, max)).map(r => Loop.continue(cont(r)))
                        case Op.StreamInfo(sid) =>
                            Abort.run[JournalStreamInfoFailure](backend.streamInfo(sid)).map(r => Loop.continue(cont(r)))
        }

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See [[kyo.AllowUnsafe]] for more details.
      *
      * These ops are the blessed, audit-marked bypass of the capability-handler seam for performance-sensitive call sites: they run a
      * program directly against a [[Journal.Backend]] instance, skipping the ArrowEffect suspend and [[Journal.run]] dispatch (and any
      * interception installed there). `AllowUnsafe` marks accepting that bypass, NOT a gate on an otherwise-unreachable operation:
      * [[Journal.Backend]] is a public implement-side SPI, so `backend.append(...)` is already callable directly and safely at identical
      * cost; these forwarders are its blessed, discoverable equivalent under the `Unsafe` namespace.
      *
      * Both tiers expose the same per-op `Abort[<trait>]` failure surface; they differ in that the safe ops suspend through the [[Journal]]
      * capability (handler-interpreted, one `Result` round-trip) while these unsafe duals invoke the backend directly under its concrete
      * effect `S`.
      */
    object Unsafe:
        /** Appends directly against `backend`, bypassing the ArrowEffect suspend and handler dispatch. */
        def append[S](backend: Backend[S])(
            streamId: StreamId,
            expected: ExpectedOffset,
            events: Chunk[EventEnvelope]
        )(using AllowUnsafe): AppendResult < (S & Abort[JournalAppendFailure]) =
            backend.append(streamId, expected, events)

        /** Reads directly against `backend`, bypassing the ArrowEffect suspend and handler dispatch. */
        def read[S](backend: Backend[S])(
            streamId: StreamId,
            from: StreamOffset,
            maxCount: Int
        )(using AllowUnsafe): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure]) =
            backend.read(streamId, from, maxCount)

        /** Reports stream state directly against `backend`, bypassing the ArrowEffect suspend and handler dispatch. */
        def streamInfo[S](backend: Backend[S])(streamId: StreamId)(using AllowUnsafe): StreamInfo < (S & Abort[JournalStreamInfoFailure]) =
            backend.streamInfo(streamId)
    end Unsafe
end Journal
