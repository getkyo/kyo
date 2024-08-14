package kyoTest

import java.time.Instant
import kyo.*
import kyo.internal.Trace

class clocksTest extends KyoTest:

    object testClock extends Clock:

        var nows = List.empty[Instant]

        def now(using Trace): Instant < IOs =
            IOs {
                val v = nows.head
                nows = nows.tail
                v
            }
    end testClock

    "now" in {
        val instant = Instant.now()
        testClock.nows = List(instant)
        val io = Clocks.let(testClock)(Clocks.now)
        assert(IOs.run(io) eq instant)
    }
end clocksTest
