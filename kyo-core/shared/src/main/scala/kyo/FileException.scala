package kyo

import java.io.IOException

/** Typed exception hierarchy for file system operations.
  *
  * Every file I/O method in [[Path]] carries one of three sealed marker traits in its `Abort` type, determined by the operation category:
  *   - `Abort[FileReadException]` — read operations (`read`, `readBytes`, `readLines`, streaming reads)
  *   - `Abort[FileWriteException]` — write operations (`write`, `append`, `truncate`)
  *   - `Abort[FileFsException]` — structural operations (`mkDir`, `list`, `walk`, `move`, `copy`, `remove`)
  *
  * Each concrete exception (e.g. [[FileNotFoundException]]) implements only the traits of operations that can actually raise it. This
  * enables precise `Abort.recover` matching:
  * {{{
  * Abort.recover[FileNotFoundException] { _ =>
  *     "default content"
  * }(path.read)
  * }}}
  *
  * @param message
  *   A description of the error
  * @param cause
  *   Either a Text explanation or a Throwable that caused this exception
  * @see
  *   [[Path]] for the file operations that raise these exceptions
  */
sealed abstract class FileException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Marker trait for exceptions that can occur during file read operations. */
sealed trait FileReadException extends FileException

/** Marker trait for exceptions that can occur during file write operations. */
sealed trait FileWriteException extends FileException

/** Marker trait for exceptions that can occur during file-system (metadata/directory) operations. */
sealed trait FileFsException extends FileException

/** Raised when an operation targets a path that does not exist.
  *
  * @param path
  *   The path that was not found
  */
case class FileNotFoundException(path: Path)(using Frame)
    extends FileException(s"File or directory not found: $path. Verify the path exists.")
    with FileReadException with FileWriteException with FileFsException derives CanEqual

/** Raised when the process lacks permission to access the given path.
  *
  * @param path
  *   The path for which access was denied
  */
case class FileAccessDeniedException(path: Path)(using Frame)
    extends FileException(s"Permission denied: $path. Check file permissions and ownership.")
    with FileReadException with FileWriteException with FileFsException derives CanEqual

/** Raised when a read or write operation is attempted on a path that is a directory.
  *
  * @param path
  *   The path that is a directory
  */
case class FileIsADirectoryException(path: Path)(using Frame)
    extends FileException(s"Expected a file but found a directory: $path")
    with FileReadException with FileWriteException derives CanEqual

/** Raised when a directory operation is attempted on a path that is not a directory.
  *
  * @param path
  *   The path that is not a directory
  */
case class FileNotADirectoryException(path: Path)(using Frame)
    extends FileException(s"Expected a directory but found a file: $path")
    with FileFsException derives CanEqual

/** Raised when a create operation targets a path that already exists.
  *
  * @param path
  *   The path that already exists
  */
case class FileAlreadyExistsException(path: Path)(using Frame)
    extends FileException(s"Path already exists: $path. Use replaceExisting = true to overwrite.")
    with FileFsException derives CanEqual

/** Raised when a directory removal is attempted on a non-empty directory.
  *
  * @param path
  *   The non-empty directory path
  */
case class FileDirectoryNotEmptyException(path: Path)(using Frame)
    extends FileException(s"Cannot remove non-empty directory: $path. Use removeAll for recursive deletion.")
    with FileFsException derives CanEqual

/** Raised for low-level I/O errors not covered by the more specific subtypes.
  *
  * @param path
  *   The path involved in the I/O error
  * @param cause
  *   The underlying IOException
  */
case class FileIOException(path: Path, cause: IOException)(using Frame)
    extends FileException(s"I/O error on $path: ${cause.getMessage}", cause)
    with FileReadException with FileWriteException with FileFsException derives CanEqual

object FileException:
    given Render[FileException] with
        def asText(value: FileException): Text = Text(value.getMessage)
