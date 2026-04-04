package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec

/** Thread interrupt flag isolation tests — JVM only.
  *
  * These tests call Thread.currentThread().interrupt() from within a task body, which causes SIGSEGV on Scala Native. The behavior is
  * JVM-specific thread management that is genuinely unsafe on Native.
  */
class CPUStallMonitorInterruptFlagTest extends AnyFreeSpec with NonImplicitAssertions {

    private def withScheduler[A](testCode: Scheduler => A): A = {
        val scheduler = new Scheduler(TestExecutors.cached, TestExecutors.scheduled)
        try testCode(scheduler)
        finally scheduler.shutdown()
    }

    "thread interrupt flag isolation" - {

        "interrupt flag cleared between tasks on same worker" in withScheduler { scheduler =>
            val flagLeaked = new AtomicBoolean(false)
            val done       = new CountDownLatch(1)

            val task1 = TestTask(_run = () => {
                Thread.currentThread().interrupt()
                Task.Done
            })
            val task2 = TestTask(_run = () => {
                if (Thread.interrupted())
                    flagLeaked.set(true)
                done.countDown()
                Task.Done
            })

            scheduler.schedule(task1)
            scheduler.schedule(task2)

            assert(done.await(5, TimeUnit.SECONDS))
            assert(!flagLeaked.get(), "Thread.interrupted() in runTask finally should clear the flag")
        }

        "stale interrupt from pool thread cleared on worker mount" in withScheduler { scheduler =>
            val flagOnEntry = new AtomicBoolean(false)
            val phase1      = new CountDownLatch(1)
            val phase2      = new CountDownLatch(1)

            val task1 = TestTask(_run = () => {
                Thread.currentThread().interrupt()
                phase1.countDown()
                Task.Done
            })
            scheduler.schedule(task1)
            assert(phase1.await(5, TimeUnit.SECONDS))

            Thread.sleep(50) // worker goes idle, returns thread to pool

            val task2 = TestTask(_run = () => {
                flagOnEntry.set(Thread.interrupted())
                phase2.countDown()
                Task.Done
            })
            scheduler.schedule(task2)
            assert(phase2.await(5, TimeUnit.SECONDS))
            assert(!flagOnEntry.get(), "Thread.interrupted() at run() start should clear stale flag")
        }
    }

    "interrupt dispatch" - {

        "interrupts multiple blocked tasks on different workers" in withScheduler { scheduler =>
            val count       = 3
            val started     = new CountDownLatch(count)
            val interrupted = Array.fill(count)(new AtomicBoolean(false))
            val tasks = (0 until count).map { i =>
                TestTask(_run = () => {
                    started.countDown()
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            interrupted(i).set(true)
                    }
                    Task.Done
                })
            }

            tasks.foreach(scheduler.schedule)
            assert(started.await(10, TimeUnit.SECONDS))
            Thread.sleep(10)

            tasks.foreach(_.requestInterrupt())

            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                (0 until count).foreach { i =>
                    assert(interrupted(i).get(), s"task $i should have been interrupted")
                }
            }
        }
    }
}
