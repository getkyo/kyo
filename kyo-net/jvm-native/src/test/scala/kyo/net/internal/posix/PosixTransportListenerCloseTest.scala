package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** How a poller-backed listener gives its descriptor back: `released` reports the close only once close(2) has returned, and no interest
  * change for the descriptor reaches the kernel alongside or after that close.
  */
class PosixTransportListenerCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport listener tests need epoll (Linux) or kqueue (macOS/BSD)")

    private def withTransport[A](
        body: (PosixTransport, RecordingSocketBindings, RecordingPollerBackend) => A < (Async & Abort[NetException | Closed] & Scope)
    )(using Frame): A < (Async & Abort[NetException | Closed] & Scope) =
        val sockets    = RecordingSocketBindings(Ffi.load[SocketBindings])
        val real       = PollerBackend.default()
        val pollerFd   = real.create()
        val backend    = RecordingPollerBackend(real)
        val driver     = TestDrivers.forBackend(backend, pollerFd, sockets)
        val transport  = TestTransports.forTesting(driver, sockets, backendIsEpoll = PosixConstants.isLinux)
        val driverDone = driver.start()
        Abort.run[NetException | Closed](body(transport, sockets, backend)).map { result =>
            Sync.defer(driver.close()).andThen(Abort.run(driverDone.safe.get).unit).andThen(Abort.get(result))
        }
    end withTransport

    /** A transport over a driver whose poll loop has not started, so every change the test causes stays queued until it calls `start`. That
      * holds the poll carrier still at the exact point where a close and a queued registration for the same fd would otherwise overlap.
      */
    private def withUnstartedDriver[A](
        body: (PosixTransport, PollerIoDriver, () => Unit, RecordingSocketBindings, RecordingPollerBackend) => A <
            (Async &
                Abort[
                    NetException | Closed
                ] & Scope)
    )(using Frame): A < (Async & Abort[NetException | Closed] & Scope) =
        val sockets   = RecordingSocketBindings(Ffi.load[SocketBindings])
        val real      = PollerBackend.default()
        val pollerFd  = real.create()
        val backend   = RecordingPollerBackend(real)
        val driver    = TestDrivers.forBackend(backend, pollerFd, sockets)
        val transport = TestTransports.forTesting(driver, sockets, backendIsEpoll = PosixConstants.isLinux)
        val done      = AtomicRef.Unsafe.init[Maybe[Fiber.Unsafe[Unit, Any]]](Absent)
        val start     = () => if done.get().isEmpty then done.set(Present(driver.start()))
        Abort.run[NetException | Closed](body(transport, driver, start, sockets, backend)).map { result =>
            start()
            Sync.defer(driver.close()).andThen(Abort.run(done.get().get.safe.get).unit).andThen(Abort.get(result))
        }
    end withUnstartedDriver

    "listener close" - {

        "released completes only once close(2) on the listen fd has returned" in {
            assumePollerReady()
            withTransport { (transport, sockets, _) =>
                transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                    val fd   = listener.asInstanceOf[PosixListener].serverFd
                    val hold = sockets.holdClose(fd)
                    listener.close()
                    val beforeClose = listener.released.done()
                    hold.completeDiscard(Result.succeed(()))
                    listener.released.safe.get.andThen {
                        assert(
                            sockets.closeCounts.getOrDefault(fd, 0) == 1,
                            s"the listen fd must be closed exactly once; closeCounts=${sockets.closeCounts}"
                        )
                        assert(!beforeClose, "released completed while close(2) on the listen fd had not returned")
                    }
                }
            }
        }

        "the listen fd's deregistration asks the kernel for nothing, since the close removes its interest" in {
            assumePollerReady()
            withTransport { (transport, _, backend) =>
                transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                    val fd           = listener.asInstanceOf[PosixListener].serverFd
                    val deregistered = backend.deregisteredFd(fd)
                    listener.close()
                    deregistered.safe.get.andThen {
                        val entries = backend.callLog.filter(_.startsWith(s"deregister($fd,"))
                        assert(
                            entries == List(s"deregister($fd, fdClosing=true)"),
                            s"a listener close must deregister its fd as closing, so no EV_DELETE races the close; got $entries"
                        )
                    }
                }
            }
        }

        "close(2) on the listen fd waits until the poll carrier has withdrawn it, and its queued accept arm never reaches the kernel" in {
            assumePollerReady()
            withUnstartedDriver { (transport, _, start, sockets, backend) =>
                transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                    val fd = listener.asInstanceOf[PosixListener].serverFd
                    listener.close()
                    val closedBeforeWithdrawal = sockets.closeCounts.getOrDefault(fd, 0)
                    start()
                    listener.released.safe.get.andThen {
                        assert(
                            closedBeforeWithdrawal == 0,
                            "close(2) on the listen fd ran while its accept registration was still queued for the poll carrier"
                        )
                        assert(sockets.closeCounts.getOrDefault(fd, 0) == 1, s"closeCounts=${sockets.closeCounts}")
                        assert(
                            !backend.callLog.contains(s"registerRead($fd)"),
                            s"an accept arm queued before the close reached the kernel; callLog=${backend.callLog}"
                        )
                    }
                }
            }
        }

        "a close between two accepts of the listener's drain is followed by no accept on the closed fd" in {
            assumePollerReady()
            withUnstartedDriver { (transport, driver, start, sockets, _) =>
                start()
                transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                    val fd      = listener.asInstanceOf[PosixListener].serverFd
                    val drained = Promise.Unsafe.init[Unit, Any]()
                    // The close lands inside the drain, after its first accept and before its next one. A close(2) released there lets the
                    // drain's next accept run on a number the kernel hands to the next socket opened, taking that socket's connection.
                    sockets.onAccepted = _ =>
                        listener.close()
                        driver.submitEngineOp(() => drained.completeDiscard(Result.succeed(())))
                    val client = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                    assert(client >= 0)
                    val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", listener.port).getOrElse(???)
                    Sync.ensure(Sync.defer { ca.close(); discard(sockets.close(client)) }) {
                        sockets.connect(client, ca, cl).safe.get
                            .andThen(drained.safe.get)
                            .andThen(listener.released.safe.get)
                            .andThen {
                                val order    = sockets.order
                                val closedAt = order.indexOf(s"close($fd)")
                                assert(closedAt >= 0, s"the listen fd was never closed; order=$order")
                                assert(
                                    !order.drop(closedAt + 1).contains(s"accept($fd)"),
                                    s"the drain accepted on the listen fd after closing it; order=$order"
                                )
                            }
                    }
                }
            }
        }
    }

    "connection close" - {

        "a read arm staged in the same poll cycle as the closing deregister reaches the kernel before close(2)" in {
            assumePollerReady()
            withUnstartedDriver { (_, driver, start, sockets, backend) =>
                val fd = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                assert(fd >= 0)
                val handle              = PosixHandle.socket(fd, PosixTestSockets.ReadBufferSize, Absent, Frame.internal)
                val read                = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val submittedAfterClose = new java.util.concurrent.atomic.AtomicBoolean(false)
                // Closing from inside the registration puts the closing deregister in the same drain, after a read arm that is already
                // admitted: a batching backend has staged its EV_ADD but not yet handed it to the kernel.
                backend.onRegisterRead = registered => if registered == fd then driver.closeHandle(handle)
                backend.onPoll = (changelist, nChanges) =>
                    if sockets.closeCounts.getOrDefault(fd, 0) > 0 then
                        var i = 0
                        while i < nChanges do
                            if KEvent.ident(changelist, i) == fd.toLong then submittedAfterClose.set(true)
                            i += 1
                        end while
                val closed = sockets.closed(fd)
                driver.awaitRead(handle, read)
                start()
                closed.safe.get.andThen(read.safe.getResult).andThen {
                    backend.onPoll = null
                    assert(!submittedAfterClose.get(), "a poll submitted a change for the fd after close(2) on it had already run")
                }
            }
        }

        "close(2) on the fd waits until the poll carrier has withdrawn it, and its queued read arm never reaches the kernel" in {
            assumePollerReady()
            withUnstartedDriver { (_, driver, start, sockets, backend) =>
                val fd = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                assert(fd >= 0)
                val handle = PosixHandle.socket(fd, PosixTestSockets.ReadBufferSize, Absent, Frame.internal)
                val read   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, read)
                val closed = sockets.closed(fd)
                driver.closeHandle(handle)
                val closedBeforeWithdrawal = sockets.closeCounts.getOrDefault(fd, 0)
                start()
                closed.safe.get.andThen(read.safe.getResult).map { readResult =>
                    assert(
                        closedBeforeWithdrawal == 0,
                        "close(2) on the connection fd ran while its read registration was still queued for the poll carrier"
                    )
                    assert(sockets.closeCounts.getOrDefault(fd, 0) == 1, s"closeCounts=${sockets.closeCounts}")
                    assert(
                        !backend.callLog.contains(s"registerRead($fd)"),
                        s"a read arm queued before the close reached the kernel; callLog=${backend.callLog}"
                    )
                    val failedClosed = readResult match
                        case Result.Failure(_: Closed) => true
                        case _                         => false
                    assert(failedClosed, s"the queued read must fail Closed, got $readResult")
                }
            }
        }
    }

end PosixTransportListenerCloseTest
