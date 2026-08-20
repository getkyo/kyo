package kyo.kernel

import kyo.Arrow
// unqualified so the inline expansions do not select it from Arrow.type at a site outside
// package kyo, where it is not accessible. See the note in Pending.scala
import kyo.Arrow.Transform
import kyo.Frame
import kyo.Tag
import kyo.kernel.internal.*
// unqualified for the same reason as Arrow.Transform above: these are private[kyo] members of
// public objects, and a qualified selection does not resolve at an expansion site outside package kyo
import kyo.kernel.internal.Handler.HandlerCont
import kyo.kernel.internal.Handler.HandlerLoop
import kyo.kernel.internal.Handler.HandlerLoopState
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
        new Suspend[I, O, E, C, B, S] with Transform[O[C], B, S]:
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new HandlerCont[I, O, E, A, B, S]:
                            def frame                                            = _frame
                            def tag                                              = effectTag
                            def run[C](input: I[C], cont: Arrow[O[C], A, E & S]) = handle[C](input, cont)
                            override def apply(a: A)                             = onDone(a)
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[C](input: I[C])  = handle[C](input)
                            override def apply(a: A) = onDone(a)
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new HandlerCont[I, O, E, A, B, S]:
                            def frame                                            = _frame
                            def tag                                              = effectTag
                            def run[X](input: I[X], cont: Arrow[O[X], A, E & S]) = handle[X](input, cont)
                            override def apply(a: A)                             = onDone(a)
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[X](input: I[X])  = handle[X](input)
                            override def apply(a: A) = onDone(a)
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
            case body: Kyo[A, E & S] @unchecked =>
                new Handle[E, A, B, C, S & S2] with Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[X](st: State, input: I[X]) = handle[X](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b match
                            case kyo: Kyo[B, S3] @unchecked => Effect.defer(kyo, this, next)
                            case _                          => next(apply(Nested.unnest(b)), Arrow.id)
            case _ => onDone(state, Nested.unnest(v)).map(f)
        end match
    end handleLoopStateWith

    // Surface the previous kernels carry that this one does not yet. Kept as signatures so the
    // gap is visible here rather than only in a parked test.

    /** Answers the first operation of a tag and leaves the rest of the region unhandled, ending it with the clause's own result type.
      *
      * The previous kernel built this on a stateful handleLoop whose clause received the continuation and used `Loop.done` to carry the
      * resumed remainder out. Neither primitive here stands in for that: `handleCont` keeps the region installed, and `handleLoopState`
      * answers with a value rather than handing the clause a continuation it can end the region from.
      */
    // private[kyo] inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
    //     inline effectTag: Tag[E],
    //     v: A < (E & S)
    // )(
    //     inline handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
    //     inline done: A => B < S2
    // )(using inline _frame: Frame): B < (S & S2)

    /** Runs a clause against the operation a computation is currently standing on, without answering it.
      *
      * Parked with the IOTask integration design, which is what reads a standing operation.
      */
    // private[kyo] inline def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    //     inline effectTag: Tag[E],
    //     v: A < (E & S)
    // )(
    //     inline f: [C] => I[C] => Unit
    // )(using inline _frame: Frame): Unit

    /** Answers operations while recovering from a throw raised inside the region.
      *
      * Wants the same unwind mechanism as Effect.catching; see the note there.
      */
    // private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
    //     inline effectTag: Tag[E],
    //     v: A < (E & S)
    // )(
    //     inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
    // )(
    //     inline recover: Throwable => A < S
    // )(using inline _frame: Frame): A < S

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
