package kyo

/** Operations recorded by generic filesystem failures. */
enum FileSystemOperation derives CanEqual:
    case Exists, Inspect, RealPath, Read, Write, List, Walk, Create, Move, Copy, Remove, Channel, Sync, Lock, Watch

/** Base type for failures reported by filesystem capabilities. */
sealed abstract class FileSystemException(message: String, cause: Throwable | String = "")(using Frame)
    extends KyoException(message, cause)

sealed trait FileReadException      extends FileSystemException
sealed trait FileWriteException     extends FileSystemException
sealed trait FileStructureException extends FileSystemException
sealed trait FileLockException      extends FileSystemException
sealed trait FileWatchException     extends FileSystemException

case class FileNotFoundException(path: Path)(using Frame)
    extends FileSystemException(s"File or directory not found: $path")
    with FileReadException with FileWriteException with FileStructureException derives CanEqual

case class FileAccessDeniedException(path: Path)(using Frame)
    extends FileSystemException(s"Permission denied: $path")
    with FileReadException with FileWriteException with FileStructureException with FileLockException with FileWatchException
    derives CanEqual

case class FileIsADirectoryException(path: Path)(using Frame)
    extends FileSystemException(s"Expected a file but found a directory: $path")
    with FileReadException with FileWriteException derives CanEqual

case class FileNotADirectoryException(path: Path)(using Frame)
    extends FileSystemException(s"Expected a directory but found a file: $path")
    with FileStructureException derives CanEqual

case class FileAlreadyExistsException(path: Path)(using Frame)
    extends FileSystemException(s"Path already exists: $path")
    with FileStructureException derives CanEqual

case class FileDirectoryNotEmptyException(path: Path)(using Frame)
    extends FileSystemException(s"Cannot remove non-empty directory: $path")
    with FileStructureException derives CanEqual

case class FileInvalidPathException(input: String, operation: FileSystemOperation)(using Frame)
    extends FileSystemException(s"Invalid path for $operation: $input")
    with FileReadException with FileWriteException with FileStructureException with FileLockException with FileWatchException
    derives CanEqual

case class FileIOException(path: Path, operation: FileSystemOperation, diagnosticCause: Throwable)(using Frame)
    extends FileSystemException(s"I/O error during $operation on $path", diagnosticCause)
    with FileReadException with FileWriteException with FileStructureException with FileLockException with FileWatchException
    derives CanEqual

/** A write consumed none of the bytes it was offered, leaving `remaining` of them unwritten.
  *
  * Distinct from a short write, which consumes some of the buffer and is retried from where it
  * stopped. A write that consumes nothing would be retried with the same bytes at the same offset
  * and would report the same result, so the write handles and raw channels stop at the first one
  * and report this instead of looping.
  */
case class FileWriteStalledException(path: Path, remaining: ByteSize)(using Frame)
    extends FileSystemException(s"Write made no progress on $path with ${remaining.show} unwritten")
    with FileWriteException derives CanEqual

case class FileAtomicMoveUnsupportedException(source: Path, target: Path)(using Frame)
    extends FileSystemException(s"Required atomic move from $source to $target is unsupported")
    with FileStructureException derives CanEqual

case class FileLockUnavailableException(path: Path)(using Frame)
    extends FileSystemException(s"Lock unavailable on $path")
    with FileLockException derives CanEqual

case class FileLockTimeoutException(path: Path, timeout: Duration)(using Frame)
    extends FileSystemException(s"Timed out acquiring lock on $path after $timeout")
    with FileLockException derives CanEqual

case class FileLockOwnershipLostException(path: Path)(using Frame)
    extends FileSystemException(s"Lock ownership lost on $path")
    with FileLockException derives CanEqual

private[kyo] case class FileLockCleanupException(
    path: Path,
    primary: FileLockException,
    cleanup: FileLockException
)(using Frame)
    extends FileSystemException(s"Multiple lock cleanup failures on $path: ${primary.getMessage}; ${cleanup.getMessage}", primary)
    with FileLockException derives CanEqual

case class FileWatchInvalidatedException(path: Path)(using Frame)
    extends FileSystemException(s"Watch invalidated for $path")
    with FileWatchException derives CanEqual

object FileSystemException:
    given Render[FileSystemException] with
        def asString(value: FileSystemException): String = value.getMessage
