package kyo

import java.io.EOFException

abstract class Console:
    def readln(using Frame): String < IO
    def print(s: String)(using Frame): Unit < IO
    def printErr(s: String)(using Frame): Unit < IO
    def println(s: String)(using Frame): Unit < IO
    def printlnErr(s: String)(using Frame): Unit < IO
end Console

object Console:

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

    def let[A, S](c: Console)(v: A < S)(using Frame): A < S =
        local.let(c)(v)

    def readln(using Frame): String < IO =
        local.use(_.readln)

    private def toString(v: Any)(using Frame): String =
        v match
            case v: String =>
                v
            case v =>
                pprint.apply(v).plainText

    def print[A](v: A)(using Frame): Unit < IO =
        local.use(_.print(toString(v)))

    def printErr[A](v: A)(using Frame): Unit < IO =
        local.use(_.printErr(toString(v)))

    def println[A](v: A)(using Frame): Unit < IO =
        local.use(_.println(toString(v)))

    def printlnErr[A](v: A)(using Frame): Unit < IO =
        local.use(_.printlnErr(toString(v)))

end Console
