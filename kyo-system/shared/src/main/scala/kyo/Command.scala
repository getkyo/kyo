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

        // --- Effectful operations ---

        /** Spawns the process and registers it with the enclosing `Scope` for automatic cleanup.
          *
          * Closing the scope terminates the owned process, waits for cleanup, and releases its streams. With [[processGroup]], cleanup
          * includes the original group even after its leader exits; otherwise it covers the directly launched process and pipeline stages.
          */
        def spawn(using Frame): Process < (Async & Scope & Abort[CommandException]) =
            self.unsafe.acquire().map(_.safe)

        /** Spawns the process WITHOUT registering it with any `Scope`: the caller owns the process
          * lifetime and must close it. The unscoped analog of [[spawn]] (mirrors `Fiber.initUnscoped`),
          * for a process that outlives the spawning computation's scope, such as a long-lived worker a
          * backend owns and closes explicitly.
          */
        def spawnUnscoped(using Frame): Process < (Async & Abort[CommandException]) =
            Scope.runUnowned(self.unsafe.acquire().map(_.safe))

        /** Spawns the process, waits for it to complete, and returns its combined stdout as a UTF-8 string. */
        def text(using Frame): String < (Async & Abort[CommandException]) =
            Command.textWithExitCode(self).map(_._1)

        /** Spawns the process and returns its stdout as a byte stream, draining stderr concurrently without retaining it. */
        def stream(using Frame): Stream[Byte, Async & Scope & Abort[CommandException]] =
            Stream {
                for
                    process <- Command.spawn(self)
                    _       <- Fiber.init(Scope.run(process.stderr.discard))
                    _       <- Scope.ensure(process.destroyTree)
                    _       <- process.stdout.emit
                yield ()
            }

        /** Spawns the process, drains its output without retaining it, and returns the `ExitCode`. Interrupting the wait releases the
          * process as [[spawn]] does.
          */
        def waitFor(using Frame): ExitCode < (Async & Abort[CommandException]) =
            Scope.run {
                for
                    process <- Command.spawn(self)
                    stdout  <- Fiber.init(Scope.run(process.stdout.discard))
                    stderr  <- Fiber.init(Scope.run(process.stderr.discard))
                    _       <- Scope.ensure(process.destroyTree)
                    code    <- process.waitFor
                    _       <- process.destroyTree
                    _       <- stdout.get
                    _       <- stderr.get
                yield code
            }

        /** Spawns the process, waits for it to exit, and aborts with the `ExitCode` if the exit is non-successful. Interrupting the wait
          * releases the process as [[spawn]] does.
          *
          * Effect type includes `Abort[CommandException | ExitCode]` to signal either a launch failure or a non-zero exit.
          */
        def waitForSuccess(using Frame): Unit < (Async & Abort[CommandException | ExitCode]) =
            Command.waitFor(self).map(code => if code.isSuccess then () else Abort.fail(code))

        /** Spawns the process, collects its stdout as a UTF-8 string, waits for exit, and returns both the text and the `ExitCode`.
          *
          * The `ExitCode` is returned as a value — the caller decides what to do with non-zero exits. stdout and stderr are drained
          * concurrently so large outputs do not deadlock. `Abort[CommandException]` fires only if the process cannot be spawned. Interrupting
          * the wait releases the process as [[spawn]] does.
          */
        def textWithExitCode(using Frame): (String, ExitCode) < (Async & Abort[CommandException]) =
            Scope.run {
                for
                    proc   <- Command.spawn(self)
                    outFib <- Fiber.init(Scope.run(proc.stdout.run))
                    errFib <- Fiber.init(Scope.run(proc.stderr.discard))
                    // Release the owned group and cancel its output to the buffered prefix before joining readers.
                    _    <- Scope.ensure(proc.destroyTree)
                    code <- proc.waitFor
                    // A group can retain descendants after its leader exits; pipeline stages can also outlive the last stage.
                    _        <- proc.destroyTree
                    outBytes <- outFib.get
                    _        <- errFib.get
                yield (new String(outBytes.toArray, StandardCharsets.UTF_8), code)
            }

        // --- Accessors ---

        /** Returns the command arguments (program name followed by its arguments). */
        def args: Chunk[String] = self.unsafe.args

        /** Returns the working directory, or `Absent` if inheriting from the parent. */
        def workDir: Maybe[kyo.Path] = self.unsafe.workDir

        /** Returns the environment variables that will be appended/replaced, or empty if inheriting. */
        def env: Map[String, String] = self.unsafe.env

        // --- Pure builder methods ---

        /** Sets the working directory for the process. */
        def cwd(path: kyo.Path): Command = self.unsafe.withCwd(path).safe

        /** Appends the given environment variables (merged on top of the inherited environment). */
        def envAppend(vars: Map[String, String]): Command = self.unsafe.withEnvAppend(vars).safe

        /** Removes the given variables from the inherited or configured process environment. */
        def envRemove(names: Iterable[String]): Command = self.unsafe.withEnvRemove(names.toSet).safe

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

        /** Opens an unmanaged pipe on the child's stdin. The caller drives writes directly via
          * `proc.unsafe.stdinJava` (no auto-pump fiber). Use when wiring the child into a custom
          * protocol layer that owns when bytes flow in (e.g. JSON-RPC over stdio).
          */
        def pipeStdin: Command = self.unsafe.withStdin(Process.Input.Pipe).safe

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

        /** Starts the program as the leader of a new process group, which [[Process.destroyTree]], and so the release of [[spawn]]'s
          * scope, kills whole, after the program has exited as well. [[text]], [[waitFor]], [[waitForSuccess]] and [[textWithExitCode]]
          * kill it at the program's exit.
          *
          * Without it, a child that the program leaves running when it exits is reparented to init, where no walk from the program finds
          * it: the child outlives the scope, and when it holds the program's stdout, a read of that output waits for the child. Every
          * process the program starts joins the group unless it moves itself out (`setsid`, `setpgid`), as a daemon does.
          *
          * [[Process.destroyTree]] ends output reads at their buffered prefix. A process that deliberately leaves the group, or receives a
          * transferred descriptor, has an independent lifetime and is never killed merely because it holds the command's output pipe.
          *
          * A process in a group of its own is not in the terminal's foreground group: Ctrl-C at the terminal does not reach it, and on the
          * supported Unix runtimes a read of an inherited terminal stops it, so a command that reads the terminal should not use this.
          *
          * The program, once exited, stays unreaped until `destroyTree` kills its group, so its pid, which is the group's id, cannot be
          * reused before that kill. An isolated native supervisor retains this ownership independently of the host runtime's reaper.
          * With [[spawnUnscoped]], the caller must run [[Process.close]] to release both the group and its descriptors. On the JVM the
          * spawn calls the C library through `java.lang.foreign`, so without
          * `--enable-native-access=ALL-UNNAMED` the JDK prints a restricted-method warning. Windows has no process groups: there the spawn
          * fails with [[ProcessGroupUnsupportedException]].
          */
        def processGroup: Command = self.unsafe.withProcessGroup.safe

        /** Returns the underlying `Unsafe` implementation. */
        def unsafe: Command.Unsafe = self

    end extension

    /** How the child process environment is composed relative to the parent. */
    private[kyo] enum EnvMode derives CanEqual:
        case Inherit
        case Append(vars: Map[String, String])
        case Remove(names: Set[String])
        case AppendThenRemove(vars: Map[String, String], names: Set[String])
        case Replace(vars: Map[String, String])
        case Clear
        case ClearThenAppend(vars: Map[String, String])
    end EnvMode

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:

        private[kyo] def acquire()(using Frame): Process.Unsafe < (Async & Scope & Abort[CommandException]) =
            Scope.acquireRelease(Sync.Unsafe.defer(Abort.get(spawnRaw())))(_.close())

        // --- Effectful operations ---

        /** Runtime-only spawn used to launch an ordinary child, including the isolated supervisor itself. */
        private[kyo] def spawnRaw()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe]

        /** Acquires the process asynchronously, reporting launch failures through the returned fiber. The caller owns its lifetime. */
        final def spawn()(using AllowUnsafe, Frame): Fiber.Unsafe[Process.Unsafe, Abort[CommandException]] =
            Fiber.Unsafe.init(Scope.runUnowned(acquire()))

        /** Spawns the process, waits for exit, collects stdout as a UTF-8 string. */
        final def text()(using AllowUnsafe, Frame): Fiber.Unsafe[String, Abort[CommandException]] =
            Fiber.Unsafe.init(Command.text(safe))

        /** Spawns the process and waits for exit, returning the `ExitCode`. */
        final def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Abort[CommandException]] =
            Fiber.Unsafe.init(Command.waitFor(safe))

        /** Spawns the process, waits for exit, and fails with the `ExitCode` if non-zero. */
        final def waitForSuccess()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[CommandException | ExitCode]] =
            Fiber.Unsafe.init(Command.waitForSuccess(safe))

        // --- Accessors ---

        /** Returns the command arguments (program name followed by its arguments). */
        def args: Chunk[String]

        /** Returns the working directory, or `Absent` if inheriting from the parent. */
        def workDir: Maybe[kyo.Path]

        /** Returns the environment mode for this command. */
        private[kyo] def envMode: EnvMode

        /** Returns the environment variables that will be appended/replaced, or empty if inheriting/cleared. */
        final def env: Map[String, String] = envMode match
            case EnvMode.Inherit                   => Map.empty
            case EnvMode.Append(vars)              => vars
            case EnvMode.Remove(_)                 => Map.empty
            case EnvMode.AppendThenRemove(vars, _) => vars
            case EnvMode.Replace(vars)             => vars
            case EnvMode.Clear                     => Map.empty
            case EnvMode.ClearThenAppend(vars)     => vars

        // --- Pure builder methods (return new Unsafe instances) ---

        def withCwd(path: kyo.Path): Unsafe
        def withEnvAppend(vars: Map[String, String]): Unsafe
        def withEnvRemove(names: Set[String]): Unsafe
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
        def withProcessGroup: Unsafe

        /** Lifts this `Unsafe` value back into the safe `Command` opaque type. */
        def safe: Command = this

    end Unsafe

end Command
