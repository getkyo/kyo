package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.HandleLife
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.transport.WriteResult

/** A closing connection completes its teardown. close() drives the Connection.Teardown machine to Released, which closes the handle
  * exactly once.
  *
  * The SpyDriver counts release.
  */
class ConnectionTeardownCompletesTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver extends IoDriver[Unit]:
        val releaseCount = AtomicInt.Unsafe.init(0)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit             = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
        private val handleLife                                                                 = HandleLife.init()
        def life(handle: Unit): HandleLife                                                     = handleLife
        def release(handle: Unit, mode: ReleaseMode, released: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Unit =
            discard(releaseCount.incrementAndGet())
            released.completeUnitDiscard()
        end release
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def runOwned(handle: Unit)(op: () => Unit)(using AllowUnsafe, Frame): Unit           = op()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "SpyDriver"
        def handleLabel(handle: Unit): String       = "spy"
    end SpyDriver

    "closing-connection-completes-teardown" in {
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        conn.start()
        Sync.defer {
            conn.close()
            assert(spy.releaseCount.get() == 1, s"teardown must close the handle exactly once, got ${spy.releaseCount.get()}")
            assert(!conn.isOpen, "the connection must not be open once its teardown has completed")
        }
    }

end ConnectionTeardownCompletesTest
