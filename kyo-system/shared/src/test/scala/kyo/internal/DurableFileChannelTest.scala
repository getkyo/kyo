package kyo.internal

import kyo.*

class DurableFileChannelTest extends kyo.test.Test[Any]:

    private val target    = Path("dir", "target.bin")
    private val temporary = Path("dir", ".kyo-durable-0")

    "a zero return code is success" in {
        assert(DurableFileChannel.outcome(target, FileSystemOperation.Write, 0) == Result.unit)
    }

    "a negative return code reports a stalled write with the unwritten count" in {
        DurableFileChannel.outcome(temporary, FileSystemOperation.Write, -7) match
            case Result.Failure(FileWriteStalledException(path, remaining)) =>
                assert(path == temporary)
                assert(remaining == 7L.bytes)
            case other => fail(s"Expected a stalled write, got $other")
    }

    "a positive return code is an OS failure of the named operation" in {
        DurableFileChannel.outcome(temporary, FileSystemOperation.Sync, 5) match
            case Result.Failure(FileIOException(path, operation, cause)) =>
                assert(path == temporary)
                assert(operation == FileSystemOperation.Sync)
                assert(cause.getMessage.contains("OS error 5"))
            case other => fail(s"Expected an OS failure, got $other")
    }

    "an open that finds a directory at the target reports the target as a directory" in {
        assert(DurableFileChannel.openFailure(target, temporary, 5, 21) == FileIsADirectoryException(target))
    }

    "an open that finds a link at the target reports that it needs a regular file" in {
        DurableFileChannel.openFailure(target, temporary, 6, 62) match
            case FileIOException(path, FileSystemOperation.Channel, cause) =>
                assert(path == target)
                assert(cause.getMessage == s"Durable replacement needs an absent target or a regular file: $target")
            case other => fail(s"Expected a regular-file failure, got $other")
    }

    "an open whose parent is not a directory names the parent" in {
        assert(DurableFileChannel.openFailure(target, temporary, 3, 20) == FileNotADirectoryException(Path("dir")))
    }

end DurableFileChannelTest
