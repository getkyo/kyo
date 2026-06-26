package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WriteResult

/** Yardstick INV-13: teardown (cancel + closeHandle) runs exactly once across concurrent close() calls.
  *
  * The single CAS on Closing -> Closed is the structural guarantee: only the fiber that wins the CAS calls the teardown body; every other
  * concurrent close() call loses the CAS and is a no-op. No sleep; the latch drives the interleaving.
  */
class INV13Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver extends IoDriver[Unit]:
        val closeHandleCount = AtomicInt.Unsafe.init(0)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit      = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                          = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                        = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCount.incrementAndGet())
        def close()(using AllowUnsafe, Frame): Unit                   = ()
        def label: String                                             = "SpyDriver"
        def handleLabel(handle: Unit): String                         = "spy"
    end SpyDriver

    "teardown-runs-exactly-once: two concurrent close() calls invoke closeHandle exactly once" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        for
            latch  <- Latch.init(1)
            fiber1 <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            fiber2 <- Fiber.init { latch.await.map(_ => Sync.defer(conn.close())) }
            _      <- latch.release
            _      <- fiber1.get
            _      <- fiber2.get
        yield assert(spy.closeHandleCount.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCount.get()}")
        end for
    }

    "teardown-runs-exactly-once: three concurrent close() calls invoke closeHandle exactly once" in {
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

end INV13Test
