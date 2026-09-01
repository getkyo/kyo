package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop.Continue2
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import scala.annotation.publicInBinary

sealed abstract private[kernel] class Handler[E <: Effect, State]:
    def tag: Tag[E]

    // Pure, like fork and join: runs whenever the region dies without resuming, at
    // abandonment and when a failure unwinds past it.
    private[kyo] def release(state: State, ex: Throwable): Unit = ()

    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    // An arrow handler transforms at exit: done delivers the region's final result, and
    // recover may replace it when the region fails. Context regions bind without
    // transforming, so neither lives on the Handler base: a binding cannot manufacture
    // the result its body failed to produce.
    sealed abstract class ArrowHandler[E <: Effect, A, B, -S, State] extends Handler[E, State]:
        def done(state: State, v: A): B < S

        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
    end ArrowHandler

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[E, A, B, S, Unit]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

    // The operation carrier for effects generic over ArrowEffect[?, ?]: the clause receives the
    // operation reified at its raise site tag, which the ContHandler protocol cannot carry.
    abstract class ContOpHandler[E <: Effect, A, B, S] extends ArrowHandler[E, A, B, S, Unit]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends ArrowHandler[E, A, B, S, State]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        def answers[X](
            state: State,
            input: I[X],
            k: Arrow[Any, Any, Any],
            armed: Boolean,
            slot: Safepoint.Slot
        ): Outcome2[State, Any, B < S] < S =
            var st = state
            try
                run(state, input) match
                    case c: Continue2[State, O[X] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                kyo.proto.Loop.continue(st, ans.map(a => k(a))(using Frame.internal))
                            case _ =>
                                kyo.proto.Loop.continue(st, k(Nested.unnest[Any](ans)))
                        end match
                    case o2 =>
                        o2.asInstanceOf[Outcome2[State, Any, B < S] < S]
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    kyo.proto.Loop.continue(at, Effect.deferInline(throw ex)(using Frame.internal))
            end try
        end answers
    end LoopHandler

    // A context region binds, it does not transform: the eval passes the region's result
    // through at exit, so there is no done here. fork and join are pure and run strictly
    // inside the eval, fork at each isolate crossing and join at the merge, where the origin
    // region continues at the joined state.
    abstract class ContextHandler[State, E <: ContextEffect[State]] extends Handler[E, State]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State
    end ContextHandler

    private[kyo] inline def answersLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B < (S)] < S,
        _frame: Frame,
        state0: State,
        input0: I[C],
        k0: Arrow[Any, Any, Any],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome2[State, Any, B < S] < S =
        var st: State                               = state0
        var in: Any                                 = input0
        var k: Arrow[Any, Any, Any]                 = k0
        var result: Outcome2[State, Any, B < S] < S = null.asInstanceOf[Outcome2[State, Any, B < S] < S]
        var running                                 = true
        while running do
            try
                handle[C](st, in.asInstanceOf[I[C]]) match
                    case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                result = kyo.proto.Loop.continue(st, ans.map(a => k(a))(using _frame))
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = kyo.proto.Loop.continue(st, Effect.defer(next, Arrow.id[Any]))
                                    running = false
                                else
                                    next match
                                        case sN: Kyo.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Kyo.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Kyo.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                                    if sN.tag.erased =:= effectTag.erased && dN.contB.isInstanceOf[Arrow.Id[?]] =>
                                                    val sc = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                    val ca = dN.contA.asInstanceOf[Arrow[Any, Any, Any]]
                                                    if sc.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = ca
                                                        matched = true
                                                    else if ca.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = sc
                                                        matched = true
                                                    end if
                                                case _ => ()
                                            end match
                                            if !matched then
                                                result = kyo.proto.Loop.continue(st, Effect.defer(v0, dN.contA, dN.contB))
                                                running = false
                                        case _ =>
                                            result = kyo.proto.Loop.continue(st, next)
                                            running = false
                                    end match
                                end if
                        end match
                    case o2 =>
                        result = o2.asInstanceOf[Outcome2[State, Any, B < S] < S]
                        running = false
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    result = kyo.proto.Loop.continue(at, Effect.deferInline(throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoopState

end Handler
