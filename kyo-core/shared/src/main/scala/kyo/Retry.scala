package kyo

import kyo.Tag
import scala.util.*

/** A utility for retrying operations with configurable backoff strategies and failure handling.
  *
  * Retry provides a structured approach to executing operations that may temporarily fail, allowing them to be automatically retried based
  * on sophisticated scheduling policies.
  *
  * Common usage patterns:
  *   - Use `Retry[ErrorType](operation)` for simple retries with the default exponential backoff strategy
  *   - Use `Retry[ErrorType](customSchedule)(operation)` for operations requiring specialized retry behavior
  *   - Combine with `Schedule` combinators for advanced policies like "retry exponentially up to 5 times with randomized delays"
  *
  * The default retry policy (`defaultSchedule`) uses exponential backoff with jitter, which provides a balance between quick recovery for
  * transient failures and protection against coordinated retries in distributed systems.
  *
  * @see
  *   [[kyo.Schedule]] for defining custom retry policies
  * @see
  *   [[kyo.Abort]] for integrating with Kyo's structured error handling
  */
object Retry:

    /** The default retry schedule. */
    val defaultSchedule =
        Schedule.exponentialBackoff(initial = 100.millis, factor = 2, maxBackoff = 5.seconds)
            .jitter(0.2).take(3)

    /** Retries an operation using the default retry schedule.
      *
      * Uses [[defaultSchedule]] which implements exponential backoff with jitter. See [[defaultSchedule]] for the specific configuration.
      *
      * @param v
      *   The operation to retry
      * @return
      *   The result of the operation, or an abort if all retries fail
      */
    def apply[E: SafeClassTag](using Frame)[A, S](v: => A < (Abort[E] & S)): A < (Async & Abort[E] & S) =
        apply(defaultSchedule)(v)

    /** Retries an operation using a custom policy builder.
      *
      * @param builder
      *   A function that modifies the default policy.
      * @param v
      *   The operation to retry.
      * @return
      *   The result of the operation, or an abort if all retries fail.
      */
    def apply[E: SafeClassTag](using Frame)[A, S](schedule: Schedule)(v: => A < (Abort[E] & S)): A < (Async & Abort[E] & S) =
        Abort.run[E](v).map {
            case Result.Success(value) => value
            case result: Result.Failure[E] @unchecked =>
                Clock.now.map { now =>
                    schedule.next(now).map { (delay, nextSchedule) =>
                        Async.delay(delay)(Retry[E](nextSchedule)(v))
                    }.getOrElse {
                        Abort.error(result)
                    }
                }
            case panic: Result.Panic => Abort.error(panic)
        }

end Retry
