package kyo

/** Runs the backend contract suite against the file backend. Each `newBackend` evaluation
  * allocates a fresh temp dir, opens `Backend.file`, and discharges the open-time
  * `Abort[JournalStorageError]` to a panic (a temp-dir open failing is test-infra breakage,
  * not a modeled condition). Scope finalization releases the LOCK between tests.
  */
class FileJournalBackendTest
    extends JournalBackendTest(
        // Path.tempDir carries PathWrite & Sync & Scope. Path.run discharges PathWrite and adds
        // Abort[FileException]; both open-time failures (FileException from tempDir, JournalStorageError
        // from Backend.file) are run to a panic: a temp-dir or open failure is test-infra breakage.
        // Scope is retained so the LOCK releases between tests.
        Abort.run[FileException](Path.run(Path.tempDir("kyo-file-journal-backend"))).map {
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
