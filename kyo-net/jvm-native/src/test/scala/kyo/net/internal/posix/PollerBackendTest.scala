package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Tests the epoll/kqueue uniformity seam [[PollerBackend]] on the host that has the syscalls.
  *
  * `PollerBackend.default()` picks epoll on Linux and kqueue on macOS/BSD; whichever the host provides is exercised end to end against a real
  * loopback socket pair (create the poller, register read interest, write a byte to the peer, confirm the bounded `poll` reports the fd
  * read-ready, then deregister and close). The epoll arm runs only on Linux and the kqueue arm only on macOS/BSD; the other is skipped because
  * its syscalls are absent. `poll` is effectful (it awaits the `@Ffi.blocking` wait fiber), so the test body runs through the suite's async
  * `run`.
  */
class PollerBackendTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerBackend needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a connected loopback socket pair and return (clientFd, acceptedFd). `connect`/`accept`/`close` are `@Ffi.blocking`. */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
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
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value.toInt)
                }.map { accepted =>
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    "PollerBackend" - {
        "default() selects the OS-appropriate backend" in {
            assumePoller()
            val backend = PollerBackend.default()
            if PosixConstants.isLinux then assert(backend eq EpollPollerBackend)
            else assert(backend eq KqueuePollerBackend)
            succeed
        }

        "create() returns a valid poller fd" in {
            assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            assert(pollerFd >= 0, s"poller fd=$pollerFd")
            backend.close(pollerFd)
            succeed
        }

        "registerRead returns synchronously; poll returns a Fiber.Unsafe consumed via done()/poll()" in {
            assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val scratch  = backend.newPollScratch()
            assert(pollerFd >= 0)
            loopbackPair().map { case (client, accepted) =>
                // Test 1: registerRead is synchronous and returns Int >= 0 directly (the backend register carries no pending effect).
                val rc = backend.registerRead(pollerFd, accepted, accepted.toLong, scratch)
                assert(rc >= 0, s"registerRead rc=$rc")
                // Client writes a byte; the accepted fd becomes read-ready.
                val wb = Buffer.fromArray[Byte](Array[Byte](7))
                Sync.ensure(Sync.defer(wb.close())) {
                    sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map(r => assert(r.value == 1L))
                }.andThen {
                    // Test 2: poll returns a Fiber.Unsafe; on JVM done() is true inline and poll() yields the ready count and fills scratch.
                    // Pass an empty changelist (no pending changes from tests; the changelist path is tested by PollerIoDriverEdgeTriggeredTest).
                    val (clBuf, clN) = scratch.kqueueData match
                        case Present(kq) => (kq.changelistBuf, kq.nChanges)
                        case Absent      => (scratch.armBuf, 0)
                    val pollFiber = backend.poll(pollerFd, 1000, clBuf, clN, scratch)
                    pollFiber.safe.get.map { n =>
                        backend.deregister(pollerFd, accepted, fdClosing = false, scratch)
                        scratch.close()
                        sock.close(client).safe.get.andThen(sock.close(accepted).safe.get).andThen {
                            backend.close(pollerFd)
                            assert(n == 1, s"poll returned $n events (expected 1)")
                            assert(scratch.fds(0) == accepted, s"event fd=${scratch.fds(0)} expected $accepted")
                            assert((scratch.flags(0) & PollFlags.Read) != 0, "event should be read-ready")
                        }
                    }
                }
            }
        }

        "poll on an idle poller returns no events within the bounded timeout" in {
            assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val scratch  = backend.newPollScratch()
            assert(pollerFd >= 0)
            // Test 3: poll returns 0 events after bounded timeout with no ready fd (a bounded park, never indefinite).
            val (clBuf2, clN2) = scratch.kqueueData match
                case Present(kq) => (kq.changelistBuf, kq.nChanges)
                case Absent      => (scratch.armBuf, 0)
            backend.poll(pollerFd, 50, clBuf2, clN2, scratch).safe.get.map { n =>
                scratch.close()
                backend.close(pollerFd)
                assert(n == 0, s"expected 0 events, got $n")
            }
        }
    }
end PollerBackendTest
