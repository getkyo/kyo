package kyo

import kyo.Tag
import kyo.kernel.*

sealed trait Choice extends ArrowEffect[Seq, Id]

object Choice:

    inline def get[A](seq: Seq[A])(using inline frame: Frame): A < Choice =
        ArrowEffect.suspend[A](Tag[Choice], seq)

    inline def eval[A, B, S](seq: Seq[A])(inline f: A => B < S)(using inline frame: Frame): B < (Choice & S) =
        seq match
            case Seq(head) => f(head)
            case seq       => ArrowEffect.suspendMap[A](Tag[Choice], seq)(f)

    inline def dropIf(condition: Boolean)(using inline frame: Frame): Unit < Choice =
        if condition then drop
        else ()

    inline def drop(using inline frame: Frame): Nothing < Choice =
        ArrowEffect.suspend[Nothing](Tag[Choice], Seq.empty)

    def run[A, S](v: A < (Choice & S))(using Frame): Chunk[A] < S =
        ArrowEffect.handle(Tag[Choice], v.map(Chunk[A](_))) {
            [C] =>
                (input, cont) =>
                    Kyo.foreach(input)(v => Choice.run(cont(v))).map(_.flatten.flatten)
        }

end Choice
