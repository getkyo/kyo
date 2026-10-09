package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** The connection close contract for [[BlockingReaderDriver]]: a read in flight in a blocking `read(2)` is owned by the handle like any other
  * driver op, so a cancel fails it and a close frees nothing it is still writing into.
  */
class BlockingReaderDriverCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Runs `use` over the descriptors `acquire` opens, closing `driver` however the leaf ends. */
    private def ensuringClosed[P, A, S](driver: BlockingReaderDriver, acquire: => P < Async)(use: P => A < S)(using
        Frame
    ): A < (S & Async & Sync) =
        Sync.ensure(Sync.defer(driver.close()))(acquire.map(use))

    "cancel fails a read the driver is blocked in with Closed".pendingUntilFixed(
        "P10: awaitRead stores no promise, so cancel cannot fail the blocked read"
    ) in {
        PosixTestSockets.assumePoller()
        val real   = PollerIoDriver.init()
        val driver = BlockingReaderDriver.init(real)
        ensuringClosed(driver, BlockingSocketPair.open()) { case (peerFd, readFd) =>
            val h    = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(h, read)
            driver.cancel(h)
            Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](read.safe.get))).map { outcome =>
                discard(sock.close(peerFd))
                Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](read.safe.get))).map { _ =>
                    driver.close()
                    discard(sock.close(readFd))
                    outcome match
                        case Result.Success(Result.Failure(_: Closed)) => succeed
                        case other => fail(s"a cancel left the blocked read pending instead of failing it Closed: $other")
                    end match
                }
            }
        }
    }

    "a close while a read is in flight keeps the read buffer until the read returns".pendingUntilFixed(
        "P10: awaitRead takes no dispatch hold, so a close frees readBuffer while the spawned read(2) writes into it"
    ) in {
        PosixTestSockets.assumePoller()
        val real   = PollerIoDriver.init()
        val driver = BlockingReaderDriver.init(real)
        ensuringClosed(driver, BlockingSocketPair.open()) { case (peerFd, readFd) =>
            val h    = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(h, read)
            // A spent claim keeps closeHandle from shutting the fd down, which would end the read before the free and hide the window.
            assert(h.claimFdClose())
            driver.closeHandle(h)
            val freedInFlight = h.readBuffer.isClosed && !read.done()
            discard(sock.close(peerFd))
            Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](read.safe.get))).map { _ =>
                real.drainFifos()
                driver.close()
                discard(sock.close(readFd))
                assert(!freedInFlight, "closeHandle freed the read buffer while read(2) was still in flight on it")
            }
        }
    }

end BlockingReaderDriverCloseContractTest
