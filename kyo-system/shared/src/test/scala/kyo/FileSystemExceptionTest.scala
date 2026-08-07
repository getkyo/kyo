package kyo

class FileSystemExceptionTest extends kyo.test.Test[Any]:

    // A dummy path used only to construct exception instances — no I/O is performed.
    val p: Path = Path("tmp", "kyo-file-exception-test")

    "structured failure leaves retain decision fields" in {
        val target = Path("tmp", "target")
        val cause  = new java.io.IOException("diagnostic")
        val values = Chunk(
            FileNotFoundException(p),
            FileAlreadyExistsException(p),
            FileAccessDeniedException(p),
            FileIsADirectoryException(p),
            FileDirectoryNotEmptyException(p),
            FileInvalidPathException("nul", FileSystemOperation.Read),
            FileOutsideRootException(p, target, FileSystemOperation.Read),
            FileIOException(p, FileSystemOperation.Read, cause),
            FileAtomicMoveUnsupportedException(p, target),
            FileLockUnavailableException(p),
            FileLockTimeoutException(p, 10.millis),
            FileLockOwnershipLostException(p),
            FileWatchInvalidatedException(p)
        )
        assert(values.size == 13)
        assert(values(5).asInstanceOf[FileInvalidPathException].input == "nul")
        assert(values(6).asInstanceOf[FileOutsideRootException].root == p)
        assert(values(6).asInstanceOf[FileOutsideRootException].path == target)
        assert(values(7).asInstanceOf[FileIOException].operation == FileSystemOperation.Read)
        assert(values(7).getCause eq cause)
        assert(values(8).asInstanceOf[FileAtomicMoveUnsupportedException].source == p)
        assert(values(8).asInstanceOf[FileAtomicMoveUnsupportedException].target == target)
    }

    "leaves belong only to applicable operation categories" in {
        assert(FileNotFoundException(p).isInstanceOf[FileReadException])
        assert(FileAlreadyExistsException(p).isInstanceOf[FileStructureException])
        assert(FileAtomicMoveUnsupportedException(p, Path("to")).isInstanceOf[FileStructureException])
        assert(FileLockTimeoutException(p, 1.millis).isInstanceOf[FileLockException])
        assert(FileLockOwnershipLostException(p).isInstanceOf[FileLockException])
        assert(FileWatchInvalidatedException(p).isInstanceOf[FileWatchException])
        assert(!FileWatchInvalidatedException(p).isInstanceOf[FileReadException])
    }

    "FileIsADirectoryException is FileReadException and FileWriteException but not FileStructureException" in {
        val ex = FileIsADirectoryException(p)
        assert(ex.isInstanceOf[FileReadException])
        assert(ex.isInstanceOf[FileWriteException])
        assert(!ex.isInstanceOf[FileStructureException])
    }

    "FileNotADirectoryException is only FileStructureException" in {
        val ex = FileNotADirectoryException(p)
        assert(ex.isInstanceOf[FileStructureException])
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
            case _: FileInvalidPathException  => "FileInvalidPathException"
            case _: FileOutsideRootException  => "FileOutsideRootException"
            case _: FileIOException           => "FileIOException"
        assert(result == "FileNotFoundException")
    }

    // Partial recovery — Abort.recover[FileNotFoundException] leaves other FileReadException
    // subtypes in Abort. We verify that:
    //   (a) a FileNotFoundException is recovered (returns the fallback value)
    //   (b) a FileAccessDeniedException is NOT recovered and remains as Abort failure
    "Abort.recover[FileNotFoundException] recovers only FileNotFoundException" in {
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
                case Result.Failure(_: FileAccessDeniedException) => ()
                case other                                        => fail(s"Expected FileAccessDeniedException failure, got $other")
        end for
    }

    // Exhaustive match on FileStructureException covers all concrete subtypes — no wildcard.
    // FileIsADirectoryException does not implement FileStructureException.
    "exhaustive match on FileStructureException" in {
        val ex: FileStructureException = FileNotFoundException(p)
        val result = ex match
            case _: FileNotFoundException              => "FileNotFoundException"
            case _: FileAccessDeniedException          => "FileAccessDeniedException"
            case _: FileNotADirectoryException         => "FileNotADirectoryException"
            case _: FileAlreadyExistsException         => "FileAlreadyExistsException"
            case _: FileDirectoryNotEmptyException     => "FileDirectoryNotEmptyException"
            case _: FileInvalidPathException           => "FileInvalidPathException"
            case _: FileOutsideRootException           => "FileOutsideRootException"
            case _: FileIOException                    => "FileIOException"
            case _: FileAtomicMoveUnsupportedException => "FileAtomicMoveUnsupportedException"
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
            case _: FileInvalidPathException  => "FileInvalidPathException"
            case _: FileOutsideRootException  => "FileOutsideRootException"
            case _: FileIOException           => "FileIOException"
        assert(result == "FileNotFoundException")
    }

end FileSystemExceptionTest
