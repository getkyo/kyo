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
  *   - [[ArrowEffect.handleCont]] hands the clause the continuation as an [[Arrow]], to apply once, many times, or not at all.
  *     [[ArrowEffect.handleContRepeated]] is its variant for a clause that applies the continuation more than once.
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
  *   [[ArrowEffect.Mask]] For hiding an effect from enclosing handlers
  * @see
  *   [[ContextEffect]] For the other kind of effect, a value bound around a computation
  */
abstract class ArrowEffect[-Input[_], +Output[_]] extends Effect

object ArrowEffect:

    // Suspensions and handled regions are arrow nodes (Pending.SuspendArrow, Pending.HandleArrow, the
    // Handler instances below), interpreted by Eval.

    /** Creates a suspended computation that requests a function implementation from an arrow effect. This establishes a requirement for a
      * function that must be satisfied by a handler higher up in the program. The requirement becomes part of the effect type, ensuring
      * that handlers must provide the requested function before the program can execute.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param funcionInput
      *   The input value to be transformed by the function
      * @return
      *   A computation that will receive the requested function when executed
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline funcionInput: I[A]
    ): O[A] < E =
        new Pending.SuspendArrow[I, O, E, A, O[A], E]:
            override def frame = _frame
            def tag            = effectTag
            def input          = funcionInput
            def cont           = Arrow.id

    /** Creates a suspended computation that requests a function implementation and transforms its result immediately upon receipt. This
      * combines the operations of requesting and transforming a function into a single step.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param funcionInput
      *   The input value to be transformed by the function
      * @param f
      *   The function to transform the handler's result
      * @return
      *   A computation containing the transformed result
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline funcionInput: I[A]
    )(
        inline f: O[A] => B < S
    ): B < (S & E) =
        new Pending.SuspendArrowWith[I, O, E, A, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def input          = funcionInput
            def cont           = this
            override def apply[D, S2](v: O[A] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[A], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[A]](v)), Arrow.id)

    /** Handles an arrow effect by providing a handler function implementation.
      *
      * Each effect occurrence is handled independently: the clause receives the operation's input and the continuation from the
      * suspension point, and answers by applying the continuation, possibly several times or not at all. New effects may be introduced
      * through the S2 type parameter.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @return
      *   The computation result with the function implementation provided
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
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @param done
      *   The function to transform the final result
      * @return
      *   The computation result with the function implementation provided
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

    /** Handles an arrow effect with a clause that may resume its continuation more than once.
      *
      * As [[handleCont]], except this region holds the regions it dumps into the continuation it hands the clause. A
      * region that discharges exactly once, a bracket's release, would otherwise fire when the first resumption ends
      * its extent, leaving later resumptions running against something already released; held, it discharges once,
      * where this region ends.
      *
      * Only for a clause that really does resume more than once: holding keeps the obligation longer than a
      * single-shot clause needs. A clause that resumes more than once without it is refused at the bracket it
      * re-enters.
      */
    @nowarn("msg=anonymous")
    inline def handleContRepeated[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
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
                        override def repeated        = true

                new Pending.HandleArrow[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleContRepeated

    /** Handles an arrow effect with a clause that may resume more than once, with a recover arm.
      *
      * As [[handleContRepeated]], except a failure of the handled computation is offered to recover, exactly as in the recovering
      * [[handleCont]]. Without this overload a clause needing both would have to drop to [[handleCont]] for the recovery and lose the
      * holding, which is not a convenience but a change in when the regions it dumps into the continuation discharge.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @param done
      *   The function to transform the final result
      * @param recover
      *   The function offered a failure of the handled computation
      * @return
      *   The computation result with the function implementation provided
      */
    @nowarn("msg=anonymous")
    inline def handleContRepeated[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
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
                            def done(state: Unit, v1: A)                     = onDone(v1)
                            override def recover(state: Unit, ex: Throwable) = onRecover(ex)
                            override def repeated                            = true

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
    end handleContRepeated

    /** Handles an arrow effect by providing a handler function implementation, with a recover arm.
      *
      * Like the variant without recover, except a failure of the handled computation is offered to recover, which may answer with a
      * replacement result or decline, letting the failure propagate.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @param done
      *   The function to transform the final result
      * @param recover
      *   The function offered a failure of the handled computation
      * @return
      *   The computation result with the function implementation provided
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

    /** Handles an arrow effect with a loop-based approach.
      *
      * Differs from handleCont in two ways:
      *   1. The clause receives only the operation's input; the continuation is resumed through the Loop.Outcome it answers with
      *   2. Control flow goes through the Loop abstraction, to continue or terminate processing
      *
      * Use it when handling needs new effects but no state between effect occurrences.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    /** Handles an arrow effect with a loop-based approach and custom completion handling.
      *
      * Differs from handleCont in two ways:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @param done
      *   The function to transform the final result
      * @return
      *   The computation result with the function implementation provided
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
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @param done
      *   The function to transform the final result
      * @param recover
      *   The function offered a failure of the handled computation
      * @return
      *   The computation result with the function implementation provided
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

    /** Handles an arrow effect with stateful loop-based approach for maximum flexibility.
      *
      * This most powerful variant combines three key capabilities:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *   3. It maintains state between effect occurrences through the State type
      *
      * The stateful handleLoop should be used when you need the full range of capabilities: introducing new effects during handling,
      * maintaining state between occurrences, and controlling when to terminate processing.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    /** Handles an arrow effect with stateful loop-based approach and custom completion handling.
      *
      * This specialized variant of handleLoop provides maximum flexibility with three key capabilities:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It maintains state between effect occurrences through the State type
      *   3. It allows custom transformation of the final state and result via the done function
      *
      * The key advantage of this variant is the separate done function, which gives precise control over how the final state and result are
      * transformed when the loop completes. This is particularly useful when the final result needs different handling from intermediate
      * steps.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation for each iteration
      * @param done
      *   The function to transform the final state and result
      * @return
      *   The computation result with the function implementation provided
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
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation for each iteration
      * @param done
      *   The function to transform the final state and result
      * @param recover
      *   The function offered the state and a failure of the handled computation
      * @return
      *   The computation result with the function implementation provided
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

    /** handleCont with a continuation fused into the region node, avoiding a separate map node.
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

    /** handleLoop with a continuation fused into the region node, avoiding a separate map node.
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

    /** handleLoopState with a continuation fused into the region node, avoiding a separate map node.
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

    // Re-raises each masked request under a Mask tag so an enclosing handler cannot see it. `S` is any effect,
    // not only an arrow one: the masking region shadows its tag in the context as well as on the stack, so a
    // context read reaches the same clause an arrow operation does.
    sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

    object Mask:

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

        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                [C] => (input, cont) => input.map(cont(_))
            }
    end Mask

    // The first suspension is carried out of the region as a value: handleFirst is a handleCont whose done
    // arm answers it. FirstSuspended appears in that inline body, so both are private[kyo].
    abstract private[kyo] class FirstSuspended[I[_], O[_], E <: ArrowEffect[I, O], A, S]:
        type C
        def input: I[C]
        def cont: Arrow[O[C], A, E & S]
    end FirstSuspended

    /** Handles the first occurrence of an arrow effect and transforms the final result. This is useful when you want to handle just the
      * first instance of an effect and transform its result into a different type, while leaving any subsequent occurrences of the effect
      * unhandled.
      *
      * The continuation handed to `handle` is the remainder of `v`, carrying every region that sat between this handler and the
      * operation, a bracket included. Those regions are re-installed when the holder resumes the continuation, so a bracket inside the
      * remainder releases once, when the remainder completes. A remainder that is never resumed releases with the discard outcome at the
      * exit of the scope enclosing this handler, or at the end of the evaluation. A remainder resumed a second time is refused at the
      * bracket it re-enters.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation containing the effect to handle
      * @param handle
      *   Function to handle the first occurrence of the effect and transform its result
      * @param done
      *   Function to transform the final result if no effect is found
      * @return
      *   The transformed computation result
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](inline effectTag: Tag[E], v: A < (E & S))(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        type First = FirstSuspended[I, O, E, A, E & S]
        def onDone(r: A | First): B < (S & S2) =
            r match
                case first: First @unchecked => handle[first.C](first.input, first.cont)
                case a                       => done(a.asInstanceOf[A])
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A | First, B, S & S2]:
                        def tag = effectTag
                        def run[X](input0: I[X], cont0: Arrow[O[X], A | First, E & S & S2]) =
                            new FirstSuspended[I, O, E, A, E & S]:
                                type C = X
                                def input = input0
                                def cont  = cont0.asInstanceOf[Arrow[O[X], A, E & S]]
                        def done(state: Unit, r: A | First) = onDone(r)
                        // the token carries the region's continuation out: what the region owes at its exit
                        // belongs to the scope below until the holder resumes or drops the remainder
                        override def escaping = true

                new Pending.HandleArrow[Unit, E, A | First, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => done(Nested.unnest(v))
        end match
    end handleFirst

    /** [[handleFirst]] for a clause that applies the continuation it peels more than once.
      *
      * The remainder is handed out as usual and held across every application, so a bracket travelling with it is not
      * released by whichever application finishes first. `Choice.runStream` is the shape: it applies the peeled
      * continuation once per branch and evaluates the results outside the clause.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirstRepeated[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        type First = FirstSuspended[I, O, E, A, E & S]
        def onDone(r: A | First): B < (S & S2) =
            r match
                case first: First @unchecked => handle[first.C](first.input, first.cont)
                case a                       => done(a.asInstanceOf[A])
        v match
            case _: Pending[?, ?] =>
                val h =
                    new Handler.ContHandler[I, O, E, A | First, B, S & S2]:
                        def tag = effectTag
                        def run[X](input0: I[X], cont0: Arrow[O[X], A | First, E & S & S2]) =
                            new FirstSuspended[I, O, E, A, E & S]:
                                type C = X
                                def input = input0
                                def cont  = cont0.asInstanceOf[Arrow[O[X], A, E & S]]
                        def done(state: Unit, r: A | First) = onDone(r)
                        // as handleFirst, plus held: the holder applies the remainder more than once, so what
                        // the region owes must survive each application, not be settled by the first
                        override def escaping = true
                        override def repeated = true

                new Pending.HandleArrow[Unit, E, A | First, B, B, S & S2]:
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

    /** Handles an arrow effect by providing a handler that receives the suspended operation itself; the result is the handled
      * computation's value.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide, given the operation and the continuation
      * @return
      *   The computation result with the function implementation provided
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
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide, given the operation and the continuation
      * @param done
      *   The function to transform the final result
      * @return
      *   The computation result with the function implementation provided
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
