package kyo

class FileExceptionTest extends Test:

    // A dummy path used only to construct exception instances — no I/O is performed.
    val p: Path = Path("tmp", "kyo-file-exception-test")

    "FileIsADirectoryException is FileReadException and FileWriteException but not FileFsException" in {
        val ex = FileIsADirectoryException(p)
        assert(ex.isInstanceOf[FileReadException])
        assert(ex.isInstanceOf[FileWriteException])
        assert(!ex.isInstanceOf[FileFsException])
    }

    "FileNotADirectoryException is FileFsException but not FileReadException or FileWriteException" in {
        val ex = FileNotADirectoryException(p)
        assert(ex.isInstanceOf[FileFsException])
        assert(!ex.isInstanceOf[FileReadException])
        assert(!ex.isInstanceOf[FileWriteException])
    }

    // Exhaustive match on FileReadException covers all concrete subtypes with no wildcard.
    // If a new concrete subtype is added without updating this match, the compiler will emit a
    // non-exhaustive match warning (with -Xfatal-warnings this would be a compile error).
    "exhaustive match on FileReadException covers all concrete subtypes" in {
        val ex: FileReadException = FileNotFoundException(p)
        val result = ex match
            case _: FileNotFoundException     => "FileNotFoundException"
            case _: FileAccessDeniedException => "FileAccessDeniedException"
            case _: FileIsADirectoryException => "FileIsADirectoryException"
            case _: FileIOException           => "FileIOException"
        assert(result == "FileNotFoundException")
    }

    // Partial recovery — Abort.recover[FileNotFoundException] leaves other FileReadException
    // subtypes in Abort. We verify that:
    //   (a) a FileNotFoundException is recovered (returns the fallback value)
    //   (b) a FileAccessDeniedException is NOT recovered and remains as Abort failure
    "Abort.recover[FileNotFoundException] recovers only FileNotFoundException" in run {
        // (a) FileNotFoundException should be recovered to -1
        val notFoundComputation: Int < (Sync & Abort[FileReadException]) =
            Abort.fail(FileNotFoundException(p)).map((_: Nothing) => 42)

        val recoveredResult: (Int | Int) < (Sync & Abort[FileReadException]) =
            Abort.recover[FileNotFoundException](_ => -1)(notFoundComputation)

        val outcome1 = Abort.run[FileReadException](recoveredResult)

        // (b) FileAccessDeniedException should NOT be recovered
        val accessDeniedComputation: Int < (Sync & Abort[FileReadException]) =
            Abort.fail(FileAccessDeniedException(p)).map((_: Nothing) => 42)

        val notRecoveredResult: (Int | Int) < (Sync & Abort[FileReadException]) =
            Abort.recover[FileNotFoundException](_ => -1)(accessDeniedComputation)

        val outcome2 = Abort.run[FileReadException](notRecoveredResult)

        for
            r1 <- outcome1
            r2 <- outcome2
        yield
            assert(r1 == Result.Success(-1))
            r2 match
                case Result.Failure(_: FileAccessDeniedException) => succeed
                case other                                        => fail(s"Expected FileAccessDeniedException failure, got $other")
        end for
    }

    // Exhaustive match on FileFsException covers all concrete subtypes — no wildcard.
    // FileNotFoundException, FileAccessDeniedException, FileNotADirectoryException,
    // FileAlreadyExistsException, FileDirectoryNotEmptyException, and FileIOException all
    // implement FileFsException. FileIsADirectoryException does NOT.
    "exhaustive match on FileFsException" in {
        val ex: FileFsException = FileNotFoundException(p)
        val result = ex match
            case _: FileNotFoundException          => "FileNotFoundException"
            case _: FileAccessDeniedException      => "FileAccessDeniedException"
            case _: FileNotADirectoryException     => "FileNotADirectoryException"
            case _: FileAlreadyExistsException     => "FileAlreadyExistsException"
            case _: FileDirectoryNotEmptyException => "FileDirectoryNotEmptyException"
            case _: FileIOException                => "FileIOException"
        assert(result == "FileNotFoundException")
    }

    // Exhaustive match on FileWriteException covers all concrete subtypes — no wildcard.
    // FileNotFoundException, FileAccessDeniedException, FileIsADirectoryException,
    // and FileIOException all implement FileWriteException.
    "exhaustive match on FileWriteException" in {
        val ex: FileWriteException = FileNotFoundException(p)
        val result = ex match
            case _: FileNotFoundException     => "FileNotFoundException"
            case _: FileAccessDeniedException => "FileAccessDeniedException"
            case _: FileIsADirectoryException => "FileIsADirectoryException"
            case _: FileIOException           => "FileIOException"
        assert(result == "FileNotFoundException")
    }

end FileExceptionTest
