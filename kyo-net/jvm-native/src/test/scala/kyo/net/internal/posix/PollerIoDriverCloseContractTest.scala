package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** The connection close contract inside [[PollerIoDriver]], at the windows the public connection cannot reach: write completion and error
  * surfacing on the TLS path, close_notify placement, EOF classification and fd release order. The far side of each connection is a raw
  * socket the test drives byte by byte, decrypting with its own real engine.
  */
class PollerIoDriverCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def engines()(using Frame, kyo.test.AssertScope): (TlsEngine, TlsEngine) =
        val client = TlsRealEngines.singleEngine(isServer = false)
        val server = TlsRealEngines.singleEngine(isServer = true)
        assert(TlsEngineLoopback.handshake(client, server), "the in-memory TLS handshake did not complete")
        (client, server)
    end engines

    private def tlsHandle(fd: Int, engine: TlsEngine): PosixHandle =
        val h = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
        h.tls = Present(engine)
        h
    end tlsHandle

    private def sendAll(fd: Int, bytes: Array[Byte])(using kyo.test.AssertScope): Unit =
        val buf = Buffer.fromArray[Byte](bytes)
        try
            val r = sock.sendNow(fd, buf, bytes.length.toLong, PosixConstants.MSG_NOSIGNAL)
            assert(r.value.toInt == bytes.length, s"the peer send was short: ${r.value} of ${bytes.length}, errno=${r.errorCode}")
        finally buf.close()
        end try
    end sendAll

    /** Every byte the non-blocking `fd` can return right now, and whether the stream ended (0) or failed (a negative errno). */
    private def recvAvailable(fd: Int): (Array[Byte], Maybe[Int]) =
        val acc = new java.io.ByteArrayOutputStream
        val buf = Buffer.alloc[Byte](64 * 1024)
        @scala.annotation.tailrec
        def loop(): Maybe[Int] =
            val r = sock.recvNow(fd, buf, 64 * 1024L, 0)
            val n = r.value.toInt
            if n > 0 then
                acc.write(Buffer.copyToArray[Byte](buf, 0, n))
                loop()
            else if n == 0 then Present(0)
            else if r.errorCode == PosixConstants.EAGAIN || r.errorCode == PosixConstants.EWOULDBLOCK then Absent
            else Present(-r.errorCode)
            end if
        end loop
        try
            val end = loop()
            (acc.toByteArray, end)
        finally buf.close()
        end try
    end recvAvailable

    /** Feed `cipher` to `engine` and decrypt all of it: the plaintext and the last `readPlain` code (-3 close_notify, -2 fatal, 0 want-read). */
    private def decryptAll(engine: TlsEngine, cipher: Array[Byte]): (Array[Byte], Int) =
        TlsEngineLoopback.feed(engine, cipher)
        val acc = new java.io.ByteArrayOutputStream
        val out = Buffer.alloc[Byte](32 * 1024)
        @scala.annotation.tailrec
        def loop(): Int =
            val n = engine.readPlain(out, 32 * 1024)
            if n > 0 then
                acc.write(Buffer.copyToArray[Byte](out, 0, n))
                loop()
            else n
            end if
        end loop
        try
            val last = loop()
            (acc.toByteArray, last)
        finally out.close()
        end try
    end decryptAll

    /** The offset where the first incomplete TLS record of `stream` starts, or its length when it ends on a record boundary. */
    private def recordEnd(stream: Array[Byte]): Int =
        @scala.annotation.tailrec
        def loop(at: Int): Int =
            if at + 5 > stream.length then at
            else
                val next = at + 5 + (((stream(at + 3) & 0xff) << 8) | (stream(at + 4) & 0xff))
                if next > stream.length then at else loop(next)
        loop(0)
    end recordEnd

    private def fifoSettled(driver: PollerIoDriver)(using Frame): Unit < Async =
        val done = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => driver.submitEngineOp(() => done.completeDiscard(Result.succeed(()))))
        done.safe.get
    end fifoSettled

    /** Runs `use` over the descriptors `acquire` opens, closing `driver` however the leaf ends. */
    private def ensuringClosed[P, A, S](driver: IoDriver[?], acquire: => P < Async)(use: P => A < S)(using
        Frame
    ): A < (S & Async & Sync) =
        Sync.ensure(Sync.defer(driver.close()))(acquire.map(use))

    private def drainAll(driver: PollerIoDriver): Unit =
        driver.drainFifos()
        driver.drainFifos()
        driver.drainFifos()
    end drainAll

    "TLS writes" - {

        "a TLS write the engine refuses to encrypt fails the connection instead of completing as Done".pendingUntilFixed(
            "P3: encryptPlaintext's result is discarded, so a writePlain that encrypts nothing drops the plaintext after the pump saw Done"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            ensuringClosed(driver, PosixTestSockets.loopbackPair()) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val recording                    = new RecordingTlsEngine(serverEngine)
                recording.onWritePlain = () => discard(serverEngine.shutdownStep())
                val h       = tlsHandle(accepted, recording)
                val payload = Span.fromUnsafe(Array.tabulate[Byte](4096)(i => (i % 251).toByte))
                val first   = driver.write(h, payload, 0)
                drainAll(driver)
                val (wire, _) = recvAvailable(client)
                val closing   = h.isClosing()
                val second    = driver.write(h, payload, 0)
                drainAll(driver)
                driver.closeHandle(h)
                drainAll(driver)
                driver.close()
                discard(sock.close(client))
                clientEngine.free()
                serverEngine.free()
                assert(first == WriteResult.Done)
                assert(
                    closing || second == WriteResult.Error,
                    s"the engine encrypted none of the write, ${wire.length} bytes reached the peer, the handle is still open and the next " +
                        s"write returned $second"
                )
            }
        }

        "a TLS write after closeHandle reports Error, as a plaintext write does".pendingUntilFixed(
            "P4: after closeHandle a TLS write is queued on the engine FIFO and returns Done, then dropped, where a plaintext write returns Error"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            ensuringClosed(driver, PosixTestSockets.loopbackPair()) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val h                            = tlsHandle(accepted, serverEngine)
                driver.closeHandle(h)
                val after = driver.write(h, Span.fromUnsafe(Array.fill[Byte](1024)(1)), 0)
                drainAll(driver)
                driver.close()
                discard(sock.close(client))
                clientEngine.free()
                serverEngine.free()
                assert(after == WriteResult.Error, s"a write on a closed TLS handle returned $after")
            }
        }

        "a TLS write after a hard send error reports Error instead of Done".pendingUntilFixed(
            "P5: a hard send error discards the whole ciphertext tail with a debug log, and later TLS writes keep returning Done"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            ensuringClosed(driver, PosixTestSockets.loopbackPair()) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val h                            = tlsHandle(accepted, serverEngine)
                PosixTestSockets.resetPeer(sock, client)
                val payload = Span.fromUnsafe(Array.fill[Byte](1024)(2))
                val first   = driver.write(h, payload, 0)
                drainAll(driver)
                val second = driver.write(h, payload, 0)
                drainAll(driver)
                val closing = h.isClosing()
                driver.closeHandle(h)
                drainAll(driver)
                driver.close()
                clientEngine.free()
                serverEngine.free()
                assert(first == WriteResult.Done)
                assert(
                    second == WriteResult.Error || closing,
                    s"the send after the peer's reset failed and its ciphertext was discarded, yet the next write returned $second " +
                        "and the handle is still open"
                )
            }
        }

        "ciphertext the engine produces while decrypting reaches the peer without a further application write".pendingUntilFixed(
            "P6: ciphertext a read produces is appended to pendingCipher and only flushed by the next application write"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            ensuringClosed(driver, PosixTestSockets.loopbackPair()) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val recording                    = new RecordingTlsEngine(serverEngine)
                val response                     = "read-produced".getBytes("UTF-8")
                // The shims bind no SSL_key_update, so the KeyUpdate reply a real read produces is stood in for by a real record the engine
                // queues on its write side from inside the read op.
                recording.onFeedCiphertext = () =>
                    val buf = Buffer.fromArray[Byte](response)
                    try discard(serverEngine.writePlain(buf, response.length))
                    finally buf.close()
                val h = tlsHandle(accepted, recording)
                sendAll(client, TlsEngineLoopback.encrypt(clientEngine, "ping".getBytes("UTF-8")))
                val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(h, read)
                for
                    outcome <- Abort.run[Timeout | Closed](Async.timeout(10.seconds)(read.safe.get))
                    _       <- fifoSettled(driver)
                    (wire, _)  = recvAvailable(client)
                    (plain, _) = decryptAll(clientEngine, wire)
                    _ <- Sync.defer(driver.closeHandle(h))
                    _ <- fifoSettled(driver)
                yield
                    driver.close()
                    discard(sock.close(client))
                    clientEngine.free()
                    serverEngine.free()
                    assert(outcome.isSuccess, s"the read of the peer's record failed: $outcome")
                    assert(
                        new String(plain, "UTF-8") == "read-produced",
                        s"the record the engine queued during the read never reached the peer (${wire.length} bytes arrived)"
                    )
                end for
            }
        }
    }

    "TLS close" - {

        "a full close with a ciphertext tail pending never splices close_notify into a partly sent record".pendingUntilFixed(
            "P7: full-close shutdownTls sends the alert straight to the socket, bypassing pendingCipher, inside the partly sent record"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            ensuringClosed(driver, PosixTestSockets.smallBufferedPair(8 * 1024, 8 * 1024)) { case (kyoFd, peerFd) =>
                val (clientEngine, serverEngine) = engines()
                val h                            = tlsHandle(kyoFd, serverEngine)
                val payload                      = Array.tabulate[Byte](2 * 1024 * 1024)(i => (i % 251).toByte)
                val written                      = driver.write(h, Span.fromUnsafe(payload), 0)
                drainAll(driver)
                val tailPending = h.unsentTailBytes
                val (sent, _)   = recvAvailable(peerFd)
                val midRecord   = recordEnd(sent) != sent.length
                driver.closeHandle(h)
                drainAll(driver)
                val (rest, end)   = recvAvailable(peerFd)
                val stream        = sent ++ rest
                val (_, lastCode) = decryptAll(clientEngine, stream)
                driver.close()
                discard(sock.close(peerFd))
                clientEngine.free()
                serverEngine.free()
                assert(written == WriteResult.Done)
                assert(tailPending > 0, "setup: the write left no ciphertext tail behind a full socket")
                assert(midRecord, "setup: the flush stopped on a record boundary, so the close cannot splice into a record")
                assert(
                    rest.isEmpty || recordEnd(stream) == stream.length,
                    s"after ${sent.length} bytes of a partly sent record the close wrote ${rest.length} more bytes that do not complete it " +
                        s"(stream end $end, last decrypt code $lastCode)"
                )
            }
        }
    }

    "TLS EOF" - {

        "a close_notify decrypted with the last data record stays an orderly close when the FIN follows".pendingUntilFixed(
            "P8: the read of the later FIN overwrites PeerCleanClose with PeerEof"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            ensuringClosed(driver, PosixTestSockets.loopbackPair()) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val plain                        = Array.tabulate[Byte](100)(i => (i % 251).toByte)
                val record                       = TlsEngineLoopback.encrypt(clientEngine, plain)
                val shutdown                     = clientEngine.shutdownStep()
                val closeNotify                  = TlsEngineLoopback.drainAll(clientEngine)
                assert(shutdown != -2 && closeNotify.nonEmpty, s"the client engine emitted no close_notify ($shutdown)")
                val h = tlsHandle(accepted, serverEngine)
                sendAll(client, record ++ closeNotify)
                PosixTestSockets.halfClose(sock, client)
                def readToEnd(acc: Array[Byte]): (Array[Byte], ReadOutcome) < (Async & Abort[Closed]) =
                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(h, p)
                    p.safe.get.map {
                        case ReadOutcome.Bytes(bytes) => readToEnd(acc ++ bytes.toArray)
                        case other                    => (acc, other)
                    }
                end readToEnd
                for
                    outcome <- Abort.run[Timeout | Closed](Async.timeout(10.seconds)(readToEnd(Array.emptyByteArray)))
                    atEnd = h.halfClose
                    _ <- Sync.defer(driver.closeHandle(h))
                    _ <- fifoSettled(driver)
                yield
                    driver.close()
                    discard(sock.close(client))
                    clientEngine.free()
                    serverEngine.free()
                    outcome match
                        case Result.Success((got, end)) =>
                            assert(got.sameElements(plain), s"got ${got.length} of ${plain.length} bytes")
                            assert(
                                atEnd == HalfCloseState.PeerCleanClose,
                                s"the orderly close was recorded as $atEnd (read ended with $end)"
                            )
                        case other =>
                            fail(s"the read never reached the end of a stream ended by close_notify and FIN: $other")
                    end match
                end for
            }
        }
    }

    "plaintext close" - {

        val fullCloseLeaf = "a full close with unread inbound bytes delivers every written byte and a FIN, never a reset".tagged()
        (if PosixConstants.isLinux then
             fullCloseLeaf.pendingUntilFixed(
                 "K1: on Linux, close() with unread inbound bytes sends RST and discards the unsent tail; the peer reads a prefix, then ECONNRESET"
             )
         else fullCloseLeaf) in {
            PosixTestSockets.assumePoller()
            val realBackend = PollerBackend.default()
            val sockets     = RecordingSocketBindings(Ffi.load[SocketBindings])
            val driver      = TestDrivers.forBackend(realBackend, realBackend.create(), sockets)
            ensuringClosed(driver, BlockingSocketPair.open()) { case (peerFd, kyoFd) =>
                assert(Ffi.load[PosixShimBindings].kyo_posix_set_nonblocking(kyoFd) == 0)
                val h      = PosixHandle.socket(kyoFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val chunk  = 64 * 1024
                val unread = Buffer.fromArray[Byte](Array.fill[Byte](1024)(9))
                try discard(sock.send(peerFd, unread, 1024L, PosixConstants.MSG_NOSIGNAL).poll())
                finally unread.close()
                @scala.annotation.tailrec
                def fill(written: Long, rounds: Int): Long =
                    val data = Span.fromUnsafe(Array.tabulate[Byte](chunk)(i => ((written + i) % 251).toByte))
                    driver.write(h, data, 0) match
                        case WriteResult.Done if rounds < 4096 => fill(written + chunk, rounds + 1)
                        case WriteResult.Partial(_, offset)    => written + offset
                        case other                             => throw new AssertionError(s"setup: the plaintext write returned $other")
                    end match
                end fill
                val written = fill(0L, 0)
                driver.closeHandle(h)
                drainAll(driver)
                val buf                                                                        = Buffer.alloc[Byte](chunk)
                def readToEnd(received: Long, inOrder: Boolean): (Long, Boolean, Long) < Async =
                    sock.recv(peerFd, buf, chunk.toLong, 0).safe.get.map { r =>
                        val n = r.value
                        if n > 0 then
                            val bytes = Buffer.copyToArray[Byte](buf, 0, n.toInt)
                            val ok    = bytes.indices.forall(i => bytes(i) == ((received + i) % 251).toByte)
                            readToEnd(received + n, inOrder && ok)
                        else (received, inOrder, if n == 0 then 0L else -r.errorCode.toLong)
                        end if
                    }
                for
                    closed <- Abort.run[Timeout](Async.timeout(10.seconds)(sockets.closed(kyoFd).safe.get))
                    result <- Abort.run[Timeout](Async.timeout(30.seconds)(readToEnd(0L, true)))
                yield
                    buf.close()
                    driver.close()
                    discard(sock.close(peerFd))
                    assert(closed.isSuccess, "the close never released the descriptor")
                    result match
                        case Result.Success((received, inOrder, end)) =>
                            val how = if end == 0L then "a FIN" else s"errno ${-end}"
                            assert(
                                received == written && end == 0L,
                                s"the peer read $received of $written written bytes and the stream ended with $how"
                            )
                            assert(inOrder, "the bytes the peer read are out of order")
                        case other => fail(s"the peer's read never ended: $other")
                    end match
                end for
            }
        }
    }

    "fd release" - {

        "a fatal TLS record closes the descriptor only after the poller has withdrawn it".pendingUntilFixed(
            "P9: on a fatal record endDispatch runs freeResources while the withdrawal is still Open, so close(fd) precedes the deregister"
        ) in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeBoringSslReady()
            val realBackend = PollerBackend.default()
            val backend     = RecordingPollerBackend(realBackend)
            val sockets     = RecordingSocketBindings(Ffi.load[SocketBindings])
            val driver      = TestDrivers.forBackend(backend, realBackend.create(), sockets)
            discard(driver.start())
            ensuringClosed(driver, PosixTestSockets.loopbackPair(sockets)) { case (client, accepted) =>
                val (clientEngine, serverEngine) = engines()
                val good                         = TlsEngineLoopback.encrypt(clientEngine, "good".getBytes("UTF-8"))
                val bad                          = TlsEngineLoopback.encrypt(clientEngine, "tampered".getBytes("UTF-8"))
                bad(bad.length - 1) = (bad(bad.length - 1) ^ 0xff).toByte
                val withdrawnAtClose = new java.util.concurrent.atomic.AtomicReference[Maybe[Boolean]](Absent)
                sockets.closed(accepted).onComplete(_ => withdrawnAtClose.set(Present(backend.deregisteredFds.contains(accepted))))
                val h = tlsHandle(accepted, serverEngine)
                sendAll(client, good ++ bad)
                val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(h, read)
                for
                    outcome <- Abort.run[Timeout | Closed](Async.timeout(10.seconds)(read.safe.get))
                    closed  <- Abort.run[Timeout](Async.timeout(10.seconds)(sockets.closed(accepted).safe.get))
                    _       <- Sync.defer(driver.closeHandle(h))
                    _       <- fifoSettled(driver)
                yield
                    driver.close()
                    discard(sock.close(client))
                    clientEngine.free()
                    serverEngine.free()
                    assert(outcome.isSuccess, s"the fatal record did not complete the read: $outcome")
                    assert(closed.isSuccess, "the fatal record never closed the descriptor")
                    assert(
                        withdrawnAtClose.get() == Present(true),
                        s"close($accepted) ran before the poller withdrew the fd (interest changes applied: ${backend.callLog})"
                    )
                end for
            }
        }
    }

end PollerIoDriverCloseContractTest
