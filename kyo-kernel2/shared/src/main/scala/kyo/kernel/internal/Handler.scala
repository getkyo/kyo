package kyo.kernel.internal

import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.targetName

sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]

object Handler:

    trait Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    trait Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S] extends Handler[I, O, E, A, S]:
        @targetName("applyInput")
        def apply[X](input: I[X]): Outcome[O[X] < (E & S), A] < S
    end Loop

    trait LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], state: State): Outcome2[State, O[X] < (E & S), A] < S

    // answers one operation and leaves: the first clause takes the continuation
    // with the effect still in its row, the second is the region's result when
    // the computation settles without an operation
    trait First[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): B < S2
        @targetName("applyDone")
        def apply(v: A): B < S2
    end First

end Handler
