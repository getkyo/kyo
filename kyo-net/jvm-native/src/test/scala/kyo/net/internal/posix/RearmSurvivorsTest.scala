package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic, cross-platform guard for the EPOLLONESHOT survivor re-arm (`PollerIoDriver.drainReady` -> `rearmSurvivors`, backed by the
  * epoll `desired` union-interest map): when one direction fires on a fd that had both read and write interest armed, the fired direction's
  * interest is consumed and the SURVIVING direction must be re-armed, so a read parked next to a writable (or the reverse) is not starved after
  * `EPOLLONESHOT` disables the whole fd.
  *
  * The existing survivor coverage ([[PollerIoDriverConcurrentInterestTest]], [[PollerIoDriverStandingReadTest]]) runs only on a real
  * epoll/kqueue host and `assumePoller`s elsewhere. On kqueue (macOS) read and write are independent filters, so those leaves pass TRIVIALLY:
  * the union-interest + `rearm` math is exercised only on Linux. Two leaves close that gap:
  *
  *   - a real-backend driver-level leaf (poller-gated) that registers read then write on real fds and asserts the driver re-arms the surviving
  *     direction when a single direction fires via EOF from the real peer close; and
  *   - a Linux-gated real-`EpollPollerBackend` leaf that registers read then write on a live epoll fd and confirms the armed interest is the
  *     UNION and that `rearm` after a read fire re-arms the write survivor.
  *
  * Gate: `PosixTestSockets.assumePoller()` for the driver-level leaf. `PosixConstants.isLinux` for the epoll leaf.
  *
  * Anti-flakiness: `backend.rearmed.safe.get` latches on the real rearm call (a real Promise.Unsafe completed by the driver worker). No sleep.
  *
  * Uses `RecordingSocketBindings(Ffi.load[SocketBindings])` over a real `loopbackPair`; a real peer close via `closePeerForEof` makes
  * `recvNow` on the accepted fd return 0 (EOF) genuinely. The assertion is that `rearmed.safe.get` completes with
  * `(firedRead=true, firedWrite=false)` and the write survivor remains armed.
  */
class RearmSurvivorsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]
    private def ep   = Ffi.load[EpollBindings]

    "rearmSurvivors (poller-gated, real sockets)" - {
        "a fired read re-arms the surviving write direction and not the fired read" in {
            PosixTestSockets.assumePoller()
            // A RecordingPollerBackend over the real epoll/kqueue records every interest change and the rearm flags while the
            // real backend delivers genuine readiness. The accepted fd is set up so ONLY its read direction fires: its send buffer is filled
            // (peer never reads) so the kernel reports it NOT write-ready, and the peer half-closes its write side so the accepted fd is
            // read-ready (EOF). With both read and write interest armed (the union), the single real read-only event makes drainReady call
            // rearmSurvivors(firedRead=true, firedWrite=false): the consumed read is cleared and the write survivor is re-armed.
            //
            // Anti-flakiness: backend.rearmed(acceptedFd).safe.get latches on the real rearm call (a real Promise.Unsafe completed by the
            // change worker). No sleep.
            smallBuffers().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
                discard(driver.start())

                // Fill the accepted fd's send buffer so the kernel reports it NOT write-ready (the client never reads). Then half-close the
                // client's write side so the accepted fd is read-ready (EOF) without becoming write-ready: ONLY the read direction fires.
                fillSendBuffer(acceptedFd)
                PosixTestSockets.halfClose(spy, clientFd)

                // Arm BOTH directions on the same fd: a parked read and a parked writable. The union is now {read, write}.
                val readPromise = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                driver.awaitRead(handle, readPromise)
                val writePromise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitWritable(handle, writePromise)

                // The real poll returns the read-only event; drainReady calls rearmSurvivors(firedRead=true, firedWrite=false) BEFORE
                // dispatching the read. Synchronize on the recorded rearm (no sleep), then assert the survivor math.
                backend.rearmed(acceptedFd).safe.get.map { case (firedRead, firedWrite) =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(firedRead, "the rearm must record the read direction as the one that fired")
                    assert(!firedWrite, "the write direction did not fire (send buffer full), so it must not be recorded as fired")
                    // The rearm was submitted after both registrations and before any dispatch re-arm.
                    val log     = backend.callLog
                    val rearmIx = log.indexWhere(_.startsWith("rearm("))
                    assert(rearmIx >= 0, s"a rearm must have been recorded: $log")
                    assert(
                        log.take(rearmIx).forall(c => c.startsWith("registerRead") || c.startsWith("registerWrite")),
                        s"the survivor rearm must run after the two registrations and before any dispatch re-arm: $log"
                    )
                }
            }
        }
    }

    /** Build a connected loopback pair with shrunk send/receive buffers on BOTH ends so the accepted fd's send buffer fills quickly when the
      * client does not read; returns (clientFd, acceptedFd).
      */
    private def smallBuffers()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
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
            val client = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            // Small RCVBUF on the client so the accepted fd's send buffer fills with a modest payload (the client never reads).
            PosixTestSockets.setIntSockOpt(client, PosixConstants.SO_RCVBUF, 2048)
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
                    PosixTestSockets.setIntSockOpt(accepted, PosixConstants.SO_SNDBUF, 2048)
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end smallBuffers

    /** Flood `fd`'s send buffer so the kernel reports it not-writable. The peer never reads, so the buffer stays full. */
    private def fillSendBuffer(fd: Int)(using AllowUnsafe): Unit =
        val chunk = Buffer.alloc[Byte](65536)
        try
            var more  = true
            var guard = 0
            while more && guard < 4096 do
                val r = sock.sendNow(fd, chunk, 65536L, PosixConstants.MSG_DONTWAIT | PosixConstants.MSG_NOSIGNAL)
                if r.value <= 0 then more = false
                guard += 1
            end while
        finally chunk.close()
        end try
    end fillSendBuffer

    "EpollPollerBackend union interest + rearm (Linux)" - {
        "registerRead then registerWrite arms the union; rearm after a read fire keeps the write survivor" in {
            if !PosixConstants.isLinux then cancel("epoll union-interest math is Linux-only (kqueue uses independent filters)")
            val epfd    = ep.epoll_create1(0).value
            val scratch = EpollPollerBackend.newPollScratch()
            assert(epfd >= 0, "epoll_create1 failed")
            loopbackPair().map { case (client, accepted) =>
                Sync.ensure(Sync.defer {
                    EpollPollerBackend.deregister(epfd, accepted, scratch)
                    scratch.close()
                    discard(ep.close(epfd))
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                }) {
                    assert(EpollPollerBackend.registerRead(epfd, accepted, scratch) >= 0, "registerRead failed")
                    assert(EpollPollerBackend.registerWrite(epfd, accepted, scratch) >= 0, "registerWrite failed")
                    for
                        first <- pollOnce(epfd, 1000, scratch)
                        _ = assert(
                            first.exists { case (fd, f) => fd == accepted && (f & PollFlags.Write) != 0 },
                            s"expected a writable event on the union arm, got $first"
                        )
                        _ = EpollPollerBackend.rearm(epfd, accepted, firedRead = false, firedWrite = true, scratch)
                        _ = sendByte(client)
                        second <- pollOnce(epfd, 1000, scratch)
                    yield assert(
                        second.exists { case (fd, f) => fd == accepted && (f & PollFlags.Read) != 0 },
                        s"the read survivor was not re-armed after the write fired (lost wakeup): got $second"
                    )
                    end for
                }
            }
        }
    }

    private def pollOnce(epfd: Int, timeoutMs: Int, scratch: PollScratch)(using Frame): List[(Int, Int)] < (Abort[Closed] & Async) =
        EpollPollerBackend.poll(epfd, timeoutMs, scratch).safe.get.map { n =>
            (0 until n).toList.map(i => (scratch.fds(i), scratch.flags(i)))
        }

    private def sendByte(fd: Int)(using AllowUnsafe): Unit =
        val buf = Buffer.fromArray[Byte](Array[Byte](1))
        try discard(sock.send(fd, buf, 1L, PosixConstants.MSG_NOSIGNAL))
        finally buf.close()
        end try
    end sendByte

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

end RearmSurvivorsTest
