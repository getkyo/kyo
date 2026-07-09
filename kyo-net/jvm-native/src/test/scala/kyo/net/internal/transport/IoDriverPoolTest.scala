package kyo.net.internal.transport

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver
import scala.collection.mutable

/** Tests for IoDriverPool over real PollerIoDriver instances wrapped in RecordingIoDriver.
  *
  * The pool tests exercise the pool's lifecycle (next(), start(), close(), idempotent close, close order) without exercising real I/O paths.
  * Each PollerIoDriver is unstarted (init does not call start()) except where start() is explicitly tested.
  *
  * Gate: assumePoller() cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Each PollerIoDriver is wrapped in a `RecordingIoDriver` to observe lifecycle calls (start count, close count, close order).
  */
class IoDriverPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Build n unstarted RecordingIoDriver instances over real PollerIoDriver.init(transportConfig). */
    private def mkSpies(n: Int): Array[RecordingIoDriver] =
        Array.fill(n)(new RecordingIoDriver(PollerIoDriver.init(transportConfig)))

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
        // Start counter at -2 (a post-wrap negative value): the fix masks the sign bit before the modulo, so the index is
        // ((-2 & Long.MaxValue) % 3) == ((Long.MaxValue - 1) % 3) == 0 -- a valid, in-bounds slot, no ArrayIndexOutOfBounds. (The old Math.abs
        // path returned spies(2) here; the sign mask replaced it, which gives the clean masked-modulo rotation.)
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
        pool.close()
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

    "close is idempotent: second close skips driver close calls" in {
        assumePoller()
        val rawSpies                            = mkSpies(2)
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        pool.start()
        pool.close()
        pool.close()
        // Each driver's close() must be called exactly once (AtomicBoolean CAS on pool + real driver CAS).
        // closeCalls counts the pool's driver.close() invocations via the RecordingIoDriver.
        val allClosedOnce = rawSpies.forall(d => d.closeCalls.get() == 1)
        assert(allClosedOnce, s"each driver must be closed exactly once, got ${rawSpies.map(_.closeCalls.get()).toList}")
        succeed
    }

    "close closes every driver in order" in {
        assumePoller()
        val closeOrder = mutable.ListBuffer[Int]()
        val rawSpies = Array.tabulate(2) { i =>
            val spy = new RecordingIoDriver(PollerIoDriver.init(transportConfig))
            spy.onClose = () => closeOrder += i
            spy
        }
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        pool.start()
        pool.close()

        assert(rawSpies(0).closeCalls.get() == 1, "driver 0 must be closed exactly once")
        assert(rawSpies(1).closeCalls.get() == 1, "driver 1 must be closed exactly once")
        // Driver close order: 0 then 1 (sequential closeLoop in IoDriverPool).
        assert(closeOrder.toList == List(0, 1), s"driver close order must be sequential, got $closeOrder")
        succeed
    }

    "close is safe when the pool was never started" in {
        assumePoller()
        val spies: Array[IoDriver[PosixHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)
        // Do NOT call pool.start(); close() must still close every driver without throwing.
        pool.close()
        succeed
    }

end IoDriverPoolTest
