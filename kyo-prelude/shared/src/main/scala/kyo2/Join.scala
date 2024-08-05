package kyo2

import kyo.Tag
import kyo2.Join.Command.*
import kyo2.kernel.*

sealed trait Join extends Effect[Join.Command, Id]

object Join:

    sealed trait Command[-A] derives CanEqual
    object Command:
        case object Drop                                                  extends Command[Unit]
        case class Batch[A, B, S](v: A, f: Seq[A] => Seq[B] < (S & Join)) extends Command[B]
        case class Eval[A, B, S](v: Seq[A], f: A => B < (S & Join))       extends Command[Seq[B]]
    end Command

    def dropIf(b: Boolean): Unit < Join =
        if b then ()
        else Effect.suspend(Tag[Join], Command.Drop)

    def batch[A, B, S](f: Seq[A] => Seq[B] < (S & Join)): A => B < (S & Join) =
        (a: A) => Effect.suspend(Tag[Join], Command.Batch(a, f))

    def eval[A, B, S](seq: Seq[A])(f: A => B < (S & Join)): Seq[B] < (S & Join) =
        Effect.suspend(Tag[Join], Command.Eval(seq, f))

    def run[A, S](v: A < (S & Join)): A < S =
        Effect.handle(Tag[Join], v)(
            [C] =>
                (input, cont) =>
                    input match
                        case Drop => ???
                        case Batch(v, f) =>
                            f(Seq(v)).map(_.head)
                        case Eval(v, f) =>

                ???
        )
        ???
    end run
end Join
