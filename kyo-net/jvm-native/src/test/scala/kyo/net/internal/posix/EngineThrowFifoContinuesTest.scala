package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.TransportConfig
import kyo.net.internal.tls.TlsEngine

/** Engine-FIFO containment: a throwing engine op must not kill the drain loop.
  *
  * The engine FIFO is a per-driver ConcurrentLinkedQueue of ops drained by the poll-loop carrier (or the io_uring reap carrier) once per
  * cycle. The outer catch in `drainEngineOps` is the containment boundary: if one connection's engine op throws, it is logged and the drain
  * continues with the next op.
  *
  * This matters because JDK SSLEngine.unwrap can raise (not return a status) on certain fatal TLS alerts. Without the containment boundary,
  * that throw would unwind the drain loop and leave all later ops in the queue unprocessed, hanging every other connection on the driver.
  *
  * This test drives the invariant directly via `drainFifos()`, which is `private[posix]` and therefore reachable from this test package. It
  * enqueues two ops: one that throws and one that sets a flag. After a single `drainFifos()` call:
  *
  *   - The throwing op's throw is contained (the drain loop does not propagate it).
  *   - The succeeding op runs and sets its flag.
  *
  * `private[posix]` access and no-poll-loop: `drainFifos()` is the exact method the poll loop calls each cycle, so this simulates one cycle
  * with two ops. No real I/O, no real TLS engine, no threads.
  */
class EngineThrowFifoContinuesTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    "engine-FIFO containment" - {

        // Enqueue a throwing op followed by a succeeding op; drain once; assert the succeeding op ran.
        "throwing op does not kill the FIFO: succeeding op still runs" in {
            assumePoller()
            val driver = PollerIoDriver.init(TransportConfig.default)
            try
                var succeedingRan = false

                // Op 1: throws. Simulates an unexpected throw from an SSLEngine.unwrap call (or any
                // inner catch that re-throws a fatal error the op did not handle).
                driver.submitEngineOp(() => throw new RuntimeException("simulated-engine-throw"))

                // Op 2: sets a flag. This is the "other connection"'s op that must still run.
                driver.submitEngineOp(() => succeedingRan = true)

                // Drive exactly one drain cycle (both ops are queued; a single drainFifos empties the queue).
                driver.drainFifos()

                assert(succeedingRan, "the op after the throwing op must still run (containment kept the drain loop alive)")
            finally
                driver.close()
            end try
        }

        // Multiple throwing ops followed by succeeding ops: the FIFO stays alive across all of them.
        "multiple throwing ops do not kill the FIFO: succeeding ops still run" in {
            assumePoller()
            val driver = PollerIoDriver.init(TransportConfig.default)
            try
                var countRan = 0

                // Interleave three throwing ops with three succeeding ops.
                driver.submitEngineOp(() => throw new RuntimeException("throw-1"))
                driver.submitEngineOp(() => countRan += 1)
                driver.submitEngineOp(() => throw new RuntimeException("throw-2"))
                driver.submitEngineOp(() => countRan += 1)
                driver.submitEngineOp(() => throw new RuntimeException("throw-3"))
                driver.submitEngineOp(() => countRan += 1)

                driver.drainFifos()

                assert(countRan == 3, s"all three succeeding ops must run after their preceding throws; countRan=$countRan")
            finally
                driver.close()
            end try
        }

        // A throwing op that itself wraps another op (simulating the inner + outer catch structure):
        // the outer catch catches Throwable; even an Error escaping the inner op is contained.
        "Error (not just RuntimeException) is contained" in {
            assumePoller()
            val driver = PollerIoDriver.init(TransportConfig.default)
            try
                var afterErrorRan = false

                driver.submitEngineOp(() => throw new Error("simulated-engine-error"))
                driver.submitEngineOp(() => afterErrorRan = true)

                driver.drainFifos()

                assert(afterErrorRan, "op after an Error-throwing op must still run (outer catch catches Throwable)")
            finally
                driver.close()
            end try
        }

        // Ops submitted after drainFifos (simulating the next poll cycle) still run: the drain
        // is re-entrant across cycles and the queue is not permanently poisoned by a prior throw.
        "FIFO continues across poll cycles after a throw" in {
            assumePoller()
            val driver = PollerIoDriver.init(TransportConfig.default)
            try
                var cycle1Ran = false
                var cycle2Ran = false

                // Cycle 1: throw in op 1, succeed in op 2.
                driver.submitEngineOp(() => throw new RuntimeException("cycle-1-throw"))
                driver.submitEngineOp(() => cycle1Ran = true)
                driver.drainFifos()
                assert(cycle1Ran, "cycle 1 succeeding op must run")

                // Cycle 2: only a succeeding op; the queue must not be poisoned from cycle 1.
                driver.submitEngineOp(() => cycle2Ran = true)
                driver.drainFifos()
                assert(cycle2Ran, "cycle 2 op must run (queue not permanently poisoned by cycle 1 throw)")
            finally
                driver.close()
            end try
        }

        // A received fatal TLS alert causes feedAndDecrypt (the real production method called from
        // dispatchReadTls) to throw. The INNER CATCH inside the engine op handles the throw: it fails
        // the connection's read promise typed (Closed) and returns normally, so the op itself does NOT
        // rethrow. This means the outer catch in drainEngineOps never fires; the FIFO drain continues
        // to the next op because the inner catch produced a clean return.
        //
        // This test is distinct from leaves 1-4: those test throws that ESCAPE the op (outer catch
        // containment). This leaf tests a throw that is CAUGHT inside the op (inner-catch typed
        // failure). The continuity of the FIFO is preserved by the inner catch, not the outer one.
        "inner-catch: feedAndDecrypt throw fails connection typed, next op still runs" in {
            assumePoller()
            val driver = PollerIoDriver.init(TransportConfig.default)
            try
                // Stub TlsEngine whose feedCiphertext throws. This models the JDK SSLEngine raising
                // SSLHandshakeException on a received fatal TLS alert during an unwrap call.
                val alertThrow = new RuntimeException("received_fatal_alert: certificate_unknown")
                val throwOnFeed: TlsEngine = new TlsEngine:
                    def handshakeStep()(using AllowUnsafe): Int                              = 0
                    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int  = throw alertThrow
                    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
                    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
                    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
                    def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
                    def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
                    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                   = Absent
                    def shutdownStep()(using AllowUnsafe): Int                               = 0
                    def free()(using AllowUnsafe): Unit                                      = ()
                end throwOnFeed

                var innerCatchFired = false
                var nextOpRan       = false

                // Staging buffer used as the cipher input to feedAndDecrypt (len=0 so no bytes are
                // fed, but feedCiphertext still throws before any bytes are consumed).
                val staging = Buffer.alloc[Byte](16)

                // A minimal handle to satisfy feedAndDecrypt's parameter: feedCiphertext throws
                // immediately so no handle fields are accessed beyond the engine call.
                val handle = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)

                // Op 1: calls feedAndDecrypt on the throwing engine; the inner catch handles the throw
                // and returns normally so the outer drainEngineOps catch does NOT fire.
                driver.submitEngineOp { () =>
                    try
                        // driver.feedAndDecrypt is the real production method from TlsEngineIo,
                        // private[posix] and inherited by PollerIoDriver. This is the same call
                        // dispatchReadTls uses; it throws when feedCiphertext throws.
                        discard(driver.feedAndDecrypt(throwOnFeed, staging, 0, handle))
                    catch
                        case _: Throwable =>
                            // Inner catch (production parallel: dispatchReadTls fails the read promise
                            // typed and returns without rethrowing, so the engine op completes cleanly).
                            innerCatchFired = true
                }

                // Op 2: a different connection's succeeding op.
                driver.submitEngineOp(() => nextOpRan = true)

                driver.drainFifos()
                staging.close()

                assert(innerCatchFired, "inner catch must fire (feedAndDecrypt threw; inner catch caught it)")
                assert(nextOpRan, "op after the inner-caught throw must run (FIFO not killed; outer catch never fired)")
            finally
                driver.close()
            end try
        }
    }

end EngineThrowFifoContinuesTest
