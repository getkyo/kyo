package kyo.stats.internal

import kyo.*
import kyo.stats.*

class SpanTest extends Test:

    "end" in run {
        val unsafe = new TestSpan
        val span   = TraceSpan(unsafe)
        for
            _ <- span.end
        yield assert(unsafe.isEnded)
    }

    "event" in run {
        val unsafe = new TestSpan
        val span   = TraceSpan(unsafe)
        for
            _ <- span.event("testEvent", Attributes.empty)
        yield assert(unsafe.lastEvent == "testEvent")
    }

    "noop" in run {
        val noopSpan = TraceSpan.noop
        noopSpan.end
        noopSpan.event("noopEvent", Attributes.empty)
        succeed
    }

    "all" - {
        "empty" in run {
            assert(TraceSpan.all(Nil).unsafe eq TraceSpan.noop.unsafe)
        }
        "one" in run {
            val span = TraceSpan(new TestSpan)
            assert(TraceSpan.all(List(span)).unsafe eq span.unsafe)
        }
        "multiple" in run {
            val unsafe1       = new TestSpan
            val unsafe2       = new TestSpan
            val compositeSpan = TraceSpan.all(List(TraceSpan(unsafe1), TraceSpan(unsafe2)))
            for
                _ <- compositeSpan.end
            yield assert(unsafe1.isEnded && unsafe2.isEnded)
        }
    }

    class TestSpan extends TraceSpan.Unsafe:
        var isEnded           = false
        var lastEvent: String = ""

        def end() =
            isEnded = true
        def event(name: String, a: Attributes) =
            lastEvent = name
    end TestSpan
end SpanTest
