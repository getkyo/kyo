package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Yardstick INV-12 (write-side half): the queued outbound is flushed to the driver BEFORE the fd is closed. The teardown's
  * ReleaseRequested -> AwaitingInFlight gate waits on the WRITE-side drain (the WritePump takes the closing outbound channel to empty and writes
  * each span, then re-enters closeFn), so every queued span is written before closeHandle runs.
  *
  * The SpyDriver records whether any `write` lands AFTER `closeHandle` (an ordering violation) and counts the writes; the closeHandle promise is
  * the deterministic settle point (no sleep). Pins: INV-12, R-036.
  */
class ConnectionOutboundFlushOnCloseTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver extends IoDriver[Unit]:
        val writeCount      = AtomicInt.Unsafe.init(0)
        val closeHandleSeen = AtomicBoolean.Unsafe.init(false)
        val writeAfterClose = AtomicBoolean.Unsafe.init(false)
        val closeHandleDone = Promise.Unsafe.init[Unit, Any]()

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
            if closeHandleSeen.get() then writeAfterClose.set(true)
            discard(writeCount.incrementAndGet())
            WriteResult.Done
        end write
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit =
            closeHandleSeen.set(true)
            discard(closeHandleDone.complete(Result.succeed(())))
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "SpyDriver"
        def handleLabel(handle: Unit): String       = "spy"
    end SpyDriver

    "queued-outbound-flushed-before-fd-close" in {
        val spy   = new SpyDriver
        val conn  = Connection.init[Unit]((), spy, 8)
        val spans = List(Span(1.toByte), Span(2.toByte), Span(3.toByte))
        conn.start()
        for
            _ <- Sync.defer(spans.foreach(s => discard(conn.outbound.offer(s))))
            _ <- Sync.defer(conn.close())
            _ <- spy.closeHandleDone.safe.get
        yield
            assert(!spy.writeAfterClose.get(), "no outbound write may land after the fd close (the write-side drain gates the close)")
            assert(
                spy.writeCount.get() == spans.size,
                s"all ${spans.size} queued outbound spans must be flushed before the close, got ${spy.writeCount.get()}"
            )
        end for
    }

end ConnectionOutboundFlushOnCloseTest
