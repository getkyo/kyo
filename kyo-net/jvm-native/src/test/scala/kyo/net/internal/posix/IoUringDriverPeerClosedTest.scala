package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.*

/** Driver-level tests for io_uring's peer-close watch (the io_uring backend of `awaitPeerClose`).
  *
  * io_uring keeps no standing read registration, so the watch is a one-shot `POLL_ADD` for `POLLRDHUP` on the read fd. Each leaf drives a real
  * loopback pair (Linux-gated): a clean FIN completes the watch through `POLLRDHUP`, an RST through `POLLERR`/`POLLHUP`, and a withdrawn watch is
  * cancelled in the kernel so the handle's deferred close still discharges.
  */
class IoUringDriverPeerClosedTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def withDriver[A](body: IoUringDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = IoUringDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    /** The watch's outcome, bounded so a watch that never fires fails the leaf instead of hanging it. */
    private def outcome(watch: Promise.Unsafe[Unit, Abort[Closed]])(using Frame): Result[Timeout | Closed, Unit] < Async =
        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(watch.safe.get))

    "awaitPeerClose: io_uring POLL_ADD watch" - {

        "completes on a clean FIN" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val watch  = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitPeerClose(handle, watch)
                    PosixTestSockets.closePeerForEof(sock, peerFd) // FIN
                    outcome(watch).map { result =>
                        driver.closeHandle(handle)
                        assert(result == Result.succeed(()), s"the watch must complete on the peer FIN via POLLRDHUP; got $result")
                    }
                }
            }
        }

        "completes on a peer RST" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val watch  = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitPeerClose(handle, watch)
                    PosixTestSockets.resetPeer(sock, peerFd) // RST
                    outcome(watch).map { result =>
                        driver.closeHandle(handle)
                        assert(result == Result.succeed(()), s"the watch must complete on a peer RST via POLLERR/POLLHUP; got $result")
                    }
                }
            }
        }

        "a withdrawn watch on a live peer is cancelled in the kernel, not completed as a close" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val watch  = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitPeerClose(handle, watch)
                    driver.cancelPeerCloseWatch(handle, watch)
                    outcome(watch).map { result =>
                        driver.closeHandle(handle)
                        discard(sock.close(peerFd))
                        assert(
                            result match
                                case Result.Failure(_: Closed) => true
                                case _                         =>
                                    false
                            ,
                            s"a cancelled watch must end Closed, not as an observed close; got $result"
                        )
                    }
                }
            }
        }
    }

end IoUringDriverPeerClosedTest
