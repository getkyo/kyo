package kyo

import kyo._
import kyo.envs._
import kyo.ios._

import java.time.Instant
import java.time.ZoneId
import java.time.{Clock => JClock}

object clocks {

  abstract class Clock {
    def now: Instant > IOs
  }

  object Clock {
    implicit val default: Clock =
      new Clock {
        val now = IOs(Instant.now())
      }
  }
  type Clocks = Envs[Clock] with IOs

  object Clocks {

    def run[T, S](c: Clock)(f: => T > (Clocks with S)): T > (IOs with S) =
      Envs[Clock].run[T, IOs with S](c)(f)

    def run[T, S](f: => T > (Clocks with S))(implicit c: Clock): T > (IOs with S) =
      Envs[Clock].run[T, IOs with S](c)(f)

    def now: Instant > Clocks =
      Envs[Clock].get.map(_.now)
  }
}
