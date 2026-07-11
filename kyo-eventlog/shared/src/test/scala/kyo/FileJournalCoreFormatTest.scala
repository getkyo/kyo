package kyo

/** Tests for the FORMAT marker that gates segment encoding at root creation.
  *
  * Opening a root with format A after it was created with format B must fail with a typed
  * [[JournalStorageError]]. Opening with the same format must succeed.
  */
class FileJournalCoreFormatTest extends kyo.test.Test[Any]:

    private def freshDir(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir("fj-format"))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    // Opens the backend with the given format, returns the JournalStorageError if the open fails.
    private def openWith(dir: Path, format: FileJournal.SegmentFormat)(using
        Frame
    )
        : Result[JournalStorageError, Unit] < Async =
        Scope.run {
            Abort.run[JournalStorageError](
                Journal.Backend.file(dir, FileJournal.Config(format = format))
            ).map(_.map(_ => ()))
        }

    "FORMAT marker" - {
        "first open with Binary writes a FORMAT file containing format: binary" in {
            for
                dir    <- freshDir
                result <- openWith(dir, FileJournal.SegmentFormat.Binary)
            yield
                assert(result == Result.succeed(()))
                // Verify FORMAT file exists after close (Scope.run has discharged by now).
                import AllowUnsafe.embrace.danger
                val fmtFile  = dir / "FORMAT"
                val contents = fmtFile.unsafe.readBytes().getOrElse(Span.empty[Byte])
                val text     = new String(contents.toArray, "UTF-8")
                assert(text.contains("format: binary"))
        }
        "first open with Jsonl writes a FORMAT file containing format: jsonl" in {
            for
                dir    <- freshDir
                result <- openWith(dir, FileJournal.SegmentFormat.Jsonl)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                val fmtFile  = dir / "FORMAT"
                val contents = fmtFile.unsafe.readBytes().getOrElse(Span.empty[Byte])
                val text     = new String(contents.toArray, "UTF-8")
                assert(text.contains("format: jsonl"))
        }
        "reopening a Binary root with Binary format succeeds" in {
            for
                dir    <- freshDir
                first  <- openWith(dir, FileJournal.SegmentFormat.Binary)
                second <- openWith(dir, FileJournal.SegmentFormat.Binary)
            yield
                assert(first == Result.succeed(()))
                assert(second == Result.succeed(()))
        }
        "reopening a Jsonl root with Jsonl format succeeds" in {
            for
                dir    <- freshDir
                first  <- openWith(dir, FileJournal.SegmentFormat.Jsonl)
                second <- openWith(dir, FileJournal.SegmentFormat.Jsonl)
            yield
                assert(first == Result.succeed(()))
                assert(second == Result.succeed(()))
        }
        "opening a Binary root with Jsonl format fails with JournalStorageError" in {
            for
                dir    <- freshDir
                _      <- openWith(dir, FileJournal.SegmentFormat.Binary)
                result <- openWith(dir, FileJournal.SegmentFormat.Jsonl)
            yield
                val isStorageError = result match
                    case Result.Failure(_: JournalStorageError) => true
                    case _                                      => false
                assert(isStorageError)
        }
        "opening a Jsonl root with Binary format fails with JournalStorageError" in {
            for
                dir    <- freshDir
                _      <- openWith(dir, FileJournal.SegmentFormat.Jsonl)
                result <- openWith(dir, FileJournal.SegmentFormat.Binary)
            yield
                val isStorageError = result match
                    case Result.Failure(_: JournalStorageError) => true
                    case _                                      => false
                assert(isStorageError)
        }
        "binary root with existing segments and no FORMAT file opens as binary without writing FORMAT" in {
            for
                dir <- freshDir
                _ <- Sync.Unsafe.defer {
                    // Simulate a legacy binary root: streams/ has a stream subdirectory
                    // but no FORMAT file was ever written. The backward-compat path must
                    // succeed and must NOT write a FORMAT file on reopen.
                    discard((dir / "streams").unsafe.mkDir())
                    discard((dir / "streams" / "0000000000000001").unsafe.mkDir())
                }
                result <- openWith(dir, FileJournal.SegmentFormat.Binary)
            yield
                assert(result == Result.succeed(()))
                import AllowUnsafe.embrace.danger
                assert(!(dir / "FORMAT").unsafe.exists())
        }
    }
end FileJournalCoreFormatTest
