package kyo

/** Runs the backend contract suite against the Async file backend in Binary encoding. Same
  * contract, same fixture shape as [[FileJournalBinaryBackendTest]], but opened through
  * `Backend.fileAsync` so every operation runs under `Async` instead of `Sync`. Each
  * `newBackend` evaluation allocates a fresh temp dir, opens the backend, and discharges the
  * open-time `Abort[JournalStorageError]` to a panic (a temp-dir open failing is test-infra
  * breakage, not a modeled condition). Scope finalization releases the LOCK between tests.
  */
class FileJournalAsyncBinaryBackendTest
    extends JournalBackendTest(
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-async-binary"))).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            val journalId = JournalId.validate("fj-async-binary-backend")(using Frame.internal)
                .getOrElse(throw new AssertionError("valid journal id"))
            val codecs = Abort.run[EventCodecConfigurationError](EventLogCodecs.bytes()).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            val configuration = Abort.run[FileJournal.ConfigurationError](
                FileJournal.Binary.configuration(journalId, codecs)
            ).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            Abort.run[JournalStorageError](
                Journal.Backend.fileAsync(dir, configuration)
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )

/** Runs the backend contract suite against the Async file backend in JSONL encoding. Exercises
  * the same recovery, framing, and per-record decode path as [[FileJournalJsonlBackendTest]]
  * under the Async store seam, the parked per-stream claim, and group-commit flushing.
  */
class FileJournalAsyncJsonlBackendTest
    extends JournalBackendTest(
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-async-jsonl"))).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            val journalId = JournalId.validate("fj-async-jsonl-backend")(using Frame.internal)
                .getOrElse(throw new AssertionError("valid journal id"))
            val codecs = Abort.run[EventCodecConfigurationError](EventLogCodecs.bytes()).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            val configuration = Abort.run[FileJournal.ConfigurationError](
                FileJournal.Jsonl.configuration(journalId, codecs)
            ).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            Abort.run[JournalStorageError](
                Journal.Backend.fileAsync(dir, configuration)
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )
