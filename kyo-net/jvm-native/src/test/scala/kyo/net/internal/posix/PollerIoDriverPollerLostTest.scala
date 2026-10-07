package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** A poller whose own fd stops being valid (its wait fails EBADF) can never deliver another event, so re-polling only spins the carrier. The
  * driver must stop: fail every pending op with a typed `Closed` naming the lost poller, run its terminal exit (completing its done-fiber),
  * and leave the lost fd number alone, since it may already name another descriptor. The leaves inject the loss through the recording
  * backend (every later wait runs against fd -1), so no recycled fd number can race them; a driver that spins never completes its done-fiber
  * and hangs the leaf to its cap.
  */
class PollerIoDriverPollerLostTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    "a driver whose poller fd is lost stops, fails its pending ops typed, and never closes the lost fd number" in {
        PosixTestSockets.assumePoller()
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val backend  = RecordingPollerBackend(real)
        val driver   = TestDrivers.forBackend(backend, pollerFd)
        val done     = driver.start()
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val read   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, read)
            backend.registeredRead(accepted).safe.get.andThen {
                backend.pollerLost.set(true)
                // Wake the parked wait so the next cycle runs against the lost fd.
                driver.submitEngineOp(() => ())
                done.safe.get.andThen(Abort.run[Closed](read.safe.get)).map { outcome =>
                    // The lost number is the test's to release: the driver must not have closed it.
                    val closedByDriver = backend.closedPollerFds.contains(pollerFd)
                    real.close(pollerFd)
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                    outcome match
                        case Result.Failure(closed) =>
                            assert(closed.getMessage.contains("poller"), s"the pending read must fail naming the lost poller: $closed")
                        case other => fail(s"the pending read must fail Closed once the poller is lost, got $other")
                    end match
                    assert(!closedByDriver, s"the driver closed the lost poller fd number $pollerFd, which may name another descriptor")
                }
            }
        }
    }

    "an op submitted after the poller is lost fails typed instead of waiting on a driver that no longer polls" in {
        PosixTestSockets.assumePoller()
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val backend  = RecordingPollerBackend(real)
        val driver   = TestDrivers.forBackend(backend, pollerFd)
        val done     = driver.start()
        backend.pollerLost.set(true)
        driver.submitEngineOp(() => ())
        done.safe.get.andThen {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val read   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, read)
                Abort.run[Closed](read.safe.get).map { outcome =>
                    real.close(pollerFd)
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                    assert(outcome.isFailure, s"a read submitted after the poller was lost must fail Closed, got $outcome")
                }
            }
        }
    }

end PollerIoDriverPollerLostTest
