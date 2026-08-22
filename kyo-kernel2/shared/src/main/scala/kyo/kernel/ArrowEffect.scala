package kyo.kernel

import kyo.Arrow
// unqualified so the inline expansions do not select it from Arrow.type at a site outside
// package kyo, where it is not accessible. See the note in Pending.scala
import kyo.Arrow.Step
import kyo.Arrow.Transform
import kyo.Frame
import kyo.Tag
import kyo.kernel.internal.*
// unqualified for the same reason as Arrow.Transform above: these are private[kyo] members of
// public objects, and a qualified selection does not resolve at an expansion site outside package kyo
import kyo.kernel.internal.Handler.HandlerCont
import kyo.kernel.internal.Handler.HandlerLoop
import kyo.kernel.internal.Handler.HandlerLoopState
import kyo.kernel.internal.Handler.Out
import kyo.kernel.internal.Handler.answersCont
import kyo.kernel.internal.Handler.answersLoop
import kyo.kernel.internal.Handler.answersLoopState
import kyo.kernel.internal.Handler.answerStep
import kyo.kernel.internal.Handler.answerStepState
import kyo.kernel.internal.Handler.nextAnswer
import kyo.kernel.internal.Handler.resuspend
import kyo.kernel.internal.Kyo.Handle
import kyo.kernel.internal.Kyo.Suspend
import scala.annotation.nowarn

// variance as the previous kernel declared it: an effect that consumes narrower inputs or produces
// wider outputs is substitutable, which is what lets Abort[-E], Emit[-V], and Poll[+V] put their own
// variance on the parameters they pass through Const
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =
        new Suspend[I, O, E, C, O[C], Any]:
            def frame = _frame
            def tag   = effectTag
            def input = effectInput
            def cont  = Arrow.id[O[C]]

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =
        new Suspend[I, O, E, C, B, S] with Step[O[C], B, S]:
            def frame                   = _frame
            def tag                     = effectTag
            def input                   = effectInput
            def cont                    = this
            override def apply(v: O[C]) = f(v)
            def apply[D, S2](v: O[C] < S2, next: Arrow[B, D, S2]): D < (S & S2) =
                v match
                    case kyo: Kyo[O[C], S2] @unchecked => Effect.defer(kyo, this, next)
                    case _                             => next(apply(Nested.unnest(v)), Arrow.id)
    end suspendWith

    // The row parameters come in pairs on every region combinator: S is the body's row, pinned when
    // `v` is typed, and S2 is a free variable for whatever the clause adds beyond it. With a single
    // row the typer commits it to the body's row before the clause is seen, so a clause that
    // introduces its own effect (Abort in Check.runAbort, the fold function's row in Emit.runFold)
    // cannot widen it and the site needs explicit instantiation. The previous kernel drew the same
    // split. The internals do not know about the pair: they instantiate at S & S2.
    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S & S2]:
                    def value = v
                    val handler =
                        new HandlerCont[I, O, E, A, B, S & S2]:
                            def frame                                                 = _frame
                            def tag                                                   = effectTag
                            def run[C](input: I[C], cont: Arrow[O[C], A, E & S & S2]) = handle[C](input, cont)
                            override def apply(a: A)                                  = onDone(a)
                            // the answer body is the eval layer's template, expanded here with the
                            // clause statically bound; see Handler.answersCont
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersCont[I, O, E, A, B, S & S2, C](effectTag, handle, input0, k0, armed, slot, out)
                    def cont = Arrow.id[B]
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    /** [[handleCont]] with the region's value as the result. */
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCont(effectTag, v)(handle, a => a)

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), B] < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S & S2]:
                    def value = v
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S & S2]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[C](input: I[C])  = handle[C](input)
                            override def apply(a: A) = onDone(a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStep and Handler.answersLoop
                            override def answer[C](input: I[C], out: Out): Any =
                                answerStep[I, O, E, A, B, S & S2, C](handle, input, out)
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoop[I, O, E, A, B, S & S2, C](effectTag, handle, _frame, input0, k0, armed, slot, out)
                    def cont = Arrow.id[B]
            case _ => onDone(Nested.unnest(v))
        end match
    end handleLoop

    /** [[handleLoop]] with the region's value as the result. */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), A] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(s: State, v: A) = done(s, v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S & S2]:
                    def value = v
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S & S2, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStepState and Handler.answersLoopState
                            override def answer[C](st: State, input: I[C], out: Out): Any =
                                answerStepState[I, O, E, A, B, S & S2, State, C](handle, st, input, out)
                            override def answers[C](
                                state0: State,
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoopState[I, O, E, A, B, S & S2, State, C](
                                    effectTag,
                                    handle,
                                    _frame,
                                    state0,
                                    input0,
                                    k0,
                                    armed,
                                    slot,
                                    out
                                )
                    def cont = Arrow.id[B]
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** The stateful region without a done clause: it completes with the body's own result and discards the final state. */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    // the one-shot region's exit token: the clause stores the operation and the dumped remainder,
    // and returning it settled is what completes the region at the first operation. It never
    // escapes handleFirst's expansion: the done lane unwraps it before anything else sees the
    // value. Abstract so each expansion implements it anonymously: the captures keep the
    // operation's own types, so a primitive input is stored unboxed
    abstract private[kyo] class FirstSuspended:
        def input: Any
        def cont: Arrow[Any, Any, Any]

    /** Answers only the first operation of the region, handing the clause the raw remainder.
      *
      * The clause receives the operation's input and the continuation with the effect still in its row:
      * nothing has been decided about the rest of the computation, and every consumer re-handles the
      * remainder with a fresh region, which is what the stream pipes do round by round. A body that
      * completes without performing the effect takes `done` instead. Both lanes run outside the region.
      *
      * Built on [[handleCont]]: the clause completes the region at once by returning a settled token,
      * and the region's done lane tells the two completions apart. The body enters the region unchanged,
      * so the remainder is the body's own continuation, raw, and re-handling it round after round keeps
      * one uniform type.
      */
    @nowarn("msg=anonymous")
    inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        handleCont[I, O, E, Any, B, S, S2](effectTag, v)(
            [C] =>
                (input0, cont0) =>
                    new FirstSuspended:
                        def input = input0
                        def cont  = cont0.asInstanceOf[Arrow[Any, Any, Any]]
            ,
            {
                case s: FirstSuspended =>
                    // the C the operation was performed at is existential here; the erased instantiation
                    // is the liberty erasure already grants. The remainder's value type is the body's own
                    // A because the body entered the region unmapped, and its row keeps the effect
                    // because the region ended without consuming it
                    handle[Any](s.input.asInstanceOf[I[Any]], s.cont.asInstanceOf[Arrow[O[Any], A, E & S]])
                case a =>
                    done(a.asInstanceOf[A])
            }
        )

    // the *With variants take the region's continuation as a separate parameter group and fuse it
    // into the region node: the node is the arrow the region's result flows into, as suspendWith's
    // node is the arrow the operation's answer flows into. A settled input takes done and then the
    // continuation as a map

    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (I[X], Arrow[O[X], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2 & S3] with Step[B, C, S & S2 & S3]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerCont[I, O, E, A, B, S & S2]:
                            def frame                                                 = _frame
                            def tag                                                   = effectTag
                            def run[X](input: I[X], cont: Arrow[O[X], A, E & S & S2]) = handle[X](input, cont)
                            override def apply(a: A)                                  = onDone(a)
                            // the answer body is the eval layer's template, expanded here with the
                            // clause statically bound; see Handler.answersCont
                            override def answers[X](
                                input0: I[X],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersCont[I, O, E, A, B, S & S2, X](effectTag, handle, input0, k0, armed, slot, out)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S4](b: B < S4, next: Arrow[C, D, S4]): D < (S & S2 & S3 & S4) =
                        b match
                            case kyo: Kyo[B, S4] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(Nested.unnest(v)).map(f)
        end match
    end handleContWith

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), B] < (S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2 & S3] with Step[B, C, S & S2 & S3]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S & S2]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[X](input: I[X])  = handle[X](input)
                            override def apply(a: A) = onDone(a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStep and Handler.answersLoop
                            override def answer[C](input: I[C], out: Out): Any =
                                answerStep[I, O, E, A, B, S & S2, C](handle, input, out)
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoop[I, O, E, A, B, S & S2, C](effectTag, handle, _frame, input0, k0, armed, slot, out)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S4](b: B < S4, next: Arrow[C, D, S4]): D < (S & S2 & S3 & S4) =
                        b match
                            case kyo: Kyo[B, S4] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(Nested.unnest(v)).map(f)
        end match
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [X] => (State, I[X]) => Loop.Outcome2[State, O[X] < (E & S & S2), B] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onDone(s: State, v: A) = done(s, v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2 & S3] with Step[B, C, S & S2 & S3]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S & S2, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[X](st: State, input: I[X]) = handle[X](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStepState and Handler.answersLoopState
                            override def answer[C](st: State, input: I[C], out: Out): Any =
                                answerStepState[I, O, E, A, B, S & S2, State, C](handle, st, input, out)
                            override def answers[C](
                                state0: State,
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoopState[I, O, E, A, B, S & S2, State, C](
                                    effectTag,
                                    handle,
                                    _frame,
                                    state0,
                                    input0,
                                    k0,
                                    armed,
                                    slot,
                                    out
                                )
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S4](b: B < S4, next: Arrow[C, D, S4]): D < (S & S2 & S3 & S4) =
                        b match
                            case kyo: Kyo[B, S4] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(state, Nested.unnest(v)).map(f)
        end match
    end handleLoopStateWith

    /** Answers operations while recovering from a throw raised inside the region.
      *
      * The handler is the recovery: the entry that answers the region's operations is the entry an unwind
      * stops at, so the scope is the region exactly. There is no second object marking where it ends and
      * nothing is allocated when the eval enters one, which is what makes this cost what a plain region costs.
      *
      * What it covers is what runs while it is installed: forcing the body, a resumption, the clause, and a
      * region nested inside. What it does not cover is the done clause, which runs once the region has been
      * popped, or anything after it. The previous kernel covered the done clause because its recovery was a
      * try around the whole traversal rather than a position on a stack.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(
        inline recover: Throwable => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        // named apart from the member below, which would shadow the parameter inside the class body
        def onPanic(ex: Throwable) = recover(ex)
        // the body is not matched here, as the other regions match theirs: `value` is a method, so the eval
        // forces it once the handler is on the stack, and a body that throws while it is forced throws inside
        // the scope that guards it. `Abort.run { throw ... }` rests on that
        new Handle[E, A, B, B, S & S2]:
            def value = v
            val handler =
                new HandlerCont[I, O, E, A, B, S & S2] with Recover[B, S & S2]:
                    def frame                                                 = _frame
                    def tag                                                   = effectTag
                    def run[C](input: I[C], cont: Arrow[O[C], A, E & S & S2]) = handle[C](input, cont)
                    override def apply(a: A)                                  = done(a)
                    def recover(ex: Throwable)                                = onPanic(ex)
            def cont = Arrow.id[B]
        end new
    end handleCatching

    /** The recovering region without a done clause: it completes with the body's own result. */
    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2)
    )(
        inline recover: Throwable => A < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCatching[I, O, E, A, A, S, S2](effectTag, v)(handle, a => a)(recover)

    // Surface the previous kernels carry that this one does not yet. Kept as signatures so the
    // gap is visible here rather than only in a parked test.

    /** Runs a clause against the operation a computation is currently standing on, without answering it.
      *
      * Parked with the IOTask integration design, which is its only caller and the only thing that can say
      * what it should read. The reach is the open question: the previous kernel's version matched a
      * suspension directly, and a mapped suspension was still one node, where here a map wraps it in a
      * deferral whose payload sits behind a method that runs user code. A park holds a field and can be read;
      * whether the rest needs marking is a decision for the design that has a use for it.
      */
    // private[kyo] inline def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    //     inline effectTag: Tag[E],
    //     v: A < (E & S)
    // )(
    //     inline f: [C] => I[C] => Unit
    // ): Unit

    /** Answers operations while the clause allows, parking at the first it refuses so a later handler finishes the remainder.
      *
      * Parked with the IOTask integration design, and it needs partial evaluation to hand back a resumable value.
      */
    // private[kyo] inline def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    //     inline effectTag: Tag[E],
    //     v: A < (E & S)
    // )(
    //     inline handle: [C] => (I[C], O[C] => A < (E & S)) => Maybe[A < (E & S)]
    // )(using inline _frame: Frame): A < (E & S)

end ArrowEffect
