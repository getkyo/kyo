package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduction + regression guard for read/write interest starvation on the same fd in [[PollerIoDriver]].
  *
  * On a socket the read end and write end share one fd (`readFd == writeFd`), so a connection can legitimately have a READ parked (awaiting the
  * peer's next bytes) AND a WRITABLE parked (awaiting send-buffer space, or a connect completion) at the same instant. A TLS handshake does
  * exactly this. The two interests must coexist: arming one direction must not drop the other.
  *
  * kqueue registers read and write as INDEPENDENT one-shot filters, so both coexist and both fire; this leaf passes there today. epoll carries
  * ONE interest mask per fd, and `EPOLL_CTL_MOD` REPLACES it: arming the writable after a read overwrote the read interest with write interest,
  * and `EPOLLONESHOT` then disabled the whole fd once the writable fired, so the parked read's readiness could never be delivered. The peer's
  * bytes arrived but the read promise hung (a `kyo.Timeout`), which is the kyo-http TLS deadlock on the JDK-`SSLEngine` floor on Linux. The
  * epoll arm now tracks per-fd interest and arms the UNION, and re-arms the non-fired survivor after `EPOLLONESHOT`, so the parked read survives.
  *
  * The leaf drives the REAL [[PollerIoDriver]] over a real loopback socket pair (epoll on Linux, kqueue on macOS/BSD): park a read with no data
  * (so it stays pending), park a writable on the same fd and await it (the freshly-connected socket is writable, so it completes; on the unfixed
  * epoll this is the arm that clobbered the read), THEN have the peer send bytes and assert the parked read delivers exactly those bytes. The
  * read await is bounded so the unfixed epoll path fails fast with a timeout instead of hanging to the suite limit.
  */
class PollerIoDriverConcurrentInterestTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a connected loopback socket pair and return (clientFd, acceptedFd), both non-blocking once connected (the driver's contract). The
      * `connect` / `accept` / `close` are `@Ffi.blocking`; non-blocking is set only after the connection is established.
      */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    /** Send `bytes` on `fd` via a single blocking `send`, asserting the whole payload went out (the test payloads are small, so one send is
      * enough on a freshly-connected loopback).
      */
    private def sendAll(fd: Int, bytes: Array[Byte])(using Frame, kyo.test.AssertScope): Unit < Async =
        val buf = Buffer.fromArray[Byte](bytes)
        Sync.ensure(Sync.defer(buf.close())) {
            sock.send(fd, buf, bytes.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get.map { r =>
                assert(r.value == bytes.length, s"short send: ${r.value} of ${bytes.length}")
                ()
            }
        }
    end sendAll

    "PollerIoDriver concurrent read/write interest on the same fd" - {
        "a read parked next to a writable on the same fd is not starved: both complete" in {
            assumePoller()
            val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](8)(i => (i + 1).toByte)

                    // Park a READ on the accepted fd: no data has arrived, so it stays pending. On epoll this arms EPOLLIN.
                    val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readPromise)

                    // Park a WRITABLE on the SAME fd. The freshly-connected socket is writable, so this completes. On the unfixed epoll this is
                    // the arm that REPLACED the read's EPOLLIN with EPOLLOUT (last-write-wins MOD); once it fired, EPOLLONESHOT disabled the
                    // whole fd and the parked read's interest was gone.
                    val writePromise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(acceptedH, writePromise)

                    for
                        // The writable must complete (the socket is writable). This is the point past which the unfixed epoll has lost the read.
                        writeOutcome <- Abort.run[Timeout | Closed](Async.timeout(5.seconds)(writePromise.safe.get))
                        // Only now does the peer send: with the read interest clobbered + ONESHOT-disabled, the unfixed epoll never reports this
                        // readiness. The fixed epoll re-armed the read survivor, so it fires and delivers the bytes.
                        _           <- sendAll(client, payload)
                        readOutcome <- Abort.run[Timeout | Closed](Async.timeout(5.seconds)(readPromise.safe.get))
                        _ = driver.closeHandle(acceptedH)
                        _ <- sock.close(client).safe.get
                    yield
                        writeOutcome match
                            case Result.Success(())         => succeed
                            case Result.Failure(_: Timeout) => fail("the writable on the shared fd never fired")
                            case other                      => fail(s"unexpected writable outcome: $other")
                        end match
                        readOutcome match
                            case Result.Success(ReadOutcome.Bytes(got)) =>
                                assert(
                                    got.toArray.toList == payload.toList,
                                    s"read delivered ${got.toArray.toList}, expected ${payload.toList}"
                                )
                            case Result.Success(other) =>
                                fail(s"expected ReadOutcome.Bytes, got $other")
                            case Result.Failure(_: Timeout) =>
                                fail(
                                    "interest starvation: the parked read was clobbered by the writable arm and its readiness was never delivered"
                                )
                            case other => fail(s"unexpected read outcome: $other")
                        end match
                    end for
                }
            }
        }
    }

end PollerIoDriverConcurrentInterestTest
