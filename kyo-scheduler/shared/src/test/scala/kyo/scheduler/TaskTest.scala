package kyo.scheduler

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

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
        // Interruption is not tracked on Task; IOTask derives it from its promise.
        // A plain Task is never interrupted, so needsInterrupt stays false.
        "false for a plain task, regardless of preemption or runtime" in {
            val t = new TestTask(5)
            assert(!t.needsInterrupt())
            t.doPreempt()
            assert(!t.needsInterrupt())
            t.addRuntime(1)
            assert(!t.needsInterrupt())
        }
    }
}
