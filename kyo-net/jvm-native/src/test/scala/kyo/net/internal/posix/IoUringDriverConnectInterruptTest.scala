package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Descriptor reclamation for a connect interrupted while the kernel still owns its submission.
  *
  * A connect that never completes still owns a socket. `PosixTransport`'s completion arm creates the fd, hands the connect to the driver, and
  * parks the caller on a promise; nothing else holds that fd. So when the caller is interrupted, the connect-failure arm is the only thing
  * that can give the descriptor back. `awaitConnectThen` forwards an external interrupt onto the connect arm precisely so that arm's
  * `onComplete` reaches `closeUnwiredHandle`, and nothing exercised that forwarding, so it could stop reclaiming and no test would notice
  * until a workload that interrupts connects ran out of descriptors.
  *
  * The reclamation is deliberately SYNCHRONOUS on the caller's carrier, and that asymmetry against its non-connect sibling is what this pins.
  * The driver's deferred close discharges only once the handle's in-flight count drains, and it forces an in-flight RECV to reap by shutting
  * the read half; there is no equivalent forcing for an in-flight CONNECT, a gap the driver's own teardown comment states outright. Route the
  * connect-phase close through the deferred path and the count never reaches zero, the credit never discharges, and every interrupted connect
  * leaks rather than a rare few.
  *
  * THE DRIVER IS A REAL [[IoUringDriver]], which is the only reason this leaf can see that. The deferral lives in the driver's own
  * `submitEngineOp` and the reap-carrier FIFO behind it. A backend without a submission ring runs engine ops inline, so the same scenario over
  * one cannot distinguish a close deferred forever from a close that ran, and a leaf built that way passes against the regression it was
  * written to catch. What makes the real driver usable here is the ringless stub: it supplies no completion for the connect, so the SQE is
  * prepped and can never reap, which is the substrate's equivalent of a black-holed handshake with no kernel required. The socket bindings are
  * REAL, so the fd is a real non-stdio descriptor and its close is a real close; the driver leaves fds 0 and 1 alone as process-owned stdio,
  * so a stubbed socket layer handing out 0 would report a leak that is an artifact of the fixture.
  *
  * The connect is armed for real rather than stalled at the decorator. A stalled arm registers nothing, so the handle's in-flight count is
  * zero, `registerDeferredClose` takes its immediate branch, and the deferred path under test never runs at all.
  *
  * Two barriers, both events. `onAwaitConnect` fires before the arm is enqueued, which is what makes it safe to install the stub's prep
  * barrier there, and `connectBarrierP` then reports that the SQE is genuinely prepped and in flight before the interrupt is issued. The
  * second barrier is the close itself: the reclamation runs after the caller's own promise has already completed, so the interrupted fiber's
  * result is NOT a barrier on it, and asserting there reads the close count before the close can have run and reports a leak that is not
  * there. `closed(fd)` completes on the real `close(fd)`, so a descriptor that is never returned leaves this leaf pending and the harness
  * budget reports it. Nothing measures elapsed time.
  */
class IoUringDriverConnectInterruptTest extends Test:

    import AllowUnsafe.embrace.danger

    /** The RING is stubbed here, but the SOCKETS are real, and that is what bounds the platforms this can run on:
      * `SocketBindingsImpl` has no Windows implementation, so on Windows the leaf dies in class initialization inside the very first
      * `socket()` call, before a connect is ever issued. Cancel there rather than fail, the same way the sibling posix leaves gate
      * themselves off a platform that cannot provide their substrate. Linux and macOS/BSD both supply real POSIX sockets, and the driver
      * under test is ordinary Scala over the bindings, so a stubbed ring exercises it identically on either.
      */
    private def assumePosixSockets(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("real POSIX sockets are needed here and SocketBindingsImpl has no Windows implementation")

    "an interrupted in-flight connect closes its socket exactly once" in {
        assumePosixSockets()
        given Frame = Frame.internal
        val spy     = RecordingSocketBindings(Ffi.load[SocketBindings])
        val stub    = new StubIoUringBindings
        val ring    = Buffer.alloc[Byte](stub.kyo_uring_sizeof().toInt)
        // Decorated only for the connect hook; the label still resolves to the real driver's, so the transport selects the completion arm
        // on the driver's own identity rather than on an override.
        val driver = RecordingIoDriver(TestDrivers.forBindings(stub, ring, spy))
        discard(driver.start())
        val transport = TestTransports.forTesting(driver, spy, backendIsEpoll = false)

        val connectFd = new java.util.concurrent.atomic.AtomicInteger(-1)
        driver.onAwaitConnect = handle =>
            connectFd.set(handle.writeFd)
            // Installed before the arm reaches the engine queue, so the prep can never precede the barrier that watches for it.
            stub.setConnectBarrierFd(handle.writeFd)

        Fiber.initUnscoped(
            Abort.run[kyo.net.NetException | Closed](
                transport.connect("127.0.0.1", 9, 1.minute, kyo.net.NetConfig.default).safe.get
            )
        ).flatMap { fiber =>
            stub.connectBarrierP.safe.get.andThen {
                val fd = connectFd.get()
                // Registered before the interrupt so the close cannot land ahead of the barrier that waits for it.
                val reclaimed = spy.closed(fd)
                fiber.interrupt.andThen {
                    reclaimed.safe.get.andThen {
                        val closes = Maybe(spy.closeCounts.get(fd)).getOrElse(0)
                        driver.close()
                        assert(
                            closes == 1,
                            s"an interrupted connect must return its descriptor exactly once, fd=$fd saw $closes close(s): " +
                                "2 closes a number the kernel may have already reassigned to an unrelated socket"
                        )
                        succeed
                    }
                }
            }
        }
    }

    /** A connect whose submission cannot be prepared must fail its caller, not sit in the driver forever.
      *
      * `io_uring_get_sqe` advances the submission tail as it hands the entry out, so by the time the prep runs the slot is already committed
      * to the kernel and cannot be given back. The op is registered before that, which is what makes a raising prep so much worse than a
      * failing one: the pending entry and the in-flight count it holds stay behind, the handle's deferred close waits on a completion for an
      * op the kernel never received, and the descriptor is owed for the life of the process. Nothing above the driver learns, because the
      * engine FIFO's backstop catches the throw so one connection's failure cannot stop the others draining.
      *
      * That is the descriptor leak this pins, and it is why the assertion is on the CALLER rather than on any driver internal: a withdrawn op
      * is one whose caller was told. The stub raises out of the connect prep the way marshalling a released buffer does on JVM; without the
      * withdrawal the connect promise is never completed at all and this leaf runs to the harness budget rather than failing an assertion.
      * Nothing measures elapsed time.
      */
    "a connect whose submission cannot be prepared fails its caller" in {
        assumePosixSockets()
        given Frame = Frame.internal
        val spy     = RecordingSocketBindings(Ffi.load[SocketBindings])
        val stub    = new StubIoUringBindings
        stub.failPrepConnect = true
        val ring   = Buffer.alloc[Byte](stub.kyo_uring_sizeof().toInt)
        val driver = RecordingIoDriver(TestDrivers.forBindings(stub, ring, spy))
        discard(driver.start())
        val transport = TestTransports.forTesting(driver, spy, backendIsEpoll = false)

        Abort.run[kyo.net.NetException | Closed](
            transport.connect("127.0.0.1", 9, 1.minute, kyo.net.NetConfig.default).safe.get
        ).map { result =>
            driver.close()
            assert(
                result.isFailure || result.isPanic,
                s"a connect whose prep raised must be reported to the caller, got $result"
            )
        }
    }

end IoUringDriverConnectInterruptTest
