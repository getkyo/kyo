package kyoTest.stats.internal

import kyoTest.KyoTest
import kyo.stats._
import kyo.stats.internal._
import kyo._
import kyo.ios.IOs

class TraceReceiverTest extends KyoTest {

  "TraceReceiver.noop" in {
    val noopReceiver = TraceReceiver.noop
    val span         = noopReceiver.startSpan(Nil, "noopSpan", None, Attributes.empty)
    assert(span == Span.noop)
  }

  "TraceReceiver.all" in {
    val mockReceiver1    = new TestTraceReceiver
    val mockReceiver2    = new TestTraceReceiver
    val combinedReceiver = TraceReceiver.all(List(mockReceiver1, mockReceiver2))

    combinedReceiver.startSpan(Nil, "combinedSpan", None, Attributes.empty)
    assert(mockReceiver1.spanStarted && mockReceiver2.spanStarted)
  }

  class TestTraceReceiver extends TraceReceiver {
    var spanStarted = false

    def startSpan(
        scope: List[String],
        name: String,
        parent: Option[Span],
        attributes: Attributes
    ): Span > IOs = {
      spanStarted = true
      Span.noop
    }
  }
}
