package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Yardstick CONC-4: the documented concurrent-use contract for close() and isOpen.
  *
  * close() is idempotent: multiple concurrent calls produce the same terminal state as one. isOpen reports a consistent boolean that
  * reflects the named state: once the state has settled to Closing or Closed, isOpen returns false for all subsequent reads, regardless of
  * the carrier that reads it. The latch drives all carriers to start simultaneously; no sleep is used.
  */
class ConnectionConcurrentCloseTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver extends IoDriver[Unit]:
        val closeHandleCount = AtomicInt.Unsafe.init(0)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                           = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                         = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCount.incrementAndGet())
        def close()(using AllowUnsafe, Frame): Unit                   = ()
        def label: String                                             = "SpyDriver"
        def handleLabel(handle: Unit): String                         = "spy"
    end SpyDriver

    "concurrent-close-idempotent: three concurrent close() calls settle to one closeHandle invocation" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        for
            latch  <- Latch.init(1)
            fiber1 <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            fiber2 <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            fiber3 <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            _      <- latch.release
            _      <- fiber1.get
            _      <- fiber2.get
            _      <- fiber3.get
        yield assert(spy.closeHandleCount.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCount.get()}")
        end for
    }

    "isOpen-consistent: isOpen returns false after close() settles" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        for
            latch      <- Latch.init(1)
            closeFiber <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            _          <- latch.release
            _          <- closeFiber.get
        yield
            // After the close fiber completes, the state has settled to Closing or Closed.
            // isOpen must return false on the observing carrier.
            val open = conn.isOpen
            assert(!open, s"isOpen must return false after close() has settled, got $open")
        end for
    }

    "isOpen-consistent: concurrent close() and isOpen do not observe an impossible state" in {
        // Many concurrent close() + isOpen calls: isOpen may return true (before close wins) or false
        // (after close wins), but it must never return true once the connection has settled to Closed.
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        for
            latch      <- Latch.init(1)
            closeFiber <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            readFibers <- Fiber.init {
                Kyo.foreach(Chunk.from(1 to 4))(_ => Fiber.init { latch.await.map(_ => Sync.defer(conn.isOpen)) })
            }
            _      <- latch.release
            _      <- closeFiber.get
            fibers <- readFibers.get
            reads  <- Kyo.foreach(fibers)(_.get)
        yield
            // After the close fiber joined, the state is settled. Every isOpen read from after the settle must be false.
            // (Reads taken before the settle may have seen true; both are valid concurrent observations.)
            val finalOpen = conn.isOpen
            assert(!finalOpen, s"isOpen must be false after close() settled, got $finalOpen")
            // The total count of closeHandle calls is exactly 1 (not 0, not more).
            assert(spy.closeHandleCount.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCount.get()}")
            discard(reads) // reads observed during the race (valid true or false); the post-settle assertion above is the invariant
        end for
    }

end ConnectionConcurrentCloseTest
