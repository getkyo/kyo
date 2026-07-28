package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** detachForUpgrade does NOT tear down the fd.
  *
  * When a STARTTLS upgrade detaches a connection (Established -> Upgrading), the underlying socket must stay open so the TLS engine can
  * drive the handshake on it. The fd is the TLS upgrade's to manage; the Connection must not call closeHandle. A subsequent close() on the
  * detached connection arrives with state == Upgrading: neither CAS (Established -> Closing, Created -> Closing) wins, so teardownHandle is
  * never reached and closeHandle stays at 0.
  */
class ConnectionDetachForUpgradeTest extends Test:

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

    "detach-for-upgrade-does-not-tear-down-fd: closeHandle is NOT called after detachForUpgrade" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        Sync.defer {
            val result = conn.detachForUpgrade()
            // detachForUpgrade returns Present when it wins the CAS (Established -> Upgrading).
            assert(result.isDefined, "detachForUpgrade on an Established connection must return Present")
            assert(
                spy.closeHandleCount.get() == 0,
                s"closeHandle must NOT be called after detachForUpgrade, got ${spy.closeHandleCount.get()}"
            )
        }
    }

    "detach-for-upgrade-does-not-tear-down-fd: a close() after detach also does not call closeHandle" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        Sync.defer {
            discard(conn.detachForUpgrade())
            conn.close()
            assert(
                spy.closeHandleCount.get() == 0,
                s"closeHandle must NOT be called even after detachForUpgrade + close(), got ${spy.closeHandleCount.get()}"
            )
        }
    }

end ConnectionDetachForUpgradeTest
