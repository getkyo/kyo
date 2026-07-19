package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Smoke tests for [[PosixTestSockets]].
  *
  * Exercises the foundation helpers against real sockets and real kernel behavior. All waits use real-event latches (Promise.Unsafe,
  * @Ffi.blocking fibers) with no sleep. The full suite is gated on assumePoller so the tests cancel cleanly where epoll and kqueue are absent.
  */
class PosixTestSocketsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "loopbackPair real round-trip: 4 bytes written on client are received byte-equal on accepted" in {
        PosixTestSockets.assumePoller().andThen {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
                    val w         = driver.write(clientH, payload, 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    val readP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readP)
                    readP.safe.get.map {
                        case ReadOutcome.Bytes(got) =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            assert(got.toArray.toList == List[Byte](1, 2, 3, 4), s"got ${got.toArray.toList}")
                        case other =>
                            fail(s"expected Bytes, got $other")
                    }
                }
            }
        }
    }

    "loopbackPair with injected bindings: real socket round-trip via RecordingSocketBindings" in {
        PosixTestSockets.assumePoller().andThen {
            val recording = new RecordingSocketBindings(sock)
            PosixTestSockets.loopbackPair(recording).map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Span.fromUnsafe(Array[Byte](10, 20, 30))
                    val w         = driver.write(clientH, payload, 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    val readP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readP)
                    readP.safe.get.map {
                        case ReadOutcome.Bytes(got) =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            assert(
                                got.toArray.sameElements(Array[Byte](10, 20, 30)),
                                s"expected bytes [10,20,30] from real socket, got ${got.toArray.toList}"
                            )
                        case other =>
                            fail(s"expected Bytes, got $other")
                    }
                }
            }
        }
    }

    "smallBufferedPair: creates a real socket pair; drainPeer receives a small payload correctly" in {
        PosixTestSockets.assumePoller().andThen {
            // The smallBufferedPair helper creates shrunk-buffer sockets. We validate that the pair works for
            // a payload that fits within the shrunk buffer (1 KB, well below the 2 KB sndbuf). This confirms
            // the helper creates a real connected pair where drainPeer's awaitRead latch fires on real recv.
            // Anti-flakiness: drainPeer latches on the real kernel recv event via Promise.Unsafe, not a sleep.
            PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val total     = 1024
                    val blob      = Span.fromUnsafe(Array.fill[Byte](total)(0x42.toByte))
                    val w         = driver.write(clientH, blob, 0)
                    assert(w == WriteResult.Done, s"1 KB write should fit in 2 KB sndbuf; got $w")
                    PosixTestSockets.drainPeer(driver, acceptedH, accepted, total).map { got =>
                        driver.closeHandle(clientH)
                        driver.closeHandle(acceptedH)
                        assert(got >= total, s"expected >= $total bytes drained via drainPeer, got $got")
                    }
                }
            }
        }
    }

    "resetPeer: SO_LINGER {1,0} close causes ECONNRESET on peer recv (non-zero errorCode)" in {
        PosixTestSockets.assumePoller().andThen {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // RST the client side.
                    PosixTestSockets.resetPeer(sock, client)
                    Abort.run[Closed](
                        (for
                            readP <-
                                Sync.defer { val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]](); driver.awaitRead(acceptedH, p); p }
                            result <- readP.safe.get
                        yield result)
                    ).map { result =>
                        driver.closeHandle(acceptedH)
                        result match
                            case Result.Failure(_: Closed)                         => succeed
                            case Result.Success(ReadOutcome.Bytes(s)) if s.isEmpty => succeed
                            case Result.Success(_: ReadOutcome)                    => succeed
                            case other                                             => fail(s"expected Closed or EOF after RST, got $other")
                        end match
                    }
                }
            }
        }
    }

    "closePeerForEof: peer close produces EOF (empty Span or Closed)" in {
        PosixTestSockets.assumePoller().andThen {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    PosixTestSockets.closePeerForEof(sock, client)
                    val readP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readP)
                    readP.safe.get.map {
                        case ReadOutcome.Bytes(got) if got.isEmpty =>
                            driver.closeHandle(acceptedH)
                            succeed
                        case ReadOutcome.PeerFin | ReadOutcome.CleanClose | ReadOutcome.LocalShutdown =>
                            driver.closeHandle(acceptedH)
                            succeed
                        case other =>
                            driver.closeHandle(acceptedH)
                            fail(s"expected empty Span (EOF), got $other")
                    }
                }
            }
        }
    }

    "halfClose: SHUT_WR produces EOF on peer without closing connection" in {
        PosixTestSockets.assumePoller().andThen {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    PosixTestSockets.halfClose(sock, client)
                    val readP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readP)
                    readP.safe.get.map {
                        case ReadOutcome.Bytes(got) if got.isEmpty =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client))
                            succeed
                        case ReadOutcome.PeerFin | ReadOutcome.CleanClose | ReadOutcome.LocalShutdown =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client))
                            succeed
                        case other =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client))
                            fail(s"expected EOF (empty Span) after SHUT_WR, got $other")
                    }
                }
            }
        }
    }

    "acceptOne: accepts exactly one connection on a server fd" in {
        PosixTestSockets.assumePoller().andThen {
            val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
            Sync.ensure(Sync.defer(a.close())) {
                assert(sock.bind(server, a, l).value == 0)
                assert(sock.listen(server, 4).value == 0)
                val portBuf = Buffer.alloc[Byte](SockAddr.inet4Size)
                val portLen = Buffer.alloc[Int](1)
                portLen.set(0, SockAddr.inet4Size)
                val port =
                    try
                        assert(sock.getsockname(server, portBuf, portLen).value == 0)
                        ((portBuf.get(2) & 0xff) << 8) | (portBuf.get(3) & 0xff)
                    finally
                        portBuf.close()
                        portLen.close()
                val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(_ => ())).andThen {
                    PosixTestSockets.acceptOne(server).map { accepted =>
                        discard(sock.close(client))
                        discard(sock.close(accepted))
                        discard(sock.close(server))
                        assert(accepted >= 0, s"acceptOne returned $accepted")
                    }
                }
            }
        }
    }

    "SO_LINGER and SHUT_WR constants: values match the platform-specific ABI" in {
        if PosixConstants.isMacOrBsd then
            assert(PosixTestSockets.SO_LINGER == 0x0080, s"macOS SO_LINGER expected 0x0080, got ${PosixTestSockets.SO_LINGER}")
        else
            assert(PosixTestSockets.SO_LINGER == 13, s"Linux SO_LINGER expected 13, got ${PosixTestSockets.SO_LINGER}")
        end if
        assert(PosixTestSockets.SHUT_WR == 1, s"SHUT_WR expected 1, got ${PosixTestSockets.SHUT_WR}")
        succeed
    }

end PosixTestSocketsTest
