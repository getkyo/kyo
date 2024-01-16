package kyo

import kyo._

import java.time.Instant
import java.time.ZoneId
import java.time.{Clock => JClock}
import kyo.locals.Locals

abstract class Clock {
  def now: Instant < IOs
}

object Clock {
  val default: Clock =
    new Clock {
      val now = IOs(Instant.now())
    }
}

object Clocks {

  private val local = Locals.init(Clock.default)

  def let[T, S](c: Clock)(f: => T < (IOs with S)): T < (IOs with S) =
    local.let(c)(f)

  val now: Instant < IOs =
    local.get.map(_.now)
}
