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
    override def getMessage() =
        Seq(
            "",
            "──────────────────────────────".dim,
            "Check failed! ".red.bold + message,
            "──────────────────────────────".dim,
            frame.parse.show,
            "──────────────────────────────".dim
        ).mkString("\n")
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
    inline def apply(inline condition: Boolean)(using inline frame: Frame): Unit < Check =
        Check(condition, "")

    /** Checks the boolean condition with a custom failure message.
      *
      * @param condition
      *   The boolean condition to check
      * @param message
      *   The message to use if the check fails
      * @return
      *   A unit computation that fails with the given message if the condition is false
      */
    inline def apply(inline condition: Boolean, inline message: => String)(using inline frame: Frame): Unit < Check =
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
            [C] => (input, cont) => Abort.error(input)
        )

    /** Runs a computation with Check effect, collecting all failures.
      *
      * @param v
      *   The computation to run
      * @return
      *   A tuple of collected failures and the computation result
      */
    def runChunk[A: Flat, S](v: A < (Check & S))(using Frame): (Chunk[CheckFailed], A) < S =
        ArrowEffect.handle.state(Tag[Check], Chunk.empty[CheckFailed], v)(
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

end Check
