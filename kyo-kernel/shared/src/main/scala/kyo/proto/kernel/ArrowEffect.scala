package kyo.proto.kernel

import kyo.Frame
import kyo.Id
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
import kyo.proto.Loop
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Handler.HandlerCont
import kyo.proto.kernel.internal.Handler.HandlerContOperation
import kyo.proto.kernel.internal.Handler.HandlerLoop
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

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
    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =
        // built at the site rather than through the companion: the identity continuation is a
        // constant, not a captured field
        new Kyo.SuspendArrow[I, O, E, C, O[C], E]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = Arrow.id

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
        new Kyo.SuspendArrowTransform[I, O, E, C, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = this
            override def apply[D, S2](v: O[C] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[C], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[C]](v)), Arrow.id)

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
            case _: Pending[?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def answer[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)
                // the region node is built at the site: the unit state and the identity continuation
                // are constants, not captured fields
                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    /** [[handleCont]] with a recovery clause.
      *
      * `recover` is consulted when a NonFatal throw unwinds the region's extent, the done clause included: a Present computation replaces
      * the region's outcome, and Absent declines so the failure keeps unwinding through the enclosing regions. A recover that fails itself
      * is the failure those enclosing regions then see.
      */
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
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def answer[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A)                     = onDone(v0)
                        override def recover(state: Unit, ex: Throwable) = onRecover(ex)
                // the region node is built at the site: the unit state and the identity continuation
                // are constants, not captured fields
                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ =>
                // the settled fast path specializes the region's law, recover included: `done` runs
                // with the region installed there, so a throw in it reaches the region's recover,
                // and it must reach this one too
                try onDone(Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(ex).getOrElse(throw ex)
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

    /** [[handleCont]] whose clause receives the answered operation reified as a value.
      *
      * A region answers an operation when the region's tag is a subtype of the operation's, so a region over an intersection answers
      * several effects with one clause and the clause alone cannot tell which effect an operation belongs to. The reified value carries
      * the operation's own tag, so evaluating it elsewhere re-raises the operation at its true identity, answerable by that effect's own
      * handler. `X` is the operation's answer type and nothing else about its shape leaks, which is what lets `E` range over
      * intersections: no input or output structure is named, so none has to be common.
      */
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
                    new HandlerContOperation[E, A, B, S & S2]:
                        def tag = effectTag
                        def answer[X](operation: X < E, next: Arrow[X, A, E & S & S2]) =
                            handle[X](operation, next)
                        def done(state: Unit, v0: A) = onDone(v0)
                // the region node is built at the site: the unit state and the identity continuation
                // are constants, not captured fields
                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleContOperation

    /** [[handleContOperation]] with the region's value as the result. */
    inline def handleContOperation[E <: ArrowEffect[?, ?], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleContOperation(effectTag, v)(handle, a => a)

    /** The one-shot region's exit token: the clause stores the operation and the raw remainder, and returning it settled is what completes
      * the region at the first operation. It never escapes [[handleFirst]]'s expansion: the done lane unwraps it before anything else sees
      * the value. Abstract so each expansion implements it anonymously, and carrying the operation's index as a type member, so both fields
      * keep their real types.
      */
    abstract private[kyo] class FirstSuspended[I[_], O[_], E <: ArrowEffect[I, O], A, S]:
        type C
        def input: I[C]
        def cont: Arrow[O[C], A, E & S]
    end FirstSuspended

    /** Answers only the first operation of the region, handing the clause the raw remainder.
      *
      * The clause receives the operation's input and the continuation with the effect still in its row: nothing has been decided about the
      * rest of the computation, and a consumer re-handles the remainder with a fresh region round after round. A body that completes
      * without performing the effect takes `done` instead. Both lanes run outside the region.
      *
      * Built on [[handleCont]] at the union of the region's two completion currencies: the clause completes the region at once by returning
      * the settled token, and the done lane tells the arms apart. The body enters the region unchanged, so the remainder is the body's own
      * continuation, raw.
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
                        // Representation assertion: the region settles the moment this token is
                        // returned, so no application of the continuation can observe the token arm
                        // of its answer type, and the remainder is body code whose row never
                        // carried S2
                        def cont = cont0.asInstanceOf[Arrow[O[C0], A, E & S]]
            ,
            r =>
                r match
                    case first: FirstSuspended[I, O, E, A, E & S] @unchecked =>
                        handle[first.C](first.input, first.cont)
                    case a =>
                        // the union's other arm; erasure and the abstract A keep the narrowing from
                        // being inferred once the token case took its own
                        done(a.asInstanceOf[A])
        )

    /** Whether an operation this tag's own region would answer stands first, delivered to `f` without disturbing anything.
      *
      * Sees through what stands in front of an operation without being one: installed regions, a parked slice, and deferrals whose payload
      * was handed in. It stops at a settled value, which stands at nothing, and at a deferral whose payload is a by-name body, which cannot
      * be read without running it, so the answer is advisory: a miss never means the operation is absent, only that it is not visible, and
      * every caller must tolerate that.
      *
      * The check runs in the dispatch direction: would a region tagged `effectTag` answer this operation. An intersection query therefore
      * sees each member's operations, the way one region over the intersection answers them.
      */
    private[kyo] def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        f: [C] => I[C] => Unit
    ): Unit =
        @tailrec def loop(x: Any): Unit =
            x match
                case kyo: Kyo.SuspendArrow[I, O, E, c, ?, ?] @unchecked =>
                    // the type claim is erased and the tag check is the semantic guard: a foreign
                    // operation matches the pattern and fails the check, which is the answer
                    if effectTag.erased <:< kyo.tag.erased then f[c](kyo.input)
                case kyo: Kyo.Handle[?, ?, ?, ?, ?, ?] => loop(kyo.value)
                case kyo: Kyo.Park[?, ?]               => loop(kyo.value)
                case kyo: Kyo.Defer[?, ?, ?, ?]        => loop(kyo.value)
                case _                                 => ()
        loop(v)
    end dispatchFirst

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

    /** [[handleLoop]] with a recovery clause, see the recovering [[handleCont]]. */
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
            case _: Pending[?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def answer[X](st: State, input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Eval.answerLoop(this, handle[X](st, input), next)
                        def done(st: State, v0: A) = onDone(st, v0)
                val state0 = state
                // the region node is built at the site: the identity continuation is a constant,
                // not a captured field
                new Kyo.Handle[E, A, B, B, S & S2, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    /** [[handleLoopState]] with a recovery clause, see the recovering [[handleCont]].
      *
      * `recover` receives the live state, the one the clauses have threaded rather than the one the region was installed with, which is
      * what lets a registry carried in the loop state drain on failure.
      */
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
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def answer[X](st: State, input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Eval.answerLoop(this, handle[X](st, input), next)
                        def done(st: State, v0: A)                     = onDone(st, v0)
                        override def recover(st: State, ex: Throwable) = onRecover(st, ex)
                val state0 = state
                // the region node is built at the site: the identity continuation is a constant,
                // not a captured field
                new Kyo.Handle[E, A, B, B, S & S2, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ =>
                // the settled fast path specializes the region's law, recover included: `done` runs
                // with the region installed there, so a throw in it reaches the region's recover,
                // and it must reach this one too
                try onDone(state, Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(state, ex).getOrElse(throw ex)
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
            case _: Pending[?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def answer[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)
                // one allocation fulfilling both roles: the region and the transform that follows it
                new Kyo.HandleTransform[E, A, B, C, S & S2 & S3, Unit]:
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
            case _: Pending[?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def answer[X](st: State, input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            Eval.answerLoop(this, handle[X](st, input), next)
                        def done(st: State, v0: A) = onDone(st, v0)
                // one allocation fulfilling both roles: the region and the transform that follows it
                new Kyo.HandleTransform[E, A, B, C, S & S2 & S3, State]:
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

    /** Hides an effect's operations from the handlers between the mask and its [[Mask.run]] boundary.
      *
      * `Mask[E](v)` translates each operation of `E` in `v` into a `Mask[E]` operation carrying the original as an unevaluated payload. The
      * computation keeps evaluating in place, and every other effect in its row stays visible to local handlers; only `E`'s operations
      * tunnel out to the enclosing [[Mask.run]], where each payload re-raises `E` for the handlers outside that boundary and its answer
      * flows back into the masked computation.
      *
      * This masks effect operations, never interruptions, which is what the nesting under `ArrowEffect` says: libraries in this space
      * commonly name interruption masking `mask`, and a top-level `kyo.Mask` would read as that.
      */
    sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

    object Mask:

        /** Masks the effect `E` in `v`, where `E` may be one effect or an intersection of several.
          *
          * Handlers for `E` between this call and [[run]] see none of `v`'s `E` operations; handlers for every other effect in the row are
          * unaffected. The effect to mask is named explicitly, `Mask[Ask](v)` or `Mask[Ask & Say](v)`: an intersection-tagged region
          * answers each member's operations, so one mask covers them all.
          *
          * The mask's own suspension is tagged at the named `E` on both ends, so [[run]] named the same way lands the tunnel by
          * construction. Each payload is re-raised at the caught operation's own tag, so an `Ask` operation re-emerges at [[run]] as an
          * `Ask` operation and the specific effect's handler outside answers it.
          */
        def apply[E](using
            Frame
        )[E2 >: E <: ArrowEffect[?, ?], A, S](v: A < (E2 & S))(
            using
            tag: Tag[E2],
            maskTag: Tag[Mask[E]]
        ): A < (Mask[E] & S) =
            handleContOperation(tag, v) {
                // the payload is the operation itself, carrying its own tag, and it conforms to the
                // mask's `A < E` input by row contravariance: `E2 >: E`, and the `E` row is honest
                // because a handler at `E` answers `E2`-tagged operations under the dispatch direction
                [X] => (operation, cont) => suspend[X](maskTag, operation).map(cont(_))
            }

        /** Unmasks: evaluates each masked operation at this boundary, re-exposing `S` to the handlers outside it. */
        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                [C] => (input, cont) => input.map(cont(_))
            }
    end Mask

end ArrowEffect
