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

    "preemption" - {
        "not preempting initially" in {
            val t = new TestTask(5)
            assert(!t.checkShouldPreempt())
        }
        "doPreempt sets preemption" in {
            val t = new TestTask(5)
            t.doPreempt()
            assert(t.checkShouldPreempt())
        }
        "addRuntime clears preemption" in {
            val t = new TestTask(10)
            t.doPreempt()
            assert(t.checkShouldPreempt())
            t.addRuntime(5)
            assert(!t.checkShouldPreempt())
        }
    }

    "runtime" - {
        "starts at the initial value" in {
            val t = new TestTask(10)
            assert(t.checkRuntime() == 11) // initial 1 + added 10
        }
        "addRuntime accumulates" in {
            val t = new TestTask(10)
            t.addRuntime(5)
            assert(t.checkRuntime() == 16)
        }
        "addRuntime accumulates while preempted" in {
            val t = new TestTask(10)
            t.doPreempt()
            t.addRuntime(5)
            assert(t.checkRuntime() == 16)
        }
        "doPreempt does not change runtime" in {
            val t = new TestTask(10)
            t.doPreempt()
            assert(t.checkRuntime() == 11)
        }
    }

    "needsInterrupt" - {
        // Interruption is no longer tracked on Task — IOTask derives it from its
        // promise. A plain Task is never interrupted, so needsInterrupt stays false.
        "false for a plain task, regardless of preemption or runtime" in {
            val t = new TestTask(5)
            assert(!t.needsInterrupt())
            t.doPreempt()
            assert(!t.needsInterrupt())
            t.addRuntime(1)
            assert(!t.needsInterrupt())
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
