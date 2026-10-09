package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test

/** A writable wait armed while a handle close is failing the previous one must still be settled.
  *
  * In production the previous wait completes on the poll carrier and the WritePump, resumed there, parks again while
  * `Connection.releaseHandle` closes the handle from another carrier. Here the re-arm runs inside the previous wait's completion callback,
  * which the close itself triggers, so the same window opens on one thread with no timing: the close has taken the old wait and the new one
  * lands in the handle's slot before the close finishes.
  */
class PollerIoDriverWritableRearmCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver writable slot" - {
        "a writable re-armed while closeHandle fails the previous one is failed Closed, not stranded" in {
            PosixTestSockets.assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd)
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, peer) =>
                    val handle = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val first  = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
                    val second = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
                    first.onComplete(_ => driver.awaitWritable(handle, second))
                    driver.awaitWritable(handle, first)

                    driver.closeHandle(handle)
                    assert(first.done(), "closeHandle must fail the writable it found")
                    discard(driver.start())

                    Abort.run[Timeout](Async.timeout(10.seconds)(second.safe.getResult)).map { result =>
                        discard(sock.close(peer))
                        result match
                            case Result.Success(Result.Failure(_: Closed)) => succeed
                            case Result.Failure(_: Timeout)                =>
                                fail("the re-armed writable was never completed: the close cleared it from the handle without failing it")
                            case other => fail(s"expected the re-armed writable failed Closed, got $other")
                        end match
                    }
                }
            }
        }
    }

end PollerIoDriverWritableRearmCloseTest
