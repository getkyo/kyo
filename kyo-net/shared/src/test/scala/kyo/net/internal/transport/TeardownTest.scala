package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** Tests for the Connection.Teardown machine fold: the connection teardown handoff is one named cell (Live -> ReleaseRequested -> AwaitingInFlight ->
  * Released), the exactly-once release is the single CAS into Released gated by the per-backend `canRelease` predicate, and the gate waits on the
  * WRITE-side drain only, never the inbound read-side drain.
  *
  * Each leaf builds a Connection[Unit] over a SpyDriver that counts closeHandle calls. The predicate is injected per connection, so a latch-gated
  * predicate drives the in-flight-deferred release deterministically.
  */
class TeardownTest extends Test:

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

    "released-fires-exactly-once-with-in-flight-op" in {
        // The per-backend canRelease predicate gates AwaitingInFlight -> Released. A false predicate models an outstanding in-flight op: the
        // teardown reaches AwaitingInFlight but holds the release. When the predicate clears (the in-flight op completes) and the carriers retry,
        // exactly one CAS into Released wins, so closeHandle runs exactly once. No sleep; latches drive it.
        val spy        = new SpyDriver
        val canRelease = AtomicBoolean.Unsafe.init(false)
        val conn       = Connection.init[Unit]((), spy, 1, canRelease = () => canRelease.get())
        conn.start()
        for
            // Phase 1: multiple carriers reach teardown while the predicate is false (an in-flight op is outstanding): no release fires.
            latch1 <- Latch.init(1)
            f1     <- Fiber.init(latch1.await.map(_ => Sync.defer(conn.close())))
            f2     <- Fiber.init(latch1.await.map(_ => Sync.defer(conn.close())))
            _      <- latch1.release
            _      <- f1.get
            _      <- f2.get
            _ = assert(
                spy.closeHandleCount.get() == 0,
                s"no release while the in-flight predicate is false, got ${spy.closeHandleCount.get()}"
            )
            // Phase 2: the in-flight op completes (predicate clears); the carriers retry teardown and exactly one reaches Released.
            _ = canRelease.set(true)
            latch2 <- Latch.init(1)
            f3     <- Fiber.init(latch2.await.map(_ => Sync.defer(conn.close())))
            f4     <- Fiber.init(latch2.await.map(_ => Sync.defer(conn.close())))
            _      <- latch2.release
            _      <- f3.get
            _      <- f4.get
        yield assert(spy.closeHandleCount.get() == 1, s"exactly one Released transition, got ${spy.closeHandleCount.get()}")
        end for
    }

    "undrained-inbound-does-not-block-fd-close" in {
        // The read-side decoupling: a pooled connection whose response was never consumed has buffered inbound bytes
        // and NO reader. The fd teardown gates on the WRITE-side drain only, never the inbound channel, so the fd is closed promptly (no CLOSE_WAIT
        // leak) while the buffered bytes stay available to a later live consumer via the closing channel.
        val spy    = new SpyDriver
        val conn   = Connection.init[Unit]((), spy, 4)
        val staged = Span(7.toByte, 8.toByte, 9.toByte)
        Sync.defer {
            // Buffer an inbound span with no reader (the ReadPump staged it before EOF; the pooled connection has no consumer).
            assert(conn.inbound.offer(staged) == Result.succeed(true), "staging the inbound span must succeed")
            // Close: the fd teardown must NOT wait on the undrained inbound channel.
            conn.close()
            assert(
                spy.closeHandleCount.get() == 1,
                s"the fd is closed even with buffered inbound and no reader (no CLOSE_WAIT), got ${spy.closeHandleCount.get()}"
            )
            // The buffered bytes remain available to a later live consumer (closeAwaitEmpty keeps them for takers, not a close-drop).
            conn.inbound.poll() match
                case Result.Success(Present(span)) =>
                    assert(
                        span.toArray.sameElements(staged.toArray),
                        s"the buffered inbound span survives the fd close, got ${span.toArray.toList}"
                    )
                case other =>
                    fail(s"buffered inbound bytes must remain takeable after the fd close, got $other")
            end match
        }
    }

end TeardownTest
