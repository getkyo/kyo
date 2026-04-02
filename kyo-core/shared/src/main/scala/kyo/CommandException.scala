package kyo

// -----------------------------------------------------------------------
// CommandException — typed failures raised when a Command cannot be launched
// -----------------------------------------------------------------------

/** Typed exception hierarchy for command launch failures.
  *
  * These exceptions represent errors detectable ''before'' any OS process is created. Every `Command` execution method (`spawn`, `text`,
  * `waitFor`, `waitForSuccess`) carries `Abort[CommandException]` so callers are forced to handle or propagate launch failures:
  *   - [[ProgramNotFoundException]] — the executable does not exist on `$PATH` or at the given path
  *   - [[PermissionDeniedException]] — the caller lacks execute permission for the program
  *   - [[WorkingDirectoryNotFoundException]] — the requested working directory does not exist
  *
  * {{{
  * Abort.recover[CommandException] {
  *     case ProgramNotFoundException(cmd) =>
  *         s"$cmd is not installed — run: brew install $cmd"
  *     case _ => "command failed"
  * }(Command("mytool").text)
  * }}}
  *
  * @see
  *   [[Command]] for the API that raises these exceptions
  */
sealed abstract class CommandException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Raised when the executable named in the command cannot be found on `$PATH` or at the given path.
  *
  * @param command
  *   The program name or path that could not be found
  */
case class ProgramNotFoundException(command: String)(using Frame)
    extends CommandException(
        s"Program not found: '$command'. Verify it is installed and on the PATH."
    )

/** Raised when the caller lacks execute permission for the named program.
  *
  * @param command
  *   The program name or path for which permission was denied
  */
case class PermissionDeniedException(command: String)(using Frame)
    extends CommandException(
        s"Permission denied to execute: '$command'. Check file permissions."
    )

/** Raised when the requested working directory does not exist.
  *
  * @param path
  *   The working directory path that was not found
  */
case class WorkingDirectoryNotFoundException(path: kyo.Path)(using Frame)
    extends CommandException(
        s"Working directory does not exist: $path"
    )
