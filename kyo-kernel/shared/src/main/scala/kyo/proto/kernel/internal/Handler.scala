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

// The region interface: a handler for effect E whose region delivers A in row S at exit.
sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    // An arrow handler transforms at exit: done turns the body's A into the region's B, and
    // recover may replace it when the region fails. Context regions bind without
    // transforming, so neither lives on the Handler base: a binding cannot manufacture
    // the result its body failed to produce.
    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def done(state: State, v: A): B < S

        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
    end ArrowHandler

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

    // The operation carrier for effects generic over ArrowEffect[?, ?]: the clause receives the
    // operation reified at its raise site tag, which the ContHandler protocol cannot carry.
    abstract class ContOpHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class LoopHandler[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[State, E, A, B, S]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        // Staged like answers: the effectful-clause dispatch is built in the handler's
        // own compiled method, out of the eval's inline budget; the loop continues with
        // it directly.
        private[kyo] def clauseDispatch[X0](
            reentry: Arrow[O[X0], A, E & S]
        ): Arrow[Outcome2[State, O[X0] < (E & S), B < S], B, S] =
            type OutT = Outcome2[State, O[X0] < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame = Frame.internal
                override def apply[D, S3](out: OutT < S3, cont2: Arrow[B, D, S3]) =
                    out match
                        case p: Pending[OutT, S3] @unchecked =>
                            Effect.defer(p, this, cont2)
                        case out: Continue2[State, O[X0] < (E & S)] @unchecked =>
                            Kyo.handle[State, E, A, B, S](
                                out._2.chain(reentry),
                                LoopHandler.this,
                                out._1
                            ).chain(cont2)
                        case out =>
                            Nested.unnest[B < S](out).chain(cont2)
            end new
        end clauseDispatch

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
    abstract class ContextHandler[State, E <: ContextEffect[State], A, -S] extends Handler[E, A, S]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State

        // The completion edge: fires strictly in the eval at the region's settled exit, in
        // the same slice that pops the entry, so nothing can park between the body settling
        // and the completion. Pure, like fork and join.
        private[kyo] def done(state: State): Unit = ()

        // Consulted before a park re-installs the region: a handler refusing a spent
        // extent throws here, and the park drains what it owes before the refusal
        // propagates. Pure, like fork and join.
        private[kyo] def reenter(state: State): Unit = ()

        // Pure, like fork and join: runs whenever the region dies without resuming, at
        // abandonment, when a failure unwinds past it, and when a dump it rode is discarded.
        // Both hooks may fire more than once per logical region (a shared dump, a fork's
        // state, a merged shadow region): the eval guarantees the edge is reached at least
        // once, and exactly-once belongs to the state. Arrow handlers have no release:
        // their failure hook is recover, and resource safety routes through a binding.
        private[kyo] def release(state: State, ex: Throwable): Unit = ()
    end ContextHandler

    private[kyo] inline def answersLoopState[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
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
