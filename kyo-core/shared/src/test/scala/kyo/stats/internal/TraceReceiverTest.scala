package kyo.stats.internal

import kyo.*
import kyo.stats.*

class TraceReceiverTest extends Test:

    "TraceReceiver.noop" in run {
        val noopReceiver = TraceReceiver.noop
        val span         = noopReceiver.startSpan(Nil, "noopSpan", Maybe.empty, Attributes.empty)
        span.map { span =>
            assert(span.unsafe eq Span.noop.unsafe)
        }
    }

    "TraceReceiver.all" in {
        val mockReceiver1    = new TestTraceReceiver
        val mockReceiver2    = new TestTraceReceiver
        val combinedReceiver = TraceReceiver.all(List(mockReceiver1, mockReceiver2))

        combinedReceiver.startSpan(Nil, "combinedSpan", Maybe.empty, Attributes.empty)
        assert(mockReceiver1.spanStarted && mockReceiver2.spanStarted)
    }

    class TestTraceReceiver extends TraceReceiver:
        var spanStarted = false

        def startSpan(
            scope: List[String],
            name: String,
            parent: Maybe[Span],
            attributes: Attributes
        )(using Frame): Span < Sync =
            spanStarted = true
            Span.noop
        end startSpan
    end TestTraceReceiver
end TraceReceiverTest
