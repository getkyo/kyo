package kyo

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.ProcessPlatformSpecific

/** An immutable description of an OS command to execute.
  *
  * Command is a builder that accumulates configuration (working directory, environment variables, stdin source, stdout/stderr routing)
  * without performing any I/O. Calling an execution method (`spawn`, `text`, `waitFor`, `waitForSuccess`) launches the process.
  *
  * {{{
  * // Simple: run and get output
  * val output: String < (Async & Abort[CommandException]) =
  *     Command("git", "log", "--oneline", "-5").text
  *
  * // Builder pattern: configure then run
  * val result: Process.ExitCode < (Async & Abort[CommandException]) =
  *     Command("npm", "run", "build")
  *         .cwd(Path / "frontend")
  *         .envAppend(Map("NODE_ENV" -> "production"))
  *         .waitFor
  *
  * // Piping: stdout of one flows into stdin of the next
  * val filtered: String < (Async & Abort[CommandException]) =
  *     Command("cat", "log.txt")
  *         .andThen(Command("grep", "ERROR"))
  *         .andThen(Command("head", "-20"))
  *         .text
  * }}}
  *
  * Arguments are passed as-is to the OS — there is no shell interpretation. Each `String` in `Command("prog", "arg1", "arg2")` becomes one
  * process argument. This avoids shell injection vulnerabilities but means shell features (globbing, pipes via `|`) require wrapping in
  * `Command("sh", "-c", "...")`.
  *
  * `Abort[CommandException]` covers pre-launch failures: program not found, permission denied, or missing working directory. Runtime
  * failures (non-zero exit) are modeled as [[Process.ExitCode]] values, not exceptions — use `waitForSuccess` to abort on non-zero exits.
  *
  * @see
  *   [[Process]] for the running process handle
  * @see
  *   [[CommandException]] for pre-launch error types
  * @see
  *   [[Command.Unsafe]] for the abstract platform-specific implementation class
  */
opaque type Command = Command.Unsafe

object Command:

    /** Creates a `Command` from the given argument list.
      *
      * The first element is the program name (looked up on `$PATH` if it is not an absolute path). Remaining elements are passed as
      * arguments.
      */
    def apply(command: String*): Command =
        ProcessPlatformSpecific.makeCommand(Chunk.from(command))

    extension (self: Command)

        // -----------------------------------------------------------------------
        // Effectful operations
        // -----------------------------------------------------------------------

        /** Spawns the process and registers it with the enclosing `Scope` for automatic cleanup.
          *
          * If the scope closes before `waitFor` completes, the process is forcibly killed.
          */
        def spawn(using Frame): Process < (Sync & Scope & Abort[CommandException]) =
            Sync.Unsafe.defer {
                Abort.get(self.unsafe.spawn()).map { proc =>
                    Scope.acquireRelease(proc.safe) { p =>
                        Sync.Unsafe.defer(if p.unsafe.isAlive() then p.unsafe.destroyForcibly())
                    }
                }
            }

        /** Spawns the process, waits for it to complete, and returns its combined stdout as a UTF-8 string. */
        def text(using Frame): String < (Async & Abort[CommandException]) =
            Sync.Unsafe.defer(self.unsafe.text().safe.get)

        /** Spawns the process and returns its stdout as a byte stream (scope-managed). */
        def stream(using Frame): Stream[Byte, Async & Scope & Abort[CommandException]] =
            Stream {
                Sync.Unsafe.defer {
                    Abort.get(self.unsafe.spawn()).map { proc =>
                        val safeProc = proc.safe
                        Scope.acquireRelease(safeProc) { p =>
                            Sync.Unsafe.defer(if p.unsafe.isAlive() then p.unsafe.destroyForcibly())
                        }.map { p =>
                            p.stdout.emit
                        }
                    }
                }
            }

        /** Spawns the process and waits for it to exit, returning the `ExitCode`. */
        def waitFor(using Frame): ExitCode < (Async & Abort[CommandException]) =
            Sync.Unsafe.defer(self.unsafe.waitFor().safe.get)

        /** Spawns the process, waits for it to exit, and aborts with the `ExitCode` if the exit is non-successful.
          *
          * Effect type includes `Abort[CommandException | ExitCode]` to signal either a launch failure or a non-zero exit.
          */
        def waitForSuccess(using Frame): Unit < (Async & Abort[CommandException | ExitCode]) =
            Sync.Unsafe.defer(self.unsafe.waitForSuccess().safe.get)

        /** Spawns the process, collects its stdout as a UTF-8 string, waits for exit, and returns both the text and the `ExitCode`.
          *
          * The `ExitCode` is returned as a value — the caller decides what to do with non-zero exits. stdout and stderr are drained
          * concurrently so large outputs do not deadlock. `Abort[CommandException]` fires only if the process cannot be spawned.
          */
        def textWithExitCode(using Frame): (String, ExitCode) < (Async & Abort[CommandException]) =
            Scope.run {
                for
                    proc <- Sync.Unsafe.defer {
                        Abort.get(self.unsafe.spawn()).map { p =>
                            Scope.acquireRelease(p.safe) { p =>
                                Sync.Unsafe.defer(if p.unsafe.isAlive() then p.unsafe.destroyForcibly())
                            }
                        }
                    }
                    outFib   <- Fiber.init(Scope.run(proc.stdout.run))
                    errFib   <- Fiber.init(Scope.run(proc.stderr.run))
                    code     <- proc.waitFor
                    outBytes <- outFib.get
                    _        <- errFib.get
                yield (new String(outBytes.toArray, StandardCharsets.UTF_8), code)
            }

        // -----------------------------------------------------------------------
        // Accessors
        // -----------------------------------------------------------------------

        /** Returns the command arguments (program name followed by its arguments). */
        def args: Chunk[String] = self.unsafe.args

        /** Returns the working directory, or `Absent` if inheriting from the parent. */
        def workDir: Maybe[kyo.Path] = self.unsafe.workDir

        /** Returns the environment variables that will be appended/replaced, or empty if inheriting. */
        def env: Map[String, String] = self.unsafe.env

        // -----------------------------------------------------------------------
        // Pure builder methods
        // -----------------------------------------------------------------------

        /** Sets the working directory for the process. */
        def cwd(path: kyo.Path): Command = self.unsafe.withCwd(path).safe

        /** Appends the given environment variables (merged on top of the inherited environment). */
        def envAppend(vars: Map[String, String]): Command = self.unsafe.withEnvAppend(vars).safe

        /** Replaces the entire environment with the given variables. */
        def envReplace(vars: Map[String, String]): Command = self.unsafe.withEnvReplace(vars).safe

        /** Clears all environment variables; the process inherits nothing. */
        def envClear: Command = self.unsafe.withEnvClear.safe

        /** Provides a UTF-8-encoded string as stdin. */
        def stdin(s: String, charset: Charset = StandardCharsets.UTF_8): Command =
            self.unsafe.withStdin(Process.Input.FromStream(
                new java.io.ByteArrayInputStream(s.getBytes(charset))
            )).safe

        /** Provides raw bytes as stdin. */
        def stdin(bytes: Span[Byte]): Command =
            self.unsafe.withStdin(Process.Input.FromStream(
                new java.io.ByteArrayInputStream(bytes.toArray)
            )).safe

        /** Provides a `Stream[Byte, Sync]` as stdin. The stream is drained into the process's stdin in a background fiber at spawn time. */
        def stdin(s: Stream[Byte, Sync]): Command = self.unsafe.withStdinStream(s).safe

        /** Provides a `Process.Input` as stdin. */
        def stdin(input: Process.Input): Command = self.unsafe.withStdin(input).safe

        /** Inherits stdin from the parent process. */
        def inheritStdin: Command = self.unsafe.withStdin(Process.Input.Inherit).safe

        /** Inherits stdout from the parent process (not captured). */
        def inheritStdout: Command = self.unsafe.withInheritStdout(true).safe

        /** Inherits stderr from the parent process (not captured). */
        def inheritStderr: Command = self.unsafe.withInheritStderr(true).safe

        /** Inherits stdin, stdout, and stderr from the parent process. */
        def inheritIO: Command = self.inheritStdin.inheritStdout.inheritStderr

        /** Redirects stdout to the given file (creates or truncates unless `append = true`). */
        def stdoutToFile(path: kyo.Path, append: Boolean = false): Command =
            self.unsafe.withStdoutFile(path, append).safe

        /** Redirects stderr to the given file (creates or truncates unless `append = true`). */
        def stderrToFile(path: kyo.Path, append: Boolean = false): Command =
            self.unsafe.withStderrFile(path, append).safe

        /** Merges stderr into stdout when `value = true`. */
        def redirectErrorStream(value: Boolean): Command =
            self.unsafe.withRedirectErrorStream(value).safe

        /** Pipes this command's stdout into `that` command's stdin (UNIX-style `|`). */
        def andThen(that: Command): Command = self.unsafe.withAndThen(that.unsafe).safe

        /** Returns the underlying `Unsafe` implementation. */
        def unsafe: Command.Unsafe = self

    end extension

    /** How the child process environment is composed relative to the parent. */
    private[kyo] enum EnvMode derives CanEqual:
        case Inherit
        case Append(vars: Map[String, String])
        case Replace(vars: Map[String, String])
        case Clear
        case ClearThenAppend(vars: Map[String, String])
    end EnvMode

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        // -- Effectful operations --

        /** Attempts to start the process; returns a typed `CommandException` on pre-launch failure. */
        def spawn()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe]

        /** Spawns the process, waits for exit, collects stdout as a UTF-8 string. */
        def text()(using AllowUnsafe, Frame): Fiber.Unsafe[String, Abort[CommandException]]

        /** Spawns the process and waits for exit, returning the `ExitCode`. */
        def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Abort[CommandException]]

        /** Spawns the process, waits for exit, and fails with the `ExitCode` if non-zero. */
        def waitForSuccess()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[CommandException | ExitCode]]

        // -- Accessors --

        /** Returns the command arguments (program name followed by its arguments). */
        def args: Chunk[String]

        /** Returns the working directory, or `Absent` if inheriting from the parent. */
        def workDir: Maybe[kyo.Path]

        /** Returns the environment mode for this command. */
        protected def envMode: EnvMode

        /** Returns the environment variables that will be appended/replaced, or empty if inheriting/cleared. */
        final def env: Map[String, String] = envMode match
            case EnvMode.Inherit               => Map.empty
            case EnvMode.Append(vars)          => vars
            case EnvMode.Replace(vars)         => vars
            case EnvMode.Clear                 => Map.empty
            case EnvMode.ClearThenAppend(vars) => vars

        // -- Pure builder methods (return new Unsafe instances) --

        def withCwd(path: kyo.Path): Unsafe
        def withEnvAppend(vars: Map[String, String]): Unsafe
        def withEnvReplace(vars: Map[String, String]): Unsafe
        def withEnvClear: Unsafe
        def withStdin(input: Process.Input): Unsafe
        def withStdinStream(s: Stream[Byte, Sync]): Unsafe
        def withInheritStdout(value: Boolean): Unsafe
        def withInheritStderr(value: Boolean): Unsafe
        def withStdoutFile(path: kyo.Path, append: Boolean): Unsafe
        def withStderrFile(path: kyo.Path, append: Boolean): Unsafe
        def withRedirectErrorStream(value: Boolean): Unsafe
        def withAndThen(that: Unsafe): Unsafe

        /** Lifts this `Unsafe` value back into the safe `Command` opaque type. */
        def safe: Command = this

    end Unsafe

end Command
