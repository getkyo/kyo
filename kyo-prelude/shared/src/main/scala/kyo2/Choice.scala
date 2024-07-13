package kyo2

import kyo.Tag
import kyo2.kernel.*

sealed trait Choice extends Effect[Seq, Id]

object Choice:

    inline def get[A](seq: Seq[A])(using inline frame: Frame): A < Choice =
        Effect.suspend[A](Tag[Choice], seq)

    inline def eval[A, B, S](seq: Seq[A])(inline f: A => B < S)(using inline frame: Frame): B < (Choice & S) =
        seq match
            case Seq(head) => f(head)
            case seq       => Effect.suspendMap[A](Tag[Choice], seq)(f)

    inline def dropIf(condition: Boolean)(using inline frame: Frame): Unit < Choice =
        if condition then drop
        else ()

    inline def drop(using inline frame: Frame): Nothing < Choice =
        Effect.suspend[Nothing](Tag[Choice], Seq.empty)

    def run[A, S](v: A < (Choice & S))(using Frame): Seq[A] < S =
        Effect.handle(Tag[Choice], v.map(Seq[A](_))) {
            [C] =>
                (input, cont) =>
                    Kyo.seq.map(input)(v => Choice.run(cont(v))).map(_.flatten.flatten)
        }

end Choice
