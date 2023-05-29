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
  type Clocks = Envs[Clock] with IOs

  object Clocks {
    type Iso = Clocks with IOs
    def run[T, S](c: Clock)(f: => T > (Iso with S)): T > (IOs with S) =
      Envs[Clock].run(c)(f)
    def run[T, S](f: => T > (Iso with S))(using c: Clock): T > (IOs with S) =
      Envs[Clock].run(c)(f)
    def now: Instant > Clocks =
      Envs[Clock].get.map(_.now)
  }
}
