package kyoTest.scheduler

import kyo.scheduler.InternalClock
import kyo.scheduler.Task
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.PriorityQueue

class TaskTest extends AnyFreeSpec with NonImplicitAssertions {

    class TestTask(runtimeValue: Int = 0) extends Task {
        addRuntime(runtimeValue)
        def run(startMillis: Long, clock: InternalClock, deadline: Long) = Task.Done
        def checkShouldPreempt(): Boolean                                = shouldPreempt()
        def checkRuntime(): Int                                          = runtime()
    }

    "needsInterrupt" - {
        "returns false initially" in {
            val t = new TestTask(5)
            assert(!t.needsInterrupt())
        }
        "returns true after requestInterrupt" in {
            val t = new TestTask(5)
            t.requestInterrupt()
            assert(t.needsInterrupt())
        }
        "requestInterrupt also preempts" in {
            val t = new TestTask(5)
            assert(!t.checkShouldPreempt())
            t.requestInterrupt()
            assert(t.checkShouldPreempt())
        }
        "requestInterrupt on already-preempted task preserves runtime" in {
            val t = new TestTask(10)
            t.doPreempt()
            t.requestInterrupt()
            assert(t.needsInterrupt())
            assert(t.checkShouldPreempt())
            assert(t.checkRuntime() == 11) // initial 1 + added 10
        }
        "doPreempt preserves needsInterrupt flag" in {
            val t = new TestTask(5)
            t.requestInterrupt()
            assert(t.needsInterrupt())
            // addRuntime on negative state un-preempts but preserves bit 0
            t.addRuntime(1)
            assert(t.needsInterrupt())
            t.doPreempt()
            assert(t.needsInterrupt())
            assert(t.checkShouldPreempt())
        }
    }

    "ordering" - {
        "no repetition" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 4)
            val q  = PriorityQueue(t2, t4, t1, t3)
            assert(q.dequeueAll == Seq(t1, t2, t3, t4))
        }
        "repetition" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 2)
            val t5 = Task((), 4)
            assert(PriorityQueue(t2, t4, t5, t1, t3).dequeueAll == Seq(t1, t2, t4, t3, t5))
        }
        "with preemption" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 4)
            t2.doPreempt()
            t4.doPreempt()
            val q = PriorityQueue(t2, t4, t1, t3)
            assert(q.dequeueAll == Seq(t1, t2, t3, t4))
        }
    }
}
