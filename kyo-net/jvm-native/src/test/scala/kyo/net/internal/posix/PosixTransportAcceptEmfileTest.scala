package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

// This suite lives in jvm-native/src/test because PosixTransport's accept loop runs on JVM-posix and Native; JS uses the Node transport.

/** Reproduce-first guard for the accept-loop spin on `EMFILE` (out of file descriptors).
  *
  * `accept(2)` documents that on a resource error (`EMFILE` / `ENFILE` / `ENOBUFS` / `ENOMEM`) the kernel does NOT dequeue the pending
  * connection: the connection stays in the backlog and the listening socket stays read-ready. Before the fix, `PosixTransport.acceptAll`'s
  * `drain` loop only treated `EAGAIN`/`EWOULDBLOCK` as "drained"; every other errno fell into an `else ()` arm that stopped the drain WITHOUT
  * consuming the backlog entry, and `scheduleNextAccept` then re-armed read interest on the listen fd. Because the pending connection was still
  * in the backlog, the poller re-fired the listen fd immediately, `acceptNow` returned `EMFILE` again, and the loop re-armed again: a tight CPU
  * spin on the poll-loop carrier that stalled every other connection on the shared driver until a fd freed elsewhere. This is the exact livelock
  * libuv (joyent/libuv #690, #315) and asyncio (Tulip #78) had to special-case. The fix classifies `EMFILE`/`ENFILE` as resource exhaustion and
  * re-arms after a bounded backoff (`PosixTransport.acceptResourceBackoff`) instead of immediately, breaking the spin while keeping the accept
  * loop alive so accepting resumes once a fd frees; `ECONNABORTED`/`EINTR` are retried in place per the man page.
  *
  * The mechanism: a delegating [[SocketBindings]] decorator injects `EMFILE` for `acceptNow` on the listen fd while a bounded budget is unspent,
  * counting every call. One real client connects, so the listen fd is genuinely read-ready with one backlog entry. The driver's poll loop fires
  * the accept, the transport drains via `acceptNow`, and the EMFILE return drives the loop. If the accept loop spins, the injected `acceptNow`
  * count climbs without bound for ONE pending connection; a loop that handles EMFILE as a backoff re-arm (rather than an immediate one) keeps the
  * count small (~1 per backoff interval). The decorator stops injecting once the spin threshold is crossed so a regressed (spinning) build still
  * tears down cleanly (the real accept then succeeds).
  *
  * Completion + anti-flakiness: no `Thread.sleep`, no busy-spin. A bounded settle window (`Async.sleep(settleWindow)`, a fiber suspension that
  * yields the carrier) is the spin ceiling, not a timing assertion: with the spin the carrier issues hundreds of `acceptNow(EMFILE)` calls inside
  * the window (far past `bound`); with the fix the backoff re-arm issues only ~`settleWindow / acceptResourceBackoff` calls (well under `bound`).
  * The window is sized so the two regimes are separated by more than an order of magnitude, and the assertion reads the count once the window
  * elapses. The spy stops injecting `EMFILE` past `spinThreshold` so a regressed (spinning) build still drains the backlog and tears down cleanly.
  */
class PosixTransportAcceptEmfileTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    // EMFILE = 24 on both Linux and macOS/BSD (stable POSIX errno). Not defined in PosixConstants (part of the defect: the accept loop has no
    // branch for it), so it is spelled out here.
    private val EMFILE = 24

    // The accept loop should call acceptNow a small bounded number of times for one pending connection (one readiness -> one or a few drains).
    // A spin drives it far past this.
    private val bound = 8

    // The spin-detection threshold: if acceptNow is invoked this many times for ONE pending connection, the loop is provably spinning. The spy
    // stops injecting EMFILE past it so a regressed (spinning) build's real accept proceeds and the test tears down cleanly instead of looping
    // forever; it is the injection cap, not the test's completion signal (the settle window below is).
    private val spinThreshold = 200

    // Bounded settle window: the spin ceiling, not a timing assertion. With the spin the poll-loop carrier issues hundreds of acceptNow(EMFILE)
    // calls inside this window (far past `bound`); with the fix the backoff re-arm (acceptResourceBackoff = 50ms) issues only ~5-6, well under
    // `bound`. The assertion reads the count once the window elapses, so the two regimes are separated by more than an order of magnitude.
    private val settleWindow = 250.millis

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport accept-loop tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** A delegating [[SocketBindings]] that injects `EMFILE` on `acceptNow` while a bounded budget is unspent, counting every call. Past the spin
      * threshold it stops injecting and delegates to the real `acceptNow` so a regressed (spinning) build still drains the backlog and tears down
      * cleanly. Every other method delegates to the real bindings (the single controlled injection pattern: one syscall's result is overridden,
      * the rest are real).
      */
    final private class EmfileAcceptSockets(real: SocketBindings) extends SocketBindings:
        val acceptNowCalls: AtomicInteger = new AtomicInteger(0)

        def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            val n = acceptNowCalls.incrementAndGet()
            if n >= spinThreshold then
                // Spin cap: a regressed build that spins past the threshold gets the real accept so the backlog drains and teardown is clean.
                real.acceptNow(fd, addr, addrlen)
            else
                // The pending connection stays in the backlog (EMFILE does not dequeue it); the listen fd remains read-ready.
                Ffi.Outcome.fromValueErrno(-1L, EMFILE)
            end if
        end acceptNow

        def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.socket(domain, `type`, protocol)
        def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.bind(fd, addr, addrlen)
        def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.listen(fd, backlog)
        def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.setsockopt(fd, level, optname, optval, optlen)
        def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using
            AllowUnsafe
        ): Ffi.Outcome[Int] =
            real.getsockopt(fd, level, optname, optval, optlen)
        def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.getsockname(fd, addr, addrlen)
        def getpeername(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.getpeername(fd, addr, addrlen)
        def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.fstat(fd, buf)
        def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int =
            real.shutdown(fd, how)
        def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            real.connect(fd, addr, addrlen)
        def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            real.accept(fd, addr, addrlen)
        def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.recv(fd, buf, len, flags)
        def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.send(fd, buf, len, flags)
        def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            real.sendNow(fd, buf, len, flags)
        def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            real.recvNow(fd, buf, len, flags)
        def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.read(fd, buf, count)
        def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
            real.close(fd)
    end EmfileAcceptSockets

    "PosixTransport accept loop" - {

        "does not spin on acceptNow EMFILE while a connection is pending (bounded retry)" in {
            assumePollerReady()
            val spy       = new EmfileAcceptSockets(Ffi.load[SocketBindings])
            val driver    = PollerIoDriver.init(transportConfig)
            val transport = TestTransports.forTesting(transportConfig, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                for
                    listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    port = listener.port
                    // One real client connect: the listen fd gets exactly one backlog entry, so it is genuinely read-ready and the poll loop
                    // drives the transport's acceptAll -> acceptNow path against the injected EMFILE.
                    clientFd <-
                        val fd       = spy.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
                        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
                        spy.connect(fd, ca, cl).safe.get.map { r =>
                            ca.close()
                            assert(r.value == 0, s"client connect failed errno=${r.errorCode}")
                            fd
                        }
                    // Let the accept loop run against the injected EMFILE for the settle window: the spin ceiling, not a timing assertion (a
                    // spinning loop floods acceptNow far past `bound` inside it; the fixed loop's backoff re-arm issues only a handful). This is
                    // an Async suspension (the fiber yields its carrier), so no thread blocks while the poll loop drives the accept path.
                    _ <- Async.sleep(settleWindow)
                yield
                    val count = spy.acceptNowCalls.get()
                    transport.close()
                    discard(spy.close(clientFd))
                    assert(
                        count <= bound,
                        s"accept loop spun: $count acceptNow(EMFILE) calls for ONE pending connection in the settle window (bound $bound). " +
                            "EMFILE leaves the connection in the backlog so the listen fd stays read-ready; an immediate re-arm re-fires it at " +
                            "once and the loop livelocks. The fix re-arms after a bounded backoff, so the count stays small."
                    )
                end for
            }
        }
    }

end PosixTransportAcceptEmfileTest
