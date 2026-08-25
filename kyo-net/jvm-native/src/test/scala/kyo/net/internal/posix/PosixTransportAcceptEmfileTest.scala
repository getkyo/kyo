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
  * connection: the connection stays in the backlog and the listening socket stays read-ready. A `PosixTransport.acceptAll`
  * `drain` loop that treated only `EAGAIN`/`EWOULDBLOCK` as "drained" would let every other errno fall into an `else ()` arm that stops the drain WITHOUT
  * consuming the backlog entry, and `scheduleNextAccept` would then re-arm read interest on the listen fd. Because the pending connection is still
  * in the backlog, the poller re-fires the listen fd immediately, `acceptNow` returns `EMFILE` again, and the loop re-arms again: a tight CPU
  * spin on the poll-loop carrier that stalls every other connection on the shared driver until a fd frees elsewhere. This is the exact livelock
  * libuv (joyent/libuv #690, #315) and asyncio (Tulip #78) had to special-case. `acceptAll` classifies `EMFILE`/`ENFILE` as resource exhaustion and
  * re-arms after a bounded backoff (`PosixTransport.acceptResourceBackoff`) instead of immediately, breaking the spin while keeping the accept
  * loop alive so accepting resumes once a fd frees; `ECONNABORTED`/`EINTR` are retried in place per the man page.
  *
  * The mechanism: a delegating [[SocketBindings]] decorator injects `EMFILE` for `acceptNow` on the listen fd while a bounded budget is unspent,
  * counting every call. One real client connects, so the listen fd is genuinely read-ready with one backlog entry. The driver's poll loop fires
  * the accept, the transport drains via `acceptNow`, and the EMFILE return drives the loop. If the accept loop spins, the injected `acceptNow`
  * count climbs without bound for ONE pending connection; a loop that handles EMFILE as a backoff re-arm (rather than an immediate one) issues
  * one `acceptNow` per re-arm. The decorator stops injecting once the spin threshold is crossed so a regressed (spinning) build still
  * tears down cleanly (the real accept then succeeds).
  *
  * Completion: no clock is read. The leaf settles on whichever event lands first and the assertion reads which: a resource-backoff re-arm
  * (the `onAcceptResourceBackoff` hook) or the spy's `spinThreshold` spin cap, which only a spinning loop reaches. `Async.timeout` is only the deadlock ceiling.
  */
class PosixTransportAcceptEmfileTest extends Test:

    import AllowUnsafe.embrace.danger

    // EMFILE = 24 on both Linux and macOS/BSD (stable POSIX errno). Not defined in PosixConstants (part of the defect: the accept loop has no
    // branch for it), so it is spelled out here.
    private val EMFILE = 24

    // Two outcomes: a resource-backoff re-arm (the fix) reports BackedOff; a loop that never backs off spins the spy to its cap and reports Spun.
    // Reporting the event, not a re-arm count, keeps the leaf platform-independent: taking the backoff PATH even once is the whole anti-spin property.
    private enum AcceptGuard derives CanEqual:
        case BackedOff, Spun

    // Spin cap: once acceptNow is called this many times for ONE pending connection (a bounded-backoff loop issues only a handful), the spy
    // settles the leaf with Spun and stops injecting EMFILE so a regressed build's real accept drains the backlog and teardown is clean.
    private val spinThreshold = 200

    // Deadlock ceiling, not a pass condition: the assertion reads which event settled the leaf, never elapsed time. This turns an accept loop
    // that neither backs off nor spins (a listener wedged with no re-arm at all) into a failed test instead of a hang.
    private val settleCeiling = 15.seconds

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport accept-loop tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** A delegating [[SocketBindings]] that injects `EMFILE` on `acceptNow` up to the spin threshold, then settles `settled` with `Spun` (the only
      * way a spinning loop ends this test) and delegates to the real `acceptNow` so the backlog drains; every other method delegates to the real bindings.
      */
    final private class EmfileAcceptSockets(real: SocketBindings, settled: Promise.Unsafe[AcceptGuard, Any]) extends SocketBindings:
        val acceptNowCalls: AtomicInteger = new AtomicInteger(0)

        def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            val n = acceptNowCalls.incrementAndGet()
            if n >= spinThreshold then
                // Settle from the spin side: a spinning loop never reaches a backoff re-arm, so this is the only event that ends its leaf. The
                // promise gate makes it idempotent, so whichever of the spin cap and the first backoff lands first owns the outcome.
                discard(settled.complete(Result.succeed(AcceptGuard.Spun)))
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
        def connectNow(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.connectNow(fd, addr, addrlen)
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
            val settled = Promise.Unsafe.init[AcceptGuard, Any]()
            val spy     = new EmfileAcceptSockets(Ffi.load[SocketBindings], settled)
            val driver  = PollerIoDriver.init()
            val transport = TestTransports.forTesting(
                driver,
                spy,
                backendIsEpoll = false,
                // First backoff re-arm means the loop took the anti-spin path: settle BackedOff. The promise gate makes it idempotent, so the
                // first backoff (or the spin cap, whichever the loop reaches) owns the outcome.
                onAcceptResourceBackoff = () => discard(settled.complete(Result.succeed(AcceptGuard.BackedOff)))
            )
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                for
                    listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    // Registered as soon as the listener is up: the connect below (or its inline assert) can fail before the tail block that
                    // used to hold the only listener.close(), which would leak the listener.
                    _ <- Scope.ensure(Sync.defer(listener.close()))
                    port = listener.port
                    // One real client connect: the listen fd gets exactly one backlog entry, so it is genuinely read-ready and the poll loop
                    // drives the transport's acceptAll -> acceptNow path against the injected EMFILE.
                    clientFd <-
                        val fd = spy.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                        // Registered immediately for the same reason: the raw client fd's only close used to sit past the same connect/assert.
                        Scope.ensure(Sync.defer(discard(spy.close(fd)))).andThen {
                            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
                            spy.connect(fd, ca, cl).safe.get.map { r =>
                                ca.close()
                                assert(r.value == 0, s"client connect failed errno=${r.errorCode}")
                                fd
                            }
                        }
                    // Settles on the first backoff re-arm or on the spin cap, whichever the accept loop reaches first.
                    outcome <- Abort.run[Timeout](Async.timeout(settleCeiling)(settled.safe.get))
                yield outcome match
                    case Result.Success(AcceptGuard.BackedOff) => succeed
                    case Result.Success(AcceptGuard.Spun) =>
                        fail(
                            s"accept loop spun: it re-armed immediately under a persistent EMFILE instead of backing off, issuing acceptNow up " +
                                s"to the spin cap ($spinThreshold) for ONE pending connection. EMFILE leaves the connection in the backlog, so an " +
                                "immediate re-arm re-fires the still-ready listen fd at once and the loop livelocks; the fix re-arms only after a " +
                                "bounded backoff."
                        )
                    case Result.Failure(_: Timeout) =>
                        fail(
                            "the accept loop neither backed off nor spun: it never re-armed under EMFILE, so the listener is wedged and the " +
                                "pending connection is never accepted"
                        )
                    case other => fail(s"unexpected accept-loop outcome: $other")
                end for
            }
        }
    }

end PosixTransportAcceptEmfileTest
