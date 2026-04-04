package kyo.scheduler

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kyo.scheduler.util.ThreadUserTime
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec

class CPUStallMonitorTest extends AnyFreeSpec with NonImplicitAssertions {

    /** Runs a blocking operation on a thread and verifies the detector identifies it as stalled. Uses latches for synchronization — no
      * Thread.sleep needed since blocked threads have flat CPU time immediately.
      */
    private def assertDetectsStall(setup: () => AutoCloseable = () => NoOpCloseable)(op: CountDownLatch => Unit): Unit = {
        val resource = setup()
        try {
            val detector = new CPUStallMonitor(1)
            val started  = new CountDownLatch(1)
            val done     = new CountDownLatch(1)
            val threadId = new AtomicLong(0L)
            val thread = new Thread((() => {
                threadId.set(ThreadUserTime.currentThreadId())
                started.countDown()
                op(done)
            }): Runnable)
            thread.setDaemon(true)
            thread.start()
            assert(started.await(5, TimeUnit.SECONDS))

            val ids = Array(threadId.get())
            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isStalled(0), "thread should be detected as stalled")
            }

            done.countDown()
            thread.interrupt()
            thread.join(5000)
        } finally
            resource.close()
    }

    /** Runs an active operation and verifies the detector does NOT identify it as stalled. Waits for the thread to accumulate CPU time (via
      * warmUp latch), then samples the detector.
      */
    private def assertNotStalled(op: (CountDownLatch, AtomicBoolean) => Unit): Unit = {
        val detector = new CPUStallMonitor(1)
        val started  = new CountDownLatch(1)
        val warmedUp = new CountDownLatch(1)
        val stop     = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            started.countDown()
            op(warmedUp, stop)
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        assert(started.await(5, TimeUnit.SECONDS))
        assert(warmedUp.await(5, TimeUnit.SECONDS))

        val ids = Array(threadId.get())
        eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
            detector.sample(ids, 1)
            assert(!detector.isStalled(0), "active thread should not be detected as stalled")
        }

        stop.set(true)
        thread.join(5000)
    }

    private object NoOpCloseable extends AutoCloseable { def close(): Unit = () }

    "detects stall" - {

        "Thread.sleep — TIMED_WAITING" in {
            assertDetectsStall() { done =>
                try { val _ = done.await(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
            }
        }

        "LockSupport.park — WAITING" in {
            assertDetectsStall() { done =>
                while (done.getCount > 0)
                    LockSupport.parkNanos(1000000000L)
            }
        }

        "Object.wait — WAITING" in {
            val lock = new Object
            assertDetectsStall() { done =>
                lock.synchronized {
                    while (done.getCount > 0)
                        try lock.wait(1000)
                        catch { case _: InterruptedException => () }
                }
            }
        }

        "ReentrantLock contention — WAITING" in {
            val lock = new ReentrantLock()
            lock.lock()
            assertDetectsStall(() => { new AutoCloseable { def close(): Unit = lock.unlock() } }) { _ =>
                lock.lock()
                lock.unlock()
            }
        }

        "ServerSocket.accept — RUNNABLE but blocked" in {
            val server = new ServerSocket(0)
            assertDetectsStall(() => server) { _ =>
                try { val _ = server.accept() }
                catch { case _: Exception => () }
            }
        }

        "Socket.read — RUNNABLE but blocked" in {
            val server = new ServerSocket(0)
            val client = new Socket()
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort))
            val accepted = server.accept()
            assertDetectsStall(() =>
                new AutoCloseable {
                    def close(): Unit = { client.close(); accepted.close(); server.close() }
                }
            ) { _ =>
                try { val _ = accepted.getInputStream.read() }
                catch { case _: Exception => () }
            }
        }

        "Process.waitFor — WAITING" in {
            assertDetectsStall() { done =>
                val proc = Runtime.getRuntime.exec(Array("sleep", "30"))
                try { val _ = proc.waitFor(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
                finally { val _ = proc.destroyForcibly() }
            }
        }
    }

    "does not detect stall" - {

        "CPU-bound computation" in {
            assertNotStalled { (warmedUp, stop) =>
                val end = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) ()
                warmedUp.countDown()
                while (!stop.get()) ()
            }
        }

        "Thread.yield loop" in {
            assertNotStalled { (warmedUp, stop) =>
                val end = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) Thread.`yield`()
                warmedUp.countDown()
                while (!stop.get()) Thread.`yield`()
            }
        }

        "busy-spin with AtomicInteger" in {
            assertNotStalled { (warmedUp, stop) =>
                val counter = new java.util.concurrent.atomic.AtomicInteger(0)
                val end     = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) { val _ = counter.incrementAndGet() }
                warmedUp.countDown()
                while (!stop.get()) { val _ = counter.incrementAndGet() }
            }
        }
    }

    "state transitions" - {

        "stall clears when thread resumes" in {
            val detector  = new CPUStallMonitor(1)
            val started   = new CountDownLatch(1)
            val unblock   = new CountDownLatch(1)
            val computing = new CountDownLatch(1)
            val stop      = new AtomicBoolean(false)
            val threadId  = new AtomicLong(0L)
            val thread = new Thread((() => {
                threadId.set(ThreadUserTime.currentThreadId())
                started.countDown()
                try { val _ = unblock.await(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
                val end = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) ()
                computing.countDown()
                while (!stop.get()) ()
            }): Runnable)
            thread.setDaemon(true)
            thread.start()
            assert(started.await(5, TimeUnit.SECONDS))

            val ids = Array(threadId.get())

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isStalled(0), "should detect stall while blocked")
            }

            unblock.countDown()
            assert(computing.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(!detector.isStalled(0), "stall should clear after resume")
            }

            stop.set(true)
            thread.join(5000)
        }

        "stall detection resets on resume" in {
            val detector = new CPUStallMonitor(1)
            val started  = new CountDownLatch(1)
            val unblock  = new CountDownLatch(1)
            val warmedUp = new CountDownLatch(1)
            val stop     = new AtomicBoolean(false)
            val threadId = new AtomicLong(0L)
            val thread = new Thread((() => {
                threadId.set(ThreadUserTime.currentThreadId())
                started.countDown()
                try { val _ = unblock.await(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
                val end = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) ()
                warmedUp.countDown()
                while (!stop.get()) ()
            }): Runnable)
            thread.setDaemon(true)
            thread.start()
            assert(started.await(5, TimeUnit.SECONDS))

            val ids = Array(threadId.get())

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isStalled(0))
            }

            unblock.countDown()
            assert(warmedUp.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(!detector.isStalled(0))
            }

            stop.set(true)
            thread.join(5000)
        }

        "multiple positions tracked independently" in {
            val detector = new CPUStallMonitor(2)
            val started  = new CountDownLatch(2)
            val stop     = new AtomicBoolean(false)
            val blocker  = new CountDownLatch(1)
            val warmedUp = new CountDownLatch(1)
            val tid0     = new AtomicLong(0L)
            val tid1     = new AtomicLong(0L)

            val t0 = new Thread((() => {
                tid0.set(ThreadUserTime.currentThreadId())
                started.countDown()
                try { val _ = blocker.await(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
            }): Runnable)
            val t1 = new Thread((() => {
                tid1.set(ThreadUserTime.currentThreadId())
                started.countDown()
                val end = System.nanoTime() + 20000000L
                while (System.nanoTime() < end) ()
                warmedUp.countDown()
                while (!stop.get()) ()
            }): Runnable)
            t0.setDaemon(true)
            t1.setDaemon(true)
            t0.start()
            t1.start()
            assert(started.await(5, TimeUnit.SECONDS))
            assert(warmedUp.await(5, TimeUnit.SECONDS))

            val ids = Array(tid0.get(), tid1.get())
            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 2)
                assert(detector.isStalled(0), "blocking thread should be stalled")
                assert(!detector.isStalled(1), "computing thread should not be stalled")
            }

            blocker.countDown()
            stop.set(true)
            t0.join(5000)
            t1.join(5000)
        }
    }

    "edge cases" - {

        "first sample is always baseline — no detection" in {
            val detector = new CPUStallMonitor(1)
            val started  = new CountDownLatch(1)
            val done     = new CountDownLatch(1)
            val threadId = new AtomicLong(0L)
            val thread = new Thread((() => {
                threadId.set(ThreadUserTime.currentThreadId())
                started.countDown()
                try { val _ = done.await(30, TimeUnit.SECONDS) }
                catch { case _: InterruptedException => () }
            }): Runnable)
            thread.setDaemon(true)
            thread.start()
            assert(started.await(5, TimeUnit.SECONDS))

            val ids = Array(threadId.get())
            detector.sample(ids, 1)
            assert(!detector.isStalled(0), "first sample must be baseline only")

            done.countDown()
            thread.join(5000)
        }
    }
}
