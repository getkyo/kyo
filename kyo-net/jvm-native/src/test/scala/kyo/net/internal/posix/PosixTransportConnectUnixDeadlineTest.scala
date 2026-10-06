package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetUnixConnectTimeoutException
import kyo.net.Test
import kyo.net.TlsTestCertShared

/** The `connectUnix` deadline, driven through a stall it can actually bound.
  *
  * The deadline does NOT exist to bound the kernel. A non-blocking AF_UNIX connect settles promptly on every arm this transport ships: it
  * completes inline or fails fast, never reporting "in progress", and `IORING_OP_CONNECT` against a full backlog reaps `-EAGAIN` immediately.
  * Driving this leaf through a full accept queue is therefore dead: measured on Linux, the connect simply succeeds, because a listener's
  * accept loop keeps draining the queue.
  *
  * What the deadline bounds is the transport's OWN asynchronous path to the promise: an op stranded on the engine queue (whose own comment in
  * `IoUringDriver.submitEngineOp` reads "a client connect armed here hangs until the transport's connect deadline"), a submission parked on a
  * full submission queue, and a driver carrier that never gets scheduled. A driver decorator that swallows `awaitConnect` reproduces exactly
  * that state: the connect is in flight, nothing will ever complete it, and the timer is the only thing that can free the caller. It needs no
  * particular OS and no timing luck.
  */
class PosixTransportConnectUnixDeadlineTest extends Test:

    import AllowUnsafe.embrace.danger

    "PosixTransport connectUnix deadline" - {

        "a Unix connect the driver never completes fails with the typed Unix timeout leaf at its deadline" in {
            PosixTestSockets.assumePoller()
            given Frame = Frame.internal
            val sock    = Ffi.load[SocketBindings]
            val backend = PollerBackend.default()
            val real    = TestDrivers.forBackend(backend, backend.create(), sock)
            val driver  = RecordingIoDriver(real)
            discard(driver.start())
            val path    = s"/tmp/kyo-uds-deadline-${TlsTestCertShared.uniquePathTag()}.sock"
            val timeout = 200.millis

            // The completion arm (the one PosixTransport selects for io_uring) is where a connect goes through driver.awaitConnect and can
            // therefore be left pending. The readiness arm settles the promise inline for AF_UNIX and never reaches the driver at all, which
            // is why the deadline is a no-op there. labelOverride drives that selection without needing a real ring, so this leaf runs on
            // every host rather than only where io_uring exists.
            driver.labelOverride = Present("IoUringDriver")
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val transport = TestTransports.forTesting(driver, sock, backendIsEpoll = false, clock = clock)
                    for
                        listener <- transport.listenUnix(path, 16)(_ => ()).safe.get
                        // From here the driver takes the connect submission and does nothing with it: the promise stays pending with no
                        // outcome coming, which is the shape every real stall class produces.
                        _       <- Sync.defer { driver.stallConnect = true }
                        outcome <- Fiber.init(Abort.run[kyo.net.NetException | Closed](transport.connectUnix(path, timeout).safe.get))
                        _       <- tc.awaitPendingSleepers(1)
                        _       <- tc.advance(timeout.minusOrZero(1.millis))
                        _       <- tc.awaitPendingSleepers(1)
                        _       <- tc.advance(1.millis)
                        result  <- outcome.get
                    yield
                        listener.close()
                        driver.close()
                        // Any connection that somehow completed must not leak.
                        result.foreach(_.close())
                        result match
                            case Result.Failure(e: NetUnixConnectTimeoutException) =>
                                assert(e.timeout == timeout, s"expected the connect's own $timeout deadline, got ${e.timeout}")
                                assert(e.path == path, s"expected the Unix path in the leaf, got ${e.path}")
                            case other =>
                                fail(
                                    s"expected NetUnixConnectTimeoutException($timeout) once the driver stalls the connect, got $other: " +
                                        "the deadline is the only thing that can free a caller whose connect the transport never completes"
                                )
                        end match
                    end for
                }
            }
        }
    }

end PosixTransportConnectUnixDeadlineTest
