package kyo

import java.io.EOFException

/** Represents a console for input and output operations.
  */
abstract class Console:
    /** Reads a line from the console.
      *
      * @return
      *   A String representing the line read from the console.
      */
    def readln(using Frame): String < IO

    /** Prints a string to the console without a newline.
      *
      * @param s
      *   The string to print.
      */
    def print(s: String)(using Frame): Unit < IO

    /** Prints a string to the console's error stream without a newline.
      *
      * @param s
      *   The string to print to the error stream.
      */
    def printErr(s: String)(using Frame): Unit < IO

    /** Prints a string to the console followed by a newline.
      *
      * @param s
      *   The string to print.
      */
    def println(s: String)(using Frame): Unit < IO

    /** Prints a string to the console's error stream followed by a newline.
      *
      * @param s
      *   The string to print to the error stream.
      */
    def printlnErr(s: String)(using Frame): Unit < IO
end Console

/** Companion object for Console, providing utility methods and a live implementation.
  */
object Console:

    /** A live implementation of the Console trait.
      */
    val live: Console =
        new Console:
            def readln(using Frame) =
                IO {
                    val line = scala.Console.in.readLine()
                    if line == null then
                        throw new EOFException("Consoles.readln failed.")
                    else
                        line
                    end if
                }
            def print(s: String)(using Frame)      = IO(scala.Console.out.print(s))
            def printErr(s: String)(using Frame)   = IO(scala.Console.err.print(s))
            def println(s: String)(using Frame)    = IO(scala.Console.out.println(s))
            def printlnErr(s: String)(using Frame) = IO(scala.Console.err.println(s))

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

    /** Reads a line from the console.
      *
      * @return
      *   A String representing the line read from the console.
      */
    def readln(using Frame): String < IO =
        local.use(_.readln)

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
    def print[A](v: A)(using Frame): Unit < IO =
        local.use(_.print(toString(v)))

    /** Prints a value to the console's error stream without a newline.
      *
      * @param v
      *   The value to print to the error stream.
      */
    def printErr[A](v: A)(using Frame): Unit < IO =
        local.use(_.printErr(toString(v)))

    /** Prints a value to the console followed by a newline.
      *
      * @param v
      *   The value to print.
      */
    def println[A](v: A)(using Frame): Unit < IO =
        local.use(_.println(toString(v)))

    /** Prints a value to the console's error stream followed by a newline.
      *
      * @param v
      *   The value to print to the error stream.
      */
    def printlnErr[A](v: A)(using Frame): Unit < IO =
        local.use(_.printlnErr(toString(v)))

end Console
