package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.*

/** Driver-level tests for the poller's peer-close watch (the epoll/kqueue backend of `awaitPeerClose`).
  *
  * A backpressured pump arms no read, so the poller's standing edge-triggered registration is the only thing that observes a peer FIN/RST. Each leaf
  * drives a real loopback pair through the driver, drains a read so no read is armed, registers the watch, then closes the peer: a FIN lands in
  * dispatchRead's Absent branch and an RST in dispatchError (where the SO_ERROR read clears the kernel error, so the latch is the only surviving
  * evidence). Either must complete the watch.
  */
class PollerIoDriverPeerClosedTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Send `n` bytes from the peer so the driver side has data to drain (arming and completing one read, then leaving no read armed). */
    private def sendFromPeer(peerFd: Int, n: Int): Unit =
        val buf = Buffer.alloc[Byte](n)
        var i   = 0
        while i < n do
            buf.set(i, 1.toByte)
            i += 1
        try discard(sock.sendNow(peerFd, buf, n.toLong, 0))
        finally buf.close()
    end sendFromPeer

    private def withDriver[A](body: PollerIoDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = PollerIoDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    /** The watch's outcome, bounded so a watch that never fires fails the leaf instead of hanging it. */
    private def outcome(watch: Promise.Unsafe[Unit, Abort[Closed]])(using Frame): Result[Timeout | Closed, Unit] < Async =
        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(watch.safe.get))

    "awaitPeerClose: poller peer-close watch" - {

        "completes on a peer FIN that lands with no read armed (dispatchRead Absent branch)" in {
            PosixTestSockets.assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    sendFromPeer(peerFd, 4)
                    // Drain the 4 bytes through the driver: this registers the fd and completes the read, then leaves no read armed (backpressure).
                    PosixTestSockets.drainPeer(driver, handle, driverFd, 4).map { _ =>
                        val watch = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitPeerClose(handle, watch)
                        val beforeFin = watch.done()
                        PosixTestSockets.closePeerForEof(sock, peerFd) // FIN with no read armed
                        outcome(watch).map { result =>
                            driver.closeHandle(handle)
                            assert(!beforeFin, "a live peer must not complete the watch")
                            assert(result == Result.succeed(()), s"the poller must complete the watch on a peer FIN; got $result")
                        }
                    }
                }
            }
        }

        "completes on a peer RST via dispatchError (SO_ERROR would otherwise destroy the evidence)" in {
            PosixTestSockets.assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    sendFromPeer(peerFd, 4)
                    PosixTestSockets.drainPeer(driver, handle, driverFd, 4).map { _ =>
                        val watch = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitPeerClose(handle, watch)
                        PosixTestSockets.resetPeer(sock, peerFd) // RST with no read armed
                        outcome(watch).map { result =>
                            driver.closeHandle(handle)
                            assert(result == Result.succeed(()), s"the poller must complete the watch on a peer RST; got $result")
                        }
                    }
                }
            }
        }

        "a withdrawn watch is not completed by a later FIN, and a watch registered after the FIN completes at once" in {
            PosixTestSockets.assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    sendFromPeer(peerFd, 4)
                    PosixTestSockets.drainPeer(driver, handle, driverFd, 4).map { _ =>
                        val withdrawn = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitPeerClose(handle, withdrawn)
                        driver.cancelPeerCloseWatch(handle, withdrawn)
                        val first = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitPeerClose(handle, first)
                        PosixTestSockets.closePeerForEof(sock, peerFd)
                        outcome(first).map { firstResult =>
                            // The FIN is latched now, so a fresh registration completes inline.
                            val late = Promise.Unsafe.init[Unit, Abort[Closed]]()
                            driver.awaitPeerClose(handle, late)
                            driver.closeHandle(handle)
                            assert(firstResult == Result.succeed(()), s"the registered watch must complete on the FIN; got $firstResult")
                            assert(!withdrawn.done(), "a withdrawn watch must not be completed")
                            assert(late.done(), "a watch registered after the observed FIN must complete at once")
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverPeerClosedTest
