package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise

class ConnectionTest extends kyo.Test:

    import AllowUnsafe.embrace.danger

    /** A mock IoDriver for testing. Records calls and allows programmatic control. */
    class MockDriver extends IoDriver[String]:
        val readCallCount     = new AtomicInteger(0)
        val writableCallCount = new AtomicInteger(0)
        val cancelCallCount   = new AtomicInteger(0)
        val closeHandleCount  = new AtomicInteger(0)
        val startCallCount    = new AtomicInteger(0)

        // Captured promises for manual completion
        val lastReadPromise = new AtomicReference[Option[Promise.Unsafe[Span[Byte], Abort[Closed]]]](None)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            discard(startCallCount.incrementAndGet())
            val p = new IOPromise[Nothing, Unit < Any]()
            p.asInstanceOf[Fiber.Unsafe[Unit, Any]]
        end start

        def awaitRead(handle: String, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            discard(readCallCount.incrementAndGet())
            lastReadPromise.set(Some(promise))

        def awaitWritable(handle: String, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            discard(writableCallCount.incrementAndGet())

        def awaitConnect(handle: String, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()

        def write(handle: String, data: Span[Byte])(using AllowUnsafe): WriteResult = WriteResult.Done

        def cancel(handle: String)(using AllowUnsafe, Frame): Unit =
            discard(cancelCallCount.incrementAndGet())

        def closeHandle(handle: String)(using AllowUnsafe, Frame): Unit =
            discard(closeHandleCount.incrementAndGet())

        def close()(using AllowUnsafe, Frame): Unit = ()

        def label: String                       = "MockDriver"
        def handleLabel(handle: String): String = s"handle=$handle"
    end MockDriver

    "init creates channels and handle" in {
        val driver = new MockDriver
        val conn   = Connection.init("handle1", driver, channelCapacity = 8)
        assert(conn.handle == "handle1")
        assert(!conn.inbound.closed())
        assert(!conn.outbound.closed())
        assert(conn.isOpen)
        succeed
    }

    "isOpen returns true before close and false after" in {
        val driver = new MockDriver
        val conn   = Connection.init("handle1", driver, channelCapacity = 8)
        assert(conn.isOpen)
        conn.close()
        assert(!conn.isOpen)
        succeed
    }

    "close is idempotent — second close is a no-op" in {
        val driver = new MockDriver
        val conn   = Connection.init("handle1", driver, channelCapacity = 8)
        conn.close()
        conn.close()
        // cancel and closeHandle must each be called exactly once
        assert(driver.cancelCallCount.get() == 1)
        assert(driver.closeHandleCount.get() == 1)
        succeed
    }

    "close closes channels and calls driver cancel and closeHandle" in {
        val closeOrder = scala.collection.mutable.ListBuffer[String]()
        val driver = new MockDriver:
            override def cancel(handle: String)(using AllowUnsafe, Frame): Unit =
                discard(cancelCallCount.incrementAndGet())
                closeOrder += "cancel"
            override def closeHandle(handle: String)(using AllowUnsafe, Frame): Unit =
                discard(closeHandleCount.incrementAndGet())
                closeOrder += "closeHandle"

        val conn = Connection.init("handle1", driver, channelCapacity = 8)
        conn.close()

        // Channels are closed
        assert(conn.inbound.closed())
        assert(conn.outbound.closed())
        // Driver was called
        assert(driver.cancelCallCount.get() == 1)
        assert(driver.closeHandleCount.get() == 1)
        // cancel comes before closeHandle (as specified in closeFn)
        assert(closeOrder.toList == List("cancel", "closeHandle"))
        succeed
    }

    "start registers first read with driver" in {
        val driver = new MockDriver
        val conn   = Connection.init("handle1", driver, channelCapacity = 8)
        conn.start()
        // ReadPump.start calls driver.awaitRead once
        assert(driver.readCallCount.get() == 1)
        succeed
    }

    "channel backpressure — full inbound channel stops read re-registration" in run {
        val driver = new MockDriver
        // Capacity 1: the channel can hold at most one item
        val conn = Connection.init("handle1", driver, channelCapacity = 1)
        conn.start()
        assert(driver.readCallCount.get() == 1)

        // Pre-fill the inbound channel so it is full BEFORE the read promise completes
        val prefill = Span.fromUnsafe("prefill".getBytes)
        discard(conn.inbound.offer(prefill)) // fills the channel

        // Get the captured read promise and complete it with new data
        val readPromise = driver.lastReadPromise.get()
        assert(readPromise.isDefined)
        val data = Span.fromUnsafe("hello".getBytes)

        // Complete the read — channel is full, so offer returns false → backpressure kicks in
        // ReadPump will NOT call awaitRead again until the channel drains
        readPromise.get.asInstanceOf[IOPromise[Closed, Span[Byte]]].completeDiscard(Result.succeed(data))

        // Give the callback time to fire
        Async.sleep(100.millis).map { _ =>
            // Channel was full when offer was attempted → ReadPump backed off → no second read registration
            assert(driver.readCallCount.get() == 1)
        }
    }

end ConnectionTest
