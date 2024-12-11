package kyo

import kyo.Tag
import scala.util.*

/** Provides utilities for retrying operations with customizable policies. */
object Retry:

    /** The default retry schedule. */
    val defaultSchedule = Schedule.exponentialBackoff(initial = 100.millis, factor = 2, maxBackoff = 5.seconds).take(3)

    /** Provides retry operations for a specific error type. */
    final class RetryOps[E >: Nothing](dummy: Unit) extends AnyVal:

        /** Retries an operation using a custom policy builder.
          *
          * @param builder
          *   A function that modifies the default policy.
          * @param v
          *   The operation to retry.
          * @return
          *   The result of the operation, or an abort if all retries fail.
          */
        def apply[A: Flat, S](schedule: Schedule)(v: => A < (Abort[E] & S))(
            using
            SafeClassTag[E],
            Tag[E],
            Frame
        ): A < (Async & Abort[E] & S) =
            Loop(schedule) { schedule =>
                Abort.run[E](v).map(_.fold { r =>
                    Clock.now.map { now =>
                        schedule.next(now).map { (delay, nextSchedule) =>
                            Async.delay(delay)(Loop.continue(nextSchedule))
                        }.getOrElse {
                            Abort.get(r)
                        }
                    }
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
