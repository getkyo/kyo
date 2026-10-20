package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.util.GrowableByteBuffer

/** A write-backpressure waiter the WritePump re-parks while `cancel` is failing the previous one must still be failed Closed by the
  * teardown, on the io_uring driver. In production the pump re-parks on the engine FIFO the moment its waiter completes while the close runs
  * on another carrier. Here the re-park runs inside the first waiter's completion callback, which `cancel` itself triggers, so it lands
  * between `cancel` taking the first waiter and `cancel` finishing, on one thread with no timing.
  */
class IoUringDriverBackpressureCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def fifoBarrier(driver: IoUringDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    // A tail at the high-water mark that nothing drains holds the waiter parked on the connected handle; loopback room cannot release it.
    "cancel and the release each fail a write-backpressure waiter parked on a connected handle with Closed at once" in {
        PosixTestSockets.assumeUring()
        val driver = IoUringDriver.init()
        discard(driver.start())
        def parked(writeFd: Int): (PosixHandle, Promise.Unsafe[Unit, Abort[Closed | NetException]]) =
            val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val tail   = new GrowableByteBuffer()
            tail.writeBytes(Array.fill[Byte](PosixHandle.WriteTailHighWater)(0.toByte), 0, PosixHandle.WriteTailHighWater)
            handle.pendingCipher = Present(tail)
            handle.pendingCipherSent = 0
            val waiter = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            driver.awaitWritable(handle, waiter)
            (handle, waiter)
        end parked
        def failedClosed(what: String, p: Promise.Unsafe[Unit, Abort[Closed | NetException]]) =
            p.poll() match
                case Present(Result.Failure(_: Closed)) => ()
                case other                              => fail(s"$what: expected a Closed failure at once, got $other")
        PosixTestSockets.loopbackPair().map { case (cancelFd, cancelPeer) =>
            PosixTestSockets.loopbackPair().map { case (releaseFd, releasePeer) =>
                val (cancelled, byCancel) = parked(cancelFd)
                val (released, byRelease) = parked(releaseFd)
                // The park's double-check has run and left both parked.
                fifoBarrier(driver).safe.get.map { _ =>
                    assert(!byCancel.done() && !byRelease.done(), "both waiters must be parked on their high-water tails")
                    driver.cancel(cancelled)
                    failedClosed("the waiter after cancel", byCancel)
                    driver.release(released, ReleaseMode.Graceful, Promise.Unsafe.init())
                    failedClosed("the waiter after the release", byRelease)
                    driver.release(cancelled, ReleaseMode.Graceful, Promise.Unsafe.init())
                    driver.close()
                    discard(sock.close(cancelPeer))
                    discard(sock.close(releasePeer))
                    succeed
                }
            }
        }
    }

    "a write-backpressure waiter re-parked while cancel fails the previous one is failed Closed by the teardown" in {
        PosixTestSockets.assumeUring()
        val driver = IoUringDriver.init()
        discard(driver.start())
        PosixTestSockets.loopbackPair().map { case (writeFd, peerFd) =>
            val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            // A tail at the high-water mark sends awaitWritable down the backpressure park branch.
            val tail = new GrowableByteBuffer()
            tail.writeBytes(Array.fill[Byte](PosixHandle.WriteTailHighWater)(0.toByte), 0, PosixHandle.WriteTailHighWater)
            handle.pendingCipher = Present(tail)
            handle.pendingCipherSent = 0
            val first  = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            val second = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            first.onComplete(_ => driver.awaitWritable(handle, second))
            driver.awaitWritable(handle, first)
            fifoBarrier(driver).safe.get.map { _ =>
                assert(!first.done(), "the first waiter must be parked on the backpressure slot")
                driver.cancel(handle)
                assert(first.done(), "cancel must fail the waiter it found")
                fifoBarrier(driver).safe.get.map { _ =>
                    PosixHandle.close(handle)
                    driver.close()
                    discard(sock.close(writeFd))
                    discard(sock.close(peerFd))
                    second.poll() match
                        case Present(Result.Failure(_: Closed)) => succeed
                        case other => fail(s"the re-parked waiter must be failed Closed by the teardown, got $other")
                    end match
                }
            }
        }
    }

end IoUringDriverBackpressureCloseTest
