package kyo.scheduler

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InternalClockTest extends AnyFreeSpec with NonImplicitAssertions {

    "currentMillis answers the source's current reading on every call" in {
        var now   = 0L
        val clock = new InternalClock(null, () => now)
        val stale = (1 to 1024).flatMap { i =>
            now = i.toLong
            val read = clock.currentMillis()
            if (read == now) None else Some(i -> read)
        }
        assert(stale.isEmpty, s"stale readings (call -> value): ${stale.take(5)}")
    }
}
