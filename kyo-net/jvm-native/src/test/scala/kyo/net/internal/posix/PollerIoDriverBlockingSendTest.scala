package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** On JVM/Native the driver's `@Ffi.blocking` send completes inline, so its fiber is always done when the driver reads it. A send that has
  * not completed may still put its bytes on the wire later, so the driver must not treat it as backpressure and send the same bytes again:
  * the peer would receive them twice. These leaves hold one send open through [[RecordingSocketBindings.holdSend]], let the driver react,
  * release the held send, and assert the peer's exact stream.
  */
class PollerIoDriverBlockingSendTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Half-closes `writerFd`, then reads `readerFd` to EOF: the EOF follows every byte the writer sent, so the result is its exact stream. A
      * read right after a send is not enough, since macOS can queue loopback delivery past the send's return.
      */
    private def streamToEof(writerFd: Int, readerFd: Int)(using Frame): Array[Byte] < Async =
        PosixTestSockets.halfClose(sock, writerFd)
        val out = new java.io.ByteArrayOutputStream()
        val buf = Buffer.alloc[Byte](65536)
        Sync.ensure(Sync.defer(buf.close())) {
            Loop(()) { _ =>
                val r = sock.recvNow(readerFd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                val n = r.value.toInt
                if n > 0 then
                    out.write(Buffer.copyToArray[Byte](buf, 0, n))
                    Loop.continue(())
                else if n == 0 then Loop.done(out.toByteArray)
                else Async.sleep(1.millis).andThen(Loop.continue(()))
                end if
            }
        }
    end streamToEof

    private def fifoBarrier(driver: PollerIoDriver)(using Frame): Unit < Async =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p.safe.get
    end fifoBarrier

    "a send that has not completed fails the write instead of sending its bytes again" - {
        "plaintext write" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val driver   = TestDrivers.forBackend(RecordingPollerBackend(real), pollerFd, spy)
                val handle   = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                discard(driver.start())
                val payload = Array.tabulate[Byte](64)(i => (i + 1).toByte)
                val release = spy.holdSend(clientFd)
                for
                    first <- Sync.defer(driver.write(handle, Span.fromUnsafe(payload), 0))
                    // React as the write pump does: a Partial awaits writability and retries from its offset.
                    _ <- first match
                        case WriteResult.Partial(rest, offset) =>
                            val writable = Promise.Unsafe.init[Unit, Abort[Closed | kyo.net.NetException]]()
                            driver.awaitWritable(handle, writable)
                            Abort.run(writable.safe.get).andThen(Sync.defer(discard(driver.write(handle, rest, offset))))
                        case _ => Kyo.unit
                    _ = release.completeDiscard(Result.succeed(()))
                    held <- spy.heldSend(clientFd).safe.get
                    got  <- streamToEof(clientFd, acceptedFd)
                    _    <- Sync.defer {
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    }
                yield
                    assert(
                        got.toList == payload.toList,
                        s"the peer must receive the payload exactly once; got ${got.length} bytes (held send: value=${held.value} " +
                            s"errno=${held.errorCode})"
                    )
                    assert(first == WriteResult.Error, s"a send that has not completed must fail the write, got $first")
                end for
            }
        }

        "TLS flush" in {
            TlsRealEngines.assumeTlsReady()
            PosixTestSockets.assumePoller()
            val clientEngine = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine = TlsRealEngines.singleEngine(isServer = true)
            PosixTestSockets.loopbackPair().map { case (writeFd, peerFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val driver   = TestDrivers.forBackend(RecordingPollerBackend(real), pollerFd, spy)
                val handle   = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                handle.tls = Present(clientEngine)
                discard(driver.start())
                val plain = Array.tabulate[Byte](64)(i => (i + 1).toByte)
                // The handshake runs on the engine FIFO, the carrier that later writes the client session.
                val handshaken = Promise.Unsafe.init[Unit, Any]()
                driver.submitEngineOp { () =>
                    discard(TlsEngineLoopback.handshake(clientEngine, serverEngine))
                    serverEngine.free()
                    handshaken.completeDiscard(Result.succeed(()))
                }
                for
                    _ <- handshaken.safe.get
                    release = spy.holdSend(writeFd)
                    written <- Sync.defer(driver.write(handle, Span.fromUnsafe(plain), 0))
                    // The flush ran and issued the held send.
                    _ <- fifoBarrier(driver)
                    _ = release.completeDiscard(Result.succeed(()))
                    _ <- spy.heldSend(writeFd).safe.get
                    // A flush armed on writability re-runs on the FIFO once the arm fires; wait for it to have run.
                    _ <- Loop(()) { _ =>
                        if handle.flushReArmPending then Async.sleep(1.millis).andThen(Loop.continue(()))
                        else Loop.done(())
                    }
                    _   <- fifoBarrier(driver)
                    got <- streamToEof(writeFd, peerFd)
                    sent = spy.heldSendBytes.get(writeFd)
                    _ <- Sync.defer {
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                    }
                yield
                    assert(written == WriteResult.Done, s"a TLS write is queued on the engine FIFO and returns Done, got $written")
                    assert(sent != null && sent.nonEmpty, "the flush must have issued the held send")
                    assert(
                        got.toList == sent.toList,
                        s"the peer must receive the flushed ciphertext exactly once; got ${got.length} bytes for a ${sent.length}-byte flush"
                    )
                end for
            }
        }
    }

end PollerIoDriverBlockingSendTest
