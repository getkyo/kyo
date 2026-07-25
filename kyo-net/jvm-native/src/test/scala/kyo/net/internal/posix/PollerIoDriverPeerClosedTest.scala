package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.*

/** Driver-level detector tests for the poller's peer-close latch (the epoll/kqueue backend of `isPeerClosed`).
  *
  * A backpressured pump arms no read, so the poller's standing edge-triggered registration is the only thing that observes a peer FIN/RST. Each leaf
  * drives a real loopback pair through the driver, drains a read so no read is armed, then closes the peer: a FIN lands in dispatchRead's Absent
  * branch and an RST in dispatchError (where the SO_ERROR read clears the kernel error, so the latch is the only surviving evidence). `isPeerClosed`
  * must then read true.
  */
class PollerIoDriverPeerClosedTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

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

    "isPeerClosed: poller peer-close latch" - {

        "latches a peer FIN that lands with no read armed (dispatchRead Absent branch)" in {
            PosixTestSockets.assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    sendFromPeer(peerFd, 4)
                    // Drain the 4 bytes through the driver: this registers the fd and completes the read, then leaves no read armed (backpressure).
                    PosixTestSockets.drainPeer(driver, handle, driverFd, 4).map { _ =>
                        assert(!driver.isPeerClosed(handle), "the peer is still live before the FIN")
                        PosixTestSockets.closePeerForEof(sock, peerFd) // FIN with no read armed
                        awaitCondition(2.seconds)(driver.isPeerClosed(handle)).map { closed =>
                            driver.closeHandle(handle)
                            assert(closed, "the poller must latch a peer FIN arriving with no read armed")
                        }
                    }
                }
            }
        }

        "latches a peer RST via dispatchError (SO_ERROR would otherwise destroy the evidence)" in {
            PosixTestSockets.assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    sendFromPeer(peerFd, 4)
                    PosixTestSockets.drainPeer(driver, handle, driverFd, 4).map { _ =>
                        PosixTestSockets.resetPeer(sock, peerFd) // RST with no read armed
                        awaitCondition(2.seconds)(driver.isPeerClosed(handle)).map { closed =>
                            driver.closeHandle(handle)
                            assert(closed, "the poller must latch a peer RST through dispatchError")
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverPeerClosedTest
