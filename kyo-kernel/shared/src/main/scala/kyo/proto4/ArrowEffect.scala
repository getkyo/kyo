package kyo.proto4

import kyo.Frame
import kyo.Tag
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
  * The type parameters are invariant so that the operation constructors are inferred exactly from the effect's tag at suspend and handle
  * sites; a variant declaration would infer them to their extremes.
  *
  * @tparam I
  *   The operation input constructor
  * @tparam O
  *   The operation output constructor
  */
abstract class ArrowEffect[I[_], O[_]] extends Effect

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

    /** Handles `E` with a first-class continuation (the ctl format).
      *
      * The clause receives each operation's input and the continuation from the operation to this handler as an [[Arrow]]. The clause
      * decides everything: invoke the continuation once to resume, not at all to abort, or several times. The handler is deep: effects of
      * `E` in the clause's result, including through resumed continuations, dispatch back to this handler.
      *
      * Handling installs the handler and returns immediately; execution happens when the computation is driven.
      */
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => (I[C], Arrow[O[C], A, E & S]) => A < (E & S)
    )(using frame: Frame): A < S =
        install(v, new Handler.Cont(effectTag.asInstanceOf[Tag[Any]], clause.asInstanceOf[Handler.Clause], frame))

    /** Handles `E` by answering each operation in place (the fun format).
      *
      * The clause produces the operation's output; the kernel resumes the continuation exactly once with it. No continuation is exposed or
      * captured. The handler is deep.
      */
    def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => I[C] => O[C] < (E & S)
    )(using frame: Frame): A < S =
        install(v, new Handler.Resume(effectTag.asInstanceOf[Tag[Any]], clause.asInstanceOf[Handler.InputClause], frame))

    /** Handles `E` by ending the region at each operation (the final ctl format).
      *
      * The clause produces the region's result directly; the continuation from the operation to this handler never runs. The handler is
      * deep: effects of `E` in the clause's result dispatch back to this handler.
      */
    def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => I[C] => A < (E & S)
    )(using frame: Frame): A < S =
        install(v, new Handler.Stop(effectTag.asInstanceOf[Tag[Any]], clause.asInstanceOf[Handler.InputClause], frame))

    /** Handles only the first operation of `E`, shallowly.
      *
      * The clause receives the first operation's input and continuation and produces the final result; the handler is not reinstalled, so
      * later operations of `E` (including through the invoked continuation) are not handled by it. `done` transforms the region's value
      * when no operation occurs before completion.
      */
    def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        clause: [C] => (I[C], Arrow[O[C], A, E & S]) => B < S
    )(
        done: A => B < S
    )(using frame: Frame): B < S =
        install(
            v,
            new Handler.First(
                effectTag.asInstanceOf[Tag[Any]],
                clause.asInstanceOf[Handler.Clause],
                done.asInstanceOf[Any => Any < Any],
                frame
            )
        )

    /** The decision a stateful loop clause returns for each operation. */
    enum Outcome[+State, +Next, +B]:
        case Continue(state: State, next: Next) extends Outcome[State, Next, Nothing]
        case Done(result: B)                    extends Outcome[Nothing, Nothing, B]

    /** Handles `E` with handler state threaded through the operations.
      *
      * The clause receives the current state, the operation's input, and the continuation, and decides: `Outcome.Continue(nextState,
      * next)` keeps handling `next` (typically the resumed continuation) with the new state, `Outcome.Done(result)` leaves the region with
      * a final result, discarding the continuation. `done` produces the result when the region completes normally, from the final state
      * and value. The handler is deep for computations passed through Continue; effects raised while the outcome itself is computed
      * dispatch to outer handlers.
      */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], State, A, B, S](
        effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        clause: [C] => (State, I[C], Arrow[O[C], A, E & S]) => Outcome[State, A < (E & S), B] < (E & S)
    )(
        done: (State, A) => B < S
    )(using frame: Frame): B < S =
        install(
            v,
            new Handler.Loop(
                effectTag.asInstanceOf[Tag[Any]],
                state,
                clause.asInstanceOf[Handler.LoopClause],
                done.asInstanceOf[(Any, Any) => Any < Any],
                frame
            )
        )

    private def install[A, S, B, S2](v: A < S, h: Handler): B < S2 =
        h.asInstanceOf[Arrow[Any, Any, Any]](v.asInstanceOf[Any < Any]).asInstanceOf[B < S2]

end ArrowEffect
