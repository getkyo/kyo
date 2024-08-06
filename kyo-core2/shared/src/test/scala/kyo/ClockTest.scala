package kyo

import java.time.Instant

class clocksTest extends Test:

    object testClock extends Clock.Service:

        var nows = List.empty[Instant]

        def now(using Frame): Instant < IO =
            IO {
                val v = nows.head
                nows = nows.tail
                v
            }
    end testClock

    "now" in {
        val instant = Instant.now()
        testClock.nows = List(instant)
        val io = Clock.let(testClock)(Clock.now)
        assert(IO.run(io).eval eq instant)
    }
end clocksTest
