package kyo.stats.internal

import kyo.AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class SummaryTest extends AnyFreeSpec {

    "sum" - {
        "carries the cumulative total of every observed value" in {
            val h = new UnsafeHistogram(UnsafeHistogram.defaultBoundaries)
            h.observe(10.0)
            h.observe(20.0)
            h.observe(30.0)
            val s = h.summary()
            assert(s.sum == 60.0)
            assert(s.count == 3)
        }
    }

}
