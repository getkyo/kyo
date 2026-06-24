package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine

/** Unit tests for [[PosixHandle]], the unified raw-fd handle. Covers the readFd/writeFd split (sockets share one fd, stdio splits 0/1), the
  * monotonic id used as the recycled-fd stale-event guard, and the idempotent resource release in `close`.
  *
  * The bodies are synchronous: `PosixHandle.socket`/`stdio`/`close` are plain `(using AllowUnsafe)` operations, not effects, so they assert
  * directly. The shared-layer signature guard lives in `PosixHandleInvariantsTest`.
  */
class PosixHandleTest extends Test:

    import AllowUnsafe.embrace.danger

    "PosixHandle" - {
        "socket handle sets readFd == writeFd (one fd both directions)" in {
            val h = PosixHandle.socket(42, PosixHandle.DefaultReadBufferSize, Absent)
            assert(h.readFd == 42)
            assert(h.writeFd == 42)
        }

        "stdio handle splits the fds to (readFd=0, writeFd=1)" in {
            val h = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)
            assert(h.readFd == 0)
            assert(h.writeFd == 1)
        }

        "each handle gets a strictly-greater monotonic id (recycled-fd guard)" in {
            val first  = PosixHandle.socket(10, PosixHandle.DefaultReadBufferSize, Absent)
            val second = PosixHandle.socket(11, PosixHandle.DefaultReadBufferSize, Absent)
            assert(second.id > first.id)
        }

        "close frees the TLS engine exactly once and is idempotent" in {
            var freed  = 0
            val engine = countingEngine(() => freed += 1)
            val h      = PosixHandle.socket(7, PosixHandle.DefaultReadBufferSize, Absent)
            h.tls = Present(engine)
            // First close releases the engine + the read buffer; the second must be a safe no-op.
            PosixHandle.close(h)
            PosixHandle.close(h)
            assert(freed == 1)    // engine freed once, not twice
            assert(h.tls.isEmpty) // slot cleared so a repeat close frees nothing
        }
    }

    /** Deterministic unit tests for the dispatch-vs-close use-after-free guard (`PosixHandle.guard`): the single `AtomicInteger` that lets
      * every in-flight read dispatch ([[PosixHandle.beginDispatch]] / [[PosixHandle.endDispatch]]) and write ([[PosixHandle.beginWrite]] /
      * [[PosixHandle.endWrite]]) hand off ownership of the shared resources (the TLS engine and the reused read buffer) so a racing close
      * frees them exactly once, and only after the LAST holder releases.
      *
      * The two re-entrant spy-engine race tests ([[PollerIoDriverRaceTest]], [[PollerIoDriverWriteRaceTest]]) each hold ONE holder (a read OR a
      * write) when the close fires. They never exercise the two-holder arithmetic (holders 0->1->2, then 2->1->0-with-close) that defends a close
      * racing BOTH an in-flight read and an in-flight write. These leaves drive the guard directly through its `private[posix]` accessors, with no
      * fibers and no driver: pure synchronous assertions on the holder-count math and the free-exactly-once handoff.
      */
    "PosixHandle.guard (dispatch-vs-close UAF)" - {
        "defers the free while two holders are in flight and frees exactly once at the last release" in {
            var freed = 0
            val h     = PosixHandle.socket(7, PosixHandle.DefaultReadBufferSize, Absent)
            h.tls = Present(countingEngine(() => freed += 1))
            // Two holders acquire: a read dispatch then a write (holders 0 -> 1 -> 2).
            assert(h.beginDispatch(), "first acquire (read) must succeed before any close")
            assert(h.beginWrite(), "second acquire (write) must succeed before any close")
            // A close requested while two ops hold the resources sets the close bit and defers the free.
            PosixHandle.close(h)
            assert(freed == 0, "free must be deferred while holders are in flight")
            assert(h.tls.isDefined, "the engine slot must still be live until the last holder releases")
            // First release: holders 2 -> 1. Not the last holder, so no free yet (returns false).
            assert(!h.endDispatch(), "the first release must not be the last holder")
            assert(freed == 0, "free must not run while one holder remains")
            // Last release: holders 1 -> 0 with the close bit set. This op frees, exactly once (returns true).
            assert(h.endWrite(), "the last release while closing must perform the deferred free")
            assert(freed == 1, "the engine must be freed exactly once, at the final release")
            assert(h.tls.isEmpty, "the engine slot must be cleared once freed")
        }

        "frees at the last release regardless of which holder releases last (reverse order)" in {
            var freed = 0
            val h     = PosixHandle.socket(8, PosixHandle.DefaultReadBufferSize, Absent)
            h.tls = Present(countingEngine(() => freed += 1))
            assert(h.beginDispatch())
            assert(h.beginWrite())
            PosixHandle.close(h)
            // Release the WRITE first this time (holders 2 -> 1), then the READ last (1 -> 0 with close): the free still happens exactly once,
            // performed by whichever op observes the count reach zero.
            assert(!h.endWrite(), "releasing the write first must not be the last holder")
            assert(freed == 0)
            assert(h.endDispatch(), "releasing the read last while closing must perform the deferred free")
            assert(freed == 1, "free runs exactly once at the terminal release, whichever holder is last")
            assert(h.tls.isEmpty)
        }

        "rejects a new holder once close has been requested (no acquire after CloseBit)" in {
            var freed = 0
            val h     = PosixHandle.socket(9, PosixHandle.DefaultReadBufferSize, Absent)
            h.tls = Present(countingEngine(() => freed += 1))
            // One holder in flight when close is requested: the close bit is set, the free deferred.
            assert(h.beginDispatch(), "acquire before close must succeed")
            PosixHandle.close(h)
            assert(freed == 0, "free deferred while the one holder is in flight")
            // No NEW holder may acquire after the close bit is set: both begin* return false and do not bump the count.
            assert(!h.beginDispatch(), "beginDispatch must fail once close is requested")
            assert(!h.beginWrite(), "beginWrite must fail once close is requested")
            assert(freed == 0, "a rejected acquire must not trigger or alter the deferred free")
            // The existing holder releasing is therefore the LAST holder: it frees exactly once.
            assert(h.endDispatch(), "the in-flight holder releasing while closing must perform the free")
            assert(freed == 1)
            assert(h.tls.isEmpty)
        }

        "frees immediately when close is requested with no holder, and is idempotent" in {
            var freed = 0
            val h     = PosixHandle.socket(10, PosixHandle.DefaultReadBufferSize, Absent)
            h.tls = Present(countingEngine(() => freed += 1))
            // No op holds the resources: requestClose frees right away (holders 0 -> terminal).
            PosixHandle.close(h)
            assert(freed == 1, "with no holder, close frees immediately")
            assert(h.tls.isEmpty)
            // Idempotent: a repeat close on the terminal value frees nothing more, and no new holder can acquire.
            PosixHandle.close(h)
            assert(freed == 1, "a repeat close must be a no-op")
            assert(!h.beginDispatch(), "no acquire is possible once the resources are freed")
            assert(!h.beginWrite(), "no acquire is possible once the resources are freed")
            assert(freed == 1)
        }
    }

    /** A [[TlsEngine]] whose only behavior under test is counting `free()` via `onFree`; the rest of the surface returns inert values. */
    private def countingEngine(onFree: () => Unit): TlsEngine =
        new TlsEngine:
            def handshakeStep()(using AllowUnsafe): Int                                      = 1
            def feedCiphertext(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int  = len
            def drainCiphertext(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
            def readPlain(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
            def writePlain(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int      = len
            def hasBufferedPlaintext(using AllowUnsafe): Boolean                             = false
            def readBuffered()(using AllowUnsafe): Span[Byte]                                = Span.empty[Byte]
            def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                           = Absent
            def shutdownStep()(using AllowUnsafe): Int                                       = 0
            def free()(using AllowUnsafe): Unit                                              = onFree()
end PosixHandleTest
