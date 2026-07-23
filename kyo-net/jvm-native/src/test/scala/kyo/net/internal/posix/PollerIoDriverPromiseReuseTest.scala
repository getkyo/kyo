package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.scheduler.IOPromise

/** A per-handle reused read [[kyo.scheduler.IOPromise]] (the ReadPump technique: `becomeAvailable()` + re-`awaitRead(self)`,
  * already the steady-state read path) honors interrupt and re-arms cleanly across reads, with waiter cleanup proven by a SCOPE-FINALIZER live
  * counter, NEVER `IOPromise.waiters()` (`mask()` leaves a ghost waiter that `waiters()` still counts, so it is not authoritative).
  *
  * The reuse leaf drives a reused promise across N back-to-back reads on one handle and asserts each delivers the right bytes (the reuse does not
  * corrupt or drop a read). The interrupt leaf parks a read that provably cannot complete (no data, no peer write), interrupts the parked fiber,
  * and asserts: (a) the interrupt is honored (the fiber's await aborts), (b) the live-waiter counter (a `Scope.ensure` finalizer that decrements
  * when the parked fiber unwinds) returns to 0, and (c) the handle re-arms cleanly afterward (a fresh read on the SAME handle delivers real bytes).
  *
  * The assertions pin the interrupt + cleanup end-state, so a regression that leaked a waiter (counter stuck above 0),
  * lost the interrupt (the await not aborting), or stranded the re-arm (the post-interrupt read hanging) FAILS this leaf.
  */
class PollerIoDriverPromiseReuseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** A reused read promise: an IOPromise subclass re-armed across reads exactly as ReadPump does (`becomeAvailable()` + `driver.awaitRead`),
      * collecting each delivered chunk. This exercises the in-tree reuse technique directly.
      */
    final private class ReusedReadPromise(driver: PollerIoDriver, handle: PosixHandle) extends IOPromise[Closed, ReadOutcome]:
        private val self: Promise.Unsafe[ReadOutcome, Abort[Closed]] = this.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]
        def arm()(using AllowUnsafe, Frame): Unit                    = driver.awaitRead(handle, self)
        def rearm()(using AllowUnsafe, Frame): Unit =
            discard(becomeAvailable())
            driver.awaitRead(handle, self)
        end rearm
        def awaitOne()(using Frame): ReadOutcome < (Abort[Closed] & Async) = self.safe.get
    end ReusedReadPromise

    "PollerIoDriver reused read promise" - {

        "reusedPromiseInterruptLifecycle" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val reused    = new ReusedReadPromise(driver, acceptedH)

                    // Part 1 -- REUSE correctness: N back-to-back reads on the reused promise, each delivering its own distinct payload.
                    val n = 4
                    def writeAndReadOne(i: Int): Unit < (Abort[Closed] & Async) =
                        val payload = Array.tabulate[Byte](8)(j => ((j + i) & 0xff).toByte)
                        assert(driver.write(clientH, Span.fromUnsafe(payload), 0) == WriteResult.Done)
                        reused.awaitOne().map { got =>
                            val ReadOutcome.Bytes(span) = got.runtimeChecked
                            assert(span.toArray.toList == payload.toList, s"reused read $i delivered wrong bytes")
                            // Re-arm the SAME promise for the next read (the becomeAvailable + awaitRead reuse).
                            if i < n then reused.rearm()
                            ()
                        }
                    end writeAndReadOne
                    reused.arm()
                    def loop(i: Int): Unit < (Abort[Closed] & Async) =
                        if i > n then () else writeAndReadOne(i).andThen(loop(i + 1))
                    loop(1).andThen {
                        // Part 2 -- INTERRUPT + cleanup: park a read that cannot complete (no data is written), interrupt it, and prove cleanup via
                        // a SCOPE-FINALIZER live counter (NOT waiters(), because `mask()` leaves a ghost waiter). The parked fiber runs inside a Scope whose `ensure` decrements the
                        // live counter AND completes a `finalized` latch when the fiber unwinds (on interrupt). We await that latch directly (the
                        // finalizer-ran signal), so the counter-is-0 assertion does not depend on any getResult/finalizer ordering assumption.
                        val live      = new JAtomicInteger(0)
                        val parked    = new ReusedReadPromise(driver, acceptedH)
                        val started   = Promise.Unsafe.init[Unit, Any]()
                        val finalized = Promise.Unsafe.init[Unit, Any]()
                        parked.arm() // arm a fresh read on the same handle; no data -> it parks
                        for
                            fiber <- Fiber.init {
                                Scope.run {
                                    Sync.defer(live.incrementAndGet()).andThen {
                                        Scope.ensure {
                                            Sync.defer {
                                                discard(live.decrementAndGet())
                                                finalized.completeDiscard(Result.succeed(()))
                                            }
                                        }.andThen {
                                            Sync.defer(started.completeDiscard(Result.succeed(())))
                                                .andThen(Abort.run[Closed](parked.awaitOne()).unit)
                                        }
                                    }
                                }
                            }
                            // Latch on the fiber having started (the waiter is live), ruling out interrupting a not-yet-parked fiber. The read cannot
                            // complete (no peer write), so the interrupt deterministically wins.
                            _    <- started.safe.get
                            done <- fiber.interrupt
                            // Await the FINALIZER's own signal: this is the observation that the parked-fiber Scope ran its ensure on the interrupt
                            // unwind, decrementing the live counter. (Also await the fiber's terminal result so it is fully torn down.)
                            _ <- finalized.safe.get
                            _ <- fiber.getResult
                        yield
                            assert(done, "fiber.interrupt returned false: the parked reused-promise await was not interrupted")
                            // The live-waiter counter is back to 0: the parked waiter was cleaned up (proven by the finalizer, NOT waiters()).
                            assert(live.get() == 0, s"reused-promise waiter leaked: live counter ${live.get()} != 0 after interrupt")
                            ()
                        end for
                    }.andThen {
                        val afterReused = new ReusedReadPromise(driver, acceptedH)
                        val payload     = Array.tabulate[Byte](6)(j => (j + 100).toByte)
                        afterReused.arm()
                        assert(driver.write(clientH, Span.fromUnsafe(payload), 0) == WriteResult.Done)
                        afterReused.awaitOne().map { got =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client).poll())
                            val ReadOutcome.Bytes(span) = got.runtimeChecked
                            assert(span.toArray.toList == payload.toList, "the handle must re-arm and deliver cleanly after an interrupt")
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }
end PollerIoDriverPromiseReuseTest
