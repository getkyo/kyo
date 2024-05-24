package kyo

import java.time.Instant
import kyo.internal.Trace

abstract class Clock:
    def now(using Trace): Instant < IOs

object Clock:
    val default: Clock =
        new Clock:
            def now(using Trace) = IOs(Instant.now())
end Clock

object Clocks:

    private val local = Locals.init(Clock.default)

    def let[T, S](c: Clock)(f: => T < (IOs & S))(using Trace): T < (IOs & S) =
        local.let(c)(f)

    def now(using Trace): Instant < IOs =
        local.use(_.now)
end Clocks
