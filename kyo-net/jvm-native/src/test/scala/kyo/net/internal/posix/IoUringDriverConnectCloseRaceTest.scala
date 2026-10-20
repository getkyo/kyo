package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.transport.ReleaseMode

/** Ghost-connect regression guard for [[IoUringDriver]]: a connect arm belonging to a handle whose close has already run must never reach the
  * ring.
  *
  * `awaitConnect` only ENQUEUES the arm on the engine FIFO; the SQE is prepped later, on the reap carrier. The connect-phase close in
  * `PosixTransport.closeUnwiredHandle` closes the fd without waiting for the driver's deferred path, so a close that lands between the
  * enqueue and the drain frees the fd number while the arm is still queued. The kernel is free to hand that
  * number to the next socket, and a late-draining arm would then prep `IORING_OP_CONNECT` against a socket belonging to somebody else. The
  * same window opens a second way: on a full submission queue the arm parks in `stalledSubmits` and `reArmStalledSubmits` re-enters the
  * submit later, by which point the handle may be long closed.
  *
  * `submitAccept` already rejects a closing handle for exactly this reason. This pins the connect side of that invariant.
  *
  * The ordering is structural rather than timed: the engine FIFO is ordered, so a second arm on a healthy handle enqueued behind the closing
  * one is a barrier. When the healthy fd reaches `kyo_uring_prep_connect`, the closing arm has already drained, and what it did is then a
  * settled fact. Waiting on the closing arm's own promise would not work, since an unguarded submit leaves that promise pending forever.
  *
  * Runs over the ringless [[StubIoUringBindings]], so it exercises the real submit path on every platform rather than only where a kernel
  * io_uring is available.
  */
class IoUringDriverConnectCloseRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    private val ClosingFd = 7
    private val HealthyFd = 9

    "a connect arm for a handle whose close already ran never preps an SQE on its freed fd" in {
        val stub = new StubIoUringBindings
        val ring = Buffer.alloc[Byte](stub.kyo_uring_sizeof().toInt)
        val drv  = TestDrivers.forBindings(stub, ring, StubSocketBindings)
        discard(drv.start())
        Sync.ensure(Sync.defer(drv.close())) {
            val closingAddr = Buffer.alloc[Byte](16)
            val healthyAddr = Buffer.alloc[Byte](16)
            val closing     =
                PosixHandle.socket(ClosingFd, PosixHandle.DefaultReadBufferSize, Present((closingAddr, 16)), Frame.internal)
            val healthy =
                PosixHandle.socket(HealthyFd, PosixHandle.DefaultReadBufferSize, Present((healthyAddr, 16)), Frame.internal)
            stub.setConnectBarrierFd(HealthyFd)
            val closingPromise = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            val healthyPromise = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            // Pin the reap carrier so the closing handle's arm is still queued when the close runs, which is the window under test.
            val gate  = new java.util.concurrent.CountDownLatch(1)
            val pinIn = Promise.Unsafe.init[Unit, Any]()
            drv.submitEngineOp { () =>
                pinIn.completeDiscard(Result.succeed(()))
                gate.await()
            }
            pinIn.safe.get.andThen {
                drv.awaitConnect(closing, closingPromise)
                // The connect-phase close in `PosixTransport.closeUnwiredHandle`, in its order: the driver release takes the release claim,
                // then the fd close is claimed. It never calls `requestClose()` on the handle, so a gate keyed on the guard's closing bit
                // would read false here while the fd number is already back with the kernel.
                drv.release(closing, ReleaseMode.Graceful, Promise.Unsafe.init())
                assert(closing.claimFdClose(), "the leaf must be the one that claims the fd close")
                drv.awaitConnect(healthy, healthyPromise)
                gate.countDown()
                stub.connectBarrierP.safe.get
            }.andThen {
                val armed = stub.prepConnectFds
                assert(
                    !armed.contains(ClosingFd),
                    s"a closing handle's connect arm must not reach the ring, fds armed: $armed"
                )
                assert(
                    armed.size == 1 && armed.head == HealthyFd,
                    s"only the healthy handle's connect may be armed, fds armed: $armed"
                )
                // Rejecting the arm has to settle its promise too. A rejection that merely skips the submit leaves the caller parked on a
                // connect that no completion will ever arrive for, which trades a misdirected connect for a hang.
                closingPromise.safe.getResult.map { result =>
                    assert(
                        result.isFailure,
                        s"a rejected connect arm must fail its promise rather than strand the caller, got $result"
                    )
                    closingAddr.close()
                    healthyAddr.close()
                    // The real deferred discharge would free these; nothing armed a recv here, so releasing them directly is safe and
                    // keeps the fork's descriptor accounting clean.
                    closing.requestClose()
                    healthy.requestClose()
                    succeed
                }
            }
        }
    }

end IoUringDriverConnectCloseRaceTest
