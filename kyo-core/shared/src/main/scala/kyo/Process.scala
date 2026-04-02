package kyo

import java.io.InputStream
import java.io.OutputStream

/** A handle to a running OS process with fiber-safe lifecycle management.
  *
  * Process represents a spawned operating system process. It is always obtained via [[Command.spawn]], which registers the process with the
  * enclosing `Scope` for automatic cleanup — if the scope closes before the process exits, it is forcibly killed.
  *
  * {{{
  * Scope.run {
  *     for
  *         proc  <- Command("my-server", "--port", "8080").spawn
  *         _     <- Async.sleep(5.seconds)
  *         alive <- proc.isAlive
  *     yield alive
  *     // proc is automatically destroyed when scope closes
  * }
  * }}}
  *
  * `waitFor` suspends the current ''fiber'' (not an OS thread) until the process exits, using the platform's async notification mechanism
  * (`Process.onExit()` on JVM/Native, `'exit'` event on Node.js). `exitCode` is a non-blocking poll that returns `Absent` if the process is
  * still running.
  *
  * '''IMPORTANT:''' Reading both `stdout` and `stderr` sequentially can deadlock if the process produces more output than the OS pipe
  * buffer (~64 KB) on both streams. Use [[Process.collectOutput]] to drain both streams concurrently:
  * {{{
  * val (out, err) = proc.collectOutput  // safe — concurrent drain
  * }}}
  *
  * @see
  *   [[Command]] for launching processes
  * @see
  *   [[Process.ExitCode]] for exit status modeling
  * @see
  *   [[Process.Unsafe]] for the abstract platform-specific implementation class
  */
opaque type Process = Process.Unsafe

export Process.ExitCode

object Process:

    // --- Public API ---

    extension (self: Process)

        /** Returns the process's standard output as a byte stream.
          *
          * The underlying `InputStream` is registered with the enclosing `Scope` and closed when the scope ends.
          */
        def stdout(using Frame): Stream[Byte, Sync & Scope] =
            Stream(Sync.Unsafe.defer(StreamCoreExtensions.streamFromJavaInputStream(self.unsafe.stdoutJava).emit))

        /** Returns the process's standard error as a byte stream.
          *
          * The underlying `InputStream` is registered with the enclosing `Scope` and closed when the scope ends.
          */
        def stderr(using Frame): Stream[Byte, Sync & Scope] =
            Stream(Sync.Unsafe.defer(StreamCoreExtensions.streamFromJavaInputStream(self.unsafe.stderrJava).emit))

        /** Suspends the current fiber until the process exits, then returns its `ExitCode`.
          *
          * No OS thread is blocked; the fiber is resumed when the underlying platform-specific promise completes.
          */
        def waitFor(using Frame): ExitCode < Async =
            Sync.Unsafe.defer(self.unsafe.waitFor().safe.get)

        /** Suspends the current fiber until the process exits or `timeout` elapses.
          *
          * Returns `Present(exitCode)` on normal exit or `Absent` on timeout.
          */
        def waitFor(timeout: Duration)(using Frame): Maybe[ExitCode] < Async =
            Sync.Unsafe.defer(self.unsafe.waitFor(timeout).safe.get)

        /** Polls the process's current exit status without suspending.
          *
          * Returns `Absent` if the process is still running, or `Present(exitCode)` if it has already exited.
          */
        def exitCode(using Frame): Maybe[ExitCode] < Sync =
            Sync.Unsafe.defer(self.unsafe.exitCode())

        /** Concurrently drains stdout and stderr, returning both as byte chunks.
          *
          * Reading both streams in parallel prevents the deadlock that occurs when a process produces more output than the OS pipe buffer
          * (~64 KB) on both stdout and stderr.
          */
        def collectOutput(using Frame): (Chunk[Byte], Chunk[Byte]) < (Async & Scope) =
            for
                outFib <- Fiber.init(Scope.run(self.stdout.run))
                errFib <- Fiber.init(Scope.run(self.stderr.run))
                out    <- outFib.get
                err    <- errFib.get
            yield (out, err)

        /** Returns `true` if the process is still running. */
        def isAlive(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.isAlive())

        /** Returns the OS-assigned process identifier. */
        def pid(using Frame): Long < Sync =
            Sync.Unsafe.defer(self.unsafe.pid())

        /** Requests termination of the process (SIGTERM on Unix). */
        def destroy(using Frame): Unit < Sync =
            Sync.Unsafe.defer(self.unsafe.destroy())

        /** Forcibly terminates the process (SIGKILL on Unix). */
        def destroyForcibly(using Frame): Unit < Sync =
            Sync.Unsafe.defer(self.unsafe.destroyForcibly())

        /** Returns the underlying `Unsafe` implementation for direct use in unsafe contexts. */
        def unsafe: Process.Unsafe = self

    end extension

    // --- ExitCode ---

    /** The exit status of a completed process.
      *
      * An exit code is either:
      *   - `Success` (exit value 0)
      *   - `Failure(code)` — a non-zero exit value that does not encode a signal
      *   - `Signaled(number)` — the process was terminated by an OS signal (Unix convention: exit value = 128 + signal number)
      */
    enum ExitCode derives CanEqual:
        case Success
        case Failure(code: Int)
        case Signaled(number: Int)

        /** Returns the raw integer exit-code value.
          *
          * For `Success` this is `0`. For `Failure(code)` this is `code`. For `Signaled(number)` this is `128 + number`, following the
          * POSIX shell convention.
          */
        def toInt: Int = this match
            case Success       => 0
            case Failure(code) => code
            case Signaled(n)   => 128 + n

        /** Returns `true` if and only if the exit code is `Success`. */
        def isSuccess: Boolean = this == Success

        /** Returns the human-readable POSIX signal name for `Signaled` cases, or `Absent` for non-signal exit codes. */
        def signalName: Maybe[String] = this match
            case Signaled(1)  => Present("SIGHUP")
            case Signaled(2)  => Present("SIGINT")
            case Signaled(3)  => Present("SIGQUIT")
            case Signaled(9)  => Present("SIGKILL")
            case Signaled(11) => Present("SIGSEGV")
            case Signaled(13) => Present("SIGPIPE")
            case Signaled(15) => Present("SIGTERM")
            case _            => Absent

    end ExitCode

    object ExitCode:

        /** Constructs an `ExitCode` from a raw integer exit value.
          *
          * Interpretation:
          *   - `0` → `Success`
          *   - `n >= 128` → `Signaled(n - 128)`
          *   - any other non-zero value → `Failure(n)`
          */
        def apply(code: Int): ExitCode =
            if code == 0 then Success
            else if code >= 128 then Signaled(code - 128)
            else Failure(code)

        // Named signal constants for pattern matching and comparison
        val SIGHUP  = Signaled(1)
        val SIGINT  = Signaled(2)
        val SIGQUIT = Signaled(3)
        val SIGKILL = Signaled(9)
        val SIGSEGV = Signaled(11)
        val SIGPIPE = Signaled(13)
        val SIGTERM = Signaled(15)

        given Render[ExitCode] with
            def asText(value: ExitCode): Text =
                value match
                    case Success       => Text("ExitCode.Success")
                    case Failure(code) => Text(s"ExitCode.Failure($code)")
                    case Signaled(n) =>
                        val name = value.signalName.getOrElse(s"signal $n")
                        Text(s"ExitCode.Signaled($n, $name)")
        end given

    end ExitCode

    // --- Input ---

    /** The source for a process's standard input.
      *
      * `Inherit` pipes the parent process's stdin through. `FromStream` reads from the given `InputStream` (feeding it into the child
      * process's stdin via a background fiber when the process is spawned).
      */
    sealed trait Input derives CanEqual
    object Input:
        case object Inherit                        extends Input
        case class FromStream(stream: InputStream) extends Input
    end Input

    // --- Unsafe ---

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:

        /** Suspends until the process exits and completes the returned `Fiber.Unsafe` with the exit code. */
        def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any]

        /** Suspends until the process exits or `timeout` elapses; completes the fiber with `Absent` on timeout. */
        def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any]

        /** Non-blocking poll. Returns `Absent` if still running. */
        def exitCode()(using AllowUnsafe): Maybe[ExitCode]

        /** Sends a termination request (SIGTERM / equivalent). */
        def destroy()(using AllowUnsafe): Unit

        /** Forcibly kills the process (SIGKILL / equivalent). */
        def destroyForcibly()(using AllowUnsafe): Unit

        /** Returns `true` if the process is still running. */
        def isAlive()(using AllowUnsafe): Boolean

        /** Returns the OS-assigned process identifier. */
        def pid()(using AllowUnsafe): Long

        /** Raw standard-output stream. Managed by the `Scope` in the safe API. */
        def stdoutJava(using AllowUnsafe): InputStream

        /** Raw standard-error stream. Managed by the `Scope` in the safe API. */
        def stderrJava(using AllowUnsafe): InputStream

        /** Raw standard-input stream for writing into the process. */
        def stdinJava(using AllowUnsafe): OutputStream

        /** Lifts this `Unsafe` value back into the safe `Process` opaque type. */
        def safe: Process = this

    end Unsafe

end Process
