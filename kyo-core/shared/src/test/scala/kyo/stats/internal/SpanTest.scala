package kyo.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.internal.*

class SpanTest extends Test:

    "end" in run {
        val unsafe = new TestSpan
        val span   = Span(unsafe)
        for
            _ <- span.end
        yield assert(unsafe.isEnded)
    }

    "event" in run {
        val unsafe = new TestSpan
        val span   = Span(unsafe)
        for
            _ <- span.event("testEvent", Attributes.empty)
        yield assert(unsafe.lastEvent == "testEvent")
    }

    "noop" in run {
        val noopSpan = Span.noop
        noopSpan.end
        noopSpan.event("noopEvent", Attributes.empty)
        succeed
    }

    "all" - {
        "empty" in run {
            assert(Span.all(Nil).unsafe eq Span.noop.unsafe)
        }
        "one" in run {
            val span = Span(new TestSpan)
            assert(Span.all(List(span)).unsafe eq span.unsafe)
        }
        "multiple" in run {
            val unsafe1       = new TestSpan
            val unsafe2       = new TestSpan
            val compositeSpan = Span.all(List(Span(unsafe1), Span(unsafe2)))
            for
                _ <- compositeSpan.end
            yield assert(unsafe1.isEnded && unsafe2.isEnded)
        }
    }

    class TestSpan extends Span.Unsafe:
        var isEnded           = false
        var lastEvent: String = ""

        def end() =
            isEnded = true
        def event(name: String, a: Attributes) =
            lastEvent = name
    end TestSpan
end SpanTest
