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

    /** Encodes each value in `events` and appends the batch atomically to `streamId`.
      *
      * The event type label for every event in the batch is derived from the schema's structure
      * name: `Schema[A].structure.name`. Each event identifier is synthesized from the stream
      * position observed just before the write: `"$streamId:$baseOffset"` for the first event,
      * `"$streamId:${baseOffset+1}"` for the second, and so on; `baseOffset` is
      * `streamInfo.lastOffset + 1` for an existing stream, or `0` for a new one.
      *
      * Synthesized identifiers are position estimates, not guarantees. The journal treats them as
      * opaque and does not deduplicate on them. Under `ExpectedOffset.Any` the actual assigned
      * offset can diverge from the pre-write estimate, so identifiers may not match the final
      * stored position. Under `ExpectedOffset.Exact` the optimistic check ensures the estimate
      * aligns with the actual commit position.
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
            Abort.run[JournalStreamInfoFailure](Journal.streamInfo(streamId)).flatMap {
                case Result.Success(info) =>
                    val baseOffset = info match
                        case StreamInfo.Absent                  => 0L
                        case StreamInfo.Existing(_, lastOffset) => lastOffset.value + 1L
                    val buildResult: Result[JournalAppendFailure, Seq[EventEnvelope]] =
                        Result.collect(
                            (0 until events.size).map { idx =>
                                val bytes = codec.encode(events(idx))
                                val idStr = s"${streamId.value}:${baseOffset + idx}"
                                EventId(idStr)
                                    .mapFailure(e =>
                                        JournalCorruptedError(Maybe(streamId), s"cannot synthesize event id '$idStr': ${e.getMessage}")
                                    )
                                    .map(id =>
                                        EventEnvelope(id = id, eventType = eventType, payload = bytes, metadata = EventMetadata.empty)
                                    )
                            }
                        )
                    Abort.get(buildResult).flatMap { envelopes =>
                        Journal.append(streamId, expected, Chunk.from(envelopes))
                    }
                case Result.Failure(e: JournalCorruptedError) =>
                    // JournalCorruptedError extends JournalAppendFailure; propagate as-is.
                    Abort.fail[JournalAppendFailure](e)
                case Result.Failure(e: JournalStorageError) =>
                    // JournalStorageError is an I/O failure, not a corruption; propagate as-is.
                    // It also extends JournalAppendFailure so the locked row holds.
                    Abort.fail[JournalAppendFailure](e)
                case p: Result.Panic =>
                    throw p.exception
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
