package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec

/** Represents abstract operations whose implementations are provided later by a handler.
  *
  * An arrow effect captures the shape of an operation without its implementation: a transformation from `Input[A]` to `Output[A]` for any
  * `A`, with how that transformation happens deferred until a handler interprets it. Code written against one is abstract over how its
  * operations are actually performed.
  *
  * Every use of an arrow effect creates a suspended operation carrying its input and the continuation from that point. A handler supplies
  * the clause that answers it, receiving the input and, depending on the family below, the continuation itself. Because a continuation is an
  * ordinary [[Arrow]], a clause may apply it more than once or not at all, which is what backtracking, non-determinism and early return are
  * built from.
  *
  * #### Declaring one
  *
  * An effect is a type and is never instantiated, so a declaration is a type extending this class. `Input` and `Output` are type
  * constructors, and two cover most effects: `Const[X]` ignores its parameter and always answers `X`, for an operation carrying a plain
  * value such as an error, while `Id[X]` answers `X` unchanged, for an operation answered with the value it was given.
  *
  * #### Answering one
  *
  * Two families answer an operation, differing in what the clause is handed:
  *   - [[ArrowEffect.handleCont]] hands the clause the continuation as an [[Arrow]], to apply once, many times, or not at all. What the
  *     region holds moves to it and is released once, where it ends, so a clause that resumes more than once runs each shot against the
  *     live resource.
  *   - [[ArrowEffect.handleLoop]] hands the clause the input alone and takes a [[Loop.Outcome]] back, continuing with an answer or
  *     terminating with a result. [[ArrowEffect.handleLoopState]] carries state between occurrences.
  *
  * Overloads of each add a `done` arm, transforming the region's final value, and a `recover` arm, answering a throwable raised inside the
  * region. The `*With` variants fuse the caller's continuation into the region node rather than building a separate map after it.
  *
  * @tparam Input
  *   The type constructor for what an operation carries in
  * @tparam Output
  *   The type constructor for what a handler answers with
  *
  * @see
  *   [[ArrowEffect.suspend]] For performing an operation
  * @see
  *   [[ArrowEffect.suspendWith]] For performing one and transforming its answer in the same step
  * @see
  *   [[ArrowEffect.Mask]] For letting an operation tunnel past the handlers wrapped around it
  * @see
  *   [[ContextEffect]] For the other kind of effect, a value bound around a computation
  */
abstract class ArrowEffect[-Input[_], +Output[_]] extends Effect

object ArrowEffect:

    // Suspensions and handled regions are arrow nodes (Pending.SuspendArrow, Pending.HandleArrow, the
    // Handler instances below), interpreted by Eval.

    /** Performs an operation of an arrow effect, suspending until a handler answers it.
      *
      * The value this builds carries the operation's input and the continuation from this point, and the effect joins the row, so the
      * computation cannot be evaluated until a handler removes it. Nothing here decides what the answer is: that belongs entirely to
      * whichever handler is installed when the computation runs.
      *
      * @param effectTag
      *   Identifies the effect this operation belongs to
      * @param functionInput
      *   The operation's input, handed to the handler's clause
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

    /** Performs an operation and transforms its answer in the same node, rather than suspending and mapping afterwards.
      *
      * This is what a helper wants where it would otherwise write `suspend(...).map(f)`. Fusing `f` into the suspension means the answer is
      * transformed where it arrives, instead of through a separate node the evaluator has to reach first.
      *
      * @param effectTag
      *   Identifies the effect this operation belongs to
      * @param functionInput
      *   The operation's input, handed to the handler's clause
      * @param f
      *   Transforms the handler's answer
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
            override def frame = _frame
            def tag            = effectTag
            def input          = functionInput
            def cont           = this
            override def apply[D, S2](v: O[A] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[A], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[A]](v)), Arrow.id)

    /** Answers an arrow effect by handing the clause the continuation from the suspension point.
      *
      * Each occurrence is answered independently: the clause receives the operation's input and the rest of the computation as an [[Arrow]],
      * and answers by applying it. Never applying it abandons that remainder, which is how an early exit is written. Applying it more than
      * once is supported: the regions this handler dumps into the continuation move their releases to this region, which runs them once,
      * where it ends, so every resumption runs against the live resource.
      *
      * The rows place this clause inside the region it serves: its result is at `E & S & S2`, so an operation of `E` the clause performs is
      * answered by this same handler and the clause is re-entrant. That is the difference from [[handleLoop]], whose clause sits outside the
      * region and whose own effects go to a handler further out.
      *
      * The continuation carries [[Region.NoEscape]], confining it to the clause.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation to provide
      */
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCont(effectTag, v)(handle, a => a)

    /** Handles an arrow effect by providing a handler function implementation, transforming the final result.
      *
      * Like the variant without done, except done transforms the result when the handled computation completes.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation to provide
      * @param done
      *   The function to transform the final result
      */
    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Region.discharge(handle[X](input, next))
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    /** Handles an arrow effect by providing a handler function implementation, with a recover arm.
      *
      * Like the variant without recover, except a failure of the handled computation is offered to recover, which may answer with a
      * replacement result or decline, letting the failure propagate.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation to provide
      * @param done
      *   The function to transform the final result
      * @param recover
      *   The function offered a failure of the handled computation
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
        def onDone(v0: A): B < (S & S2)                   = done(v0)
        def onRecover(ex: Throwable): Maybe[B < (S & S2)] = recover(ex)
        // The input is forced under the recovery clause: a throw while building it is the region's to answer.
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.ContHandler[I, O, E, A, B, S & S2]:
                            def tag = effectTag
                            def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                                Region.discharge(handle[X](input, next))
                            def done(state: Unit, v0: A)                     = onDone(v0)
                            override def recover(state: Unit, ex: Throwable) = onRecover(ex)

                    new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = ()
                        def cont           = Arrow.id
                    end new
                case _ =>
                    onDone(Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end try
    end handleCont

    /** Answers an arrow effect with a clause that is handed the operation's input alone.
      *
      * Where [[handleCont]] gives the clause the continuation to apply, this gives it only the input and takes a [[Loop.Outcome]] back:
      * `Loop.continue` with an answer resumes the region, `Loop.done` ends it with a result. The continuation never becomes a value the
      * clause holds, so each occurrence is answered exactly once or not at all.
      *
      * The rows say where the clause runs. Its outcome sits at `S & S2` while the answer inside it sits at `E & S & S2`, which places the
      * clause outside the region it serves: only the answer handed back is region currency. A `Loop.done` result therefore bypasses the
      * region, and an effect the clause performs is answered by a handler outside this one, not by this one.
      *
      * Reach for it when answering needs no state between occurrences. [[handleLoopState]] carries state across them, and [[handleCont]]
      * hands over the continuation itself.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The clause, answering each occurrence with a [[Loop.Outcome]]
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    /** [[handleLoop]] with a `done` arm transforming the region's final value.
      *
      * `done` runs when the computation finishes on its own, having produced an `A` without the clause ending the region first. A
      * `Loop.done` from the clause answers with a `B` directly and does not pass through it.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The clause, answering each occurrence with a [[Loop.Outcome]]
      * @param done
      *   Transforms the value the computation finished with
      */
    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
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
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleLoop

    /** Handles an arrow effect with a loop-based approach, custom completion handling and a recover arm.
      *
      * Like the variant without recover, except a failure of the handled computation is offered to recover, which may answer with a
      * replacement result or decline, letting the failure propagate.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @param done
      *   The function to transform the final result
      * @param recover
      *   The function offered a failure of the handled computation
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
        def onDone(v0: A): B < (S & S2)                   = done(v0)
        def onRecover(ex: Throwable): Maybe[B < (S & S2)] = recover(ex)
        // the input is forced under the recovery clause, as in the recovering handleCont
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                            def tag = effectTag
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
                            def done(state: Unit, v0: A)                     = onDone(v0)
                            override def recover(state: Unit, ex: Throwable) = onRecover(ex)

                    new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = ()
                        def cont           = Arrow.id
                    end new
                case _ =>
                    onDone(Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end try
    end handleLoop

    /** [[handleLoop]] with a state carried from one occurrence to the next.
      *
      * The clause is handed the state alongside the input and answers with a `Loop.continue` carrying both the next state and the answer, so
      * the state threads through the region without a mutable cell. `Loop.done` ends the region and discards it.
      *
      * The state is per region rather than per computation: it threads forward through the occurrences of one evaluation, and evaluating the
      * same computation again starts from the initial value.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param state
      *   The value the first occurrence is handed
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The clause, answering each occurrence with a [[Loop.Outcome]] carrying the next state
      */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    /** [[handleLoopState]] with a `done` arm receiving the final state alongside the region's value.
      *
      * `done` is where the state leaves the region: without it the state is discarded when the computation finishes, so this is the variant
      * to use when the state is the answer, as it is for a counter, an accumulator, or a log collected across occurrences.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param state
      *   The value the first occurrence is handed
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The clause, answering each occurrence with a [[Loop.Outcome]] carrying the next state
      * @param done
      *   Transforms the state and the value the computation finished with
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
        def onDone(st: State, v0: A): B < (S & S2) = done(st, v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                        def tag = effectTag
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
                        def done(st: State, v0: A) = onDone(st, v0)
                val state0 = state

                new Pending.HandleArrow[State, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** Handles an arrow effect with stateful loop-based approach, custom completion handling and a recover arm.
      *
      * Like the variant without recover, except the current state and a failure of the handled computation are offered to recover, which
      * may answer with a replacement result or decline, letting the failure propagate.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param state
      *   The initial state value
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation for each iteration
      * @param done
      *   The function to transform the final state and result
      * @param recover
      *   The function offered the state and a failure of the handled computation
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
        def onDone(st: State, v0: A): B < (S & S2)                   = done(st, v0)
        def onRecover(st: State, ex: Throwable): Maybe[B < (S & S2)] = recover(st, ex)
        // the input is forced under the recovery clause, as in the recovering handleCont; a throw there
        // sees the initial state, the only one the region has had
        try
            val v0 = v
            v0 match
                case _: Pending[?, ?] =>
                    val h =
                        new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                            def tag = effectTag
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
                            def done(st: State, v0: A)                     = onDone(st, v0)
                            override def recover(st: State, ex: Throwable) = onRecover(st, ex)
                    val state0 = state

                    new Pending.HandleArrow[State, E, A, B, B, S & S2]:
                        override def frame = _frame
                        def value          = v0
                        def handler        = h
                        def state          = state0
                        def cont           = Arrow.id
                    end new
                case _ =>
                    onDone(state, Nested.unnest(v0))
            end match
        catch
            case ex if !IsFatal(ex) => onRecover(state, ex).getOrElse(throw ex)
        end try
    end handleLoopState

    /** [[handleCont]] with the caller's own continuation fused into the region rather than mapped over its result.
      *
      * `handleContWith(tag, v)(handle, done)(f)` answers what `handleCont(tag, v)(handle, done).map(f)` answers, carrying `f` on the region
      * itself so the evaluator reaches one node instead of two.
      *
      * @param f
      *   The transformation applied to the region's result
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
        def onDone(v0: A): B < (S & S2) = done(v0)
        def onF(v0: B): C < S3          = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Region.discharge(handle[X](input, next))
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleArrowWith[Unit, E, A, B, C, S & S2 & S3]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(Nested.unnest(v)).map(onF)
        end match
    end handleContWith

    /** [[handleLoop]] with the caller's own continuation fused into the region rather than mapped over its result.
      *
      * See [[handleContWith]] for what the fusion saves.
      *
      * @param f
      *   The transformation applied to the region's result
      */
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
        def onDone(v0: A): B < (S & S2) = done(v0)
        def onF(v0: B): C < S3          = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
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
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleArrowWith[Unit, E, A, B, C, S & S2 & S3]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(Nested.unnest(v)).map(onF)
        end match
    end handleLoopWith

    /** [[handleLoopState]] with the caller's own continuation fused into the region rather than mapped over its result.
      *
      * See [[handleContWith]] for what the fusion saves.
      *
      * @param f
      *   The transformation applied to the region's result
      */
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
        def onDone(st: State, v0: A): B < (S & S2) = done(st, v0)
        def onF(v0: B): C < S3                     = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.LoopStateHandler[State, I, O, E, A, B, S & S2]:
                        def tag = effectTag
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
                        def done(st: State, v0: A) = onDone(st, v0)

                new Pending.HandleArrowWith[State, E, A, B, C, S & S2 & S3]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(state0, Nested.unnest(v)).map(onF)
        end match
    end handleLoopStateWith

    /** Hides an effect from the handlers wrapped around a computation, so its operations are answered further out.
      *
      * Inside a mask every operation of the masked effect becomes a `Mask[S]` operation carrying the original as an unevaluated payload,
      * which the handlers in between cannot see. [[Mask.run]] is the boundary where each payload re-raises the original for the handlers
      * outside it, and the answer flows back into the masked computation.
      *
      * The effect to hide is named rather than inferred, and only that effect tunnels: everything else in the row stays answerable where it
      * is. Masking the same effect twice behaves as one mask.
      *
      * Note: this is not limited to arrow effects. The region shadows its tag in the context as well as on the stack, so a [[ContextEffect]]
      * read inside a mask tunnels past an inner binding and is answered by the binding outside.
      *
      * IMPORTANT: moving where a value is answered moves where a scope ends with it. A bracket inside a masked computation releases when the
      * outer handler is done with the tunneled continuation, not at the mask boundary. An outer handler that discards that continuation
      * releases it there, told the discard signal.
      *
      * @tparam S
      *   The effect being hidden, which may be an intersection when several have to tunnel together
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
            handleMasking(tag, v) {
                [X] => (operation, cont) => suspend[X](maskTag, operation).map(cont(_))
            }

        /** The boundary where masked operations re-raise for the handlers outside, removing `Mask[S]` from the row. */
        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                [C] => (input, cont) => input.map(cont(_))
            }
    end Mask

    /** Handles the first occurrence of an arrow effect and transforms the final result. This is useful when you want to handle just the
      * first instance of an effect and transform its result into a different type, while leaving any subsequent occurrences of the effect
      * unhandled.
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
        def onDone(a: A): B < (S & S2) = done(a)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.FirstHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], cont: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, cont.asInstanceOf[Arrow[O[X], A, E & S]])
                        def done(state: Unit, a: A) = onDone(a)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleFirst

    /** As [[handleFirst]], but the handed-out remainder may be resumed more than once: its dumped regions are held rather than closed at
      * each resumption's end, so a resource shared across the resumptions (a streamed choice's branches) stays live across all of them and
      * is released once after the scope that resumes them ends. Use the single-shot [[handleFirst]] when the remainder is consumed once.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirstRepeated[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](inline effectTag: Tag[E], v: A < (E & S))(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(a: A): B < (S & S2) = done(a)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.FirstHandler[I, O, E, A, B, S & S2]:
                        def tag               = effectTag
                        override def repeated = true
                        def run[X](input: I[X], cont: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, cont.asInstanceOf[Arrow[O[X], A, E & S]])
                        def done(state: Unit, a: A) = onDone(a)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleFirstRepeated

    // Mask must re-suspend an operation it cannot inspect, so the clause is handed the operation, not its input.

    /** Handles an arrow effect by providing a handler that receives the suspended operation itself; the result is the handled
      * computation's value.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation to provide, given the operation and the continuation
      */
    private[kyo] inline def handleMasking[E <: Effect, A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape)
    )(using inline _frame: Frame): A < (S & S2) =
        handleMasking(effectTag, v)(handle, a => a)

    /** Handles an arrow effect by providing a handler that receives the suspended operation itself, as a computation in the effect, rather
      * than its input. The continuation is the one from the suspension point, as in handleCont.
      *
      * @param effectTag
      *   Identifies which arrow effect to answer
      * @param v
      *   The computation performing the effect
      * @param handle
      *   The function implementation to provide, given the operation and the continuation
      * @param done
      *   The function to transform the final result
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleMasking[E <: Effect, A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2 & Region.NoEscape]) => A < (E & S & S2 & Region.NoEscape),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.MaskingHandler[E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](operation: X < E, next: Arrow[X, A, E & S & S2]) =
                            Region.discharge(handle[X](operation, next))
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleMasking

end ArrowEffect
