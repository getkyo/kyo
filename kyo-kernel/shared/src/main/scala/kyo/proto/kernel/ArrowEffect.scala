package kyo.proto.kernel

import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
import kyo.proto.Loop
import kyo.proto.kernel.internal.Handler.HandlerCont
import kyo.proto.kernel.internal.Handler.HandlerLoop
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import scala.annotation.nowarn

/** Represents abstract functions whose implementations are provided later by a handler.
  *
  * ArrowEffect captures the shape of a function without specifying its implementation. It describes a transformation from Input[A] to
  * Output[A] for any type A, but defers how that transformation actually happens until a handler interprets it.
  *
  * ArrowEffect supports multi-shot continuations, meaning that handlers can invoke the continuation function multiple times or not at all.
  *
  * ArrowEffect provides two main kinds of handling methods with distinct capabilities:
  *   - handleCont: Handler with the continuation in hand, free to apply it any number of times
  *   - handleLoop: Handler that answers occurrences through the Loop abstraction:
  *     - Without state: When you need to answer occurrences without state between them
  *     - With state (handleLoopState): When you need state maintenance between occurrences
  */
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    /** Creates a suspended computation that requests a function implementation from an arrow effect.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param effectInput
      *   The input value to be transformed by the function
      * @return
      *   A computation that will receive the requested function when executed
      */
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =
        Kyo.SuspendArrow[I, O, E, C, O[C], E](effectTag, effectInput, Arrow.id)

    /** Creates a suspended computation that requests a function implementation and transforms its result immediately upon receipt.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param effectInput
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
        // one allocation fulfilling both roles: the node is the suspension and its own continuation
        new Kyo.SuspendArrow[I, O, E, C, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = this
            override def apply[D, S2](v: Any < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Arrow[Any, O[C], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                    => cont2(f(Nested.unnest[O[C]](v)), Arrow.id)

    // The row parameters come in pairs on every region combinator: S is the body's row, pinned when
    // `v` is typed, and S2 is a free variable for whatever the clause adds beyond it. With a single
    // row the typer commits it to the body's row before the clause is seen, so a clause that
    // introduces its own effect cannot widen it and the site needs explicit instantiation. The
    // internals do not know about the pair: they instantiate at S & S2.

    /** Handles an arrow effect by providing a handler function implementation, with the continuation in hand.
      *
      * Each effect occurrence is processed with its captured continuation, which the handler is free to apply any number of times or not at
      * all, and a done clause transforms the region's final value.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @param done
      *   Function to transform the final result
      * @return
      *   The computation result with the function implementation provided
      */
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
            case _: Arrow[?, ?, ?] =>
                Kyo.handle[E, A, B, S & S2, Unit](
                    v,
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X, C, S3](input: I[X], cont: Arrow[O[X], A, E & S & S2], k: Arrow[A, C, S3]) =
                            handle[X](input, cont).chain(k)
                        def done(state: Unit, v0: A) = onDone(v0)
                    ,
                    ()
                )
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

    /** Handles an arrow effect with a loop-based approach, without state between occurrences.
      *
      * Answers each occurrence directly, without materializing the continuation, and provides control flow through the Loop abstraction to
      * continue or terminate processing. The continue outcome's first slot is the unit state of the underlying stateful region.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome2 for each iteration
      * @param done
      *   Function to transform the final result
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

    /** [[handleLoop]] with the region's value as the result. */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    /** Handles an arrow effect with a stateful loop-based approach for maximum flexibility.
      *
      * Combines the loop-based answering with state maintenance between effect occurrences through the State type.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome2 for each iteration
      * @param done
      *   Function to transform the final result, receiving the final state
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
            case _: Arrow[?, ?, ?] =>
                Kyo.handle[E, A, B, S & S2, State](
                    v,
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag                            = effectTag
                        def run[X](st: State, input: I[X]) = handle[X](st, input)
                        def done(st: State, v0: A)         = onDone(st, v0)
                    ,
                    state
                )
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** The stateful region without a done clause: it completes with the body's own result and discards the final state. */
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    /** [[handleCont]] with the transform that follows the region fused into the region node itself. */
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
            case _: Arrow[?, ?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X, C2, S4](input: I[X], cont: Arrow[O[X], A, E & S & S2], k: Arrow[A, C2, S4]) =
                            handle[X](input, cont).chain(k)
                        def done(state: Unit, v0: A) = onDone(v0)
                // one allocation fulfilling both roles: the region and the transform that follows it
                new Kyo.Handle[E, A, B, C, S & S2 & S3, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = this
                    override def apply[D, S4](b: Any < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Arrow[Any, B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                                 => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(Nested.unnest(v)).map(onF)
        end match
    end handleContWith

    /** [[handleLoop]] with the transform that follows the region fused into the region node itself. */
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

    /** [[handleLoopState]] with the transform that follows the region fused into the region node itself. */
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
            case _: Arrow[?, ?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag                            = effectTag
                        def run[X](st: State, input: I[X]) = handle[X](st, input)
                        def done(st: State, v0: A)         = onDone(st, v0)
                // one allocation fulfilling both roles: the region and the transform that follows it
                new Kyo.Handle[E, A, B, C, S & S2 & S3, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = this
                    override def apply[D, S4](b: Any < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Arrow[Any, B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                                 => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(state0, Nested.unnest(v)).map(onF)
        end match
    end handleLoopStateWith

end ArrowEffect
