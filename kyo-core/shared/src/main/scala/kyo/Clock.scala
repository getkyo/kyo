package kyo

import java.time.Instant

abstract class Clock:
    def now(using Frame): Instant < IO

object Clock:

    val live: Clock =
        new Clock:
            def now(using Frame) = IO(Instant.now())

    private val local = Local.init(live)

    def let[A, S](c: Clock)(f: => A < (IO & S))(using Frame): A < (IO & S) =
        local.let(c)(f)

    def now(using Frame): Instant < IO =
        local.use(_.now)

end Clock
