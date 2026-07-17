package kyo

/** Confirms the Node.js Async backend never blocks the event loop on a per-record I/O call: a
  * large append (a multi-megabyte single payload, dominating any fixed per-append overhead) is
  * started first, and a quick append to an independent stream is started immediately after, while
  * the large one is still in flight. If the store performed a synchronous `fs` call anywhere on
  * this path, the single Node.js thread could not run the quick append's own work until the large
  * one's blocking call returned, so the quick append could only ever finish after it, not before.
  * The ordering is confirmed by which of the two completion signals is taken off a shared channel
  * first: never a wall-clock check.
  */
class FileJournalNodeAsyncLivenessTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))

    private def freshDir(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir("fj-node-async-liveness"))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def binaryConfiguration(using Frame) =
        for
            codecs        <- EventLogCodecs.bytes()
            journalId     <- JournalId("fj-node-async-liveness")
            configuration <- FileJournal.Binary.configuration(journalId, codecs)
        yield configuration

    private def envelope(id: String, payload: Array[Byte]): Event.Pending =
        Event.Pending(
            id = valid(Event.Id(id)),
            eventType = valid(Event.Type("LivenessProbe")),
            payload = Span.from(payload),
            metadata = Event.Metadata.empty
        )

    "Node async liveness" - {
        "a quick append to an independent stream completes before a much larger, earlier-started append" in {
            val largePayload = new Array[Byte](32 * 1024 * 1024)
            val quickPayload = Array[Byte](1, 2, 3)
            for
                dir           <- freshDir
                order         <- Channel.initUnscoped[String](2)
                configuration <- binaryConfiguration
                backend <- Abort.run[JournalStorageError](Journal.Backend.fileAsync(dir, configuration)).map {
                    case Result.Success(b)   => b
                    case Result.Failure(err) => throw err
                    case panic: Result.Panic => throw panic.exception
                }
                large <- Fiber.initUnscoped(
                    Abort.run[JournalError](backend.append(
                        valid(Event.StreamId("large-stream")),
                        ExpectedOffset.NoStream,
                        Chunk(envelope("large-event", largePayload))
                    )).andThen(order.put("append-done"))
                )
                quick <- Fiber.initUnscoped(
                    Abort.run[JournalError](backend.append(
                        valid(Event.StreamId("quick-stream")),
                        ExpectedOffset.NoStream,
                        Chunk(envelope("quick-event", quickPayload))
                    )).andThen(order.put("quick-done"))
                )
                _      <- large.get
                _      <- quick.get
                first  <- order.take
                second <- order.take
            yield
                assert(first == "quick-done", s"expected the quick append to complete first, but observed order was ($first, $second)")
                assert(second == "append-done")
            end for
        }
    }
end FileJournalNodeAsyncLivenessTest
