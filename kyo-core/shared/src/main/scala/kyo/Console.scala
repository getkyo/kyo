package kyo

import java.io.EOFException
import kyo.internal.Trace

abstract class Console:
    def readln(using Trace): String < IOs
    def print(s: String)(using Trace): Unit < IOs
    def printErr(s: String)(using Trace): Unit < IOs
    def println(s: String)(using Trace): Unit < IOs
    def printlnErr(s: String)(using Trace): Unit < IOs
end Console

object Console:
    val default: Console =
        new Console:
            def readln(using Trace) =
                IOs {
                    val line = scala.Console.in.readLine()
                    if line == null then
                        throw new EOFException("Consoles.readln failed.")
                    else
                        line
                    end if
                }
            def print(s: String)(using Trace)      = IOs(scala.Console.out.print(s))
            def printErr(s: String)(using Trace)   = IOs(scala.Console.err.print(s))
            def println(s: String)(using Trace)    = IOs(scala.Console.out.println(s))
            def printlnErr(s: String)(using Trace) = IOs(scala.Console.err.println(s))
end Console

opaque type Consoles <: IOs = IOs

object Consoles:

    private val local = Locals.init(Console.default)

    def run[T, S](v: T < (Consoles & S))(using Trace): T < (S & IOs) =
        v

    def run[T, S](c: Console)(v: T < (Consoles & S))(using Trace): T < (S & IOs) =
        local.let(c)(v)

    def readln(using Trace): String < IOs =
        local.use(_.readln)

    private def toString(v: Any)(using Trace): String =
        v match
            case v: String =>
                v
            case v =>
                pprint.apply(v).plainText

    def print[T](v: T)(using Trace): Unit < Consoles =
        local.use(_.print(toString(v)))

    def printErr[T](v: T)(using Trace): Unit < Consoles =
        local.use(_.printErr(toString(v)))

    def println[T](v: T)(using Trace): Unit < Consoles =
        local.use(_.println(toString(v)))

    def printlnErr[T](v: T)(using Trace): Unit < Consoles =
        local.use(_.printlnErr(toString(v)))
end Consoles
