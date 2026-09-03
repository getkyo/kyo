package kyo.kernel

import kyo.Frame
import kyo.Id
import kyo.Maybe
import kyo.Tag
import kyo.kernel.Arrow
import kyo.kernel.Arrow.Transform
import kyo.kernel.Loop
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Handler
import kyo.kernel.internal.Handler.ContHandler
import kyo.kernel.internal.Handler.ContOpHandler
import kyo.kernel.internal.Handler.LoopHandler
import kyo.kernel.internal.Nested
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
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
  *   - handle: Basic handler that doesn't allow introducing new effects during handling
  *   - handleLoop: Enhanced handler that explicitly allows introducing new effects (via S2 type parameter) during handling:
  *     - Without state: When you need to add effects but not state between occurrences
  *     - With state: When you need both new effects and state maintenance between occurrences
  *
  * When defining concrete effects, ArrowEffect is commonly used with two special type constructors: Const and Id. The Const[X] type
  * constructor ignores its type parameter and always returns X, while Id[X] simply returns X unchanged. For instance, an effect that needs
  * to fail with errors of type E would use Const[E] as its input type - it only needs the error value itself, not any type parameters.
  * Similarly, an effect for making choices among values would use Id as its output type - it passes through the chosen value unchanged.
  */
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

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
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =

        new Pending.SuspendArrow[I, O, E, C, O[C], E]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
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
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =

        new Pending.SuspendArrowWith[I, O, E, C, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = this
            override def apply[D, S2](v: O[C] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[C], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[C]](v)), Arrow.id)

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new ContHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.Handle[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2)                   = done(v0)
        def onRecover(ex: Throwable): Maybe[B < (S & S2)] = recover(ex)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new ContHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A)                     = onDone(v0)
                        override def recover(state: Unit, ex: Throwable) = onRecover(ex)

                new Pending.Handle[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ =>

                try onDone(Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end match
    end handleCont

    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCont(effectTag, v)(handle, a => a)

    @nowarn("msg=anonymous")
    inline def handleContOperation[E <: ArrowEffect[?, ?], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new ContOpHandler[E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](operation: X < E, next: Arrow[X, A, E & S & S2]) =
                            handle[X](operation, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.Handle[Unit, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleContOperation

    inline def handleContOperation[E <: ArrowEffect[?, ?], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleContOperation(effectTag, v)(handle, a => a)

    abstract private[kyo] class FirstSuspended[I[_], O[_], E <: ArrowEffect[I, O], A, S]:
        type C
        def input: I[C]
        def cont: Arrow[O[C], A, E & S]
    end FirstSuspended

    /** Handles the first occurrence of an arrow effect and transforms the final result. This is useful when you want to handle just the
      * first instance of an effect and transform its result into a different type, while leaving any subsequent occurrences of the effect
      * unhandled.
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
    private[kyo] inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        handleCont[I, O, E, A | FirstSuspended[I, O, E, A, E & S], B, S, S2](effectTag, v)(
            [C0] =>
                (input0, cont0) =>
                    new FirstSuspended[I, O, E, A, E & S]:
                        type C = C0
                        def input = input0

                        def cont = cont0.asInstanceOf[Arrow[O[C0], A, E & S]]
            ,
            r =>
                r match
                    case first: FirstSuspended[I, O, E, A, E & S] @unchecked =>
                        handle[first.C](first.input, first.cont)
                    case a =>

                        done(a.asInstanceOf[A])
        )

    /** Inspects the head suspension of `v`. If it matches `effectTag`, invokes `f` with the suspension's input;
      * otherwise does nothing. Unlike [[handleFirst]] this never enters the Safepoint, never executes the
      * continuation, and never schedules a continuation. It is intended for purely-inspecting handlers that
      * read the input as a value and produce side effects directly (e.g. registering an interrupt cascade
      * link). Used by `IOTask.ensureInterrupt` to walk a stalled `curr` after the fiber's promise has already
      * been completed (e.g. by an interrupt), so the Safepoint preempt flag would otherwise short-circuit the
      * walk.
      */
    private[kyo] def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        f: [C] => I[C] => Unit
    ): Unit =
        @tailrec def loop(x: Any): Unit =
            x match
                case kyo: Pending.SuspendArrow[I, O, E, c, ?, ?] @unchecked =>

                    if effectTag.erased <:< kyo.tag.erased then f[c](kyo.input)
                case kyo: Pending.Handle[?, ?, ?, ?, ?, ?] => loop(kyo.value)
                case kyo: Pending.Park[?, ?]               => loop(kyo.value)
                case kyo: Pending.Defer[?, ?, ?, ?]        => loop(kyo.value)
                case _                                     => ()
        loop(v)
    end dispatchFirst

    /** Handles an arrow effect with a loop-based approach for greater flexibility.
      *
      * This variant provides two key advantages over basic handle:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
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
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        handleLoopState[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [C] => (st: Unit, input: I[C]) => handle[C](input),
            (st, v0) => done(v0)
        )

    /** Handles an arrow effect with a loop-based approach for greater flexibility.
      *
      * This variant provides two key advantages over basic handle:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
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
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        handleLoopState[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [C] => (st: Unit, input: I[C]) => handle[C](input),
            (st, v0) => done(v0),
            (st, ex) => recover(ex)
        )

    /** Handles an arrow effect with a loop-based approach for greater flexibility.
      *
      * This variant provides two key advantages over basic handle:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
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
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

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
                    new LoopHandler[State, I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        override def answers[X](
                            state0: State,
                            input0: I[X],
                            k0: Arrow[Any, Any, Any],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome2[State, Any, B < (S & S2)] < (S & S2) =
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

                new Pending.Handle[State, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2),
        inline recover: (State, Throwable) => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(st: State, v0: A): B < (S & S2)                   = done(st, v0)
        def onRecover(st: State, ex: Throwable): Maybe[B < (S & S2)] = recover(st, ex)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new LoopHandler[State, I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        override def answers[X](
                            state0: State,
                            input0: I[X],
                            k0: Arrow[Any, Any, Any],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome2[State, Any, B < (S & S2)] < (S & S2) =
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

                new Pending.Handle[State, E, A, B, B, S & S2]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ =>

                try onDone(state, Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(state, ex).getOrElse(throw ex)
        end match
    end handleLoopState

    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

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
        def onDone(v0: A): B < (S & S2) = done(v0)
        def onF(v0: B): C < S3          = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new ContHandler[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Pending.HandleWith[Unit, E, A, B, C, S & S2 & S3]:
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

    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome2[Unit, O[X] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        handleLoopStateWith[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [X] => (st: Unit, input: I[X]) => handle[X](input),
            (st, v0) => done(v0)
        )(f)

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
                    new LoopHandler[State, I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        override def answers[X](
                            state0: State,
                            input0: I[X],
                            k0: Arrow[Any, Any, Any],
                            armed: Boolean,
                            slot: Safepoint.Slot,
                            frame: Frame
                        ): Loop.Outcome2[State, Any, B < (S & S2)] < (S & S2) =
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

                new Pending.HandleWith[State, E, A, B, C, S & S2 & S3]:
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

    sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

    object Mask:

        def apply[E](using
            Frame
        )[E2 >: E <: ArrowEffect[?, ?], A, S](v: A < (E2 & S))(
            using
            tag: Tag[E2],
            maskTag: Tag[Mask[E]]
        ): A < (Mask[E] & S) =
            handleContOperation(tag, v) {

                [X] => (operation, cont) => suspend[X](maskTag, operation).map(cont(_))
            }

        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                [C] => (input, cont) => input.map(cont(_))
            }
    end Mask

end ArrowEffect
