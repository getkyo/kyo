package kyo

import java.time.Instant

abstract class Clock:
    def now(using Frame): Instant < IO

object Clock:

    val live: Clock =
        new Clock:
            def now(using Frame) = IO(Instant.now())

    private val local = Local.init(live)

    def let[T, S](c: Clock)(f: => T < (IO & S))(using Frame): T < (IO & S) =
        local.let(c)(f)

    def now(using Frame): Instant < IO =
        local.use(_.now)

end Clock
