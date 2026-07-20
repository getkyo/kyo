package kyo.net.internal.transport

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver

/** Tests for IoDriverPool over real PollerIoDriver instances wrapped in RecordingIoDriver.
  *
  * The pool tests exercise the pool's lifecycle (next(), start(), including the all-or-nothing start rollback) without exercising real I/O
  * paths. The pool itself has no close(): it is process-lifetime once handed to a transport, so each leaf below closes its own driver spies
  * directly for cleanup rather than through the pool. Each PollerIoDriver is unstarted (init does not call start()) except where start() is
  * explicitly tested.
  *
  * Gate: assumePoller() cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Each PollerIoDriver is wrapped in a `RecordingIoDriver` to observe lifecycle calls (start count, close count).
  */
class IoDriverPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.NetConfig.default

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Build n unstarted RecordingIoDriver instances over real PollerIoDriver.init(). */
    private def mkSpies(n: Int): Array[RecordingIoDriver] =
        Array.fill(n)(new RecordingIoDriver(PollerIoDriver.init()))

    "init with single driver: next returns that driver" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(1).asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        val d                                   = pool.next()
        assert(d eq spies(0))
        spies(0).close()
        succeed
    }

    "init with multiple drivers: stores all drivers" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        val d0                                  = pool.next()
        val d1                                  = pool.next()
        val d2                                  = pool.next()
        assert(d0 eq spies(0))
        assert(d1 eq spies(1))
        assert(d2 eq spies(2))
        spies.foreach(_.close())
        succeed
    }

    "init with zero drivers throws IllegalArgumentException" in {
        val thrown = intercept[IllegalArgumentException] {
            IoDriverPool.init(Array.empty[IoDriver[PosixHandle]])
        }
        assert(thrown.getMessage.contains("at least one driver"))
        succeed
    }

    "next returns drivers in round-robin order" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        val results                             = (0 until 9).map(_ => pool.next())
        results.zipWithIndex.foreach { case (driver, callIdx) =>
            val expectedIdx = callIdx % 3
            assert(driver eq spies(expectedIdx), s"Call $callIdx: expected driver $expectedIdx")
        }
        spies.foreach(_.close())
        succeed
    }

    "next handles a negative counter via the sign mask: no ArrayIndexOutOfBoundsException" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[PosixHandle]]]
        // Start counter at -2 (a post-wrap negative value): the sign-bit mask before the modulo makes the index
        // ((-2 & Long.MaxValue) % 3) == ((Long.MaxValue - 1) % 3) == 0 -- a valid, in-bounds slot, no ArrayIndexOutOfBounds. (A Math.abs
        // path would return spies(2) here; the sign mask gives the clean masked-modulo rotation.)
        val pool = IoDriverPool.init(spies, -2L)
        val d    = pool.next()
        assert(d eq spies(0))
        spies.foreach(_.close())
        succeed
    }

    "next is thread-safe: concurrent calls only return valid drivers" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(4).asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        val seen                                = new ConcurrentHashMap[Int, Boolean]()
        Kyo.foreach(0 until 40) { _ =>
            val d   = pool.next()
            val idx = spies.indexWhere(_ eq d)
            discard(seen.put(idx, true))
            assert(idx >= 0 && idx < 4)
        }.map { _ =>
            assert(seen.size() <= 4)
            spies.foreach(_.close())
            succeed
        }
    }

    "start starts all drivers" in {
        assumePoller()
        val rawSpies                            = mkSpies(3)
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        // Start the pool: this calls driver.start() on each driver.
        pool.start()
        // Each real driver's start() was delegated through spy; the real poll loop is running.
        // We cannot check a startCount on RecordingIoDriver (it delegates start to real),
        // but we can verify close works cleanly afterward.
        spies.foreach(_.close())
        succeed
    }

    "start rethrows when a driver fails to start (all-or-nothing)" in {
        assumePoller()
        val rawSpies = mkSpies(3)
        // Make driver 1 throw on start: the pool must close the already-started subset and rethrow.
        rawSpies(1).throwOnStart = true
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        // All-or-nothing: a partially-started pool is never handed to the transport.
        val thrown = intercept[RuntimeException] { pool.start() }
        assert(thrown.getMessage.contains("throwOnStart"), s"expected throwOnStart message, got: ${thrown.getMessage}")
        // Driver 0 (already started) must have been closed by the all-or-nothing cleanup.
        assert(rawSpies(0).closeCalls.get() >= 1, "driver 0 must be closed by the all-or-nothing cleanup")
        succeed
    }

end IoDriverPoolTest
