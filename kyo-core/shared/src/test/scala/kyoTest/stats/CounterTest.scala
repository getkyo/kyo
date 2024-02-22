package kyoTest.stats

import kyoTest.KyoTest
import kyo.stats.*
import kyo.*

class CounterTest extends KyoTest:

    "noop" in run {
        for
            _ <- Counter.noop.inc
            _ <- Counter.noop.add(1)
            _ <- Counter.noop.add(1, Attributes.add("test", 1))
        yield succeed
    }

    "unsafe" in run {
        val unsafe  = new TestCounter
        val counter = Counter(unsafe)
        for
            _ <- counter.inc
            _ <- counter.add(1)
            _ <- counter.add(1, Attributes.add("test", 1))
        yield assert(unsafe.curr == 3)
        end for
    }

    "all" - {
        "empty" in run {
            assert(Counter.all(Nil) == Counter.noop)
        }
        "one" in run {
            val counter = Counter(new TestCounter)
            assert(Counter.all(List(counter)) == counter)
        }
        "multiple" in run {
            val unsafe1 = new TestCounter
            val unsafe2 = new TestCounter
            val counter = Counter.all(List(Counter(unsafe1), Counter(unsafe2)))
            for
                _ <- counter.inc
                _ <- counter.add(1)
                _ <- counter.add(1, Attributes.add("test", 1))
            yield assert(unsafe1.curr == 3 && unsafe2.curr == 3)
            end for
        }
    }

    class TestCounter extends Counter.Unsafe:
        var curr = 0L
        def inc() =
            curr += 1
        def add(v: Long, b: Attributes) =
            curr += v
        def add(v: Long) =
            curr += v
        def attributes(b: Attributes) = this
    end TestCounter
end CounterTest
