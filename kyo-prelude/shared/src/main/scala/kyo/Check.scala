package kyo

import Ansi.*
import kyo.kernel.*

/** Represents a failed check condition.
  *
  * `CheckFailed` is an exception that is thrown when a condition in a `Check` effect fails. It contains information about the failure,
  * including a custom message and the frame where the failure occurred.
  *
  * @param message
  *   The custom message describing the failed condition
  * @param frame
  *   The [[Frame]] where the check failure occurred
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
        ArrowEffect.handleState(Tag[Check], Chunk.empty[CheckFailed], v)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(())),
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
