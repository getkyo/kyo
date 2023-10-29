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

  type Clocks >: Clocks.Effects <: Clocks.Effects

  object Clocks {

    type Effects = Envs[Clock] with IOs

    private val envs = Envs[Clock]

    def run[T, S](c: Clock)(f: => T > (Clocks with S)): T > (IOs with S) =
      envs.run[T, IOs with S](c)(f)

    def run[T, S](f: => T > (Clocks with S))(implicit c: Clock): T > (IOs with S) =
      envs.run[T, IOs with S](c)(f)

    val now: Instant > Clocks =
      envs.get.map(_.now)
  }
}
