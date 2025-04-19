package kyo

import Ansi.*
import kyo.kernel.*

/** Validation effect for asserting conditions during computation.
  *
  * The `Check` effect provides a functional alternative to traditional assertions, enabling runtime validation of conditions within the Kyo
  * effect system. Unlike exceptions that immediately halt execution, checks can be collected, transformed, or handled in various ways
  * without disrupting the control flow.
  *
  * Checks create a `CheckFailed` instance when a condition isn't met, capturing both a message and the frame location where the failure
  * occurred. This rich error context makes debugging easier by providing precise information about what failed and where.
  *
  * Three handling strategies give flexibility in how validation failures are processed:
  *   - Collecting all failures for later inspection
  *   - Converting failures to the `Abort` effect for immediate error propagation
  *   - Discarding failures for non-critical validations
  *
  * The effect supports parallel computations where it can accumulate multiple failures across branches rather than short-circuiting on the
  * first error, enabling more comprehensive validation reporting.
  *
  * @see
  *   [[kyo.Check.require]] for creating conditional checks
  * @see
  *   [[kyo.Check.runChunk]] for collecting all failures during execution
  * @see
  *   [[kyo.Check.runAbort]] for converting failures to the Abort effect
  * @see
  *   [[kyo.Check.runDiscard]] for ignoring check failures
  */
final class CheckFailed(val message: String, val frame: Frame) extends AssertionError(message):
    override def getMessage() = frame.render("Check failed! ".red.bold + message)
end CheckFailed

/** Represents a check effect for validating conditions.
  *
  * The `Check` effect allows for defining and running assertions within computations. It provides a way to validate conditions and collect
  * or handle failures in various ways.
  */
sealed trait Check extends ArrowEffect[Const[CheckFailed], Const[Unit]]

object Check:

    /** Checks if the boolean condition is true.
      *
      * @param condition
      *   The boolean condition to check
      * @return
      *   A unit computation that fails if the condition is false
      */
    inline def require(inline condition: Boolean)(using inline frame: Frame): Unit < Check =
        Check.require(condition, "")

    /** Checks the boolean condition with a custom failure message.
      *
      * @param condition
      *   The boolean condition to check
      * @param message
      *   The message to use if the check fails
      * @return
      *   A unit computation that fails with the given message if the condition is false
      */
    inline def require(inline condition: Boolean, inline message: => String)(using inline frame: Frame): Unit < Check =
        if condition then ()
        else ArrowEffect.suspend[Unit](Tag[Check], new CheckFailed(message, frame))

    /** Runs a computation with Check effect, converting failure to Abort.
      *
      * @param v
      *   The computation to run
      * @return
      *   A computation that may abort with CheckFailed if any checks fail
      */
    def runAbort[A: Flat, S](v: A < (Check & S))(using Frame): A < (Abort[CheckFailed] & S) =
        ArrowEffect.handle(Tag[Check], v)(
            [C] => (input, cont) => Abort.fail(input)
        )

    /** Runs a computation with Check effect, collecting all failures.
      *
      * @param v
      *   The computation to run
      * @return
      *   A tuple of collected failures and the computation result
      */
    def runChunk[A: Flat, S](v: A < (Check & S))(using Frame): (Chunk[CheckFailed], A) < S =
        ArrowEffect.handleLoop(Tag[Check], Chunk.empty[CheckFailed], v)(
            handle = [C] =>
                (input, state, cont) =>
                    Loop.continue(state.append(input), cont(())),
            done = (state, result) => (state, result)
        )

    /** Runs a computation with Check effect, discarding any failures.
      *
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, ignoring any check failures
      */
    def runDiscard[A: Flat, S](v: A < (Check & S))(using Frame): A < S =
        ArrowEffect.handle(Tag[Check], v)(
            [C] => (_, cont) => cont(())
        )

    /** Default isolate that accumulates and re-emits failures.
      *
      * When the isolation ends, accumulates any check failures that occurred during the isolated computation with failures from the outer
      * context. This allows building up a complete set of failed checks.
      *
      * Important: Note that `Check.runAbort(Async.parallel(computation1, computation2))` will only short circuit once both computations
      * finish and the isolate re-emits values to restore its state.
      */
    given isolate: Isolate.Stateful[Check, Any] with

        type State = Chunk[CheckFailed]

        type Transform[A] = (State, A)

        given flatTransform[A: Flat]: Flat[(State, A)] = Flat.derive

        def capture[A: Flat, S2](f: State => A < S2)(using Frame) =
            f(Chunk.empty)

        def isolate[A: Flat, S2](state: Chunk[CheckFailed], v: A < (Check & S2))(using Frame) =
            Check.runChunk(v)

        def restore[A: Flat, S2](v: (Chunk[CheckFailed], A) < S2)(using Frame) =
            v.map { (state, r) =>
                Kyo.foreachDiscard(state)(check => Check.require(false, check.message)(using check.frame)).andThen(r)
            }
    end isolate

end Check
