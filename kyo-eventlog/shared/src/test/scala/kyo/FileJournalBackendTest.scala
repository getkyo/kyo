package kyo

/** Runs the backend contract suite against the file backend in Binary encoding. Each
  * `newBackend` evaluation allocates a fresh temp dir, opens `Backend.file`, and discharges
  * the open-time `Abort[JournalStorageError]` to a panic (a temp-dir open failing is
  * test-infra breakage, not a modeled condition). Scope finalization releases the LOCK between
  * tests.
  */
class FileJournalBinaryBackendTest
    extends JournalBackendTest(
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-binary"))).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            Abort.run[JournalStorageError](
                Journal.Backend.file(dir, FileJournal.Config(format = FileJournal.SegmentFormat.Binary))
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )

/** Runs the backend contract suite against the file backend in JSONL encoding. Exercises the
  * full format path end-to-end: FORMAT marker creation, JSONL frameBatch, per-record decode,
  * and recovery.
  */
class FileJournalJsonlBackendTest
    extends JournalBackendTest(
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-jsonl"))).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            Abort.run[JournalStorageError](
                Journal.Backend.file(dir, FileJournal.Config(format = FileJournal.SegmentFormat.Jsonl))
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )
