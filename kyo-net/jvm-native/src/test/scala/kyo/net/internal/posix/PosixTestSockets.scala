package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.TransportConfig
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome

/** Shared real-socket helpers for posix-level tests on JVM and Native (Linux epoll, macOS/BSD kqueue, io_uring).
  *
  * Every method uses real POSIX syscalls through [[SocketBindings]]. Callers must gate tests with the appropriate [[assumePoller]],
  * [[assumeEpoll]], [[assumeKqueue]], or [[assumeUring]] before calling helpers that require a specific backend.
  *
  * Anti-flakiness: all waits use real-event latches via [[Promise.Unsafe]] and real kernel conditions (shrunk SO_SNDBUF/SO_RCVBUF for
  * guaranteed EAGAIN, SO_LINGER 0 for guaranteed RST). No [[Thread.sleep]] or [[Async.sleep]] in any path here.
  */
object PosixTestSockets:

    // Install SIGPIPE suppression once: on JVM this is a no-op (JVM installs SIG_IGN globally);
    // on Native it calls signal(SIGPIPE, SIG_IGN) so raw socket writes to a peer-closed connection
    // return EPIPE instead of killing the test process. Production send paths already suppress SIGPIPE
    // via SO_NOSIGPIPE / MSG_NOSIGNAL, but raw SocketBindings calls in test helpers do not.
    SigpipeInit.install()

    // Test-only constants absent from PosixConstants (which only has SHUT_RDWR = 2).
    // SO_LINGER: 0x0080 on macOS/BSD (socket.h), 13 on Linux (asm-generic/socket.h).
    val SO_LINGER: Int = if PosixConstants.isMacOrBsd then 0x0080 else 13
    val SHUT_WR: Int   = 1

    private def sock(using AllowUnsafe) = Ffi.load[SocketBindings]

    /** Build a connected TCP loopback pair on 127.0.0.1; returns (clientFd, acceptedFd).
      *
      * Both ends are set non-blocking after connect/accept so drivers (PollerIoDriver, IoUringDriver) can call sendNow/recvNow without
      * blocking the event loop.
      *
      * Anti-flakiness: connect and accept use @Ffi.blocking fibers awaited with .safe.get; no sleep.
      */
    def loopbackPair()(using Frame, AllowUnsafe): (Int, Int) < Async =
        val sockets = sock
        val server  = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(server, a, l).value == 0)
            assert(sockets.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sockets.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sockets.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    /** Overload taking injected bindings; the variant that wraps supplied bindings, used with [[RecordingSocketBindings]].
      *
      * Same as [[loopbackPair()]] but uses the supplied bindings so callers can attach a recording decorator.
      */
    def loopbackPair(sockets: SocketBindings)(using Frame, AllowUnsafe): (Int, Int) < Async =
        val server = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(server, a, l).value == 0)
            assert(sockets.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sockets.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sockets.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    /** Accept exactly one connection on the given already-bound and listening fd. */
    def acceptOne(serverFd: Int)(using Frame, AllowUnsafe): Int < Async =
        val sockets = sock
        val noAddr  = Buffer.alloc[Byte](SockAddr.inet4Size)
        val noLen   = Buffer.alloc[Int](1)
        noLen.set(0, SockAddr.inet4Size)
        Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
            sockets.accept(serverFd, noAddr, noLen).safe.get.map(_.value)
        }
    end acceptOne

    /** Build a loopback pair with shrunk SO_SNDBUF and SO_RCVBUF so a large send genuinely fills and EAGAINs.
      *
      * Lifted verbatim from PollerIoDriverWriteBackpressureTest.scala lines 81-121. The kernel rounds and doubles the requested size; the
      * blob sent in tests is sized far larger than any plausible small buffer so EAGAIN is guaranteed.
      *
      * Anti-flakiness: a real kernel buffer limit produces a real EAGAIN; no scripted behavior.
      */
    def smallBufferedPair(sndBuf: Int, rcvBuf: Int)(using Frame, AllowUnsafe): (Int, Int) < Async =
        val sockets = sock
        val server  = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(server, a, l).value == 0)
            setIntSockOpt(server, PosixConstants.SO_RCVBUF, rcvBuf)
            assert(sockets.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            setIntSockOpt(client, PosixConstants.SO_SNDBUF, sndBuf)
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sockets.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    setIntSockOpt(accepted, PosixConstants.SO_RCVBUF, rcvBuf)
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sockets.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end smallBufferedPair

    /** Set a 4-byte int socket option (SO_SNDBUF / SO_RCVBUF) little-endian. Failures are non-fatal.
      *
      * Lifted verbatim from PollerIoDriverWriteBackpressureTest.scala lines 126-135.
      */
    def setIntSockOpt(fd: Int, optname: Int, value: Int)(using AllowUnsafe): Unit =
        val opt = Buffer.alloc[Byte](4)
        opt.set(0, (value & 0xff).toByte)
        opt.set(1, ((value >> 8) & 0xff).toByte)
        opt.set(2, ((value >> 16) & 0xff).toByte)
        opt.set(3, ((value >> 24) & 0xff).toByte)
        try discard(sock.setsockopt(fd, PosixConstants.SOL_SOCKET, optname, opt, 4))
        finally opt.close()
        end try
    end setIntSockOpt

    /** Drain from `fd` via the driver's readiness until at least `want` bytes have arrived, returning the running total.
      *
      * Accepts an IoDriver[PosixHandle], so it works with both PollerIoDriver and IoUringDriver.
      *
      * Anti-flakiness: each iteration parks an awaitRead (a real Promise.Unsafe latch completing on the real recv) and drains with recvNow
      * until EAGAIN. No sleep. The loop exits on the real condition total >= want.
      */
    def drainPeer(driver: IoDriver[PosixHandle], handle: PosixHandle, fd: Int, want: Int)(using Frame): Int < (Abort[Closed] & Async) =
        import AllowUnsafe.embrace.danger
        val sockets = sock
        def recvLoop(total: Int): Int =
            val buf = Buffer.alloc[Byte](65536)
            try
                var acc  = total
                var more = true
                while more do
                    val r = sockets.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                    val n = r.value.toInt
                    if n > 0 then acc += n
                    else more = false
                end while
                acc
            finally buf.close()
            end try
        end recvLoop

        def loop(total: Int): Int < (Abort[Closed] & Async) =
            if total >= want then total
            else
                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, promise)
                promise.safe.get.map {
                    case ReadOutcome.Bytes(span) =>
                        val afterDelivered = total + span.size
                        val afterDrain     = recvLoop(afterDelivered)
                        loop(afterDrain)
                    case _ => total // EOF: stop draining
                }
            end if
        end loop
        loop(0)
    end drainPeer

    /** Drain at least `want` bytes from `fd` via the driver's readiness, returning the bytes received IN ORDER.
      *
      * The byte-collecting twin of [[drainPeer]]: each iteration parks an `awaitRead` (a real Promise.Unsafe latch completing on the real recv)
      * on a fresh peer handle, then drains with `recvNow` until EAGAIN, appending the bytes in receive order. Used by the coalescing tests to
      * assert the peer received the spans' bytes in exact enqueue order (not just the right total count). No sleep; the loop exits on the real
      * condition total >= want.
      */
    def drainCollect(driver: IoDriver[PosixHandle], fd: Int, want: Int)(using Frame): List[Byte] < (Abort[Closed] & Async) =
        import AllowUnsafe.embrace.danger
        val sockets = sock
        val handle  = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent)
        def recvLoop(acc: List[Byte]): List[Byte] =
            val buf = Buffer.alloc[Byte](65536)
            try
                var out  = acc
                var more = true
                while more do
                    val r = sockets.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                    val n = r.value.toInt
                    if n > 0 then out = out ++ Buffer.copyToArray[Byte](buf, 0, n).toList
                    else more = false
                end while
                out
            finally buf.close()
            end try
        end recvLoop

        def loop(acc: List[Byte]): List[Byte] < (Abort[Closed] & Async) =
            if acc.length >= want then acc
            else
                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, promise)
                promise.safe.get.map {
                    case ReadOutcome.Bytes(span) =>
                        // The delivered span carries the readiness chunk; recvLoop drains any further bytes the same edge made available.
                        val afterDelivered = acc ++ span.toArray.toList
                        loop(recvLoop(afterDelivered))
                    case _ => acc // EOF: stop
                }
            end if
        end loop
        loop(Nil)
    end drainCollect

    /** Force an RST on `fd` by setting SO_LINGER {l_onoff=1, l_linger=0} then closing.
      *
      * The peer's next recv sees ECONNRESET.
      */
    def resetPeer(sockets: SocketBindings, fd: Int)(using AllowUnsafe): Unit =
        val linger = Buffer.alloc[Byte](8)
        linger.set(0, 1.toByte) // l_onoff = 1
        var i = 1
        while i < 8 do
            linger.set(i, 0.toByte) // l_linger = 0
            i += 1
        try discard(sockets.setsockopt(fd, PosixConstants.SOL_SOCKET, SO_LINGER, linger, 8))
        finally linger.close()
        discard(sockets.close(fd))
    end resetPeer

    /** Close `fd` cleanly, causing the peer's next recv to return 0 (EOF / empty Span). */
    def closePeerForEof(sockets: SocketBindings, fd: Int)(using AllowUnsafe): Unit =
        discard(sockets.close(fd))
    end closePeerForEof

    /** Half-close `fd` for writing via shutdown(SHUT_WR), signaling EOF to the peer without closing the connection.
      *
      * Uses SocketBindings.shutdown with SHUT_WR = 1 (absent from PosixConstants which only has SHUT_RDWR = 2).
      */
    def halfClose(sockets: SocketBindings, fd: Int)(using AllowUnsafe): Unit =
        discard(sockets.shutdown(fd, SHUT_WR))
    end halfClose

    /** An in-flight (SYN-SENT) client connect whose write-readiness provably never fires, plus the fd it owns.
      *
      * `targetFd` is a fresh non-blocking client socket whose connect to a non-routable black-hole address is stuck mid-handshake. The OS signals
      * write-readiness on a connecting socket only when the connect succeeds (3-way handshake completes) or fails (RST / error). A connect to a
      * black-hole address (no SYN-ACK, no RST) does neither, so the socket stays in SYN-SENT and the poller never reports it writable. This is the
      * connect-side mirror of a listen fd with no incoming connection: the kernel cannot spontaneously make either fd ready without an external
      * event the setup denies.
      */
    final case class InFlightConnect(targetFd: Int)

    // RFC 5737 TEST-NET-1 (192.0.2.0/24) is reserved for documentation and is guaranteed non-routable on the public internet. A non-blocking
    // connect to it gets no SYN-ACK and no RST, so it stays in SYN-SENT for the whole connect timeout (far longer than any test leaf), giving a
    // genuinely never-writable connecting socket. Chosen over a backlog-saturated loopback listener (whose dropped SYN can still complete on a
    // transient slot, reintroducing intermittency) and over a closed loopback port (which RSTs immediately, firing a write-ready error event).
    private val BlackHoleHost = "192.0.2.1"
    private val BlackHolePort = 9

    /** Build a client TCP connect parked in SYN-SENT against a non-routable black-hole address, so a write-readiness arm on the returned
      * `targetFd` provably never fires for the duration of a test leaf. The connect-side equivalent of [[listenSocket]]'s never-accept-ready
      * listen fd, giving the connect/writable interrupt leaves the same deterministic "the op cannot complete, so interrupt always wins" property
      * the accept leaves have. The caller closes `targetFd` when done.
      *
      * The non-blocking connect downcall runs inline on JVM/Native so `.safe.get` resolves immediately with the connect's `Ffi.Outcome[Int]`, which must
      * be `EINPROGRESS` (in flight). A host with no default route may instead reject the connect immediately with `ENETUNREACH`/`EHOSTUNREACH`; in
      * that case the leaf cannot construct a stably-in-flight connect, so the test cancels rather than running a non-deterministic setup.
      */
    def connectInFlight()(using Frame, AllowUnsafe): InFlightConnect < Async =
        val sockets = sock
        val client  = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val shim    = Ffi.load[PosixShimBindings]
        assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, BlackHoleHost, BlackHolePort).getOrElse(???)
        Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get).map { res =>
            if res.value < 0 && res.errorCode == PosixConstants.EINPROGRESS then InFlightConnect(client)
            else
                discard(sockets.close(client).poll())
                throw new kyo.test.TestCancelled(
                    s"black-hole connect to $BlackHoleHost:$BlackHolePort did not stay in flight (value=${res.value} errno=${res.errorCode}); host has no route to TEST-NET-1"
                )
        }
    end connectInFlight

    /** Close the fd owned by an [[InFlightConnect]]. */
    def closeInFlight(sockets: SocketBindings, c: InFlightConnect)(using AllowUnsafe): Unit =
        closePeerForEof(sockets, c.targetFd)
    end closeInFlight

    /** Cancel the test if neither epoll nor kqueue is available on this host.
      *
      * Returns cleanly where the platform has epoll (Linux) or kqueue (macOS/BSD).
      */
    def assumePoller()(using Frame): Unit < Any =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            throw new kyo.test.TestCancelled(
                "PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)"
            )
        end if
    end assumePoller

    /** Cancel the test if epoll is not available (not Linux). */
    def assumeEpoll()(using Frame): Unit < Any =
        if !PosixConstants.isLinux then
            throw new kyo.test.TestCancelled("epoll is Linux-only")
    end assumeEpoll

    /** Cancel the test if kqueue is not available (not macOS/BSD). */
    def assumeKqueue()(using Frame): Unit < Any =
        if !PosixConstants.isMacOrBsd then
            throw new kyo.test.TestCancelled("kqueue is macOS/BSD-only")
    end assumeKqueue

    /** Cancel the test if io_uring is unavailable at the PRODUCTION ring depth.
      *
      * Probes at max(256, ioPoolSize*64) (the exact formula from IoUringDriver.scala), not depth 2. If io_uring_queue_init fails at that depth
      * the gate cancels, so the gate never reports available when IoUringDriver.init would actually fail. The ring is closed immediately after
      * the probe; no resources are leaked.
      *
      * Closes the probe-vs-driver-depth gap: a depth-2 probe succeeds in restricted environments where the production-depth ring fails. The
      * specific failure mode is a container-level cgroup `io_uring.max` cap that limits the total number of io_uring entries a process may
      * have in flight. A depth-2 ring stays under the cap; a depth-256 ring exceeds it and io_uring_queue_init returns ENOENT. The
      * `--privileged` container flag does not lift this cgroup limit (it only relaxes seccomp); the gate must probe at the same depth the
      * driver will use so that "gate passes" iff "driver init succeeds".
      */
    def assumeUring(config: TransportConfig = TransportConfig.default)(using Frame): Unit < Any =
        if !PosixConstants.isLinux then
            throw new kyo.test.TestCancelled("io_uring is Linux-only")
        else
            import AllowUnsafe.embrace.danger
            val available =
                try
                    val uring = Ffi.load[IoUringBindings]
                    val depth = math.max(256, config.ioPoolSize * 64)
                    val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
                    val rc    = uring.io_uring_queue_init(depth, ring, 0)
                    // io_uring_queue_init returns 0 on success or -errno on failure and does NOT set the global errno
                    // (liburing returns the negated errno directly). Read the return value, not the captured errno: a
                    // stale errno left by a prior syscall would spuriously report io_uring unavailable here.
                    if rc != 0 then
                        ring.close()
                        false
                    else
                        uring.io_uring_queue_exit(ring)
                        ring.close()
                        true
                    end if
                catch case _: Throwable => false
            if !available then
                throw new kyo.test.TestCancelled(
                    "io_uring unavailable at production depth on this kernel/runtime (needs Linux >= 5.6)"
                )
            end if
        end if
    end assumeUring

    /** Open a real temporary file, write `content`, and return (raw POSIX fd, backing File) where the fd is opened `O_RDONLY` through the
      * native [[PosixShimBindings.kyo_posix_open]] shim.
      *
      * The raw fd is obtained from the kernel directly via the `open(2)` shim, so no reflection on `java.io.FileDescriptor` is needed. The
      * `fstat` and `read(2)` tests use the fd immediately, then the caller must release it with [[closeTempFd]] (which closes the fd via the
      * `close(2)` shim); the backing file is registered for delete-on-exit.
      *
      * Anti-flakiness: the file write is synchronous; the fd is valid on return.
      */
    def tempFileFd(content: Array[Byte])(using AllowUnsafe): (Int, java.io.File) =
        val f = java.io.File.createTempFile("kyo-net-test-", ".bin")
        f.deleteOnExit()
        val out = new java.io.FileOutputStream(f)
        try out.write(content)
        finally out.close()
        val shim  = Ffi.load[PosixShimBindings]
        val rawFd = shim.kyo_posix_open(f.getAbsolutePath, PosixConstants.O_RDONLY)
        assert(rawFd >= 0, s"kyo_posix_open failed for ${f.getAbsolutePath}, rc=$rawFd")
        (rawFd, f)
    end tempFileFd

    /** Close a raw fd returned by [[tempFileFd]] via the native `close(2)` shim. The backing file is delete-on-exit, so no further cleanup is
      * required.
      */
    def closeTempFd(fd: Int)(using AllowUnsafe): Unit =
        discard(Ffi.load[PosixShimBindings].kyo_posix_close(fd))
    end closeTempFd

end PosixTestSockets
