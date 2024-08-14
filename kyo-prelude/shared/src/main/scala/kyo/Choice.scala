package kyo

import kyo.core.*
import kyo.internal.Trace

class Choices extends Effect[Choices]:
    type Command[T] = Seq[T]

object Choices extends Choices:

    def run[T: Flat, S](v: T < (Choices & S))(using Trace): Seq[T] < S =
        this.handle(handler)((), v)

    def get[T](v: Seq[T])(using Trace): T < Choices =
        this.suspend(v)

    def eval[T, U, S](v: Seq[T])(f: T => U < S)(using Trace): U < (Choices & S) =
        v match
            case Seq(head) => f(head)
            case v         => this.suspend(v, f)

    def filter[S](v: Boolean < S)(using Trace): Unit < (Choices & S) =
        v.map {
            case true =>
                ()
            case false =>
                drop
        }

    def drop(using Trace): Nothing < Choices =
        suspend(this)(Seq.empty)

    private val handler =
        new ResultHandler[Unit, Seq, Choices, Seq, Any]:
            def done[T](st: Unit, v: T)(using Tag[Choices]) = Seq(v)
            def resume[T, U: Flat, S](st: Unit, v: Seq[T], f: T => U < (Choices & S))(using Tag[Choices]) =
                Seqs.collect(v.map(e => Choices.run(f(e)))).map(_.flatten)
end Choices
