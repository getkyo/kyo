package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.*

/** Driver-level detector tests for io_uring's peer-close probe (the io_uring backend of `isPeerClosed`).
  *
  * io_uring keeps no standing read registration, so `isPeerClosed` asks the kernel directly with a non-blocking `poll(2)` off the ring. Each leaf
  * drives a real loopback pair (Linux-gated), then closes the peer and asserts the probe flips from false to true: a clean FIN via POLLRDHUP, an RST
  * via POLLERR/POLLHUP.
  */
class IoUringDriverPeerClosedTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def withDriver[A](body: IoUringDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = IoUringDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    "isPeerClosed: io_uring poll(2) probe" - {

        "is false for a live peer and true after a clean FIN" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    assert(!driver.isPeerClosed(handle), "a live peer must read as not closed")
                    PosixTestSockets.closePeerForEof(sock, peerFd) // FIN
                    // An isPeerClosed that misses the FIN's POLLRDHUP hangs the leaf here.
                    untilState(driver.isPeerClosed(handle)).andThen { driver.closeHandle(handle); succeed }
                }
            }
        }

        "is true after a peer RST" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    PosixTestSockets.resetPeer(sock, peerFd) // RST
                    // An isPeerClosed that misses the RST's POLLERR/POLLHUP hangs the leaf here.
                    untilState(driver.isPeerClosed(handle)).andThen { driver.closeHandle(handle); succeed }
                }
            }
        }
    }

end IoUringDriverPeerClosedTest
