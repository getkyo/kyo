package kyo

import java.time.Instant

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

  def let[T, S](c: Clock)(f: => T < (IOs & S)): T < (IOs & S) =
    local.let(c)(f)

  val now: Instant < IOs =
    local.get.map(_.now)
}
