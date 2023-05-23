package kyo

import kyo._
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
      val now: Instant > IOs =
        IOs(Instant.now())
    }
  }
  opaque type Clocks = Envs[Clock] & IOs

  object Clocks {
    type Iso = Clocks & IOs
    def run[T, S](c: Clock)(f: => T > (Iso & S)): T > (IOs & S) =
      Envs[Clock].let(c)(f)
    def run[T, S](f: => T > (Iso & S))(using c: Clock): T > (IOs & S) =
      Envs[Clock].let(c)(f)
    def now: Instant > Clocks =
      Envs[Clock].get.map(_.now)
  }
}
