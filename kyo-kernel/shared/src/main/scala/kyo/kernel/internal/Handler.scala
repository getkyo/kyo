package kyo.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Loop
import kyo.kernel.Loop.Continue
import kyo.kernel.Loop.Continue2
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.publicInBinary

/** What a region installs: the clause that answers an effect, plus what the evaluator has to know to run the region around it.
  *
  * One instance per region entry, pushed onto the [[Stack]] and matched by [[tag]] when a suspension looks for who answers it. The subclasses
  * below are the answering shapes the public API offers; everything they have in common is here, and it is all information the evaluator
  * needs about a region rather than about the effect: whether the clause may resume more than once, whether it hands the continuation out,
  * and what the region contributes to the context.
  *
  * Those three are declared rather than inferred because the evaluator has to decide what to do with a region's obligations before the clause
  * has run, and by then it is too late to observe what the clause actually does.
  */
sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]


@publicInBinary private[kernel] object Handler:

    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def done(state: State, v: A): B < S
        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

    abstract class MaskingHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class FirstHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): B < (E & S)

    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B < S] < S

    abstract class LoopStateHandler[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[State, E, A, B, S]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

    abstract class ContextHandler[State, E <: ContextEffect[State], A, -S] extends Handler[E, A, S]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State
        def release(state: State, failure: Maybe[Throwable]): Unit 

end Handler
