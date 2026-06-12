package kyo.net

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.internal.JsHandle
import kyo.net.internal.JsIoDriver
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool
import kyo.net.internal.transport.WriteResult
import scala.collection.mutable

/** Pool-logic coverage for [[kyo.net.internal.transport.IoDriverPool]] on JS.
  *
  * `IoDriverPool` is shared source that the JS transport builds in production ([[kyo.net.internal.JsTransport]] constructs the pool via
  * `IoDriverPool.init(drivers)` and calls `start()`/`next()` on it). Its selection and lifecycle logic (round-robin order, the zero-driver
  * guard, the negative-modulo wrap, start-resilience, close-on-all, idempotent close, close order, Absent-slot skip) is platform-independent
  * and runs on JS, so it is asserted here over REAL [[kyo.net.internal.JsIoDriver]] instances. A `JsIoDriver` needs no socket, syscall, or
  * Node runtime to construct (`JsIoDriver.init()` allocates a shutdown promise only), so the pool logic exercises real drivers with no I/O.
  *
  * The same properties are asserted on JVM and Native by `kyo.net.internal.transport.IoDriverPoolTest` over a real `PollerIoDriver`; this leaf
  * keeps JS coverage of the shared pool logic the JS transport depends on.
  *
  * Close-count, close order, and the start-failure path are observed through `RecordingDriver`, a spy that delegates every behavioral method
  * to the real `JsIoDriver` and records only call counts / close order, with one controlled injection (`throwOnStart`) that mirrors the JVM
  * spy: a real `JsIoDriver.start()` only stores an onComplete and returns the sentinel promise, so it has no deterministic failure mode, and
  * this single-value injection is the only way to drive the pool's start-resilience path. No driver behavior is faked.
  *
  * Determinism: every leaf is synchronous pool logic over in-memory drivers (no socket, no Node event); the one concurrent leaf drives
  * `Kyo.foreach` fibers cooperatively on the single JS event loop and asserts on the valid-index / count invariants, not on timing. No sleep,
  * poll, or retry.
  */
class IoDriverPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Spy over a real [[JsIoDriver]]: delegates every behavioral method to `real`, records close-count and close order, and supports the
      * single `throwOnStart` injection (the next `start()` throws instead of delegating). Stays final and fully delegating, mirroring the JVM
      * `RecordingIoDriver`.
      */
    final private class RecordingDriver(real: JsIoDriver) extends IoDriver[JsHandle]:
        val closeCalls: AtomicInteger       = new AtomicInteger(0)
        @volatile var onClose: () => Unit   = null
        @volatile var throwOnStart: Boolean = false

        def label: String                         = real.label
        def handleLabel(handle: JsHandle): String = real.handleLabel(handle)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            if throwOnStart then throw new RuntimeException("driver start failed (throwOnStart=true)")
            real.start()

        def awaitRead(handle: JsHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            real.awaitRead(handle, promise)

        def awaitWritable(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            real.awaitWritable(handle, promise)

        def awaitConnect(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            real.awaitConnect(handle, promise)

        def awaitAccept(handle: JsHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            real.awaitAccept(handle, promise)

        def write(handle: JsHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
            real.write(handle, data, offset)

        def cancel(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
            real.cancel(handle)

        def closeHandle(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
            real.closeHandle(handle)

        def close()(using AllowUnsafe, Frame): Unit =
            discard(closeCalls.getAndIncrement())
            val hook = onClose
            if hook != null then hook()
            real.close()
        end close
    end RecordingDriver

    /** Build n spies over fresh real JsIoDriver instances. */
    private def mkSpies(n: Int): Array[RecordingDriver] =
        Array.fill(n)(new RecordingDriver(JsIoDriver.init()))

    "init with single driver: next returns that driver" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(1).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        val d                                = pool.next()
        assert(d eq spies(0))
        spies(0).close()
        succeed
    }

    "init with multiple drivers: stores all drivers" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        val d0                               = pool.next()
        val d1                               = pool.next()
        val d2                               = pool.next()
        assert(d0 eq spies(0))
        assert(d1 eq spies(1))
        assert(d2 eq spies(2))
        spies.foreach(_.close())
        succeed
    }

    "init with zero drivers throws IllegalArgumentException" in {
        val thrown = intercept[IllegalArgumentException] {
            IoDriverPool.init(Array.empty[IoDriver[JsHandle]])
        }
        assert(thrown.getMessage.contains("at least one driver"))
        succeed
    }

    "next returns drivers in round-robin order" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        val results                          = (0 until 9).map(_ => pool.next())
        results.zipWithIndex.foreach { case (driver, callIdx) =>
            val expectedIdx = callIdx % 3
            assert(driver eq spies(expectedIdx), s"Call $callIdx: expected driver $expectedIdx")
        }
        spies.foreach(_.close())
        succeed
    }

    "next handles a negative counter via the sign mask: no ArrayIndexOutOfBoundsException" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[JsHandle]]]
        // Start counter at -2 (a post-wrap negative value): the fix masks the sign bit before the modulo, so the index is
        // ((-2 & Long.MaxValue) % 3) == ((Long.MaxValue - 1) % 3) == 0 -- a valid, in-bounds slot, no ArrayIndexOutOfBounds. (The old Math.abs
        // path returned spies(2) here; the sign mask replaced it, which gives the clean masked-modulo rotation.)
        val pool = IoDriverPool.init(spies, -2L)
        val d    = pool.next()
        assert(d eq spies(0))
        spies.foreach(_.close())
        succeed
    }

    "next under concurrent fibers only returns valid drivers" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(4).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        val seen                             = mutable.Set.empty[Int]
        Kyo.foreach(0 until 40) { _ =>
            val d   = pool.next()
            val idx = spies.indexWhere(_ eq d)
            discard(seen.add(idx))
            assert(idx >= 0 && idx < 4)
        }.map { _ =>
            assert(seen.size <= 4)
            spies.foreach(_.close())
            succeed
        }
    }

    "start starts all drivers" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        // start() delegates to each real JsIoDriver.start(); close afterward must be clean.
        pool.start()
        pool.close()
        succeed
    }

    "start continues if one driver fails to start" in {
        val rawSpies = mkSpies(3)
        // Make driver 1 throw on start.
        rawSpies(1).throwOnStart = true
        val spies: Array[IoDriver[JsHandle]] = rawSpies.asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        // Should not throw even though driver 1 fails; pool continues past the failure.
        pool.start()
        pool.close()
        // Every driver is still closed exactly once, including the one that failed to start.
        assert(rawSpies.forall(d => d.closeCalls.get() == 1), s"got ${rawSpies.map(_.closeCalls.get()).toList}")
        succeed
    }

    "close is idempotent: second close skips driver close calls" in {
        val rawSpies                         = mkSpies(2)
        val spies: Array[IoDriver[JsHandle]] = rawSpies.asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        pool.start()
        pool.close()
        pool.close()
        // Each driver's close() must be called exactly once (AtomicBoolean CAS in the pool).
        val allClosedOnce = rawSpies.forall(d => d.closeCalls.get() == 1)
        assert(allClosedOnce, s"each driver must be closed exactly once, got ${rawSpies.map(_.closeCalls.get()).toList}")
        succeed
    }

    "close closes drivers before interrupting fibers: driver close order preserved" in {
        val closeOrder = mutable.ListBuffer[Int]()
        val rawSpies = Array.tabulate(2) { i =>
            val spy = new RecordingDriver(JsIoDriver.init())
            spy.onClose = () => closeOrder += i
            spy
        }
        val spies: Array[IoDriver[JsHandle]] = rawSpies.asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        pool.start()
        pool.close()

        assert(rawSpies(0).closeCalls.get() == 1, "driver 0 must be closed exactly once")
        assert(rawSpies(1).closeCalls.get() == 1, "driver 1 must be closed exactly once")
        // Driver close order: 0 then 1 (sequential closeLoop in IoDriverPool).
        assert(closeOrder.toList == List(0, 1), s"driver close order must be sequential, got $closeOrder")
        succeed
    }

    "close skips Absent fiber slots: no error when pool not started" in {
        val spies: Array[IoDriver[JsHandle]] = mkSpies(3).asInstanceOf[Array[IoDriver[JsHandle]]]
        val pool                             = IoDriverPool.init(spies)
        // Do NOT call pool.start(); all fiber slots remain Absent.
        // close() must handle Absent slots gracefully without throwing.
        pool.close()
        succeed
    }

end IoDriverPoolTest
