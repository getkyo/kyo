package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.internal.tasty.query.TastyStat
import kyo.stats.Attributes

/** Tests for TastyStat.scope traceSpan delegates to the block exactly once (T2).
  *
  * TastyStat.scope is a Stat initialized with scope "kyo-tasty". traceSpan wraps its block in a Sync effect and returns the block result.
  */
class TastyStatTest extends kyo.test.Test[Any]:

    // Test 1 (T2): TastyStat.scope.traceSpan invokes the supplied block exactly once.
    "TastyStat.scope.traceSpan invokes the block exactly once" in {
        val counter = new AtomicInteger(0)
        TastyStat.scope.traceSpan("test", Attributes.empty) {
            counter.incrementAndGet()
        }.map { _ =>
            assert(counter.get() == 1, s"Expected counter == 1, got ${counter.get()}")
        }
    }

end TastyStatTest
