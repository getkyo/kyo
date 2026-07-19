package kyo

/** Runs the backend contract suite against a `fileOver(FileSystem.host, ...)`-backed
  * instance: the general [[FileSystem]]-channel path opened over the same real host
  * filesystem [[Journal.Backend.file]] uses via its [[kyo.internal.SegmentStore]] fast
  * path. Each `newBackend` evaluation allocates a fresh temp dir and discharges the
  * open-time `Abort[JournalStorageError]` to a panic (a temp-dir open failing is
  * test-infra breakage, not a modeled condition). Scope finalization releases the LOCK
  * between tests.
  */
class FileJournalOverHostBackendTest
    extends JournalBackendTest(
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-over-host"))).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            val journalId = JournalId.validate("fj-over-host-backend")(using Frame.internal)
                .getOrElse(throw new AssertionError("valid journal id"))
            val codecs = Abort.run[EventCodecConfigurationError](EventLogCodecs.bytes()).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            val configuration = FileJournal.Binary.configuration(journalId, codecs)
            Abort.run[JournalStorageError](
                Journal.Backend.fileOver(FileSystem.host, dir, configuration)
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )
