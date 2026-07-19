package kyo

/** Tests for the MANIFEST marker that gates segment encoding at root creation.
  *
  * Opening a root with profile A after it was created with profile B must fail with a typed
  * [[JournalStorageError]]. Opening with the same profile must succeed.
  */
class FileJournalCoreFormatTest extends kyo.test.Test[Any]:

    private val journalId = JournalId.validate("fj-format")(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))

    private def freshDir(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir("fj-format"))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def binaryConfiguration(using Frame) =
        for
            codecs <- EventLogCodecs.bytes()
            configuration = FileJournal.Binary.configuration(journalId, codecs)
        yield configuration

    private def jsonlConfiguration(using Frame) =
        for
            codecs <- EventLogCodecs.bytes()
            configuration = FileJournal.Jsonl.configuration(journalId, codecs)
        yield configuration

    // Opens the backend with the given configuration, returns the JournalStorageError if the open fails.
    private def openWith(dir: Path, configuration: FileJournal.Configuration[Span[Byte]])(using
        Frame
    ): Result[JournalStorageError, Unit] < Async =
        Scope.run {
            Abort.run[JournalStorageError](
                Journal.Backend.file(dir, configuration)
            ).map(_.map(_ => ()))
        }

    "MANIFEST marker" - {
        "first open with Binary writes a MANIFEST file containing format: binary" in {
            for
                dir    <- freshDir
                config <- binaryConfiguration
                result <- openWith(dir, config)
            yield
                assert(result == Result.succeed(()))
                // Verify MANIFEST file exists after close (Scope.run has discharged by now).
                import AllowUnsafe.embrace.danger
                val fmtFile  = dir / "MANIFEST"
                val contents = fmtFile.unsafe.readBytes().getOrElse(Span.empty[Byte])
                val text     = new String(contents.toArray, "UTF-8")
                assert(text.contains("format: binary"))
        }
        "first open with Jsonl writes a MANIFEST file containing format: jsonl" in {
            for
                dir    <- freshDir
                config <- jsonlConfiguration
                result <- openWith(dir, config)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                val fmtFile  = dir / "MANIFEST"
                val contents = fmtFile.unsafe.readBytes().getOrElse(Span.empty[Byte])
                val text     = new String(contents.toArray, "UTF-8")
                assert(text.contains("format: jsonl"))
        }
        "MANIFEST records the resolved payload and metadata media types" in {
            for
                dir    <- freshDir
                config <- binaryConfiguration
                result <- openWith(dir, config)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                val fmtFile  = dir / "MANIFEST"
                val contents = fmtFile.unsafe.readBytes().getOrElse(Span.empty[Byte])
                val text     = new String(contents.toArray, "UTF-8")
                assert(text.contains(s"payload-media-type: ${config.payloadMediaType}"))
                assert(text.contains(s"metadata-media-type: ${config.metadataMediaType}"))
        }
        "reopening a Binary root with Binary profile succeeds" in {
            for
                dir    <- freshDir
                config <- binaryConfiguration
                first  <- openWith(dir, config)
                second <- openWith(dir, config)
            yield
                assert(first == Result.succeed(()))
                assert(second == Result.succeed(()))
        }
        "reopening a Jsonl root with Jsonl profile succeeds" in {
            for
                dir    <- freshDir
                config <- jsonlConfiguration
                first  <- openWith(dir, config)
                second <- openWith(dir, config)
            yield
                assert(first == Result.succeed(()))
                assert(second == Result.succeed(()))
        }
        "opening a Binary root with Jsonl profile fails with JournalStorageError" in {
            for
                dir          <- freshDir
                binaryConfig <- binaryConfiguration
                jsonlConfig  <- jsonlConfiguration
                _            <- openWith(dir, binaryConfig)
                result       <- openWith(dir, jsonlConfig)
            yield
                val isStorageError = result match
                    case Result.Failure(_: JournalStorageError) => true
                    case _                                      => false
                assert(isStorageError)
        }
        "opening a Jsonl root with Binary profile fails with JournalStorageError" in {
            for
                dir          <- freshDir
                binaryConfig <- binaryConfiguration
                jsonlConfig  <- jsonlConfiguration
                _            <- openWith(dir, jsonlConfig)
                result       <- openWith(dir, binaryConfig)
            yield
                val isStorageError = result match
                    case Result.Failure(_: JournalStorageError) => true
                    case _                                      => false
                assert(isStorageError)
        }
        "binary root with existing segments and no MANIFEST file opens as binary without writing MANIFEST" in {
            for
                dir    <- freshDir
                config <- binaryConfiguration
                _ <- Sync.Unsafe.defer {
                    // Simulate a legacy binary root: streams/ has a stream subdirectory
                    // but no MANIFEST file was ever written. The backward-compat path must
                    // succeed and must NOT write a MANIFEST file on reopen.
                    discard((dir / "streams").unsafe.mkDir())
                    discard((dir / "streams" / "0000000000000001").unsafe.mkDir())
                }
                result <- openWith(dir, config)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                assert(!(dir / "MANIFEST").unsafe.exists())
        }
        "crash-partial binary root (segments present, MANIFEST write never landed) opens as binary" in {
            // Distinct from the legacy-root case above only in narrative (a journal that started
            // writing and crashed before its first-open MANIFEST write completed, rather than a
            // pre-MANIFEST-era root); the engine has one code path for MANIFEST-absent-with-streams
            // and treats both identically.
            for
                dir    <- freshDir
                config <- binaryConfiguration
                _ <- Sync.Unsafe.defer {
                    discard((dir / "streams").unsafe.mkDir())
                    discard((dir / "streams" / "crash-partial-1").unsafe.mkDir())
                }
                result <- openWith(dir, config)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                assert(!(dir / "MANIFEST").unsafe.exists())
        }
    }
end FileJournalCoreFormatTest
