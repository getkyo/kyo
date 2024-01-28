package kyo

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

object Retries {

  case class Policy(backoff: Int => Duration, limit: Int) {
    def exponential(
        startBackoff: Duration,
        factor: Int = 2,
        maxBackoff: Duration = Duration.Inf
    ): Policy =
      backoff { i =>
        (startBackoff * factor * (i + 1)).min(maxBackoff)
      }
    def backoff(f: Int => Duration): Policy =
      copy(backoff = f)
    def limit(v: Int): Policy =
      copy(limit = v)
  }

  object Policy {
    val default = Policy(_ => Duration.Zero, 3)
  }

  def apply[T, S](policy: Policy)(v: => T < S)(
      implicit f: Flat[T < S]
  ): T < (Fibers with S) =
    apply(_ => policy)(v)

  def apply[T, S](builder: Policy => Policy)(v: => T < S)(
      implicit f: Flat[T < S]
  ): T < (Fibers with S) = {
    val b = builder(Policy.default)
    def loop(attempt: Int): T < (Fibers with S) =
      Tries.run[T, S](v).map {
        case Failure(ex) =>
          if (attempt < b.limit) {
            Fibers.sleep(b.backoff(attempt)).andThen {
              loop(attempt + 1)
            }
          } else {
            IOs.fail(ex)
          }
        case Success(value) =>
          value
      }
    loop(0)
  }
}
