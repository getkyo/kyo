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

abstract class ArrowEffect[I[_], O[_]] extends Effect

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

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => A < (E & S),
        inline done: A => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = v
                    val handler =
                        new HandlerCont[I, O, E, A, B, S]:
                            def frame                                            = _frame
                            def tag                                              = effectTag
                            def run[C](input: I[C], cont: Arrow[O[C], A, E & S]) = handle[C](input, cont)
                            override def apply(a: A)                             = onDone(a)
                            // the answer body is the eval layer's template, expanded here with the
                            // clause statically bound; see Handler.answersCont
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersCont[I, O, E, A, B, S, C](effectTag, handle, input0, k0, armed, slot, out)
                    def cont = Arrow.id[B]
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = v
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[C](input: I[C])  = handle[C](input)
                            override def apply(a: A) = onDone(a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStep and Handler.answersLoop
                            override def answer[C](input: I[C], out: Out): Any =
                                answerStep[I, O, E, A, B, S, C](handle, input, out)
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoop[I, O, E, A, B, S, C](effectTag, handle, input0, k0, armed, slot, out)
                    def cont = Arrow.id[B]
            case _ => onDone(Nested.unnest(v))
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(s: State, v: A) = done(s, v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = v
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStepState and Handler.answersLoopState
                            override def answer[C](st: State, input: I[C], out: Out): Any =
                                answerStepState[I, O, E, A, B, S, State, C](handle, st, input, out)
                            override def answers[C](
                                state0: State,
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoopState[I, O, E, A, B, S, State, C](effectTag, handle, state0, input0, k0, armed, slot, out)
                    def cont = Arrow.id[B]
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** The stateful region without a done clause: it completes with the body's own result and discards the final state. */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S), A] < S
    )(using inline _frame: Frame): A < S =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    // the *With variants take the region's continuation as a separate parameter group and fuse it
    // into the region node: the node is the arrow the region's result flows into, as suspendWith's
    // node is the arrow the operation's answer flows into. A settled input takes done and then the
    // continuation as a map

    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (I[X], Arrow[O[X], A, E & S]) => A < (E & S),
        inline done: A => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Step[B, C, S & S2]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerCont[I, O, E, A, B, S]:
                            def frame                                            = _frame
                            def tag                                              = effectTag
                            def run[X](input: I[X], cont: Arrow[O[X], A, E & S]) = handle[X](input, cont)
                            override def apply(a: A)                             = onDone(a)
                            // the answer body is the eval layer's template, expanded here with the
                            // clause statically bound; see Handler.answersCont
                            override def answers[X](
                                input0: I[X],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersCont[I, O, E, A, B, S, X](effectTag, handle, input0, k0, armed, slot, out)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b match
                            case kyo: Kyo[B, S3] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(Nested.unnest(v)).map(f)
        end match
    end handleContWith

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome[O[X] < (E & S), B] < S,
        inline done: A => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(v: A) = done(v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Step[B, C, S & S2]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[X](input: I[X])  = handle[X](input)
                            override def apply(a: A) = onDone(a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStep and Handler.answersLoop
                            override def answer[C](input: I[C], out: Out): Any =
                                answerStep[I, O, E, A, B, S, C](handle, input, out)
                            override def answers[C](
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoop[I, O, E, A, B, S, C](effectTag, handle, input0, k0, armed, slot, out)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b match
                            case kyo: Kyo[B, S3] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(Nested.unnest(v)).map(f)
        end match
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [X] => (State, I[X]) => Loop.Outcome2[State, O[X] < (E & S), B] < S,
        inline done: (State, A) => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(s: State, v: A) = done(s, v)
        v match
            case _: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Step[B, C, S & S2]:
                    def frame = _frame
                    def value = v
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[X](st: State, input: I[X]) = handle[X](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                            // the answer bodies are the eval layer's templates, expanded here with the
                            // clause statically bound; see Handler.answerStepState and Handler.answersLoopState
                            override def answer[C](st: State, input: I[C], out: Out): Any =
                                answerStepState[I, O, E, A, B, S, State, C](handle, st, input, out)
                            override def answers[C](
                                state0: State,
                                input0: I[C],
                                k0: Arrow[Any, Any, Any],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                out: Out
                            ): Any =
                                answersLoopState[I, O, E, A, B, S, State, C](effectTag, handle, state0, input0, k0, armed, slot, out)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b match
                            case kyo: Kyo[B, S3] @unchecked => Effect.defer(kyo, this, next)
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
    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => A < (E & S),
        inline done: A => B < S
    )(
        inline recover: Throwable => B < S
    )(using inline _frame: Frame): B < S =
        // named apart from the member below, which would shadow the parameter inside the class body
        def onPanic(ex: Throwable) = recover(ex)
        // the body is not matched here, as the other regions match theirs: `value` is a method, so the eval
        // forces it once the handler is on the stack, and a body that throws while it is forced throws inside
        // the scope that guards it. `Abort.run { throw ... }` rests on that
        new Handle[E, A, B, B, S]:
            def value = v
            val handler =
                new HandlerCont[I, O, E, A, B, S] with Recover[B, S]:
                    def frame                                            = _frame
                    def tag                                              = effectTag
                    def run[C](input: I[C], cont: Arrow[O[C], A, E & S]) = handle[C](input, cont)
                    override def apply(a: A)                             = done(a)
                    def recover(ex: Throwable)                           = onPanic(ex)
            def cont = Arrow.id[B]
        end new
    end handleCatching

    /** The recovering region without a done clause: it completes with the body's own result. */
    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => A < (E & S)
    )(
        inline recover: Throwable => A < S
    )(using inline _frame: Frame): A < S =
        handleCatching(effectTag, v)(handle, a => a)(recover)

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
