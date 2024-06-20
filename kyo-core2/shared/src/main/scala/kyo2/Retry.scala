package kyo2

import kyo.Tag
import scala.reflect.ClassTag
import scala.util.*

object Retry:

    case class Policy(backoff: Int => Duration, limit: Int):
        def exponential(
            startBackoff: Duration,
            factor: Int = 2,
            maxBackoff: Duration = Duration.Infinity
        ): Policy =
            backoff { i =>
                (startBackoff * factor * (i + 1)).min(maxBackoff)
            }
        def backoff(f: Int => Duration): Policy =
            copy(backoff = f)
        def limit(v: Int): Policy =
            copy(limit = v)
    end Policy

    object Policy:
        val default = Policy(_ => Duration.Zero, 3)

    final class RetryOps[E >: Nothing](dummy: Unit) extends AnyVal:
        def apply[T, S](policy: Policy)(v: => T < S)(
            using
            ClassTag[E],
            Tag[E],
            Frame
        ): T < (Async & Abort[E] & S) =
            apply(_ => policy)(v)

        def apply[T, S](builder: Policy => Policy)(v: => T < (Abort[E] & S))(
            using
            ClassTag[E],
            Tag[E],
            Frame
        ): T < (Async & Abort[E] & S) =
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

    inline def apply[E >: Nothing]: RetryOps[E] = RetryOps(())

end Retry
