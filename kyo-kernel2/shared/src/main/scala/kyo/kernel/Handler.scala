package kyo.kernel

import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.targetName

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

object Handler:

    abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): Loop.Outcome[O[X] < (E & S), A] < S

    abstract class LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): Loop.Outcome2[LoopState[I, O, E, A, S], O[X] < (E & S), A] < S

    object Loop:

        sealed abstract class Continue[A]:
            private[kernel] def _1: A

        sealed abstract class Continue2[A, B]:
            private[kernel] def _1: A
            private[kernel] def _2: B

        opaque type Outcome[A, O]     = O | Continue[A]
        opaque type Outcome2[A, B, O] = O | Continue2[A, B]

        @nowarn("msg=anonymous")
        inline def continue[A, O, S](inline v: A): Outcome[A, O] < S =
            `<`.lift(
                new Continue[A]:
                    def _1 = v
            )

        @nowarn("msg=anonymous")
        inline def continue[A, B, O, S](inline v1: A, inline v2: B): Outcome2[A, B, O] < S =
            `<`.lift(
                new Continue2[A, B]:
                    def _1 = v1
                    def _2 = v2
            )

        @targetName("done1")
        inline def done[A, O, S](inline v: O): Outcome[A, O] < S =
            `<`.lift(v)

        @targetName("done2")
        inline def done[A, B, O, S](inline v: O): Outcome2[A, B, O] < S =
            `<`.lift(v)

    end Loop

end Handler
