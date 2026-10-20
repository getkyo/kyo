package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.transport.WriteResult

/** The poller's writable waits: every one parked on a handle is settled by the cancel or close that ends it.
  *
  * A writable wait armed while a handle close is failing the previous one must still be settled. In production the previous wait completes
  * on the poll carrier and the WritePump, resumed there, parks again while the connection's release closes the handle from another carrier.
  * Here the re-arm runs inside the previous wait's completion callback, which the close itself triggers, so the same window opens on one
  * thread with no timing: the close has taken the old wait and the new one lands in the handle's slot before the close finishes. A re-armed
  * wait the close strands never completes, which the leaf timeout fails.
  *
  * The pump's wait and the driver's own TLS flush re-arm park at the same time, so a cancel must fail both.
  */
class PollerIoDriverWritableRearmCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver writable slot" - {
        "a writable re-armed while release fails the previous one is failed Closed, not stranded" in {
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

                    driver.release(handle, ReleaseMode.Graceful, Promise.Unsafe.init())
                    assert(first.done(), "release must fail the writable it found")
                    discard(driver.start())

                    second.safe.getResult.map { result =>
                        discard(sock.close(peer))
                        result match
                            case Result.Failure(_: Closed) => succeed
                            case other                     => fail(s"expected the re-armed writable failed Closed, got $other")
                        end match
                    }
                }
            }
        }

        // The TLS tail is non-empty only while a flush hit EAGAIN, which is exactly when the pump may park on tail backpressure, so the
        // driver's flush re-arm and the pump's wait are parked at the same time by construction.
        "a TLS tail flush re-arm and the pump's tail-backpressure wait park together, and cancel fails both" in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (fd, peer) =>
                    val client = TlsRealEngines.singleEngine(isServer = false)
                    val server = TlsRealEngines.singleEngine(isServer = true)
                    val handle = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    Sync.ensure(Sync.defer {
                        driver.release(handle, ReleaseMode.Graceful, Promise.Unsafe.init())
                        discard(sock.close(peer))
                        driver.submitEngineOp { () =>
                            client.free()
                            server.free()
                        }
                    }) {
                        assert(TlsEngineLoopback.handshake(client, server), "the in-memory TLS handshake did not complete")
                        handle.tls = Present(server)
                        // The peer never reads, so the flush EAGAINs with most of the ciphertext still in the tail and parks its re-arm.
                        assert(driver.write(handle, Span.fromUnsafe(Array.fill[Byte](2 * 1024 * 1024)(7)), 0) == WriteResult.Done)
                        fifoBarrier(driver).safe.get.map { _ =>
                            val flush = handle.life.flush.current
                            assert(flush.nonEmpty, "the flush must park its writable re-arm while the tail is unsent")
                            assert(
                                handle.unsentTailBytes >= PosixHandle.WriteTailLowWater,
                                s"the tail must be over the low-water mark for the pump to park on it, got ${handle.unsentTailBytes}"
                            )
                            val pump = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
                            driver.awaitWritable(handle, pump)
                            fifoBarrier(driver).safe.get.map { _ =>
                                driver.cancel(handle)
                                assert(
                                    pump.poll().exists(_.isFailure),
                                    s"cancel must fail the pump's tail-backpressure wait, got ${pump.poll()}"
                                )
                                assert(
                                    flush.exists(_.poll().exists(_.isFailure)),
                                    s"cancel must fail the flush re-arm parked beside the pump's wait, got ${flush.map(_.poll())}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** A promise the engine FIFO completes once every op submitted before it has run. */
    private def fifoBarrier(driver: PollerIoDriver)(using Frame): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

end PollerIoDriverWritableRearmCloseTest
