package kyo.kernel.internal

import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.targetName

// traits, so a handling method can allocate one object serving as both the
// handler and its region's exit arrow
sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]

object Handler:

    trait Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    trait Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S] extends Handler[I, O, E, A, S]:
        // the target name avoids the erased clash with Transform's plain
        // apply when one object serves as both handler and exit arrow
        @targetName("applyInput")
        def apply[X](input: I[X]): Outcome[O[X] < (E & S), A] < S
    end Loop

    // pure logic: the region's state lives in its node and its cell, so the
    // handler object is allocated once per handling call site and never
    // copied across state updates
    trait LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], state: State): Outcome2[State, O[X] < (E & S), A] < S

end Handler
