package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Represents abstract functions whose implementations are provided later by a handler.
  *
  * ArrowEffect captures the shape of a function without specifying its implementation. It describes a transformation from Input[A] to
  * Output[A] for any type A, but defers how that transformation actually happens until a handler interprets it. This makes it a powerful
  * way to write code that is abstract over how its operations are performed.
  *
  * ArrowEffect supports multi-shot continuations, meaning that handlers can invoke the continuation function multiple times or not at all.
  * This enables powerful control flow effects like backtracking, non-determinism, or early returns. For example, a choice effect could
  * invoke its continuation multiple times with different values to explore multiple execution paths.
  *
  * The type parameters Input[_] and Output[_] define the "shape" of the function being abstracted:
  *
  * @tparam Input
  *   The input type constructor - what arguments the function takes
  * @tparam Output
  *   The output type constructor - what results the function produces
  *
  * Every use of an ArrowEffect creates a suspended function call. This suspended call contains all the information needed to perform the
  * operation, but doesn't specify how to perform it.
  *
  * A handler then provides the actual function implementation that determines what happens when that suspended call is executed. Each
  * handler takes two parameters: an input value of type I[C] that contains the input of the operation, and a continuation function
  * representing the remainder of the computation from the point where the effect was suspended to the point where it's being handled.
  *
  * ArrowEffect provides two main kinds of handling methods with distinct capabilities:
  *   - handleCont: Basic handler that receives the continuation as an Arrow and may introduce new effects (via S2 type parameter)
  *   - handleLoop: Enhanced handler that resumes the continuation through the Loop abstraction, to continue or terminate processing:
  *     - Without state (handleLoop): When you need loop control but not state between occurrences
  *     - With state (handleLoopState): When you need both loop control and state maintenance between occurrences
  *
  * When defining concrete effects, ArrowEffect is commonly used with two special type constructors: Const and Id. The Const[X] type
  * constructor ignores its type parameter and always returns X, while Id[X] simply returns X unchanged. For instance, an effect that needs
  * to fail with errors of type E would use Const[E] as its input type - it only needs the error value itself, not any type parameters.
  * Similarly, an effect for making choices among values would use Id as its output type - it passes through the chosen value unchanged.
  */
abstract class ArrowEffect[-Input[_], +Output[_]] extends Effect

object ArrowEffect:

    // Diverges from main: suspensions and handled regions are arrow nodes (Pending.SuspendArrow,
    // Pending.HandleArrow and the Handler instances built below) interpreted by Eval, where main
    // built KyoSuspend/KyoContinue chains that every handler walked itself.

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

    // Diverges from main: main's `handle` is `handleCont` here, the clause receives the continuation
    // as an Arrow and may introduce effects through S2, and the overloads below add `done` and
    // `recover` (D2). Main's two, three and four effect `handle` overloads, its `handleCatching` and
    // its `handlePartial` are gone.

    /** Handles an arrow effect by providing a handler function implementation.
      *
      * This is the basic form of effect handling where each effect occurrence is processed independently: the clause receives the
      * operation's input and the continuation from the suspension point, and answers by applying the continuation, possibly several times
      * or not at all. New effects may be introduced during handling through the S2 type parameter.
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
      * Like the variant without done, with one addition: when the handled computation completes, done transforms its result.
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
      * The same as [[handleCont]] except that the region holds the regions it dumps into the continuation it hands the
      * clause. A region that discharges exactly once, a bracket's release, would otherwise fire when the first
      * resumption ends its extent, leaving every later resumption running against something already released. Held, it
      * is discharged where this region ends instead, once, after every resumption has run.
      *
      * Only a clause that really does resume more than once should use this: holding keeps the obligation longer than
      * the clause does, so a single-shot handler would release later than it needs to for nothing. A clause that
      * resumes more than once without using this is refused at the bracket it re-enters, with a message saying so.
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

    /** Handles an arrow effect by providing a handler function implementation, with a recover arm.
      *
      * Like the variant without recover, with one addition: when the handled computation fails, recover is offered the failure and may
      * answer with a replacement result; when it declines the failure propagates.
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
        // the input is forced under the recovery clause, as main's handleCatching forces its by-name
        // input: a throw while building the computation is the region's to answer, like one raised
        // in its extent
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
            case ex if NonFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end try
    end handleCont

    // Diverges from main: the clause is handed only the operation's input, the continuation reaching it
    // through the Loop.Outcome2 it answers with, and the overloads below add `done` and `recover` (D2).

    /** Handles an arrow effect with a loop-based approach for greater flexibility.
      *
      * This variant differs from basic handleCont in two ways:
      *   1. The clause receives only the operation's input; the continuation is resumed through the Loop.Outcome2 it answers with
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *
      * This non-stateful handleLoop is ideal when you need to perform effectful operations with access to new effects during handling, but
      * don't need to maintain state between effect occurrences.
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
      * This variant provides two key advantages over handleCont:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *
      * The separate done function gives precise control over how the final result is transformed when the loop completes.
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
      * Like the variant without recover, with one addition: when the handled computation fails, recover is offered the failure and may
      * answer with a replacement result; when it declines the failure propagates.
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
            case ex if NonFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end try
    end handleLoop

    // Diverges from main: main's stateful `handleLoop` overloads are `handleLoopState` here, and the
    // last overload adds `recover` (D2).

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
      * Like the variant without recover, with one addition: when the handled computation fails, recover is offered the current state and
      * the failure and may answer with a replacement result; when it declines the failure propagates.
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
        // the input is forced under the recovery clause, as in the recovering handleCont; a throw
        // there sees the initial state, the only one the region has had
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
            case ex if NonFatal(ex) => onRecover(state, ex).getOrElse(throw ex)
        end try
    end handleLoopState

    // Not on main: the `With` variants fuse the continuation into the region node, so the region's
    // result flows into it without allocating a separate map node.

    /** handleCont with a continuation fused into the region node: the region's result flows into f without a separate map node.
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

    /** handleLoop with a continuation fused into the region node: the region's result flows into f without a separate map node.
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

    /** handleLoopState with a continuation fused into the region node: the region's result flows into f without a separate map node.
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

    // Not on main: Mask lives in this object (D5), re-raising each masked request under a Mask tag so an
    // enclosing handler cannot see it. `S` is any effect, not only an arrow one: the masking region shadows
    // its tag in the context as well as holding it on the stack, so a context read reaches the same clause an
    // arrow operation does. One mask, both kinds, including an intersection of the two.
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

    // Not on main: the first suspension is carried out of the region as a value, so handleFirst is a
    // handleCont whose done arm answers it. FirstSuspended appears in that inline body, which is why
    // both it and handleFirst are private[kyo].
    abstract private[kyo] class FirstSuspended[I[_], O[_], E <: ArrowEffect[I, O], A, S]:
        type C
        def input: I[C]
        def cont: Arrow[O[C], A, E & S]
    end FirstSuspended

    /** Handles the first occurrence of an arrow effect and transforms the final result. This is useful when you want to handle just the
      * first instance of an effect and transform its result into a different type, while leaving any subsequent occurrences of the effect
      * unhandled.
      *
      * The continuation handed to `handle` is the remainder of `v`, and it carries every region that sat between this handler and the
      * operation, a bracket included. Those regions are re-installed when the holder resumes the continuation, so a bracket inside the
      * remainder releases when the remainder completes, once. A remainder that is never resumed releases at the exit of the scope
      * enclosing this handler, or at the end of the evaluation, with the discard outcome. A remainder resumed a second time is refused
      * at the bracket it re-enters.
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
                        // the token carries the region's continuation out, so what the region owes at
                        // its exit is not orphaned: it belongs to the scope below until the holder
                        // resumes or drops the remainder
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
      * The remainder is handed out as usual and, on top of that, held across every application, so a bracket
      * travelling with it is not released by whichever application finishes first. `Choice.runStream` is the shape: it
      * applies the peeled continuation once per branch and drives the results outside the clause.
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
                        // as handleFirst, and held on top of that: the holder applies the remainder more
                        // than once, so what the region owes belongs to the scope below AND must survive
                        // each application rather than being settled by the first
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

    // Not on main: the clause is handed the suspended operation itself instead of its input, which is
    // what lets Mask re-suspend an operation it cannot inspect.

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
