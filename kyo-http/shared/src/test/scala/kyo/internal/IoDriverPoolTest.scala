package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise
import scala.collection.mutable

class IoDriverPoolTest extends kyo.Test:

    import AllowUnsafe.embrace.danger

    /** A minimal mock IoDriver that records lifecycle events. */
    class TrackingDriver(val id: Int) extends IoDriver[String]:
        val startCount                          = new AtomicInteger(0)
        val closeCount                          = new AtomicInteger(0)
        @volatile var startShouldThrow: Boolean = false

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            if startShouldThrow then throw new RuntimeException(s"Driver $id start failed")
            discard(startCount.incrementAndGet())
            new IOPromise[Nothing, Unit < Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        end start

        def awaitRead(handle: String, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: String, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
        def awaitConnect(handle: String, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: String, data: Span[Byte])(using AllowUnsafe): WriteResult                                       = WriteResult.Done
        def cancel(handle: String)(using AllowUnsafe, Frame): Unit                                                        = ()
        def closeHandle(handle: String)(using AllowUnsafe, Frame): Unit                                                   = ()

        def close()(using AllowUnsafe, Frame): Unit =
            discard(closeCount.incrementAndGet())

        def label: String                       = s"TrackingDriver($id)"
        def handleLabel(handle: String): String = s"handle=$handle"
    end TrackingDriver

    def mkRawDrivers(n: Int): Array[TrackingDriver] = Array.tabulate(n)(i => new TrackingDriver(i))
    def mkDrivers(n: Int): Array[IoDriver[String]]  = mkRawDrivers(n).asInstanceOf[Array[IoDriver[String]]]

    "init with single driver — next returns that driver" in {
        val drivers = mkDrivers(1)
        val pool    = IoDriverPool.init(drivers)
        val d       = pool.next()
        assert(d eq drivers(0))
        succeed
    }

    "init with multiple drivers — stores all drivers" in {
        val drivers = mkDrivers(3)
        val pool    = IoDriverPool.init(drivers)
        val d0      = pool.next()
        val d1      = pool.next()
        val d2      = pool.next()
        assert(d0 eq drivers(0))
        assert(d1 eq drivers(1))
        assert(d2 eq drivers(2))
        succeed
    }

    "init with zero drivers throws IllegalArgumentException" in {
        val thrown = intercept[IllegalArgumentException] {
            IoDriverPool.init(Array.empty[IoDriver[String]])
        }
        assert(thrown.getMessage.contains("at least one driver"))
        succeed
    }

    "next returns drivers in round-robin order" in {
        val drivers = mkDrivers(3)
        val pool    = IoDriverPool.init(drivers)
        val results = (0 until 9).map(_ => pool.next())
        // Expected pattern: 0,1,2,0,1,2,0,1,2
        results.zipWithIndex.foreach { case (driver, callIdx) =>
            val expectedIdx = callIdx % 3
            assert(driver eq drivers(expectedIdx), s"Call $callIdx: expected driver $expectedIdx")
        }
        succeed
    }

    "next handles negative modulo via Math.abs — no ArrayIndexOutOfBoundsException" in {
        // When the atomic counter wraps or is set to a negative value, (counter % length)
        // can be negative on JVM. Math.abs in next() prevents negative array indexing.
        val drivers = mkDrivers(3)
        // Start counter at -2: (-2 % 3).toInt == -2, Math.abs(-2) == 2, so drivers(2) is returned
        val pool = IoDriverPool.init(drivers, -2L)
        val d    = pool.next()
        assert(d eq drivers(2))
        succeed
    }

    "next is thread-safe — concurrent calls only return valid drivers" in run {
        val drivers = mkDrivers(4)
        val pool    = IoDriverPool.init(drivers)
        val seen    = new ConcurrentHashMap[Int, Boolean]()
        Kyo.foreach(0 until 40) { _ =>
            val d   = pool.next()
            val idx = drivers.indexWhere(_ eq d)
            discard(seen.put(idx, true))
            assert(idx >= 0 && idx < 4)
        }.map(_ => assert(seen.size() <= 4))
    }

    "start starts all drivers" in {
        val rawDrivers                       = mkRawDrivers(3)
        val drivers: Array[IoDriver[String]] = rawDrivers.asInstanceOf[Array[IoDriver[String]]]
        val pool                             = IoDriverPool.init(drivers)
        pool.start()
        val allStarted = rawDrivers.forall(d => d.startCount.get() == 1)
        assert(allStarted)
        succeed
    }

    "start continues if one driver fails to start" in {
        val rawDrivers = mkRawDrivers(3)
        rawDrivers(1).startShouldThrow = true
        val drivers: Array[IoDriver[String]] = rawDrivers.asInstanceOf[Array[IoDriver[String]]]
        val pool                             = IoDriverPool.init(drivers)
        // Should not throw even though driver 1 fails
        pool.start()
        assert(rawDrivers(0).startCount.get() == 1)
        assert(rawDrivers(1).startCount.get() == 0) // failed, never incremented
        assert(rawDrivers(2).startCount.get() == 1)
        succeed
    }

    "close is idempotent — second close skips driver close calls" in {
        val rawDrivers                       = mkRawDrivers(2)
        val drivers: Array[IoDriver[String]] = rawDrivers.asInstanceOf[Array[IoDriver[String]]]
        val pool                             = IoDriverPool.init(drivers)
        pool.start()
        pool.close()
        pool.close()
        // Each driver's close() should be called exactly once
        val allClosedOnce = rawDrivers.forall(d => d.closeCount.get() == 1)
        assert(allClosedOnce)
        succeed
    }

    "close closes drivers before interrupting fibers — driver close order preserved" in {
        val closeOrder = mutable.ListBuffer[String]()
        val rawDrivers = Array.tabulate(2)(i =>
            new TrackingDriver(i):
                override def close()(using AllowUnsafe, Frame): Unit =
                    closeOrder += s"driver-close-$id"
                    discard(closeCount.incrementAndGet())
        )
        val drivers: Array[IoDriver[String]] = rawDrivers.asInstanceOf[Array[IoDriver[String]]]
        val pool                             = IoDriverPool.init(drivers)
        pool.start()
        pool.close()

        // Both drivers must be closed
        assert(rawDrivers(0).closeCount.get() == 1)
        assert(rawDrivers(1).closeCount.get() == 1)
        // Driver close order: 0 then 1 (sequential closeLoop)
        assert(closeOrder.toList == List("driver-close-0", "driver-close-1"))
        succeed
    }

    "close skips Absent fiber slots — no error when pool not started" in {
        val drivers: Array[IoDriver[String]] = mkDrivers(3)
        val pool                             = IoDriverPool.init(drivers)
        // Do NOT call pool.start() — all fiber slots remain Absent
        // close() must handle Absent slots gracefully without throwing
        pool.close()
        succeed
    }

end IoDriverPoolTest
