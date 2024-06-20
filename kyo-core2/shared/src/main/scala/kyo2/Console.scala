package kyo2

import java.io.EOFException

opaque type Console <: IO = IO

object Console:

    abstract class Service:
        def readln(using Frame): String < IO
        def print(s: String)(using Frame): Unit < IO
        def printErr(s: String)(using Frame): Unit < IO
        def println(s: String)(using Frame): Unit < IO
        def printlnErr(s: String)(using Frame): Unit < IO
    end Service

    val live: Service =
        new Service:
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

    def run[T, S](v: T < (Console & S))(using Frame): T < (S & IO) =
        v

    def run[T, S](c: Service)(v: T < (Console & S))(using Frame): T < (S & IO) =
        local.let(c)(v)

    def readln(using Frame): String < IO =
        local.use(_.readln)

    private def toString(v: Any)(using Frame): String =
        v match
            case v: String =>
                v
            case v =>
                pprint.apply(v).plainText

    def print[T](v: T)(using Frame): Unit < Console =
        local.use(_.print(toString(v)))

    def printErr[T](v: T)(using Frame): Unit < Console =
        local.use(_.printErr(toString(v)))

    def println[T](v: T)(using Frame): Unit < Console =
        local.use(_.println(toString(v)))

    def printlnErr[T](v: T)(using Frame): Unit < Console =
        local.use(_.printlnErr(toString(v)))

end Console
