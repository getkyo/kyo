package kyoTest.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.internal.*
import kyoTest.KyoTest

class TraceReceiverTest extends KyoTest:

    "TraceReceiver.noop" in {
        val noopReceiver = TraceReceiver.noop
        val span         = noopReceiver.startSpan(Nil, "noopSpan", None, Attributes.empty)
        assert(IOs.run(span).unsafe eq Span.noop.unsafe)
    }

    "TraceReceiver.all" in {
        val mockReceiver1    = new TestTraceReceiver
        val mockReceiver2    = new TestTraceReceiver
        val combinedReceiver = TraceReceiver.all(List(mockReceiver1, mockReceiver2))

        combinedReceiver.startSpan(Nil, "combinedSpan", None, Attributes.empty)
        assert(mockReceiver1.spanStarted && mockReceiver2.spanStarted)
    }

    class TestTraceReceiver extends TraceReceiver:
        var spanStarted = false

        def startSpan(
            scope: List[String],
            name: String,
            parent: Option[Span],
            attributes: Attributes
        ): Span < IOs =
            spanStarted = true
            Span.noop
        end startSpan
    end TestTraceReceiver
end TraceReceiverTest
