package kyo

import kyo.core._
import kyo.ios._
import kyo.envs._
import java.time.{Clock => JClock, Instant, ZoneId}

object clocks {
  trait Clock {
    def now: Instant > IOs
  }
  object Clock {
    given default: Clock with {
      def now: Instant > IOs =
        IOs(Instant.now())
    }
  }
  type Clocks = Envs[Clock]
  object Clocks {
    def run[T, S](c: Clock)(f: => T > (S | Clocks)): T > S =
      Envs.let(c)(f)
    def run[T, S](f: => T > (S | Clocks))(using c: Clock): T > S =
      Envs.let(c)(f)
    def now: Instant > (Clocks | IOs) =
      Envs[Clock](_.now)
  }
}
