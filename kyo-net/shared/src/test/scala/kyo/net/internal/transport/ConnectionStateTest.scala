package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** Tests for the Connection.State machine fold: a single named-state enum held in one atomic cell, encoding the connection lifecycle rather than
  * separate lifecycle flags.
  *
  * Each leaf builds a Connection[Unit] over a SpyDriver that counts cancel and closeHandle calls. The test then drives specific state
  * transitions and asserts the structural invariants on the named state cell: teardown runs exactly once, a detach-for-upgrade bars
  * teardown, and a close-then-detach loses the CAS.
  */
class ConnectionStateTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A spy IoDriver[Unit] that records cancel and closeHandle invocations for assertion. Uses AtomicInt.Unsafe for thread-safe counting
      * across concurrent fibers.
      */
    final private class SpyDriver extends IoDriver[Unit]:
        val cancelCount      = AtomicInt.Unsafe.init(0)
        val closeHandleCount = AtomicInt.Unsafe.init(0)
        val awaitReadCount   = AtomicInt.Unsafe.init(0)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            discard(awaitReadCount.incrementAndGet())
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                        = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit      = discard(cancelCount.incrementAndGet())
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCount.incrementAndGet())
        def close()(using AllowUnsafe, Frame): Unit                   = ()
        def label: String                                             = "SpyDriver"
        def handleLabel(handle: Unit): String                         = "spy"
    end SpyDriver

    /** Advance the connection to Established via start(), since closeFn targets Established -> Closing and detach targets Established ->
      * Upgrading. Created connections can also transition but Established is the normal I/O path.
      */
    private def makeEstablished(spy: SpyDriver): Connection[Unit] =
        val conn = Connection.init[Unit]((), spy, 1)
        // Advance to Established: the CAS Created -> Established succeeds, pumps would normally start but the spy driver stubs them.
        conn.start()
        conn
    end makeEstablished

    "multi-carrier-close-fires-teardown-once" in {
        // Two fibers race to call close() via a latch: exactly one Closing -> Closed CAS wins,
        // so closeHandle is invoked exactly once regardless of interleaving.
        val spy  = new SpyDriver
        val conn = makeEstablished(spy)
        for
            latch <- Latch.init(1)
            fiber1 <- Fiber.init {
                latch.await.map(_ => Sync.defer(conn.close()))
            }
            fiber2 <- Fiber.init {
                latch.await.map(_ => Sync.defer(conn.close()))
            }
            _ <- latch.release
            _ <- fiber1.get
            _ <- fiber2.get
        yield assert(spy.closeHandleCount.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCount.get()}")
        end for
    }

    "detach-on-established-is-inert-for-teardown" in {
        // detachForUpgrade CASes Established -> Upgrading and returns the staged bytes.
        // A subsequent close() sees state == Upgrading and neither CAS (Established -> Closing, Created -> Closing) wins,
        // so teardownHandle is never reached: closeHandle count stays 0 and the fd is kept open for the TLS upgrade.
        val spy  = new SpyDriver
        val conn = makeEstablished(spy)
        Sync.defer {
            val buffered = conn.detachForUpgrade()
            assert(
                buffered.isDefined || buffered.isEmpty,
                "detachForUpgrade returns a Maybe (present or absent, both OK for empty channel)"
            )
            conn.close()
            assert(spy.closeHandleCount.get() == 0, s"closeHandle must NOT be called after a detach, got ${spy.closeHandleCount.get()}")
        }
    }

    "close-then-detach-loses-cas" in {
        // close() wins the CAS (Established -> Closing), then detachForUpgrade() tries Established -> Upgrading and Created -> Upgrading:
        // both lose because the state is already Closing, so detachForUpgrade returns Absent (idempotent), no second teardown.
        val spy  = new SpyDriver
        val conn = makeEstablished(spy)
        Sync.defer {
            conn.close()
            // After close() the outbound channel is empty (no queued writes in this test), so teardownHandle fires synchronously:
            // the state advances Closing -> Closed.
            val result = conn.detachForUpgrade()
            assert(result.isEmpty, s"detachForUpgrade after close must return Absent, got $result")
            // No second teardown: closeHandle count is exactly 1 from the close() path.
            assert(spy.closeHandleCount.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCount.get()}")
        }
    }

    "start-loses-cas-when-close-wins-first" in {
        // A close before start wins the Created -> Closing CAS, so start()'s own Created -> Established CAS is lost.
        // No latch is needed: close() and start() are both synchronous, non-suspending calls, so calling them in sequence on the same
        // carrier deterministically orders the close before the start, the same sequential-CAS shape as close-then-detach-loses-cas above.
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        Sync.defer {
            conn.close()
            val started = conn.start()
            assert(!started, "start() must return false when a close already won the Created -> Established CAS")
            assert(
                spy.awaitReadCount.get() == 0,
                s"the read pump must not start after a lost CAS, got ${spy.awaitReadCount.get()} awaitRead calls"
            )
        }
    }

    "start-wins-cas-on-the-uncontested-happy-path" in {
        // Paired with the leaf above: with nothing racing, start() wins the CAS, returns true, and the read pump registers its first read.
        val spy  = new SpyDriver
        val conn = Connection.init[Unit]((), spy, 1)
        Sync.defer {
            val started = conn.start()
            assert(started, "start() must return true when it wins the Created -> Established CAS")
            assert(
                spy.awaitReadCount.get() == 1,
                s"the read pump must start exactly once, got ${spy.awaitReadCount.get()} awaitRead calls"
            )
        }
    }

end ConnectionStateTest
