package kyo

import kyo.core._
import kyo.envs._
import kyo.ios._

import java.time.Instant
import java.time.ZoneId
import java.time.{Clock => JClock}

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
  type Clocks = Envs[Clock] | IOs
  object Clocks {
    def run[T, S](c: Clock)(f: => T > (S | Clocks)): T > (S | IOs) =
      Envs.let(c)(f)
    def run[T, S](f: => T > (S | Clocks))(using c: Clock): T > (S | IOs) =
      Envs.let(c)(f)
    def now: Instant > Clocks =
      Envs[Clock](_.now)
  }
}
