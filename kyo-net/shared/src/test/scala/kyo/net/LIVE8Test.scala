package kyo.net

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Yardstick LIVE-8: a pool shutdown never races a driver's own event-loop teardown with an external interrupt. `IoDriverPool.close` calls
  * every driver's close() and nothing else; it is each driver's own close() that is trusted to bring its loop down (a closed selector aborts
  * a blocked select(); the posix io_uring/poller drivers wake their loop and let it observe the close signal on its own carrier). An
  * interrupt issued by the pool right after signaling close could abort a driver's carrier-confined deferred-close draining before it
  * reached the code that reclaims a still in-flight handle's fd -- exactly the CLOSE_WAIT leak this yardstick exists to rule out.
  *
  * Each SpyDriver appends `close-<id>` when its close runs and would append `interrupt-<id>` if the loop fiber the pool started for it were
  * ever completed by an external interrupt. The test pins that every driver is closed exactly once and no loop fiber is ever interrupted by
  * the pool. Pins: LIVE-8.
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

    "pool-shutdown-never-interrupts-driver-loops" in {
        val events                       = new ConcurrentLinkedQueue[String]()
        val spies: Array[IoDriver[Unit]] = Array(new SpyDriver(0, events), new SpyDriver(1, events))
        val pool                         = IoDriverPool.init(spies)
        Sync.defer {
            pool.start()
            pool.close()
            val seq = scala.jdk.CollectionConverters.IterableHasAsScala(events).asScala.toList
            assert(seq.count(_.startsWith("close-")) == 2, s"both drivers must be closed on shutdown, got $seq")
            assert(
                seq.count(_.startsWith("interrupt-")) == 0,
                s"pool.close() must never interrupt a driver's own loop fiber (racing that interrupt against a driver's carrier-confined " +
                    s"deferred-close drain is the CLOSE_WAIT leak class this yardstick guards), got $seq"
            )
        }
    }

end LIVE8Test
