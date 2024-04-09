package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyoTest.KyoTest

class CounterTest extends KyoTest:

    "noop" in IOs.run {
        for
            _ <- Counter.noop.inc
            _ <- Counter.noop.add(1)
            _ <- Counter.noop.add(1, Attributes.add("test", 1))
        yield succeed
    }

    "unsafe" in IOs.run {
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
        "empty" in IOs.run {
            assert(Counter.all(Nil).unsafe eq Counter.noop.unsafe)
        }
        "one" in IOs.run {
            val counter = Counter(new TestCounter)
            assert(Counter.all(List(counter)).unsafe eq counter.unsafe)
        }
        "multiple" in IOs.run {
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
