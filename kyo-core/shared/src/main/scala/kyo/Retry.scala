package kyo

import kyo.Tag
import scala.util.*

/** Provides utilities for retrying operations with customizable policies. */
object Retry:

    /** Represents a retry policy with backoff strategy and attempt limit. */
    final case class Policy(backoff: Int => Duration, limit: Int):

        /** Creates an exponential backoff strategy.
          *
          * @param startBackoff
          *   The initial backoff duration.
          * @param factor
          *   The multiplier for each subsequent backoff.
          * @param maxBackoff
          *   The maximum backoff duration.
          * @return
          *   A new Policy with exponential backoff.
          */
        def exponential(
            startBackoff: Duration,
            factor: Int = 2,
            maxBackoff: Duration = Duration.Infinity
        ): Policy =
            backoff { i =>
                (startBackoff * factor * (i + 1)).min(maxBackoff)
            }

        /** Sets a custom backoff function.
          *
          * @param f
          *   A function that takes the attempt number and returns a Duration.
          * @return
          *   A new Policy with the custom backoff function.
          */
        def backoff(f: Int => Duration): Policy =
            copy(backoff = f)

        /** Sets the maximum number of retry attempts.
          *
          * @param v
          *   The maximum number of attempts.
          * @return
          *   A new Policy with the specified attempt limit.
          */
        def limit(v: Int): Policy =
            copy(limit = v)
    end Policy

    object Policy:
        /** The default retry policy with no backoff and 3 attempts. */
        val default = Policy(_ => Duration.Zero, 3)

    /** Provides retry operations for a specific error type. */
    final class RetryOps[E >: Nothing](dummy: Unit) extends AnyVal:

        /** Retries an operation using the specified policy.
          *
          * @param policy
          *   The retry policy to use.
          * @param v
          *   The operation to retry.
          * @return
          *   The result of the operation, or an abort if all retries fail.
          */
        def apply[A: Flat, S](policy: Policy)(v: => A < S)(
            using
            SafeClassTag[E],
            Tag[E],
            Frame
        ): A < (Async & Abort[E] & S) =
            apply(_ => policy)(v)

        /** Retries an operation using a custom policy builder.
          *
          * @param builder
          *   A function that modifies the default policy.
          * @param v
          *   The operation to retry.
          * @return
          *   The result of the operation, or an abort if all retries fail.
          */
        def apply[A: Flat, S](builder: Policy => Policy)(v: => A < (Abort[E] & S))(
            using
            SafeClassTag[E],
            Tag[E],
            Frame
        ): A < (Async & Abort[E] & S) =
            val b = builder(Policy.default)
            Loop.indexed { attempt =>
                Abort.run[E](v).map(_.fold { r =>
                    if attempt < b.limit then
                        Async.sleep(b.backoff(attempt)).andThen {
                            Loop.continue
                        }
                    else
                        Abort.get(r)
                }(Loop.done(_)))
            }
        end apply
    end RetryOps

    /** Creates a RetryOps instance for the specified error type.
      *
      * @tparam E
      *   The error type to handle in retries.
      * @return
      *   A RetryOps instance for the specified error type.
      */
    inline def apply[E >: Nothing]: RetryOps[E] = RetryOps(())

end Retry
