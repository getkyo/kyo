package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

/** Driver-level guard test for the PosixHandle fd-mutex (R-039): the handle's `guard` is the generalized [[HandleGuard]] (independent read/write
  * holder bits plus a close bit), so a read dispatch and a write proceed full-duplex while a close requested mid-flight defers the resource free
  * to whichever holder releases last, running it exactly once.
  *
  * Observes the deferred free through a side effect of `PosixHandle.freeResources`: the reused read buffer is closed exactly once when the free
  * runs (the bound fd is the driver's to close, not freeResources'; here the handle is unbound, so only the buffer free is exercised). Pins:
  * R-039, INV-4, INV-13.
  */
class HandleGuardDriverTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "full-duplex-read-write-then-deferred-close" in {
        val handle = PosixHandle.socket(-1, PosixHandle.DefaultReadBufferSize, Absent)
        Sync.defer {
            // A read dispatch and a write acquire concurrently: the independent read/write halves let them proceed full-duplex.
            assert(handle.beginDispatch(), "the read dispatch acquires the read half")
            assert(handle.beginWrite(), "the write acquires the write half while the read still holds (full-duplex)")
            // Close requested while both hold: the free is deferred to the last releasing holder, so the buffer stays live.
            PosixHandle.close(handle)
            assert(!handle.readBuffer.isClosed, "the resource free is deferred while a holder is in flight")
            // The read ends first: not the last holder (the write still holds), so no free here.
            assert(!handle.endDispatch(), "ending the read while the write still holds does not run the free")
            assert(!handle.readBuffer.isClosed, "still deferred: the write holds the guard")
            // The write ends: now the last holder while closing, so it runs the deferred free exactly once.
            assert(handle.endWrite(), "ending the last holder while closing runs the deferred free exactly once")
            assert(handle.readBuffer.isClosed, "freeResources ran: the reused read buffer is closed")
            // The guard is terminal: no acquire after the free.
            assert(!handle.beginDispatch(), "no read acquire after the deferred free")
            assert(!handle.beginWrite(), "no write acquire after the deferred free")
        }
    }

    "idle-close-frees-immediately" in {
        // With no holder in flight, requestClose runs the free now (the HandleGuard idle-close path), so a plain close frees the buffer at once.
        val handle = PosixHandle.socket(-1, PosixHandle.DefaultReadBufferSize, Absent)
        Sync.defer {
            assert(!handle.readBuffer.isClosed, "the buffer is live before close")
            PosixHandle.close(handle)
            assert(handle.readBuffer.isClosed, "an idle close frees the buffer immediately (no holder to defer to)")
            // Idempotent: a second close is a no-op.
            PosixHandle.close(handle)
            assert(handle.readBuffer.isClosed, "a repeat close is idempotent")
        }
    }

end HandleGuardDriverTest
