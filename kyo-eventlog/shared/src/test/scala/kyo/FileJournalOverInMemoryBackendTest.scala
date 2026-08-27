package kyo

import kyo.AllowUnsafe.embrace.danger

private object FileJournalOverInMemoryBackendTest:
    // Unsafe: a process-wide monotonic counter, a test-only construct with no Scope, giving each
    // newBackend evaluation a unique root. JournalBackendTest's leaves run concurrently within one
    // suite, so each names its own root rather than reusing a fixed literal that concurrent
    // evaluations would contend over on the journal's process-keyed held-root registry.
    private val rootCounter = AtomicInt.Unsafe.init(0)
end FileJournalOverInMemoryBackendTest

/** Runs the backend contract suite against a `fileOver(FileSystem.inMemory, ...)`-backed
  * instance: a deterministic, temp-dir-free journal. Each `newBackend` evaluation
  * allocates a fresh [[FileSystem.inMemory]] value and discharges the open-time
  * `Abort[JournalStorageError]` to a panic (test-infra breakage, not a modeled condition).
  */
class FileJournalOverInMemoryBackendTest
    extends JournalBackendTest(
        FileSystem.inMemory.map { fs =>
            val root = Path(s"backend-inmemory-root-${FileJournalOverInMemoryBackendTest.rootCounter.incrementAndGet()}")
            val journalId = JournalId.validate("fj-over-inmemory-backend")(using Frame.internal)
                .getOrElse(throw new AssertionError("valid journal id"))
            val codecs = Abort.run[EventCodecConfigurationError](EventLogCodecs.bytes()).eval match
                case Result.Success(c)   => c
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            val configuration = FileJournal.Binary.configuration(journalId, codecs)
            Abort.run[JournalStorageError](
                Journal.Backend.fileOver(fs, root, configuration)
            ).map {
                case Result.Success(backend) => backend
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }
    )
