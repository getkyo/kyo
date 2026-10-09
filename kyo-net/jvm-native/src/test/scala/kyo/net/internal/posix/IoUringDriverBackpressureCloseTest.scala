package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test
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
                assert(handle.backpressurePromise.get().isDefined, "the first waiter must be parked on the backpressure slot")
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
