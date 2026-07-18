package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Loopback round-trip tests for [[SocketBindings]] over real libc sockets on the host (POSIX: Linux + macOS/BSD).
  *
  * Exercises the net-new socket syscall surface end to end: socket/bind/listen/getsockname resolve an ephemeral port; connect/accept/send/recv
  * carry bytes across a loopback pair; recv after peer-close returns 0 (EOF). Uses blocking sockets so the test stays a straight-line
  * round-trip without an event loop (the production driver layers non-blocking + the poller on top in later phases).
  *
  * The `@Ffi.blocking` syscalls (`connect`, `accept`, `recv`, `send`, `close`) return a `Fiber.Unsafe[<value>, Any]` that the test AWAITS with
  * `.safe.get`, producing a `… < Async` computation. The test never assumes the fiber is already completed: identical code is correct on JS
  * (the fiber is genuinely pending and `.safe.get` suspends) and on JVM/Native (the fiber is already completed and `.safe.get` returns
  * instantly). The non-blocking calls (`socket`, `bind`, `listen`, `setsockopt`, `getsockname`) return plain values. The helpers that issue a
  * blocking call (`connectTo`, `acceptOne`) are effectful; the test bodies run through the suite's async `run` via a `for`-comprehension.
  *
  * Runs identically on every backend: the libc socket bindings are reachable from the JVM via Panama, from Native via `@extern`, and from
  * Node via koffi. The only guard is on the real capability (a POSIX OS), so the round-trip skips only where libc sockets do not exist (e.g.
  * Windows), never on a platform merely for being Scala.js.
  */
class SocketBindingsTest extends Test:

    import AllowUnsafe.embrace.danger

    // The libc socket bindings load identically on JVM (Panama), Native (@extern), and JS (koffi), so these syscall-level round-trips run on
    // every backend. The genuine requirement is a POSIX OS that exposes libc sockets (Linux + macOS/BSD); guard on that capability, not on the
    // backend, so the test never skips on JS merely for being JS.
    private def assumePosixSockets(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("SocketBindings round-trips need libc sockets (POSIX: Linux or macOS/BSD)")

    private def b = Ffi.load[SocketBindings]

    // POSIX errno is only meaningful after a FAILED call (negative return); on success it is left untouched (often stale).
    // So success is asserted on the return VALUE, and errno is surfaced only in the failure message.
    // `socket` is non-blocking, so this stays a plain synchronous value.
    private def newSocket()(using kyo.test.AssertScope, Frame): Int =
        val r = b.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0)
        assert(r.value >= 0, s"socket failed fd=${r.value} errno=${r.errorCode}")
        r.value
    end newSocket

    /** Bind to 127.0.0.1:0, listen, and resolve the ephemeral port via getsockname. Returns (serverFd, port). Every call here is non-blocking,
      * so this stays a plain synchronous value.
      */
    private def listenEphemeral()(using kyo.test.AssertScope, Frame): (Int, Int) =
        val fd = newSocket()
        // SO_REUSEADDR so repeated test runs do not trip TIME_WAIT.
        Buffer.use[Byte, Unit](4) { opt =>
            opt.set(0, 1.toByte); opt.set(1, 0); opt.set(2, 0); opt.set(3, 0)
            b.setsockopt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_REUSEADDR, opt, 4): Unit
        }
        val (addr, len) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        try
            val bindR = b.bind(fd, addr, len)
            assert(bindR.value == 0, s"bind failed errno=${bindR.errorCode}")
            val listenR = b.listen(fd, 16)
            assert(listenR.value == 0, s"listen failed errno=${listenR.errorCode}")
            // Resolve the ephemeral port: getsockname fills a sockaddr_in; addrlen is an in/out socklen_t.
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            try
                val gsn = b.getsockname(fd, out, ol)
                assert(gsn.value == 0, s"getsockname failed errno=${gsn.errorCode}")
                val port = ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                (fd, port)
            finally
                out.close()
                ol.close()
            end try
        finally addr.close()
        end try
    end listenEphemeral

    /** Connect a fresh socket to `port`. `connect` is `@Ffi.blocking`, so its `Fiber.Unsafe` is awaited with `.safe.get`. */
    private def connectTo(port: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val fd          = newSocket()
        val (addr, len) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(addr.close())) {
            b.connect(fd, addr, len).safe.get.map { r =>
                assert(r.value == 0, s"connect failed errno=${r.errorCode}")
                fd
            }
        }
    end connectTo

    /** Accept one connection on `serverFd`. `accept` is `@Ffi.blocking`, so its `Fiber.Unsafe` is awaited with `.safe.get`. */
    private def acceptOne(serverFd: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
        val noLen  = Buffer.alloc[Int](1)
        noLen.set(0, SockAddr.inet4Size)
        Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
            b.accept(serverFd, noAddr, noLen).safe.get.map { r =>
                assert(r.value >= 0, s"accept failed fd=${r.value} errno=${r.errorCode}")
                r.value
            }
        }
    end acceptOne

    "SocketBindings" - {
        "socket + bind + listen + getsockname resolves an ephemeral port" in {
            assumePosixSockets()
            val (fd, port) = listenEphemeral()
            b.close(fd).safe.get.map { closed =>
                assert(port > 0)
                assert(closed == 0)
            }
        }

        "connect + send + recv echoes 5 bytes over a loopback pair" in {
            assumePosixSockets()
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                sbuf = Buffer.fromArray[Byte](Array[Byte](1, 2, 3, 4, 5))
                sent <- Sync.ensure(Sync.defer(sbuf.close()))(b.send(clientFd, sbuf, 5L, PosixConstants.MSG_NOSIGNAL).safe.get)
                rbuf = Buffer.alloc[Byte](5)
                got <- Sync.ensure(Sync.defer(rbuf.close())) {
                    b.recv(acceptedFd, rbuf, 5L, 0).safe.get.map { got =>
                        val out = Buffer.copyToArray[Byte](rbuf, 0, 5)
                        (got, out.toList)
                    }
                }
                _ <- b.close(clientFd).safe.get
                _ <- b.close(acceptedFd).safe.get
                _ <- b.close(serverFd).safe.get
            yield
                assert(sent.value == 5L, s"send returned ${sent.value} errno=${sent.errorCode}")
                assert(got._1.value == 5L, s"recv returned ${got._1.value} errno=${got._1.errorCode}")
                assert(got._2 == List[Byte](1, 2, 3, 4, 5))
            end for
        }

        "recv after peer-close returns 0 (EOF)" in {
            assumePosixSockets()
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                // Peer (client) closes; the accepted side should observe orderly EOF.
                clientClosed <- b.close(clientFd).safe.get
                rbuf = Buffer.alloc[Byte](8)
                got <- Sync.ensure(Sync.defer(rbuf.close()))(b.recv(acceptedFd, rbuf, 8L, 0).safe.get)
                _   <- b.close(acceptedFd).safe.get
                _   <- b.close(serverFd).safe.get
            yield
                assert(clientClosed == 0)
                assert(got.value == 0L, s"recv after peer-close returned ${got.value} errno=${got.errorCode}")
            end for
        }

        // fcntl O_NONBLOCK regression guard (arm64 ABI): kyo_posix_set_nonblocking uses a C shim that calls the variadic fcntl correctly.
        // A direct non-variadic binding of fcntl would silently drop O_NONBLOCK on arm64 because AAPCS64 routes variadic arguments
        // through different registers than fixed arguments. The shim avoids that: the C compiler sees the variadic prototype and emits the
        // correct call-site code on every architecture.
        "kyo_posix_set_nonblocking sets O_NONBLOCK; kyo_posix_get_flags reads it back" in {
            assumePosixSockets()
            val shim = Ffi.load[PosixShimBindings]
            val fd   = newSocket()
            val rc   = shim.kyo_posix_set_nonblocking(fd)
            assert(rc == 0, s"kyo_posix_set_nonblocking failed rc=$rc")
            val flags = shim.kyo_posix_get_flags(fd)
            b.close(fd).safe.get.map { _ =>
                assert(flags >= 0, s"kyo_posix_get_flags failed flags=$flags")
                assert(
                    (flags & PosixConstants.O_NONBLOCK) != 0,
                    s"O_NONBLOCK not set after kyo_posix_set_nonblocking: flags=0x${flags.toHexString} O_NONBLOCK=0x${PosixConstants.O_NONBLOCK.toHexString}"
                )
            }
        }

        // recvNow tests: verify the synchronous non-blocking recv downcall behavior on non-blocking fds.

        "recvNow returns byte count on a ready non-blocking socket" in {
            assumePosixSockets()
            val shim             = Ffi.load[PosixShimBindings]
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                sbuf = Buffer.fromArray[Byte](Array[Byte](10, 20, 30, 40, 50))
                sent <- Sync.ensure(Sync.defer(sbuf.close()))(b.send(clientFd, sbuf, 5L, PosixConstants.MSG_NOSIGNAL).safe.get)
                // acceptedFd is still BLOCKING (accept does not inherit O_NONBLOCK). A blocking recv with MSG_PEEK waits for the bytes to
                // actually land without consuming them, so the readiness wait is deterministic on the calling fiber (the @Ffi.blocking ->
                // < Async binding) instead of a busy-spin on recvNow/EAGAIN. The bytes stay queued for the recvNow below to observe.
                peekBuf = Buffer.alloc[Byte](5)
                _ <- Sync.ensure(Sync.defer(peekBuf.close()))(b.recv(acceptedFd, peekBuf, 5L, PosixConstants.MSG_PEEK).safe.get)
                // The bytes have landed; switch acceptedFd to non-blocking so recvNow exercises the synchronous non-blocking downcall.
                _    = assert(shim.kyo_posix_set_nonblocking(clientFd) == 0, "set_nonblocking clientFd failed")
                _    = assert(shim.kyo_posix_set_nonblocking(acceptedFd) == 0, "set_nonblocking acceptedFd failed")
                rbuf = Buffer.alloc[Byte](8)
                got <- Sync.ensure(Sync.defer(rbuf.close())) {
                    Sync.defer {
                        val result = b.recvNow(acceptedFd, rbuf, 8L, 0)
                        (result, Buffer.copyToArray[Byte](rbuf, 0, 5).toList)
                    }
                }
                _ <- b.close(clientFd).safe.get
                _ <- b.close(acceptedFd).safe.get
                _ <- b.close(serverFd).safe.get
            yield
                assert(sent.value == 5L, s"send returned ${sent.value} errno=${sent.errorCode}")
                assert(got._1.value == 5L, s"recvNow returned ${got._1.value} errno=${got._1.errorCode}")
                assert(got._2 == List[Byte](10, 20, 30, 40, 50))
            end for
        }

        "recvNow returns -1 with EAGAIN on an empty non-blocking socket" in {
            assumePosixSockets()
            val shim             = Ffi.load[PosixShimBindings]
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                // Set acceptedFd non-blocking before calling recvNow with no data pending.
                _    = assert(shim.kyo_posix_set_nonblocking(acceptedFd) == 0, "set_nonblocking failed")
                rbuf = Buffer.alloc[Byte](8)
                got <- Sync.ensure(Sync.defer(rbuf.close()))(Sync.defer(b.recvNow(acceptedFd, rbuf, 8L, 0)))
                _   <- b.close(clientFd).safe.get
                _   <- b.close(acceptedFd).safe.get
                _   <- b.close(serverFd).safe.get
            yield
                assert(got.value == -1L, s"recvNow on empty socket returned ${got.value} (expected -1/EAGAIN)")
                assert(got.errorCode == PosixConstants.EAGAIN, s"errorCode=${got.errorCode} expected EAGAIN=${PosixConstants.EAGAIN}")
            end for
        }

        "recvNow returns 0 on orderly peer close (EOF)" in {
            assumePosixSockets()
            val shim             = Ffi.load[PosixShimBindings]
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                // Close the client end to signal EOF to the accepted side.
                _ <- b.close(clientFd).safe.get
                // acceptedFd is still BLOCKING. A blocking recv with MSG_PEEK waits for the peer's FIN to actually propagate and returns 0
                // on the orderly EOF without consuming it (EOF is sticky), so the readiness wait is deterministic on the calling fiber (the
                // @Ffi.blocking -> < Async binding) instead of a busy-spin on recvNow/EAGAIN. The EOF state stays for the recvNow below.
                peekBuf = Buffer.alloc[Byte](8)
                _ <- Sync.ensure(Sync.defer(peekBuf.close()))(b.recv(acceptedFd, peekBuf, 8L, PosixConstants.MSG_PEEK).safe.get)
                // The FIN has propagated; switch acceptedFd to non-blocking so recvNow exercises the synchronous non-blocking downcall.
                _    = assert(shim.kyo_posix_set_nonblocking(acceptedFd) == 0, "set_nonblocking failed")
                rbuf = Buffer.alloc[Byte](8)
                got <- Sync.ensure(Sync.defer(rbuf.close()))(Sync.defer(b.recvNow(acceptedFd, rbuf, 8L, 0)))
                _   <- b.close(acceptedFd).safe.get
                _   <- b.close(serverFd).safe.get
            yield
                // EOF: recvNow returns 0 (not -1/EAGAIN), distinct from the no-data case.
                assert(got.value == 0L, s"recvNow after peer-close returned ${got.value} (expected 0/EOF)")
            end for
        }

        // acceptNow tests: verify the synchronous non-blocking accept downcall behavior on non-blocking listen fds.

        // acceptNow returning an accepted fd for a pending connection is covered deterministically by PollerIoDriverTest (its accept-readiness
        // path) and by every integration listen test (PosixTransportTest, PosixTransportTlsTest), which accept real connections via acceptNow
        // through the poller; there is no non-spin raw primitive for that positive case (blocking accept would consume the connection and
        // there is no MSG_PEEK for accept), so the only acceptNow leaf kept here is the deterministic idle-EAGAIN negative below.
        "acceptNow returns -1 with EAGAIN on an idle non-blocking listen socket" in {
            assumePosixSockets()
            val shim          = Ffi.load[PosixShimBindings]
            val (serverFd, _) = listenEphemeral()
            // Set the listen fd non-blocking with no client connecting.
            val _rc = shim.kyo_posix_set_nonblocking(serverFd)
            assert(_rc == 0, "set_nonblocking serverFd failed")
            val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
            val noLen  = Buffer.alloc[Int](1)
            noLen.set(0, SockAddr.inet4Size)
            Sync.ensure(Sync.defer { noAddr.close(); noLen.close(); () }) {
                Sync.defer(b.acceptNow(serverFd, noAddr, noLen)).map { got =>
                    b.close(serverFd).safe.get.map { _ =>
                        assert(got.value == -1, s"acceptNow on idle socket returned ${got.value} (expected -1/EAGAIN)")
                        assert(
                            got.errorCode == PosixConstants.EAGAIN,
                            s"errorCode=${got.errorCode} expected EAGAIN=${PosixConstants.EAGAIN}"
                        )
                    }
                }
            }
        }

        // MSG_DONTWAIT handshake-probe regression: recvNow with MSG_DONTWAIT must return -1/EAGAIN immediately on a
        // BLOCKING fd with no data pending. A probe using flags=0 relies on O_NONBLOCK being set; a freshly
        // accepted fd is blocking by default on Linux (accept does not inherit O_NONBLOCK), so flags=0 would block the carrier for the
        // full ~15s TLS handshake timeout whenever the peer's next flight was not yet buffered. MSG_DONTWAIT keeps the
        // probe unconditionally non-blocking regardless of the fd's O_NONBLOCK state.
        //
        // This test leaves the accepted fd BLOCKING (no kyo_posix_set_nonblocking call) and calls recvNow with MSG_DONTWAIT and no data
        // pending, then asserts it returns -1 with EAGAIN/EWOULDBLOCK immediately. A regression to flags=0 would block here and the
        // test would hang until the 15s timeout fires.
        "recvNow with MSG_DONTWAIT does not block on a blocking fd with no data (handshake-probe regression)" in {
            assumePosixSockets()
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                // acceptedFd is LEFT BLOCKING: no kyo_posix_set_nonblocking call. This mimics a freshly accepted fd on Linux where
                // accept does not inherit O_NONBLOCK. MSG_DONTWAIT must make recvNow return immediately even without O_NONBLOCK.
                rbuf = Buffer.alloc[Byte](8)
                got <- Sync.ensure(Sync.defer(rbuf.close()))(Sync.defer(b.recvNow(acceptedFd, rbuf, 8L, PosixConstants.MSG_DONTWAIT)))
                _   <- b.close(clientFd).safe.get
                _   <- b.close(acceptedFd).safe.get
                _   <- b.close(serverFd).safe.get
            yield
                // Must return -1 with EAGAIN/EWOULDBLOCK, not block. A regression to flags=0 would hang here.
                assert(got.value == -1L, s"recvNow(MSG_DONTWAIT) on blocking fd with no data returned ${got.value} (expected -1/EAGAIN)")
                assert(
                    got.errorCode == PosixConstants.EAGAIN,
                    s"errorCode=${got.errorCode} expected EAGAIN=${PosixConstants.EAGAIN} (EWOULDBLOCK=${PosixConstants.EWOULDBLOCK})"
                )
            end for
        }

        // Head-of-line-blocking regression (the proof the @blocking->Async change works): two concurrent fibers each issuing a genuinely
        // blocking syscall must both make progress; one fiber's in-flight blocking recv must not freeze the scheduler/event loop and starve
        // the other. Fiber A blocks in recv on a socket with no data; concurrently fiber B sends the bytes that unblock A. A Channel latch
        // orders it deterministically (no sleeps): A signals it is about to block, B waits for that signal, then sends. If recv were a
        // SYNCHRONOUS call instead of `… < Async`, on the single-threaded JS runtime A's recv would freeze the only thread, B would never get
        // to run, and the latch would deadlock; because recv suspends the fiber (koffi worker on JS), B runs and feeds A. On the JVM the same
        // structure proves the carrier is not parked in a way that starves the other fiber.
        "blocking recv on one fiber does not stall a concurrent fiber (no head-of-line blocking)" in {
            assumePosixSockets()
            val (serverFd, port) = listenEphemeral()
            for
                clientFd   <- connectTo(port)
                acceptedFd <- acceptOne(serverFd)
                // Latch carrying the order: A puts before blocking in recv; B takes, proving it ran while A was parked, then sends.
                ready <- Channel.init[Unit](1)
                rbuf = Buffer.alloc[Byte](5)
                // Fiber A: announce intent to block, then block in recv until B feeds the bytes.
                fiberA <- Fiber.initUnscoped(
                    ready.put(()).andThen {
                        Sync.ensure(Sync.defer(rbuf.close())) {
                            b.recv(acceptedFd, rbuf, 5L, 0).safe.get.map { got =>
                                (got, Buffer.copyToArray[Byte](rbuf, 0, 5).toList)
                            }
                        }
                    }
                )
                // Fiber B: only proceeds once A has reached its recv, then sends the unblocking bytes.
                fiberB <- Fiber.initUnscoped(
                    ready.take.andThen {
                        val sbuf = Buffer.fromArray[Byte](Array[Byte](9, 8, 7, 6, 5))
                        Sync.ensure(Sync.defer(sbuf.close()))(b.send(clientFd, sbuf, 5L, PosixConstants.MSG_NOSIGNAL).safe.get)
                    }
                )
                sent     <- fiberB.get
                received <- fiberA.get
                _        <- b.close(clientFd).safe.get
                _        <- b.close(acceptedFd).safe.get
                _        <- b.close(serverFd).safe.get
            yield
                // B made progress (sent its bytes) while A was parked in recv, and A then received exactly B's bytes.
                assert(sent.value == 5L, s"send returned ${sent.value} errno=${sent.errorCode}")
                assert(received._1.value == 5L, s"recv returned ${received._1.value} errno=${received._1.errorCode}")
                assert(received._2 == List[Byte](9, 8, 7, 6, 5))
            end for
        }
    }
end SocketBindingsTest
