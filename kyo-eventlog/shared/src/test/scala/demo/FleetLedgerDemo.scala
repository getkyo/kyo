package demo

import kyo.*

/** A self-contained demo of a fleet operations ledger: vehicle lifecycle events persisted to
  * disk through the file journal and replayed with typed EventLog records.
  *
  * Stands in for a rental-fleet service that appends domain events and rebuilds read models
  * from the log. The demo nests `Path.run` (workspace dir), `Journal.Backend.file` (durable
  * segments), and `EventLog` (schema-backed payloads). After the first journal scope closes
  * and releases the root lock, a second open reads the same stream from disk, proving the
  * on-disk format survives process-style restart. A naive in-memory journal loses state on
  * close; callers that need crash recovery must use the file backend rooted at a Path.
  *
  * Dual-purpose: run it to print the ledger trace (`runMain`), and [[DemoValidationTest]]
  * exercises the same `flow` plus `validate` as a CI guard.
  *
  * Run: `sbt 'kyo-eventlogJVM/testOnly demo.DemoValidationTest'`
  * Run: `sbt 'kyo-eventlogJVM/Test/runMain demo.FleetLedgerDemo'`
  *
  * Demonstrates: Path.run, Path.tempDir, Journal.Backend.file, Journal.run, EventLog.init,
  * EventLog.append, EventLog.read, Journal.streamInfo, ExpectedOffset, StreamOffset.
  */
object FleetLedgerDemo extends KyoApp:

    sealed trait FleetEvent derives Schema, CanEqual
    object FleetEvent:
        final case class VehicleAdded(id: String, make: String) extends FleetEvent derives Schema, CanEqual
        final case class VehicleRetired(id: String)             extends FleetEvent derives Schema, CanEqual
    end FleetEvent

    final case class LedgerSnapshot(
        firstBatchSize: Int,
        replayedSize: Int,
        retiredPayload: FleetEvent,
        streamPresentAfterReopen: Boolean
    ) derives CanEqual

    // Unsafe: eagerly resolves the pure (no real Sync/IO) identity/codec/configuration
    // construction to a plain value at object-init time; every factory below can only fail on a
    // genuine construction-time misconfiguration, never on this fixed, valid setup.
    private def evalPure[E, A](v: A < Abort[E])(using Frame, ConcreteTag[E]): A =
        Abort.run[E](v).eval match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new IllegalStateException(s"unexpected construction failure: $err")
            case panic: Result.Panic => throw panic.exception

    private val journalId = evalPure(JournalId("fleet-main"))
    private val streamId  = StreamId(journalId.value).getOrElse(throw new IllegalStateException("invalid stream id"))

    private val vehicleAddedStream: EventLog.StreamSelector[FleetEvent.VehicleAdded] =
        new EventLog.StreamSelector[FleetEvent.VehicleAdded] {}
    private val vehicleRetiredStream: EventLog.StreamSelector[FleetEvent.VehicleRetired] =
        new EventLog.StreamSelector[FleetEvent.VehicleRetired] {}

    private given EventLog.EventDefinition[FleetEvent, FleetEvent.VehicleAdded] =
        EventLog.EventDefinition.schema[FleetEvent, FleetEvent.VehicleAdded](vehicleAddedStream)
    private given EventLog.EventDefinition[FleetEvent, FleetEvent.VehicleRetired] =
        EventLog.EventDefinition.schema[FleetEvent, FleetEvent.VehicleRetired](vehicleRetiredStream)

    private val fleetCodecs: EventLog.Codecs[FleetEvent] =
        evalPure(EventLogCodecs.schema[FleetEvent]())

    private val jsonlConfiguration: FileJournal.Configuration[FleetEvent, FileJournal.Jsonl] =
        evalPure(FileJournal.Jsonl.configuration(journalId, fleetCodecs))

    def flow(using Frame): LedgerSnapshot < (Async & Abort[FileException | JournalError | EventLog.PreparationFailure]) =
        Scope.run {
            Path.run {
                for
                    dir            <- Path.tempDir("fleet-ledger-")
                    firstBatchSize <- writeAndCount(dir)
                    replayed       <- rereadAfterReopen(dir)
                    streamPresent  <- streamInfoAfterReopen(dir)
                yield LedgerSnapshot(
                    firstBatchSize = firstBatchSize,
                    replayedSize = replayed.size,
                    retiredPayload = replayed.last.payload,
                    streamPresentAfterReopen = streamPresent
                )
            }
        }
    end flow

    private def writeAndCount(dir: Path)(using Frame): Int < (Async & Abort[FileException | JournalError | EventLog.PreparationFailure]) =
        Scope.run {
            for
                log     <- EventLog.init(fleetCodecs, journalId)
                backend <- Journal.Backend.file(dir, jsonlConfiguration)
                count <- Journal.run(backend) {
                    for
                        _ <- log.append(
                            FleetEvent.VehicleAdded("V001", "Toyota"),
                            EventLog.AppendDirective.expected(ExpectedOffset.NoStream)
                        )
                        _      <- log.append(FleetEvent.VehicleRetired("V001"))
                        events <- log.read(streamId, StreamOffset.first, maxCount = 10)
                    yield events.size
                }
            yield count
        }

    private def rereadAfterReopen(dir: Path)(using
        Frame
    ): Chunk[EventLog.Record[FleetEvent]] < (Async & Abort[FileException | JournalError]) =
        Scope.run {
            for
                log     <- EventLog.init(fleetCodecs, journalId)
                backend <- Journal.Backend.file(dir, jsonlConfiguration)
                events <- Journal.run(backend) {
                    log.read(streamId, StreamOffset.first, maxCount = 10)
                }
            yield events
        }

    private def streamInfoAfterReopen(dir: Path)(using Frame): Boolean < (Async & Abort[FileException | JournalError]) =
        Scope.run {
            for
                backend <- Journal.Backend.file(dir, jsonlConfiguration)
                present <- Journal.run(backend) {
                    Journal.streamInfo(streamId).map(_.exists)
                }
            yield present
        }

    def validate(result: LedgerSnapshot): Maybe[String] =
        val checks = Seq(
            (result.firstBatchSize == 2, s"first session must append 2 events, got ${result.firstBatchSize}"),
            (result.replayedSize == 2, s"reopened journal must replay 2 events, got ${result.replayedSize}"),
            (
                result.retiredPayload == FleetEvent.VehicleRetired("V001"),
                s"last event must be VehicleRetired(V001), got ${result.retiredPayload}"
            ),
            (result.streamPresentAfterReopen, "streamInfo must report Existing after reopen")
        )
        checks.collectFirst { case (false, msg) => msg }.map(Present(_)).getOrElse(Absent)
    end validate

    run {
        for
            snapshot <- Abort.run[FileException | JournalError | EventLog.PreparationFailure](flow)
            verdict = snapshot match
                case Result.Success(value) => validate(value)
                case Result.Failure(err)   => Present(s"flow aborted: $err")
                case Result.Panic(ex)      => Present(s"flow panicked: $ex")
            _ <- verdict match
                case Absent =>
                    Console.printLine("\n[OK] validation passed")
                case Present(msg) =>
                    Console.printLineErr(s"\n[FAIL] validation: $msg")
                        .andThen(Abort.fail(new RuntimeException(s"demo validation failed: $msg")))
        yield ()
    }

end FleetLedgerDemo
