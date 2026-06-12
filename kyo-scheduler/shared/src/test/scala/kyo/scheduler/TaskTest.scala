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

    "interrupt runtime reset re-assert" - {
        // addRuntime is a non-atomic RMW that can erase a concurrent interrupt resetRuntime;
        // it re-asserts the reset after its own write so the runtime of an interrupted task
        // always converges to 0 (the priority that gets it rescheduled promptly).
        class InterruptedTask(runtimeValue: Int = 0) extends TestTask(runtimeValue) {
            @volatile var interrupted              = false
            override def needsInterrupt(): Boolean = interrupted
        }

        "addRuntime keeps an interrupted task's runtime at 0" in {
            val t = new InterruptedTask(1000)
            t.interrupted = true
            t.resetRuntime()
            t.addRuntime(5)
            assert(t.checkRuntime() == 0)
        }

        "addRuntime re-asserts a reset its own write erased" in {
            val t = new InterruptedTask(1000)
            // Simulates the lost-write interleaving: addRuntime's read happened before the
            // interrupt's reset, so its write restores the stale runtime; the re-assert fixes it.
            t.interrupted = true
            t.addRuntime(5)
            assert(t.checkRuntime() == 0)
        }

        "addRuntime accumulates normally while not interrupted" in {
            val t = new InterruptedTask(1000)
            t.addRuntime(5)
            assert(t.checkRuntime() == 1006)
            t.interrupted = true
            t.addRuntime(5)
            assert(t.checkRuntime() == 0)
        }

        "doPreempt re-asserts the reset on an interrupted task" in {
            val t = new InterruptedTask(1000)
            t.interrupted = true
            t.doPreempt()
            assert(t.checkRuntime() == 0)
        }

        "doPreempt leaves a non-interrupted task's runtime alone" in {
            val t = new InterruptedTask(1000)
            t.doPreempt()
            assert(t.checkRuntime() == 1001)
            assert(t.checkShouldPreempt())
        }
    }
}
