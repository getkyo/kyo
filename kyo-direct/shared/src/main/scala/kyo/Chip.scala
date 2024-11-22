package kyo

import kyo.Console.Out
import kyo.kernel.*

given Frame = Frame.internal

type ~[Name <: String, T]

trait Signal[A]

object Signal:
    extension (self: Signal[Boolean])
        def ^(other: Signal[Boolean]): Signal[Boolean] = ???
        def &(other: Signal[Boolean]): Signal[Boolean] = ???
        def |(other: Signal[Boolean]): Signal[Boolean] = ???
    end extension
end Signal

trait Chip[+Input, +Output]

object Chip:

    def input[Name <: String, T]: Signal[T] < Chip[Name ~ T, Any]                  = ???
    def output[Name <: String, T]: (Signal[T] => Unit < Chip[Any, Name ~ T]) < Any = ???

    class WireOps[Name <: String]():
        def apply[T, A, S, Rest, Output](value: Signal[T])(v: A < (Chip[Name ~ T & Rest, Output] & S)): A < (Chip[Rest, Output] & S) = ???

    def wire[Name <: String]: WireOps[Name] = ???
end Chip

def module: Unit < Chip["a" ~ Boolean & ("b" ~ Boolean & "cin" ~ Boolean), "sum" ~ Boolean & "cout" ~ Boolean] =
    for
        a    <- Chip.input["a", Boolean]
        b    <- Chip.input["b", Boolean]
        cin  <- Chip.input["cin", Boolean]
        sum  <- Chip.output["sum", Boolean]
        cout <- Chip.output["cout", Boolean]
        _    <- sum(a ^ b ^ cin)
        _    <- cout((a & b) | (a & cin) | (b & cin))
    yield ()

// using direct syntax via dotty-cps-async
def moduleD = defer {
    val a    = !Chip.input["a", Boolean]
    val b    = !Chip.input["b", Boolean]
    val cin  = !Chip.input["cin", Boolean]
    val sum  = !Chip.output["sum", Boolean]
    val cout = !Chip.output["cout", Boolean]
    !sum(a ^ b ^ cin)
    !cout((a & b) | (a & cin) | (b & cin))
}

val v: Signal[Boolean]                                                                  = ???
val x: Unit < Chip["b" ~ Boolean & "cin" ~ Boolean, "sum" ~ Boolean & "cout" ~ Boolean] = Chip.wire["a"](v)(module)
