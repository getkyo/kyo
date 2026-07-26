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

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

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
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    assert(!driver.isPeerClosed(handle), "a live peer must read as not closed")
                    PosixTestSockets.closePeerForEof(sock, peerFd) // FIN
                    awaitCondition(2.seconds)(driver.isPeerClosed(handle)).map { closed =>
                        driver.closeHandle(handle)
                        assert(closed, "io_uring isPeerClosed must observe the peer FIN via poll(2) POLLRDHUP")
                    }
                }
            }
        }

        "is true after a peer RST" in {
            PosixTestSockets.assumeUring()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                    val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    PosixTestSockets.resetPeer(sock, peerFd) // RST
                    awaitCondition(2.seconds)(driver.isPeerClosed(handle)).map { closed =>
                        driver.closeHandle(handle)
                        assert(closed, "io_uring isPeerClosed must observe a peer RST via poll(2) POLLERR/POLLHUP")
                    }
                }
            }
        }
    }

end IoUringDriverPeerClosedTest
