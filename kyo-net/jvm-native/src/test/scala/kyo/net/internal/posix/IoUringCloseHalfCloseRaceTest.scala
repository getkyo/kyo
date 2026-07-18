package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first regression guard for the io_uring local-close half-close-state race (a new root distinct from B').
  *
  * The defect: [[IoUringDriver.closeHandle]] (via its private `registerDeferredClose`) deliberately issues `shutdown(readFd, SHUT_RD)`
  * to force a kernel-owned in-flight recv SQE to complete, because io_uring holds its own reference to the file and closing the fd alone
  * does not complete it. That self-induced `res == 0` completion reaps through the SAME branch a genuine unprompted peer half-close (a
  * bare TCP FIN, no close_notify) uses, with no guard distinguishing "I closed myself" from "the peer actually sent EOF": it
  * unconditionally stamps `handle.halfClose = HalfCloseState.PeerEof`. [[PosixTransport.installStatus]] checks `PeerEof` ahead of
  * the `LocalClose` fallback, so a connection the LOCAL side closed (no peer FIN ever happened) incorrectly reports `Truncated`.
  *
  * This is a TIMING RACE through the public `Connection` API: `HalfCloseStateTest`'s "local-close" leaf can fail only intermittently, because
  * `Connection.close()` synchronously CASes the connection state but only ENQUEUES the driver-side teardown (`closeHandle` ->
  * `submitEngineOp`), so the public-API race depends on whether the async cancel+SHUT_RD+CQE-reap settles before or after the
  * `status` read. This test removes that timing dependence by driving the exact mechanism directly at the driver level (no
  * `Connection`/`Transport` involved) and waiting on a REAL completion signal -- `handle.isClosing()`, set only at the very end of
  * `closeNow` (`PosixHandle.close` -> `requestClose`) -- instead of guessing at a deadline. `closeNow` is reached via
  * `decrementInFlight`'s inline call, which runs immediately AFTER the buggy/fixed `res == 0` branch for this same CQE, so observing
  * `isClosing() == true` proves that branch has already run.
  *
  * Fails-before: `handle.halfClose` ends as `PeerEof` even though no peer FIN ever happened (only the local close's own self-induced
  * SHUT_RD recv completion occurred), which would surface as `Truncated` instead of `LocalClose`. Passes-after: `handle.halfClose`
  * stays `Open`.
  *
  * Also verifies the in-flight read promise is not stranded by guarding the stamp out: `cancel` (run synchronously inside
  * `closeHandle`, strictly before `registerDeferredClose`'s SHUT_RD) already fails it `Closed` independently of the later CQE reap, so
  * it must already be resolved once the close sequence has settled, independently of the SHUT_RD reap ordering.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): the poller backends (epoll/kqueue) synchronously deregister interest before
  * closing the fd, so there is no kernel-owned in-flight op to force-complete and no equivalent self-induced completion to race.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin. The only wait is `awaitCondition` (mirrors
  * [[IoUringHandshakeTimeoutOrderingTest.awaitCondition]]), a bounded poll on a real driver-carrier state transition via `Async.sleep`
  * between checks, not a timer-based settle. No data is ever sent on `client`, so the in-flight recv has exactly one way to complete:
  * the close path's own SHUT_RD.
  */
class IoUringCloseHalfCloseRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Poll a real condition until it holds or the bound elapses, re-checking each turn after a short Async.sleep. Mirrors
      * [[IoUringHandshakeTimeoutOrderingTest.awaitCondition]].
      */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "IoUringDriver local-close half-close-state race" - {

        "closing a connection with an in-flight recv and no peer FIN must not stamp PeerEof (LocalClose, not Truncated)" in {
            PosixTestSockets.assumeUring()
            given Frame = Frame.internal
            val depth   = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring   = Ffi.load[IoUringBindings]
            val ring    = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc      = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("IoUringCloseHalfCloseRaceTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val promise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    // Arm a real in-flight recv: nothing is ever sent on `client`, so this recv would block forever on its own -- the
                    // ONLY thing that can complete it is the close path's self-induced SHUT_RD below.
                    driver.awaitRead(acceptedH, promise)
                    // Local close; no peer-initiated close ever happens (the test never sends on or closes `client` before this).
                    driver.closeHandle(acceptedH)
                    awaitCondition(5.seconds)(acceptedH.isClosing()).map { settled =>
                        assert(settled, "close sequence did not settle within 5s (closeNow never ran: a hang, not the race under test)")
                        promise.poll() match
                            case Present(Result.Failure(_: Closed)) => ()
                            case other => fail(s"in-flight recv promise must resolve Closed (not stranded) on local close; got $other")
                        discard(sock.close(client))
                        assert(
                            acceptedH.halfClose == HalfCloseState.Open,
                            s"local close()'s self-induced SHUT_RD recv completion must not change halfClose from Open " +
                                s"(would surface as Truncated instead of LocalClose); got ${acceptedH.halfClose}"
                        )
                        succeed
                    }
                }
            }
        }
    }

end IoUringCloseHalfCloseRaceTest
