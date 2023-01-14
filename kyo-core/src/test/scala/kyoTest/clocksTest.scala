package kyoTest

import kyo.core._
import kyo.envs._
import kyo.ios._
import kyo.clocks._
import java.time.Instant
import java.time.ZoneId

class clocksTest extends KyoTest {

  "now" in new Context {
    val instant = Instant.now()
    testClock.nows = List(instant)
    val io = Clocks.run(testClock)(Clocks.now)
    assert(IOs.run(io) == instant)
  }

  "now implicit clock" in new Context {
    given Clock = testClock
    val instant = Instant.now()
    testClock.nows = List(instant)
    val io = Clocks.run(Clocks.now)
    assert(IOs.run(io) == instant)
  }

  trait Context {
    object testClock extends Clock {
      var nows = List.empty[Instant]

      def now: Instant > IOs =
        IOs {
          val v = nows.head
          nows = nows.tail
          v
        }
    }
  }
}
