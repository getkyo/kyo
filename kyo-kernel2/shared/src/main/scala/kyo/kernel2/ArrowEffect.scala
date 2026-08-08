package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Handler
import scala.annotation.nowarn

/** An effect whose operations are functions awaiting implementation.
  *
  * An `ArrowEffect[I, O]` declares a family of operations indexed by a type `C`: each operation takes an input `I[C]` and produces an
  * output `O[C]`. Suspending an operation describes it as data; a handler interprets it, choosing per handle site how much control it
  * needs: answer in place, stop the computation, or receive the continuation as a first-class [[Arrow]].
  *
  * Effects that never resume declare it in their output type: an effect with output `Const[Nothing]` cannot be resumed by any handler,
  * whatever format the handler uses.
  *
  * @tparam I
  *   The operation input constructor
  * @tparam O
  *   The operation output constructor
  */
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    /** Suspends an operation of the effect `E`.
      *
      * Returns the operation as a pending computation. The operation runs when a handler for `E` interprets it.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    ): O[A] < E =
        val in = operationInput
        new Kyo.Suspend[I, O, E, A]:
            def input = in
            def tag   = effectTag
            def frame = _frame
        .asInstanceOf[O[A] < E]
    end suspend

    /** Suspends an operation and maps its output in one step. */
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    )(
        inline f: O[A] => B < S
    ): B < (E & S) =
        suspend[A](using _frame)[I, O, E](effectTag, operationInput).map(f)

    /** Handles `E` with the continuation as a function (the ctl format).
      *
      * The clause receives each operation's input and the continuation from the operation to this handler. The clause decides everything:
      * invoke the continuation once to resume, not at all to abort, or several times. The handler is deep: effects of `E` in the clause's
      * result, including through resumed continuations, dispatch back to this handler.
      *
      * Handling evaluates immediately: the region runs as far as it can, and the result is either its value or the computation parked on
      * an effect this handler does not cover, with the handler traveling in it.
      */
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        val clause: Handler.Clause =
            [C] =>
                (input, cont) =>
                    handle(
                        input.asInstanceOf[I[C]],
                        o => cont(Kyo.lift(o)).asInstanceOf[A < (E & S & S2)]
                    ).asInstanceOf[Any < Any]
        install(v, new Handler.Cont(effectTag.asInstanceOf[Tag[Any]], clause, frame))
    end handle

    /** Handles `E` by answering each operation in place (the fun format).
      *
      * The clause produces the operation's output; the kernel resumes the continuation exactly once with it. No continuation is exposed or
      * captured. The handler is deep.
      */
    def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => I[C] => O[C] < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        install(v, new Handler.Resume(effectTag.asInstanceOf[Tag[Any]], clause.asInstanceOf[Handler.InputClause], frame))

    /** Handles `E` by ending the region at each operation (the final ctl format).
      *
      * The clause produces the region's result directly; the continuation from the operation to this handler never runs. The handler is
      * deep: effects of `E` in the clause's result dispatch back to this handler.
      */
    def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => I[C] => A < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        install(v, new Handler.Stop(effectTag.asInstanceOf[Tag[Any]], clause.asInstanceOf[Handler.InputClause], frame))

    /** Handles only the first operation of `E`, shallowly.
      *
      * The clause receives the first operation's input and continuation and produces the final result; the handler is not reinstalled, so
      * later operations of `E` (including through the invoked continuation) are not handled by it. `done` transforms the region's value
      * when no operation occurs before completion.
      */
    def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
        done: A => B < S2
    )(using frame: Frame): B < (S & S2) =
        val clause: Handler.Clause =
            [C] =>
                (input, cont) =>
                    handle(
                        input.asInstanceOf[I[C]],
                        o => cont(Kyo.lift(o)).asInstanceOf[A < (E & S)]
                    ).asInstanceOf[Any < Any]
        install(
            v,
            new Handler.First(
                effectTag.asInstanceOf[Tag[Any]],
                clause,
                done.asInstanceOf[Any => Any < Any],
                frame
            )
        )
    end handleFirst

    /** Handles `E` without handler state.
      *
      * The clause decides per operation: `Loop.continue(next)` keeps handling `next` (typically the resumed continuation),
      * `Loop.done(result)` leaves the region, discarding the continuation. Effects raised while the outcome itself is computed dispatch to
      * outer handlers.
      */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2
    )(using frame: Frame): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, Unit](effectTag, (), v)(
            [C] =>
                (input, _, cont) =>
                    handle(input, cont).map {
                        case next: Loop.Continue[A < (E & S)] @unchecked => Loop.continue((), next._1)
                        case res                                         => res.asInstanceOf[Loop.Outcome2[Unit, A < (E & S), A]]
                },
            (_, a) => a
        )

    /** Handles `E` with handler state threaded through the operations, the region's value passing through unchanged. */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), A] < S2
    )(using frame: Frame): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, State](effectTag, state, v)(handle, (_, a) => a)

    /** Handles `E` with handler state and custom completion.
      *
      * The clause receives the operation's input, the current state, and the continuation, and decides: `Loop.continue(nextState, next)`
      * keeps handling `next` (typically the resumed continuation) with the new state, `Loop.done(result)` leaves the region with a final
      * result, discarding the continuation. `done` produces the result when the region completes normally, from the final state and value.
      * The handler is deep for computations passed through Continue; effects raised while the outcome itself is computed dispatch to outer
      * handlers.
      */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), B] < S2,
        done: (State, A) => B < (S & S2)
    )(using frame: Frame): B < (S & S2) =
        val clause: Handler.LoopClause =
            [C] =>
                (state, input, cont) =>
                    handle(
                        input.asInstanceOf[I[C]],
                        state.asInstanceOf[State],
                        o => cont(Kyo.lift(o)).asInstanceOf[A < (E & S)]
                    ).asInstanceOf[Any < Any]
        install(
            v,
            new Handler.Loop(
                effectTag.asInstanceOf[Tag[Any]],
                state,
                clause,
                done.asInstanceOf[(Any, Any) => Any < Any],
                frame
            )
        )
    end handleLoop

    /** Inspects the head of a computation without running it: if the first pending suspension is an operation of `E`, hands its input to
      * `f`. Used by the runtime to walk interrupted remainders without draining any user code.
      */
    private[kyo] def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        f: [C] => I[C] => Unit
    ): Unit =
        def probe(suspension: Kyo.Suspension[?, ?]): Unit =
            suspension match
                case s: Kyo.Suspend[?, ?, ?, ?] if effectTag.asInstanceOf[Tag[Any]] <:< s.tag.asInstanceOf[Tag[Any]] =>
                    f(s.input.asInstanceOf[I[Any]])
                case _ => ()
        v match
            case c: Kyo.Continue[?, ?, ?] => probe(c.suspend)
            case s: Kyo.Suspension[?, ?]  => probe(s)
            case _                        => ()
        end match
    end dispatchFirst

    /** Handles `E` and intercepts non-fatal exceptions in one step.
      *
      * `recover` receives exceptions thrown at construction, in the handler clause, or in any later step of the computation, including
      * after parks. `done` maps the region's completion value. The current kernel's `accept` input filter is not carried yet; it lands
      * with the effect that needs it.
      */
    private[kyo] def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, S3](
        effectTag: Tag[E],
        v: => A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2),
        done: A => B < S3 = (v: A) => v,
        recover: Throwable => B < (S & S2 & S3)
    )(using frame: Frame): B < (S & S2 & S3) =
        Effect.catching(ArrowEffect.handle[I, O, E, A, S, S2](effectTag, v)(handle).map(done))(recover)

    /** Drives the computation, handling `E` as the effect of last resort (the runtime boundary).
      *
      * Unlike the installing handle APIs, this drives immediately and the clause is not a delimiter: it is consulted only for operations
      * of `E` that no installed delimiter matched, making it the outermost handler. The clause receives the operation input and the full
      * continuation (delimiters included) and decides: `Present(next)` continues the drive with `next`, deep across operations; `Absent`
      * parks the drive with the suspension still pending, typically after capturing the continuation to resume out of band. This is the
      * scheduler integration point: a task drives its computation handling the runtime's own effect here, parks on `Absent`, and re-enters
      * with the same clause on the next slice. Preemption polls on the same cadence as the plain drive.
      */
    private[kyo] def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S),
        preempt: () => Boolean = `<`.neverPreempt,
        period: Int = Arrow.Period
    )(
        clause: [C] => (I[C], Arrow[O[C], A, E & S]) => Maybe[A < (E & S)]
    )(using frame: Frame): A < (E & S) =
        `<`.evalLoop(
            v.asInstanceOf[Any < Any],
            preempt,
            Integer.max(1, period / Arrow.Period),
            boundary = true,
            new `<`.LastResort(
                effectTag.asInstanceOf[Tag[Any]],
                clause.asInstanceOf[[C] => (Any, Arrow[Any, Any, Any]) => Maybe[Any < Any]]
            )
        ).asInstanceOf[A < (E & S)]

    /** Drives a freshly installed handler's region immediately: handling evaluates as far as it can, like every other strict position in
      * the kernel. Preemption for these drives is a later iteration.
      */
    private def install[A, S, B, S2](v: A < S, h: Handler): B < S2 =
        `<`.evalLoop(
            h.asInstanceOf[Arrow[Any, Any, Any]](v.asInstanceOf[Any < Any]),
            `<`.neverPreempt,
            1,
            boundary = false
        ).asInstanceOf[B < S2]

end ArrowEffect
