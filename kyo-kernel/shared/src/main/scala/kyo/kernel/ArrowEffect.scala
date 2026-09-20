package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec

/** Represents abstract operations whose implementations are provided later by a handler.
  *
  * An arrow effect is the shape of an operation, a transformation from `Input[A]` to `Output[A]`, with how it happens deferred until a
  * handler interprets it. Each use suspends, carrying the input and the continuation from that point, and a handler supplies the clause that
  * answers it. Because a continuation is an ordinary [[Arrow]], a clause may apply it more than once or not at all, which is what
  * backtracking, non-determinism and early return are built from.
  *
  * An effect is a type, never instantiated: declare one as a type extending this class. `Input` and `Output` are type constructors, and two
  * cover most: `Const[X]` ignores its parameter and always answers `X` (an operation carrying a plain value such as an error), while `Id[X]`
  * answers `X` unchanged.
  *
  * Two families answer an operation:
  *   - [[ArrowEffect.handleCont]] hands the clause the continuation as an [[Arrow]], to apply once, many times, or not at all.
  *   - [[ArrowEffect.handleLoop]] hands the clause the input alone and takes a [[Loop.Outcome]] back; [[ArrowEffect.handleLoopState]] carries
  *     state between occurrences.
  *
  * Overloads add a `done` arm (transforming the region's final value) and a `recover` arm (answering a throwable raised inside it); the
  * `*With` variants fuse the caller's continuation into the region node.
  *
  * @tparam Input
  *   The type constructor for what an operation carries in
  * @tparam Output
  *   The type constructor for what a handler answers with
  * @see
  *   [[ArrowEffect.suspend]], [[ArrowEffect.suspendWith]], [[ArrowEffect.Mask]], [[ContextEffect]]
  */
abstract class ArrowEffect[-Input[_], +Output[_]] extends Effect

object ArrowEffect:

    // Suspensions and handled regions are arrow nodes (Pending.SuspendArrow, Pending.HandleArrow, the
    // Handler instances below), interpreted by Eval.

    /** Performs an operation of an arrow effect, suspending until a handler answers it.
      *
      * The value carries the input and the continuation from this point, and the effect joins the row, so nothing evaluates until a handler
      * removes it. The answer belongs to whichever handler is installed when the computation runs.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline functionInput: I[A]
    ): O[A] < E =
        new Pending.SuspendArrow[I, O, E, A, O[A], E]:
            override def frame = _frame
            def tag            = effectTag
            def input          = functionInput
            def cont           = Arrow.id

    /** Performs an operation and transforms its answer in the same node, fusing `f` in rather than suspending and mapping afterwards, so the
      * answer is transformed where it arrives instead of through a separate node the evaluator must reach first.
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline functionInput: I[A]
    )(
        inline f: O[A] => B < S
    ): B < (S & E) =
        new Pending.SuspendArrowWith[I, O, E, A, B, E & S]:
            override def frame                                              = _frame
            def tag                                                         = effectTag
            def input                                                       = functionInput
            def cont                                                        = this
            override def apply[D, S2](v: O[A] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[A], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[A]](v)), Arrow.id)

    /** Answers an arrow effect by handing the clause the operation's input and the rest of the computation as an [[Arrow]].
      *
      * Each occurrence is answered independently. Never applying the continuation abandons the remainder (an early exit); applying it more
      * than once is supported, the regions this handler dumps into it moving their releases to this region, which runs them once where it
      * ends, so every resumption runs against the live resource. The rows place the clause inside the region it serves (result at
      * `E & S & S2`), so an `E` operation the clause performs is answered by this same handler: it is re-entrant. That is the difference from
      * [[handleLoop]], whose clause sits outside the region. The continuation carries [[Region.NoEscape]], confining it to the clause.
      */
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCont(effectTag, v)(handle, a => a)

    /** [[handleCont]] with a `done` arm transforming the result when the handled computation completes. */
    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A, B, S & S2]:
                        def tag                                                   = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Region.discharge(handle[X](input, next))
                        def onDone(state: Unit, v0: A) = done(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleCont

    /** [[handleCont]] with a `recover` arm: a failure of the handled computation is offered to `recover`, which answers with a replacement
      * or declines, letting the failure propagate.
      */
    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        // The input is forced under the recovery clause: a throw while building it is the region's to answer.
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.ContHandler[I, O, E, A, B, S & S2]:
                            def tag                                                   = effectTag
                            def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                                Region.discharge(handle[X](input, next))
                            def onDone(state: Unit, v0: A)                     = done(v0)
                            override def onRecover(state: Unit, ex: Throwable) = recover(ex)

                    new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = ()
                        def cont           = Arrow.id
                    end new
                case _ =>
                    done(Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => recover(ex).getOrElse(throw ex)
        end try
    end handleCont

    /** Answers an arrow effect with a clause handed the operation's input alone, taking a [[Loop.Outcome]] back: `Loop.continue` with an
      * answer resumes the region, `Loop.done` ends it with a result.
      *
      * The continuation never becomes a value the clause holds, so each occurrence is answered exactly once or not at all. The rows place the
      * clause outside the region it serves: the outcome sits at `S & S2`, the answer inside it at `E & S & S2`, so only the answer handed back
      * is region currency, a `Loop.done` result bypasses the region, and an effect the clause performs is answered by a handler further out.
      * Contrast [[handleCont]], which hands over the continuation itself; [[handleLoopState]] carries state between occurrences.
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    /** [[handleLoop]] with a `done` arm transforming the region's final value: it runs when the computation finishes on its own, whereas a
      * `Loop.done` from the clause answers with a `B` directly and bypasses it.
      */
    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                        def tag                 = effectTag
                        def run[X](input: I[X]) =
                            handle[X](input)
                        override def answers[X](
                            input0: I[X],
                            k0: Arrow[O[X], A, E & S & S2],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome[A < (E & S & S2), B < (S & S2)] < (S & S2) =
                            Handler.answersLoop[I, O, E, A, B, S & S2, X](
                                effectTag,
                                [C] => (in: I[C]) => handle[C](in),
                                frame,
                                input0,
                                k0,
                                armed,
                                slot
                            )
                        def onDone(state: Unit, v0: A) = done(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleLoop

    /** [[handleLoop]] with a `recover` arm: a failure of the handled computation is offered to `recover`, which answers with a replacement
      * or declines, letting the failure propagate.
      */
    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        // the input is forced under the recovery clause, as in the recovering handleCont
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                            def tag                 = effectTag
                            def run[X](input: I[X]) =
                                handle[X](input)
                            override def answers[X](
                                input0: I[X],
                                k0: Arrow[O[X], A, E & S & S2],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                frame: Frame
                            ): Loop.Outcome[A < (E & S & S2), B < (S & S2)] < (S & S2) =
                                Handler.answersLoop[I, O, E, A, B, S & S2, X](
                                    effectTag,
                                    [C] => (in: I[C]) => handle[C](in),
                                    frame,
                                    input0,
                                    k0,
                                    armed,
                                    slot
                                )
                            def onDone(state: Unit, v0: A)                     = done(v0)
                            override def onRecover(state: Unit, ex: Throwable) = recover(ex)

                    new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = ()
                        def cont           = Arrow.id
                    end new
                case _ =>
                    done(Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => recover(ex).getOrElse(throw ex)
        end try
    end handleLoop

    /** [[handleLoop]] with a state carried from one occurrence to the next: the clause is handed it alongside the input and answers with a
      * `Loop.continue` carrying the next state and the answer, so it threads through the region without a mutable cell; `Loop.done` discards
      * it. The state is per region, not per computation: it threads through one evaluation's occurrences, and evaluating the same computation
      * again starts from the initial value.
      */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    /** [[handleLoopState]] with a `done` arm receiving the final state alongside the region's value. `done` is where the state leaves the
      * region (without it the state is discarded on completion), so use it when the state is the answer, as for a counter or accumulator.
      */
    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                        def tag                            = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        override def answers[X](
                            state0: State,
                            input0: I[X],
                            k0: Arrow[O[X], A, E & S & S2],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome2[State, A < (E & S & S2), B < (S & S2)] < (S & S2) =
                            Handler.answersLoopState[State, I, O, E, A, B, S & S2, X](
                                effectTag,
                                [C] => (st: State, in: I[C]) => handle[C](st, in),
                                frame,
                                state0,
                                input0,
                                k0,
                                armed,
                                slot
                            )
                        def onDone(st: State, v0: A) = done(st, v0)
                val state0 = state

                new Pending.HandleArrow[State, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => done(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** [[handleLoopState]] with a `recover` arm: the current state and a failure of the handled computation are offered to `recover`, which
      * answers with a replacement or declines, letting the failure propagate.
      */
    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2),
        inline recover: (State, Throwable) => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        // the input is forced under the recovery clause, as in the recovering handleCont; a throw there
        // sees the initial state, the only one the region has had
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                            def tag                            = effectTag
                            def run[X](st: State, input: I[X]) =
                                handle[X](st, input)
                            override def answers[X](
                                state0: State,
                                input0: I[X],
                                k0: Arrow[O[X], A, E & S & S2],
                                armed: Boolean,
                                slot: Safepoint.Slot,
                                frame: Frame
                            ): Loop.Outcome2[State, A < (E & S & S2), B < (S & S2)] < (S & S2) =
                                Handler.answersLoopState[State, I, O, E, A, B, S & S2, X](
                                    effectTag,
                                    [C] => (st: State, in: I[C]) => handle[C](st, in),
                                    frame,
                                    state0,
                                    input0,
                                    k0,
                                    armed,
                                    slot
                                )
                            def onDone(st: State, v0: A)                     = done(st, v0)
                            override def onRecover(st: State, ex: Throwable) = recover(st, ex)
                    val state0 = state

                    new Pending.HandleArrow[State, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = state0
                        def cont           = Arrow.id
                    end new
                case _ =>
                    done(state, Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => recover(state, ex).getOrElse(throw ex)
        end try
    end handleLoopState

    /** [[handleCont]] with the caller's continuation `f` fused into the region rather than mapped over its result:
      * `handleContWith(tag, v)(handle, done)(f)` answers what `handleCont(tag, v)(handle, done).map(f)` does, but the evaluator reaches one
      * node instead of two.
      */
    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (I[X], Arrow[O[X], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onF(v0: B): C < S3 = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A, B, S & S2]:
                        def tag                                                   = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Region.discharge(handle[X](input, next))
                        def onDone(state: Unit, v0: A) = done(v0)

                new Pending.HandleArrowWith[Unit, E, A, B, C, S & S2 & S3]:
                    override def frame                                           = _frame
                    def value                                                    = v
                    def handler                                                  = h
                    def state                                                    = ()
                    def cont                                                     = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => done(Nested.unnest(v)).map(onF)
        end match
    end handleContWith

    /** [[handleLoop]] with the caller's continuation fused into the region rather than mapped over its result; see [[handleContWith]]. */
    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onF(v0: B): C < S3 = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                        def tag                 = effectTag
                        def run[X](input: I[X]) =
                            handle[X](input)
                        override def answers[X](
                            input0: I[X],
                            k0: Arrow[O[X], A, E & S & S2],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome[A < (E & S & S2), B < (S & S2)] < (S & S2) =
                            Handler.answersLoop[I, O, E, A, B, S & S2, X](
                                effectTag,
                                [X0] => (in: I[X0]) => handle[X0](in),
                                frame,
                                input0,
                                k0,
                                armed,
                                slot
                            )
                        def onDone(state: Unit, v0: A) = done(v0)

                new Pending.HandleArrowWith[Unit, E, A, B, C, S & S2 & S3]:
                    override def frame                                           = _frame
                    def value                                                    = v
                    def handler                                                  = h
                    def state                                                    = ()
                    def cont                                                     = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => done(Nested.unnest(v)).map(onF)
        end match
    end handleLoopWith

    /** [[handleLoopState]] with the caller's continuation fused into the region rather than mapped over its result; see [[handleContWith]]. */
    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        state0: State,
        v: A < (E & S)
    )(
        inline handle: [X] => (State, I[X]) => Loop.Outcome2[State, O[X] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onF(v0: B): C < S3 = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                        def tag                            = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        override def answers[X](
                            state0: State,
                            input0: I[X],
                            k0: Arrow[O[X], A, E & S & S2],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome2[State, A < (E & S & S2), B < (S & S2)] < (S & S2) =
                            Handler.answersLoopState[State, I, O, E, A, B, S & S2, X](
                                effectTag,
                                [C] => (st: State, in: I[C]) => handle[C](st, in),
                                frame,
                                state0,
                                input0,
                                k0,
                                armed,
                                slot
                            )
                        def onDone(st: State, v0: A) = done(st, v0)

                new Pending.HandleArrowWith[State, E, A, B, C, S & S2 & S3]:
                    override def frame                                           = _frame
                    def value                                                    = v
                    def handler                                                  = h
                    def state                                                    = state0
                    def cont                                                     = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => done(state0, Nested.unnest(v)).map(onF)
        end match
    end handleLoopStateWith

    /** Hides an effect from the handlers wrapped around a computation, so its operations are answered further out.
      *
      * Inside a mask, each operation of the masked effect becomes a `Mask[S]` operation carrying the original as an unevaluated payload the
      * handlers in between cannot see; [[Mask.run]] is the boundary where each payload re-raises for the handlers outside, and the answer
      * flows back in. The effect to hide is named, and only it tunnels; masking the same effect twice behaves as one mask. This is not limited
      * to arrow effects: the region shadows its tag in the context too, so a [[ContextEffect]] read inside a mask is answered by the binding
      * outside an inner one.
      *
      * IMPORTANT: moving where a value is answered moves where a scope ends with it. A bracket inside a masked computation releases when the
      * outer handler is done with the tunneled continuation, not at the mask boundary; an outer handler that discards that continuation
      * releases it there, told `Absent` (a clean ending).
      *
      * @tparam S
      *   The effect being hidden, an intersection when several tunnel together
      */
    sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

    object Mask:

        /** Hides `E` inside `v`, so its operations tunnel past every handler between here and the matching [[run]]. */
        def apply[E](using
            Frame
        )[E2 >: E <: Effect, A, S](v: A < (E2 & S))(
            using
            tag: Tag[E2],
            maskTag: Tag[Mask[E]]
        ): A < (Mask[E] & S) =
            // Through the representation, not `.map(cont(_))`: a map interposes a poll, so a stop as the tunneled
            // operation settles parks its value in front of `cont`, stranding a bracket's install. `Effect.defer`
            // reaches `cont`'s first link with no poll.
            handleMasking(tag, v) {
                [X] => (operation, cont) => Effect.defer(suspend[X](maskTag, operation), cont)
            }

        /** The boundary where masked operations re-raise for the handlers outside, removing `Mask[S]` from the row. */
        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                // As in `apply`: a `map` would park a settled answer in front of `cont`'s install under a stop.
                [C] => (input, cont) => Effect.defer(input, cont)
            }
    end Mask

    /** Handles the first occurrence of an arrow effect and transforms the final result, leaving later occurrences unhandled.
      *
      * The continuation handed to `handle` is the remainder of `v`, carrying every region that sat between this handler and the operation, a
      * bracket included. Those regions are re-installed each time the holder resumes the continuation, running against the live resource, and
      * a bracket travelling with the remainder releases once, where the holder ends. A remainder that is never resumed releases at the exit
      * of the scope enclosing this handler, or at the end of the evaluation.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation containing the effect to handle
      * @param handle
      *   Function to handle the first occurrence of the effect and transform its result
      * @param done
      *   Function to transform the final result if no effect is found
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](inline effectTag: Tag[E], v: A < (E & S))(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.FirstHandler[I, O, E, A, B, S & S2]:
                        def tag                                                   = effectTag
                        def run[X](input: I[X], cont: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, cont.asInstanceOf[Arrow[O[X], A, E & S]])
                        def onDone(state: Unit, a: A) = done(a)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleFirst

    /** As [[handleFirst]], but the handed-out remainder may be resumed more than once: its dumped regions are held rather than closed at each
      * resumption's end, so a resource shared across the resumptions (a streamed choice's branches) stays live and is released once after the
      * scope that resumes them ends. Use [[handleFirst]] when the remainder is consumed once.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirstRepeated[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](inline effectTag: Tag[E], v: A < (E & S))(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.FirstHandler[I, O, E, A, B, S & S2]:
                        def tag                                                   = effectTag
                        override def repeated                                     = true
                        def run[X](input: I[X], cont: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, cont.asInstanceOf[Arrow[O[X], A, E & S]])
                        def onDone(state: Unit, a: A) = done(a)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleFirstRepeated

    // Mask must re-suspend an operation it cannot inspect, so the clause is handed the operation, not its input.

    /** Handles an arrow effect with a clause that receives the suspended operation itself rather than its input. */
    private[kyo] inline def handleMasking[E <: Effect, A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape)
    )(using inline _frame: Frame): A < (S & S2) =
        handleMasking(effectTag, v)(handle, a => a)

    /** [[handleMasking]] with a `done` arm; the clause receives the suspended operation as a computation in the effect, and the continuation
      * is the one from the suspension point, as in [[handleCont]].
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleMasking[E <: Effect, A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.MaskingHandler[E, A, B, S & S2]:
                        def tag                                                     = effectTag
                        def run[X](operation: X < E, next: Arrow[X, A, E & S & S2]) =
                            Region.discharge(handle[X](operation, next))
                        def onDone(state: Unit, v0: A) = done(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleMasking

end ArrowEffect
