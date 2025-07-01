package kyo.internal

import scala.Tuple.*

abstract class Zippable[-A, -B]:
    type Out
    def zip(left: A, right: B): Out

object Zippable extends ZippableLowPriority0:

    given left[A]: (Zippable[A, Unit] { type Out = A })  = (left: A, _) => left
    given right[B]: (Zippable[Unit, B] { type Out = B }) = (_, right: B) => right

    given concat[A <: Tuple, B <: Tuple]: (Zippable[A, B] { type Out = A ++ B }) = (left: A, right: B) => left ++ right

    def apply[A, B](using z: Zippable[A, B]): Zippable[A, B]  = z
    def zip[A, B](a: A, b: B)(using z: Zippable[A, B]): z.Out = z.zip(a, b)
end Zippable

trait ZippableLowPriority0 extends ZippableLowPriority1:
    given prepend[A, B <: Tuple]: (Zippable[A, B] { type Out = A *: B }) = (left: A, right: B) => left *: right
    given append[A <: Tuple, B]: (Zippable[A, B] { type Out = A :* B })  = (left: A, right: B) => left :* right
end ZippableLowPriority0

trait ZippableLowPriority1:
    given pair[A, B]: (Zippable[A, B] { type Out = (A, B) }) = (left: A, right: B) => (left, right)
end ZippableLowPriority1
