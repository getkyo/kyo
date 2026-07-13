package kyo

/** Typed facade over `Journal` for event payload type `A`.
  *
  * Encodes each `A` to bytes on append and decodes each [[kyo.RecordedEvent]] payload back to
  * `A` on read, surfacing decoded records as [[kyo.EventLog.Typed]] values. The three
  * operations mirror [[kyo.Journal]] exactly in their effect rows: each carries its own per-op
  * `Abort` trait ([[kyo.JournalAppendFailure]], [[kyo.JournalReadFailure]], or
  * [[kyo.JournalStreamInfoFailure]]), so `Retry(eventLog.append(...))` composes without
  * collapsing failures into an umbrella type.
  *
  * Callers run `EventLog[A]` methods inside a `Journal.run(backend)(...)` block, the same way
  * they run raw `Journal` operations; no backend is captured at construction time. Payload
  * bytes that cannot be decoded fold into [[kyo.JournalCorruptedError]] so the row stays
  * `Abort[JournalReadFailure]`, never widening to a codec-specific error.
  *
  * @tparam A the domain event type; requires a `Schema[A]` in implicit scope at construction
  * @see [[kyo.Journal]] for the raw `Span[Byte]` capability this type wraps
  * @see [[kyo.EventLog.Typed]] for the decoded record type returned by `read`
  * @see [[kyo.EventPayloadCodec]] for the underlying codec abstraction
  */
final class EventLog[A](using Schema[A]):

    private val codec = new SchemaPayloadCodec[A]

    // Unsafe: counter and seed are allocated at construction without a Sync effect.
    // AllowUnsafe sanctions the low-level atomic initialisation; the values never escape this instance.
    import AllowUnsafe.embrace.danger
    private val seed: Long          = java.lang.System.nanoTime() ^ java.lang.System.identityHashCode(this).toLong
    private val counter: AtomicLong = AtomicLong.Unsafe.init(0L).safe

    /** Encodes each value in `events` and appends the batch atomically to `streamId`.
      *
      * The event type label for every event in the batch is derived from the schema's structure
      * name: `Schema[A].structure.name`. Each event identifier is an opaque producer token drawn
      * from a per-instance monotonic counter seeded at construction time; identifiers carry no
      * positional information and are not derived from the stream offset. The journal treats them
      * as opaque and does not deduplicate on them. The true committed position of each event is
      * always available as [[kyo.EventLog.Typed.offset]] after a `read`.
      *
      * @see [[kyo.ExpectedOffset]] for the concurrency semantics of `expected`
      */
    def append(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[A]
    )(using Frame): AppendResult < (Journal & Abort[JournalAppendFailure]) =
        val typeName = summon[Schema[A]].structure.name
        Abort.get(
            EventType(typeName)
                .mapFailure(_ => JournalCorruptedError(Maybe(streamId), "schema segments produce an empty event type label")): Result[
                JournalAppendFailure,
                EventType
            ]
        ).flatMap { eventType =>
            val buildResult: Result[JournalAppendFailure, Seq[EventEnvelope]] =
                Result.collect(
                    (0 until events.size).map { idx =>
                        // Unsafe: counter.unsafe.getAndIncrement() bypasses the Sync wrapper;
                        // AllowUnsafe is in scope from the class-level import above.
                        val idStr = s"$seed-${counter.unsafe.getAndIncrement()}"
                        EventId(idStr)
                            .mapFailure(e =>
                                JournalCorruptedError(Maybe(streamId), s"cannot build event id '$idStr': ${e.getMessage}")
                            )
                            .map(id =>
                                EventEnvelope(
                                    id = id,
                                    eventType = eventType,
                                    payload = codec.encode(events(idx)),
                                    metadata = EventMetadata.empty
                                )
                            )
                    }
                )
            Abort.get(buildResult).flatMap { envelopes =>
                Journal.append(streamId, expected, Chunk.from(envelopes))
            }
        }
    end append

    def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    )(using Frame): Chunk[EventLog.Typed[A]] < (Journal & Abort[JournalReadFailure]) =
        Journal.read(streamId, from, maxCount).flatMap { records =>
            Kyo.foreach(records) { rec =>
                val r: Result[JournalReadFailure, A] =
                    codec.decode(rec.payload).mapFailure(ex => JournalCorruptedError(Maybe(streamId), ex.getMessage))
                Abort.get(r).map(a => EventLog.Typed(rec.offset, rec.eventId, rec.eventType, rec.metadata, a))
            }
        }

    def streamInfo(
        streamId: StreamId
    )(using Frame): StreamInfo < (Journal & Abort[JournalStreamInfoFailure]) =
        Journal.streamInfo(streamId)

end EventLog

object EventLog:
    /** Write-side decider pattern: read typed history, decide the next event, append with optimistic
      * concurrency.
      *
      * A decider consumes the typed event stream through [[EventLog.read]], folds events into local
      * state (a pure function over `Chunk[[Typed]]`), and produces the next domain event to append.
      * The append uses [[ExpectedOffset]] so concurrent writers fail with [[JournalConflictError]];
      * catch the conflict, re-read from `conflict.actual`, re-decide, and retry with the corrected
      * guard (the same recovery loop documented in the kyo-eventlog README under Conflict recovery).
      *
      * Deciders do not require a separate public API type: they are ordinary `Journal.run` programs
      * that call `EventLog[A]` methods. Read-model replay (folding events into state without writing)
      * is the projection side; the decider is the write side that closes the loop by appending new
      * events when the folded state says to act.
      *
      * @see
      *   [[Typed]] for the decoded record type returned by `read`
      * @see
      *   [[kyo.ExpectedOffset]] for the append concurrency model
      */
    /** A decoded record from the event log, with the raw payload decoded to the domain type `A`.
      *
      * Each field maps directly from the underlying [[kyo.RecordedEvent]]: `offset` locates the
      * event within its stream, `eventId` is the application-assigned identity, `eventType`
      * describes the event kind as a string label, `metadata` carries optional key-value
      * annotations, and `payload` is the decoded domain value.
      *
      * @tparam A the domain event type
      */
    final case class Typed[A](
        offset: StreamOffset,
        eventId: EventId,
        eventType: EventType,
        metadata: EventMetadata,
        payload: A
    ) derives CanEqual
end EventLog
