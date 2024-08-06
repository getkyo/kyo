package kyo

import java.time.Instant

object Clock:
    abstract class Service:
        def now(using Frame): Instant < IO

    val live: Service =
        new Service:
            def now(using Frame) = IO(Instant.now())

    private val local = Local.init(live)

    def let[T, S](c: Service)(f: => T < (IO & S))(using Frame): T < (IO & S) =
        local.let(c)(f)

    def now(using Frame): Instant < IO =
        local.use(_.now)

end Clock
