package kyo

import java.io.EOFException
import java.io.IOException

/** Represents a console for input and output operations.
  *
  * The methods that print to the console output and error streams ([[print]], [[printErr]], [[printLine]], [[printLineErr]]) don't return
  * an [[Abort]] effect because they don't throw exceptions. The behavior is the same as the standard Scala `scala.Console` methods, which
  * don't throw exceptions either. The cause is the underlying Java `PrintStream` class implementation, which doesn't throw exceptions when
  * writing to the console output or error streams (see
  * [[https://stackoverflow.com/questions/297303/printwriter-and-printstream-never-throw-ioexceptions PrintWriter and PrintStream never throw IOExceptions]]
  * for more details).
  *
  * To check if an error occurred in the console output or error streams, use the [[checkErrors]], which returns a boolean indicating if an
  * error occurred.
  */
final case class Console(unsafe: Console.Unsafe):

    /** Reads a line from the console.
      *
      * @return
      *   A String representing the line read from the console.
      */
    def readLine(using Frame): String < (Sync & Abort[IOException]) = Sync.Unsafe(Abort.get(unsafe.readLine()))

    /** Prints a string to the console without a newline.
      *
      * @param s
      *   The string to print.
      */
    def print(s: Text)(using Frame): Unit < Sync = Sync.Unsafe(unsafe.print(s.show))

    /** Prints a string to the console's error stream without a newline.
      *
      * @param s
      *   The string to print to the error stream.
      */
    def printErr(s: Text)(using Frame): Unit < Sync = Sync.Unsafe(unsafe.printErr(s.show))

    /** Prints a string to the console followed by a newline.
      *
      * @param s
      *   The string to print.
      */
    def println(s: Text)(using Frame): Unit < Sync = Sync.Unsafe(unsafe.printLine(s.show))

    /** Prints a string to the console's error stream followed by a newline.
      *
      * @param s
      *   The string to print to the error stream.
      */
    def printLineErr(s: Text)(using Frame): Unit < Sync = Sync.Unsafe(unsafe.printLineErr(s.show))

    /** Checks if an error occurred in the console output or error streams.
      *
      * @return
      *   True if an error occurred, false otherwise.
      */
    def checkErrors(using Frame): Boolean < Sync = Sync.Unsafe(unsafe.checkErrors)

    /** Flushes the console output streams.
      *
      * This method ensures that any buffered output is written to the console.
      */
    def flush(using Frame): Unit < Sync = Sync.Unsafe(unsafe.flush())
end Console

/** Companion object for Console, providing utility methods and a live implementation.
  */
object Console:

    /** A live implementation of the Console trait.
      */
    val live: Console = Console(
        new Unsafe:
            def readLine()(using AllowUnsafe) =
                Result.catching[IOException](Maybe(scala.Console.in.readLine()))
                    .flatMap(_.toResult(Result.fail(new EOFException("Consoles.readLine failed."))))
            def print(s: String)(using AllowUnsafe)        = scala.Console.out.print(s)
            def printErr(s: String)(using AllowUnsafe)     = scala.Console.err.print(s)
            def printLine(s: String)(using AllowUnsafe)    = scala.Console.out.println(s)
            def printLineErr(s: String)(using AllowUnsafe) = scala.Console.err.println(s)
            def checkErrors(using AllowUnsafe): Boolean    = scala.Console.out.checkError() || scala.Console.err.checkError()
            def flush()(using AllowUnsafe)                 = scala.Console.flush()
    )

    private val local = Local.init(live)

    /** Executes a computation with a custom Console implementation.
      *
      * @param c
      *   The Console implementation to use.
      * @param v
      *   The computation to execute.
      * @return
      *   The result of the computation.
      */
    def let[A, S](c: Console)(v: A < S)(using Frame): A < S =
        local.let(c)(v)

    /** Executes a computation with access to the current Console instance.
      *
      * @param f
      *   A function that takes a Console and returns a computation.
      * @tparam A
      *   The type of the computation result.
      * @tparam S
      *   The type of effects in the computation.
      * @return
      *   The result of the computation.
      */
    def use[A, S](f: Console => A < S)(using Frame): A < S =
        local.use(f)

    /** Gets the current Console instance from the local context.
      *
      * @return
      *   The current Console instance.
      */
    def get(using Frame): Console < Any =
        local.get

    /** Executes a computation with a custom input stream containing the provided lines.
      *
      * @param lines
      *   The lines to be used as input.
      * @param v
      *   The computation to execute.
      * @tparam A
      *   The type of the computation result.
      * @tparam S
      *   The type of effects in the computation.
      * @return
      *   The result of the computation with Sync effects.
      */
    def withIn[A, S](lines: Iterable[String])(v: A < S)(using Frame): A < (Sync & S) =
        Sync.withLocal(local) { console =>
            val it = lines.iterator
            val proxy =
                new Proxy(console.unsafe):
                    override def readLine()(using AllowUnsafe) =
                        if !it.hasNext then Result.fail(new EOFException("Consoles.readLine failed."))
                        else Result.succeed(it.next())
            let(Console(proxy))(v)
        }

    /** Container for captured console output.
      *
      * @param stdOut
      *   The captured standard output as a string.
      * @param stdErr
      *   The captured standard error output as a string.
      */
    case class Out(stdOut: String, stdErr: String)

    /** Executes a computation while capturing its console output.
      *
      * @param v
      *   The computation to execute.
      * @tparam A
      *   The type of the computation result.
      * @tparam S
      *   The type of effects in the computation.
      * @return
      *   A tuple containing the captured output (Out) and the computation result.
      */
    def withOut[A, S](v: A < S)(using Frame): (Out, A) < (Sync & S) =
        Sync.withLocal(local) { console =>
            val stdOut = new StringBuffer
            val stdErr = new StringBuffer
            val proxy =
                new Proxy(console.unsafe):
                    override def print(s: String)(using AllowUnsafe) =
                        stdOut.append(s)
                        ()
                    override def printErr(s: String)(using AllowUnsafe) =
                        stdErr.append(s)
                        ()
                    override def printLine(s: String)(using AllowUnsafe) =
                        stdOut.append(s + "\n")
                        ()
                    override def printLineErr(s: String)(using AllowUnsafe) =
                        stdErr.append(s + "\n")
                        ()
            let(Console(proxy))(v)
                .map(r => Sync((Out(stdOut.toString(), stdErr.toString()), r)))
        }

    /** Reads a line from the console.
      *
      * @return
      *   A String representing the line read from the console.
      */
    def readLine(using Frame): String < (Sync & Abort[IOException]) =
        Sync.Unsafe.withLocal(local)(console => Abort.get(console.unsafe.readLine()))

    private def toString(v: Any)(using Frame): String =
        v match
            case v: String =>
                v
            case v =>
                pprint.apply(v).plainText

    /** Prints a value to the console without a newline.
      *
      * @param v
      *   The value to print.
      */
    def print[A](v: A)(using Frame): Unit < Sync =
        Sync.Unsafe.withLocal(local)(console => console.unsafe.print(toString(v)))

    /** Prints a value to the console's error stream without a newline.
      *
      * @param v
      *   The value to print to the error stream.
      */
    def printErr[A](v: A)(using Frame): Unit < Sync =
        Sync.Unsafe.withLocal(local)(console => console.unsafe.printErr(toString(v)))

    /** Prints a value to the console followed by a newline.
      *
      * @param v
      *   The value to print.
      */
    def printLine[A](v: A)(using Frame): Unit < Sync =
        Sync.Unsafe.withLocal(local)(console => console.unsafe.printLine(toString(v)))

    /** Prints a value to the console's error stream followed by a newline.
      *
      * @param v
      *   The value to print to the error stream.
      */
    def printLineErr[A](v: A)(using Frame): Unit < Sync =
        Sync.Unsafe.withLocal(local)(console => console.unsafe.printLineErr(toString(v)))

    /** Checks if an error occurred in the console output or error streams.
      *
      * @return
      *   True if an error occurred, false otherwise.
      */
    def checkErrors(using Frame): Boolean < Sync = Sync.Unsafe.withLocal(local)(console => console.unsafe.checkErrors)

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        def readLine()(using AllowUnsafe): Result[IOException, String]
        def print(s: String)(using AllowUnsafe): Unit
        def printErr(s: String)(using AllowUnsafe): Unit
        def printLine(s: String)(using AllowUnsafe): Unit
        def printLineErr(s: String)(using AllowUnsafe): Unit
        def checkErrors(using AllowUnsafe): Boolean
        def flush()(using AllowUnsafe): Unit
        def safe: Console = Console(this)
    end Unsafe

    private class Proxy(underlying: Unsafe) extends Unsafe:
        def readLine()(using AllowUnsafe)              = underlying.readLine()
        def print(s: String)(using AllowUnsafe)        = underlying.print(s)
        def printErr(s: String)(using AllowUnsafe)     = underlying.printErr(s)
        def printLine(s: String)(using AllowUnsafe)    = underlying.printLine(s)
        def printLineErr(s: String)(using AllowUnsafe) = underlying.printLineErr(s)
        def checkErrors(using AllowUnsafe): Boolean    = underlying.checkErrors
        def flush()(using AllowUnsafe)                 = underlying.flush()
    end Proxy

end Console
