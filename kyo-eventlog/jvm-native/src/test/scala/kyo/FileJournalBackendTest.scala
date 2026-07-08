package kyo

/** Runs the backend contract suite against the file backend. Each `newBackend` evaluation
  * allocates a fresh temp dir, opens `Backend.file`, and discharges the open-time
  * `Abort[JournalStorageError]` to a panic (a temp-dir open failing is test-infra breakage,
  * not a modeled condition). Scope finalization releases the LOCK between tests.
  */
class FileJournalBackendTest
    extends JournalBackendTest(
        // Path.tempDir has row Sync & Abort[FileFsException]; the by-name hook wants Sync & Scope. Both
        // open-time Aborts are run to a panic here (FileFsException from tempDir, then JournalStorageError
        // from Backend.file): a temp-dir or open failure is test-infra breakage, not a modeled condition.
        // Scope is retained so the LOCK releases between tests.
        Abort.run[FileFsException](Path.tempDir("kyo-file-journal-backend")).map {
            case Result.Success(dir) => dir
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }.map { dir =>
            Abort.run[JournalStorageError](Journal.Backend.file(dir)).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )
