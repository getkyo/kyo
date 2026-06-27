package kyo.net

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Yardstick LIVE-8: a pool/transport shutdown drains the in-flight per-driver closes BEFORE it stops the loops. `IoDriverPool.close` closes every
  * driver first (each driver's close drains its in-flight/deferred closes) and only then interrupts the loop fibers, so a close in flight when the
  * pool shuts down is not orphaned by a loop that stopped first.
  *
  * Each SpyDriver appends `close-<id>` when its close runs and `interrupt-<id>` when the loop fiber the pool started for it is interrupted (the
  * pool interrupts it with a Panic, firing the fiber's onComplete). The test pins that EVERY close precedes EVERY interrupt. Pins: LIVE-8.
  */
class LIVE8Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver(id: Int, events: ConcurrentLinkedQueue[String]) extends IoDriver[Unit]:
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            val p = Promise.Unsafe.init[Unit, Any]()
            p.onComplete(_ => discard(events.add(s"interrupt-$id")))
            p.asInstanceOf[Fiber.Unsafe[Unit, Any]]
        end start
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                           = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                         = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                    = ()
        def close()(using AllowUnsafe, Frame): Unit = discard(events.add(s"close-$id"))
        def label: String                           = s"SpyDriver-$id"
        def handleLabel(handle: Unit): String       = "spy"
    end SpyDriver

    "pool-shutdown-drains-in-flight-closes" in {
        val events                       = new ConcurrentLinkedQueue[String]()
        val spies: Array[IoDriver[Unit]] = Array(new SpyDriver(0, events), new SpyDriver(1, events))
        val pool                         = IoDriverPool.init(spies)
        Sync.defer {
            pool.start()
            pool.close()
            val seq        = scala.jdk.CollectionConverters.IterableHasAsScala(events).asScala.toList
            val lastClose  = seq.lastIndexWhere(_.startsWith("close-"))
            val firstInter = seq.indexWhere(_.startsWith("interrupt-"))
            assert(seq.count(_.startsWith("close-")) == 2, s"both drivers must be closed on shutdown, got $seq")
            assert(seq.count(_.startsWith("interrupt-")) == 2, s"both loop fibers must be interrupted on shutdown, got $seq")
            assert(
                lastClose < firstInter,
                s"every driver close (draining its in-flight closes) must precede every loop interrupt, got $seq"
            )
        }
    }

end LIVE8Test
