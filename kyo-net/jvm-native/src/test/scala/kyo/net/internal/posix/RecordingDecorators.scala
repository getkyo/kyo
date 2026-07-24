package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Spy decorator over a real [[SocketBindings]].
  *
  * Delegates all 16 methods to the real bindings and records observations: close counts per fd, shutdown calls (fd, how) in order,
  * send/recv buffer identities and regions, and optional one-shot hooks for race-determinism. The real syscall runs on every method call,
  * except the two authorized one-shot errno injections below; no other behavior is scripted.
  *
  * Authorized single-result errno injections (`injectRecvEintrOnce` / `injectSendEintrOnce`): a real mid-syscall signal delivery that makes a
  * non-blocking recv/send return -1 with errno EINTR before any byte is transferred is not deterministically injectable from Scala. These two
  * flags stand in for it at the bindings seam: when armed, the next recvNow (or sendNow / the `@Ffi.blocking` send on the JVM/Native flush path)
  * returns a synthesized (-1, EINTR) exactly once, then clears itself so every later call delegates to the real syscall. The real socket still
  * holds its data, so the retried call delivers it for real. This is the same controlled single-value-injection style as
  * RecordingPollerBackend.syntheticErrorFd and the single-CQE override on RecordingIoUringBindings, not a behavioral fake.
  *
  * Template: the LatchingSockets spy in StartTlsUpgradeCloseRaceTest.scala lines 64-109.
  *
  * Anti-flakiness: hooks fire exactly once (CAS the volatile field to null before invoking) so they are safe to set from a test fiber and
  * fire from inside a delegating method on a driver worker thread without further synchronization.
  */
final class RecordingSocketBindings(real: SocketBindings) extends SocketBindings:

    // Per-fd close count. close() increments before delegating so a double-close is observable immediately.
    val closeCounts: ConcurrentHashMap[Int, Int] = new ConcurrentHashMap[Int, Int]()

    // (fd, how) per shutdown() call, in order, so a test can assert NO stale shutdown landed on a given (possibly recycled) fd number.
    // Recorded before delegating, mirroring closeCounts.
    val shutdownCalls: ConcurrentLinkedQueue[(Int, Int)] = new ConcurrentLinkedQueue[(Int, Int)]()

    // Unified call-order log across shutdown/send/sendNow/close for this spy instance, in execution order (each entry recorded before
    // delegating to real, mirroring RecordingTlsEngine.entries/order below). A close-during-io race test uses this to assert the exact
    // interleaving of a claimed fd-close credit (shutdown, deferred) against the in-flight syscall it was deferred past (send) and the
    // eventual real close it unblocks (close).
    val callOrder: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]()

    /** The recorded shutdown/send/close call order. */
    def order: List[String] =
        import scala.jdk.CollectionConverters.*
        callOrder.iterator().asScala.toList
    end order

    // (position-into-buf, len) per sendNow call for byte-conservation and ordering assertions.
    val sendNowRegions: ConcurrentLinkedQueue[(Int, Long)] = new ConcurrentLinkedQueue[(Int, Long)]()

    // Buffer[Byte] instance passed to sendNow (buffer-identity / send-mirror assertions).
    val sendNowBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // Buffer[Byte] instance passed to the @Ffi.blocking send (the JVM/Native flush path), for send-mirror identity on that path.
    val sendBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // Buffer[Byte] instance passed to recvNow (buffer-identity / recv-staging assertions).
    val recvNowBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // One-shot hook fired the first time recvNow for the watched fd returns EAGAIN.
    // Mirrors the signalled+compareAndSet pattern in LatchingSockets (StartTlsUpgradeCloseRaceTest.scala:66,73-74).
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onRecvEagain: Int => Unit = null

    // One-shot hook fired from inside sendNow BEFORE delegating to real, for close-during-flush races.
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onSend: () => Unit = null

    // One-shot hook fired from inside recvNow BEFORE delegating to real, for close-during-dispatch races (the read-side twin of onSend).
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onRecvNow: Int => Unit = null

    // One-shot hook fired from inside shutdown BEFORE delegating to real, for release-ordering races (a shutdown sits between a release
    // path's fd claim and whatever it does next, so a hook here can hold the releasing carrier inside that exact window).
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onShutdown: Int => Unit = null

    // Per-fd latch that completes the first time close(fd) is actually called, mirroring RecordingPollerBackend's
    // registeredRead/deregisteredFd pattern. A close-during-io race test uses this to await the deferred real close(fd) running (which
    // happens asynchronously on whatever carrier releases the last guard holder), rather than polling closeCounts.
    private val closedOf: ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]] = new ConcurrentHashMap()

    /** A promise that completes when close(fd) is called. Created on first request, so it is ready before the close runs; if the close
      * already ran, the returned promise is already complete.
      */
    def closed(fd: Int)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        closedOf.computeIfAbsent(fd, _ => Promise.Unsafe.init[Unit, Any]())

    // Authorized one-shot errno injection: when set to true, the next recvNow returns a synthesized (-1, EINTR) Outcome ONCE
    // instead of delegating, then clears itself so every later recvNow delegates to the real syscall. A real mid-syscall signal
    // delivery (EINTR before any data is transferred) is not deterministically injectable from Scala, so this seam stands in for it:
    // the driver must retry the interrupted recv and recover the connection rather than failing it Closed. CAS to false on first use
    // so the injection fires exactly once even under concurrent recvNow calls (the same single-result-injection style as
    // RecordingPollerBackend.syntheticErrorFd and RecordingIoUringBindings' single-CQE override). After the injected EINTR the real
    // socket still holds the unread data, so the retried recvNow delivers it for real; the injection only forces the interrupted-call path.
    val injectRecvEintrOnce: AtomicBoolean = new AtomicBoolean(false)

    // Authorized one-shot errno injection for sendNow, symmetric to injectRecvEintrOnce: the next sendNow returns (-1, EINTR) ONCE
    // (no data sent), then clears itself so later sends delegate to the real syscall. EINTR on send means no bytes were transferred, so
    // the driver must retry the send (POSIX send(2)); the retried sendNow writes the bytes for real. CAS to false on first use.
    val injectSendEintrOnce: AtomicBoolean = new AtomicBoolean(false)

    def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
        real.socket(domain, `type`, protocol)

    def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
        real.bind(fd, addr, addrlen)

    def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
        real.listen(fd, backlog)

    def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
        real.setsockopt(fd, level, optname, optval, optlen)

    def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
        real.getsockopt(fd, level, optname, optval, optlen)

    def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
        real.getsockname(fd, addr, addrlen)
    def getpeername(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
        real.getpeername(fd, addr, addrlen)

    def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int] =
        real.fstat(fd, buf)

    def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int =
        // Record before delegating, mirroring close()'s ordering.
        shutdownCalls.add((fd, how))
        discard(callOrder.add(s"shutdown($fd)"))
        // Fire the onShutdown hook before delegating, one-shot via the same read-then-null pattern as onRecvNow above.
        val hook = onShutdown
        if hook != null then
            if onShutdown.eq(hook) then
                onShutdown = null
                hook(fd)
        end if
        real.shutdown(fd, how)
    end shutdown

    def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
        real.connect(fd, addr, addrlen)

    def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
        real.accept(fd, addr, addrlen)

    def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
        real.recv(fd, buf, len, flags)

    def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
        sendBufs.add(buf)
        // One-shot EINTR injection (JVM/Native flush path uses sockets.send): return (-1, EINTR) once, no bytes sent, without delegating.
        if injectSendEintrOnce.compareAndSet(true, false) then
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno(-1L, PosixConstants.EINTR)))
        else
            // Fire the onSend hook before delegating so it fires while beginWrite is held on JVM/Native (where PollerIoDriver uses
            // sockets.send for the flush path, not sendNow). This mirrors the HookSockets.send -> onSend() ordering.
            val hook = onSend
            if hook != null then
                if onSend.eq(hook) then
                    onSend = null
                    hook()
            end if
            discard(callOrder.add(s"send($fd)"))
            real.send(fd, buf, len, flags)
        end if
    end send

    def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
        sendNowBufs.add(buf)
        // Position 0 and len at call time; the actual buffer position is always 0 here because we pass sliced spans.
        sendNowRegions.add((0, len))
        // One-shot EINTR injection (JS flush path and JS writeRaw use sendNow): return (-1, EINTR) once, no bytes sent, without delegating.
        if injectSendEintrOnce.compareAndSet(true, false) then
            Ffi.Outcome.fromValueErrno(-1L, PosixConstants.EINTR)
        else
            // Fire hook before delegating so the hook fires while beginWrite is still held (the flush-race ordering requirement).
            val hook = onSend
            if hook != null then
                if onSend.eq(hook) then
                    onSend = null
                    hook()
            end if
            discard(callOrder.add(s"send($fd)"))
            real.sendNow(fd, buf, len, flags)
        end if
    end sendNow

    def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
        recvNowBufs.add(buf)
        // One-shot EINTR injection: return (-1, EINTR) once without delegating, so the real socket still holds the unread data; the
        // driver must retry the interrupted recv and the retried call (delegating below) then delivers the data for real.
        if injectRecvEintrOnce.compareAndSet(true, false) then
            return Ffi.Outcome.fromValueErrno(-1L, PosixConstants.EINTR)
        // Fire the onRecvNow hook before delegating so it fires while beginDispatch is held, the read-side twin of onSend's flush-race hook.
        val recvHook = onRecvNow
        if recvHook != null then
            if onRecvNow.eq(recvHook) then
                onRecvNow = null
                recvHook(fd)
        end if
        discard(callOrder.add(s"recv($fd)"))
        val r        = real.recvNow(fd, buf, len, flags)
        val isEagain = r.value < 0 && (r.errorCode == PosixConstants.EAGAIN || r.errorCode == PosixConstants.EWOULDBLOCK)
        if isEagain then
            val hook = onRecvEagain
            if hook != null then
                if onRecvEagain.eq(hook) then
                    onRecvEagain = null
                    hook(fd)
            end if
        end if
        r
    end recvNow

    def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
        real.acceptNow(fd, addr, addrlen)

    def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
        real.read(fd, buf, count)

    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
        // Record before delegating so the count is visible even if the caller does not await the returned fiber.
        discard(closeCounts.merge(fd, 1, (a, b) => a + b))
        discard(callOrder.add(s"close($fd)"))
        closedOf.computeIfAbsent(fd, _ => Promise.Unsafe.init[Unit, Any]()).completeDiscard(Result.succeed(()))
        real.close(fd)
    end close

end RecordingSocketBindings

/** Spy decorator over a real [[IoUringBindings]] backed by a real initialized ring.
  *
  * Delegates every kyo_uring_* method to the real bindings over the supplied real ring buffer. Records the buffer instances and regions
  * passed to send/recv ops for buffer-identity and ordering assertions, the user_data keys set on submitted SQEs, and fires latches when a
  * CQE is marked seen so tests synchronize on the real reap rather than a timer. The kernel completes real ring ops on every call; no
  * behavior is scripted.
  *
  * The authorized single-value injection (one cqe_res override for a specific CQE) is NOT implemented in this base decorator. A
  * per-test subclass or wrapper that overrides exactly one CQE is the correct location for that controlled injection.
  *
  * Anti-flakiness: cqeSeen and the awaitReap waiter FIFO fire AFTER the real kyo_uring_cqe_seen returns (the driver has already run the op's
  * completion / buffer release / deferred close in `complete`, which `drainReady` calls before `cqe_seen`). Awaiting one of these is therefore
  * a deterministic "this CQE was reaped and its post-completion work ran" signal, replacing a poll on a flag with a real-event latch.
  */
// Not final: the TLS send-error-discard test uses a per-test subclass that overrides exactly one kyo_uring_cqe_res to a negative value
// while every other ring op still delegates to the real ring, keeping the injection minimal and the ring otherwise fully real.
class RecordingIoUringBindings(real: IoUringBindings, realRing: Buffer[Byte]) extends IoUringBindings:

    import AllowUnsafe.embrace.danger

    // cqePtr buffer passed to kyo_uring_wait_cqe_timeout (buffer-identity assertions).
    val waitCqePtrs: ConcurrentLinkedQueue[Buffer[Long]] = new ConcurrentLinkedQueue[Buffer[Long]]()

    // cqePtr buffer passed to kyo_uring_peek_cqe (buffer-identity assertions).
    val peekCqePtrs: ConcurrentLinkedQueue[Buffer[Long]] = new ConcurrentLinkedQueue[Buffer[Long]]()

    // Buffer[Byte] instances passed to kyo_uring_prep_send (buffer-identity assertions).
    val sendBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // (offset, len) per kyo_uring_prep_send (byte-order / conservation assertions).
    val sendRegions: ConcurrentLinkedQueue[(Long, Long)] = new ConcurrentLinkedQueue[(Long, Long)]()

    // Buffer[Byte] instances passed to kyo_uring_prep_recv (buffer-identity assertions).
    val recvBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // The len argument of each kyo_uring_prep_recv call (recv-length / negative-length-guard assertions).
    val recvLens: ConcurrentLinkedQueue[Long] = new ConcurrentLinkedQueue[Long]()

    // user_data keys set on submitted SQEs, in submission order (re-arm / no-new-SQE assertions). Observation only.
    val submittedKeys: ConcurrentLinkedQueue[Long] = new ConcurrentLinkedQueue[Long]()

    // Count of CQEs marked seen by the reap loop. Bumped after the real kyo_uring_cqe_seen returns.
    val cqeSeenCount: AtomicInteger = new AtomicInteger(0)

    // One-shot latch completed the first time the reap loop marks any CQE seen (a deterministic "the first CQE was reaped" signal).
    val cqeSeen: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

    // Count of kyo_uring_wait_cqe_timeout calls (the reap loop's bounded-wait turns). Bumped before delegating.
    val waitCount: AtomicInteger = new AtomicInteger(0)

    // The timeoutNs argument of the most recent kyo_uring_wait_cqe_timeout (bounded-wait assertion). Volatile for cross-carrier visibility.
    @volatile var lastWaitTimeoutNs: Long = -1L

    // One-shot latches completed on the first and second wait turn, so a test can synchronize on the reap loop having run a bounded wait
    // (firstWait: the recorded timeout is in hand) and on it surviving an empty timeout turn (secondWait: it kept iterating).
    val firstWait: Promise.Unsafe[Unit, Any]  = Promise.Unsafe.init[Unit, Any]()
    val secondWait: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

    // FIFO of one-shot "next CQE seen" waiters: awaitReap registers one, kyo_uring_cqe_seen completes the oldest, so a test can synchronize
    // on each successive reap in order (the per-reap settle the write-ordering conservation leaves need).
    private val reapWaiters: ConcurrentLinkedQueue[Promise.Unsafe[Unit, Any]] = new ConcurrentLinkedQueue[Promise.Unsafe[Unit, Any]]()

    /** Whether `IORING_FEAT_NODROP` (bit 1, kernel >= 5.5) was set at ring init. Tests use this to assert the expected
      * conditional-park behavior: indefinite (`Long.MaxValue`) when `nodropAvailable` and the wake multishot is armed with no stalled ops;
      * bounded `ReapTimeoutNs` otherwise.
      */
    val nodropAvailable: Boolean =
        (real.kyo_uring_get_features(realRing) & IoUringDriver.FeatNodrop) != 0

    /** A promise that completes when the reap loop next marks a CQE seen (i.e. has reaped one completion and run its post-completion work). */
    def awaitReap()(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        discard(reapWaiters.offer(p))
        p
    end awaitReap

    def io_uring_queue_init(entries: Int, ring: Buffer[Byte], flags: Int)(using AllowUnsafe): Int =
        real.io_uring_queue_init(entries, realRing, flags)

    def io_uring_queue_exit(ring: Buffer[Byte])(using AllowUnsafe): Unit =
        real.io_uring_queue_exit(realRing)

    def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int =
        real.io_uring_submit(realRing)

    def kyo_uring_sizeof()(using AllowUnsafe): Long =
        real.kyo_uring_sizeof()

    def kyo_uring_get_sqe(ring: Buffer[Byte])(using AllowUnsafe): Maybe[Ffi.Handle[IoUringSqe]] =
        real.kyo_uring_get_sqe(realRing)

    def kyo_uring_prep_read(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using AllowUnsafe): Int =
        real.kyo_uring_prep_read(sqe, fd, buf, nbytes, offset)

    def kyo_uring_prep_write(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using AllowUnsafe): Int =
        real.kyo_uring_prep_write(sqe, fd, buf, nbytes, offset)

    def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int =
        recvBufs.add(buf)
        recvLens.add(len)
        real.kyo_uring_prep_recv(sqe, fd, buf, len, flags)
    end kyo_uring_prep_recv

    def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int =
        sendBufs.add(buf)
        sendRegions.add((0L, len))
        real.kyo_uring_prep_send(sqe, fd, buf, len, flags)
    end kyo_uring_prep_send

    def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
        AllowUnsafe
    ): Unit =
        real.kyo_uring_prep_accept(sqe, fd, addr, addrlen, flags)

    def kyo_uring_prep_connect(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Unit =
        real.kyo_uring_prep_connect(sqe, fd, addr, addrlen)

    def kyo_uring_sqe_set_data64(sqe: Ffi.Handle[IoUringSqe], data: Long)(using AllowUnsafe): Unit =
        discard(submittedKeys.add(data))
        real.kyo_uring_sqe_set_data64(sqe, data)
    end kyo_uring_sqe_set_data64

    def kyo_uring_wait_cqe_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any] =
        waitCqePtrs.add(cqePtr)
        lastWaitTimeoutNs = timeoutNs
        val turns = waitCount.incrementAndGet()
        if turns >= 1 then firstWait.completeDiscard(Result.succeed(()))
        if turns >= 2 then secondWait.completeDiscard(Result.succeed(()))
        real.kyo_uring_wait_cqe_timeout(realRing, cqePtr, timeoutNs)
    end kyo_uring_wait_cqe_timeout

    def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any] =
        waitCqePtrs.add(cqePtr)
        lastWaitTimeoutNs = timeoutNs
        val turns = waitCount.incrementAndGet()
        if turns >= 1 then firstWait.completeDiscard(Result.succeed(()))
        if turns >= 2 then secondWait.completeDiscard(Result.succeed(()))
        real.kyo_uring_submit_and_wait_timeout(realRing, cqePtr, timeoutNs)
    end kyo_uring_submit_and_wait_timeout

    def kyo_uring_kernel_version()(using AllowUnsafe): Int =
        real.kyo_uring_kernel_version()

    def kyo_uring_get_features(ring: Buffer[Byte])(using AllowUnsafe): Int =
        real.kyo_uring_get_features(realRing)

    def kyo_uring_prep_multishot_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
        AllowUnsafe
    ): Unit =
        real.kyo_uring_prep_multishot_accept(sqe, fd, addr, addrlen, flags)

    def kyo_uring_cqe_get_flags(cqe: Long)(using AllowUnsafe): Int =
        real.kyo_uring_cqe_get_flags(cqe)

    def kyo_uring_recv_multishot_flag()(using AllowUnsafe): Int =
        real.kyo_uring_recv_multishot_flag()

    def kyo_uring_peek_cqe(ring: Buffer[Byte], cqePtr: Buffer[Long])(using AllowUnsafe): Int =
        peekCqePtrs.add(cqePtr)
        real.kyo_uring_peek_cqe(realRing, cqePtr)
    end kyo_uring_peek_cqe

    def kyo_uring_cqe_get_data64(cqe: Long)(using AllowUnsafe): Long =
        real.kyo_uring_cqe_get_data64(cqe)

    def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int =
        real.kyo_uring_cqe_res(cqe)

    def kyo_uring_cqe_seen(ring: Buffer[Byte], cqe: Long)(using AllowUnsafe): Unit =
        // The wake eventfd's multishot POLL_ADD CQE is reap-loop infrastructure (it just returns the parked wait), NOT a connection-op
        // completion. Read its key before cqe_seen (which may invalidate the pointer) and skip the reap observability for it, so the reap
        // latches/counters fire only on real op reaps (recv/send/accept/connect) the tests synchronize on. Without this skip a wake CQE that
        // precedes a connection CQE in the same drain batch would fire awaitReap early and confound the deferred-close ordering tests.
        val isWake = real.kyo_uring_cqe_get_data64(cqe) == IoUringDriver.WakeKey
        // Delegate first: the driver has already run complete() (op completion + buffer release + deferred close) before drainReady calls
        // cqe_seen, so by the time these latches fire the post-completion work is done. Fire AFTER the real call so the spy never reorders.
        real.kyo_uring_cqe_seen(realRing, cqe)
        if !isWake then
            discard(cqeSeenCount.getAndIncrement())
            cqeSeen.completeDiscard(Result.succeed(()))
            Maybe(reapWaiters.poll()).foreach(_.completeDiscard(Result.succeed(())))
        end if
    end kyo_uring_cqe_seen

    def kyo_uring_probe_available(depth: Int)(using AllowUnsafe): Boolean =
        real.kyo_uring_probe_available(depth)

    def kyo_uring_prep_poll_multishot(sqe: Ffi.Handle[IoUringSqe], fd: Int, pollMask: Int)(using AllowUnsafe): Unit =
        real.kyo_uring_prep_poll_multishot(sqe, fd, pollMask)

    def kyo_uring_poll_peer_closed(fd: Int)(using AllowUnsafe): Int =
        real.kyo_uring_poll_peer_closed(fd)

    def kyo_uring_eventfd_create(initval: Int, flags: Int)(using AllowUnsafe): Int =
        real.kyo_uring_eventfd_create(initval, flags)

    def kyo_uring_eventfd_write(fd: Int)(using AllowUnsafe): Int =
        real.kyo_uring_eventfd_write(fd)

    def kyo_uring_eventfd_read(fd: Int)(using AllowUnsafe): Int =
        real.kyo_uring_eventfd_read(fd)

    def kyo_uring_eventfd_close(fd: Int)(using AllowUnsafe): Int =
        real.kyo_uring_eventfd_close(fd)

end RecordingIoUringBindings

/** Spy decorator over a real [[PollerBackend]].
  *
  * Delegates all methods and records registration counts and fd lists. The key hook is a one-shot latch fired from inside poll() BEFORE
  * delegating to the real epoll_wait/kevent, enabling deterministic close-during-poll race tests.
  *
  * Per-fd latches let a test synchronize on the exact change running on the driver's change-FIFO worker: registeredRead(fd) completes when
  * registerRead(fd) runs, deregisteredFd(fd) when deregister(fd) runs. onRegisterRead runs at the very start of registerRead (before counting
  * or completing the per-fd latch) so a test can pin the change worker inside the first change.
  *
  * The authorized synthetic-scratch-entry injection: when syntheticErrorFd is set to a non-negative fd, the next poll() delegates to
  * real.poll() and then injects one synthetic error-flag entry for that fd into the returned scratch (scratch.fds[n] = fd,
  * scratch.flags[n] = PollFlags.Error, ready count becomes n+1). The injection fires exactly once via CAS (set to -1 after consumption).
  * The driver's dispatch path then calls getsockopt(SO_ERROR) on the real fd; a healthy fd returns SO_ERROR=0, dropping the event. This
  * is the stale-error case: one synthetic readiness entry triggers the real getsockopt check path. The injection index n is guarded to stay
  * below the scratch capacity (MaxEvents = 64) so a full real poll never overflows the arrays.
  */
final class RecordingPollerBackend(real: PollerBackend) extends PollerBackend:

    // The real backend's scratch capacity (EpollPollerBackend / KqueuePollerBackend allocate arrays of this size). The synthetic injection
    // index is guarded to stay below it so a full real poll batch never writes out of bounds.
    private val MaxEvents = 64

    // Count of registerRead calls.
    val registerReadCount: AtomicInteger = new AtomicInteger(0)

    // Count of registerWrite calls.
    val registerWriteCount: AtomicInteger = new AtomicInteger(0)

    // Fds registered for read.
    val registeredReadFds: ConcurrentLinkedQueue[Int] = new ConcurrentLinkedQueue[Int]()

    // Fds registered for write.
    val registeredWriteFds: ConcurrentLinkedQueue[Int] = new ConcurrentLinkedQueue[Int]()

    // Fds deregistered, in execution order.
    val deregisteredFds: ConcurrentLinkedQueue[Int] = new ConcurrentLinkedQueue[Int]()

    // Every interest change recorded in execution order: "registerRead(fd)", "registerWrite(fd)", "deregister(fd)".
    // Used by FIFO-ordering assertions. Under edge-triggered registration there is no rearm; the fd stays armed at the kernel.
    val callLogQueue: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]()

    /** The recorded interest changes in execution order. */
    def callLog: List[String] =
        import scala.jdk.CollectionConverters.*
        callLogQueue.iterator().asScala.toList
    end callLog

    // Last poll timeout recorded. Volatile for cross-thread visibility.
    @volatile var lastPollTimeoutMs: Long = -1L

    // The scratch the real backend allocated (set on first newPollScratch). The driver holds exactly one scratch; a test that needs to
    // observe its buffers (e.g. eventsBuffer.isClosed) reads it here.
    @volatile var lastScratch: PollScratch = null

    // The scratch.armBuf instance passed to each registerRead (arm-buffer reuse assertions across re-arms).
    val registerReadArmBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // The scratch.eventsBuffer instance passed to each poll (poll-scratch reuse assertions across cycles).
    val pollEventsBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // The scratch.fds array instance passed to each poll (parallel-array reuse assertions across cycles).
    val pollFdsArrays: ConcurrentLinkedQueue[Array[Int]] = new ConcurrentLinkedQueue[Array[Int]]()

    // One-shot latch fired from inside poll() BEFORE delegating to the real backend.
    // The latch completes before epoll_wait/kevent so a close-during-poll fiber can act while the real poll is about to block.
    // Stored as AnyRef to allow null (Promise.Unsafe is opaque over IOPromise which is an AnyRef).
    // Null means no hook set; getAndSet to null atomically before firing so it fires exactly once.
    val prePollLatch: AtomicReference[AnyRef] = new AtomicReference[AnyRef](null)

    /** Set the pre-poll latch to the given promise (called from test setup). */
    def setPrePollLatch(p: Promise.Unsafe[Unit, Any]): Unit =
        prePollLatch.set(p.asInstanceOf[AnyRef])

    // One-shot controllable hold: when set, the first poll() returns THIS pending fiber (after firing prePollLatch) instead of delegating to the
    // real blocking epoll_wait/kevent for that cycle. The poll loop parks on it (the pending-fiber path), so the poll carrier provably owns the
    // scratch and is NOT inside a real blocking syscall that close() could wake. This is the authorized controlled substitution for the
    // close-during-poll scratch-ownership test (real epoll_wait cannot be held in a Scala-observable parked state, and close() closing the poller
    // fd would otherwise wake it). The test completes it to let the loop re-enter; subsequent polls delegate to the real backend.
    // Stored as AnyRef to allow null; getAndSet to null so it substitutes exactly one cycle.
    val prePollHold: AtomicReference[AnyRef] = new AtomicReference[AnyRef](null)

    /** Set the one-shot pre-poll hold to the given promise (called from test setup). */
    def setPrePollHold(p: Promise.Unsafe[Int, Any]): Unit =
        prePollHold.set(p.asInstanceOf[AnyRef])

    // One-shot callback fired at the START of registerRead (before counting / recording / completing the per-fd latch), so a test can pin the
    // change worker inside the first change (the single-owner proof). null means none set; CAS to null before firing so it fires exactly once.
    @volatile var onRegisterRead: Int => Unit = null

    // Per-fd latch that completes the first time registerRead(fd) runs on the change worker.
    private val registeredReadOf: ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]] = new ConcurrentHashMap()

    // Per-fd latch that completes the first time registerWrite(fd) runs on the change worker.
    private val registeredWriteOf: ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]] = new ConcurrentHashMap()

    // Per-fd latch that completes the first time deregister(fd) runs on the change worker.
    private val deregisteredFdOf: ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]] = new ConcurrentHashMap()

    /** A promise that completes when registerRead(fd) executes on the change worker. Created on first request so it is ready before the change
      * runs; a test sets it up before submitting the change.
      */
    def registeredRead(fd: Int)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        registeredReadOf.computeIfAbsent(fd, _ => Promise.Unsafe.init[Unit, Any]())

    /** A promise that completes when registerWrite(fd) executes on the change worker. Created on first request. */
    def registeredWrite(fd: Int)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        registeredWriteOf.computeIfAbsent(fd, _ => Promise.Unsafe.init[Unit, Any]())

    /** A promise that completes when deregister(fd) executes on the change worker. Created on first request. */
    def deregisteredFd(fd: Int)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        deregisteredFdOf.computeIfAbsent(fd, _ => Promise.Unsafe.init[Unit, Any]())

    // Authorized one-shot synthetic-scratch injection: the fd to inject an error entry for, or -1 if no injection is pending.
    // CAS from the target fd to -1 on first use so the injection fires exactly once even under concurrent poll calls.
    val syntheticErrorFd: AtomicInteger = new AtomicInteger(-1)

    def create()(using AllowUnsafe): Int =
        real.create()

    def registerRead(pollerFd: Int, fd: Int, id: Long, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        val hook = onRegisterRead
        if hook != null && onRegisterRead.eq(hook) then
            onRegisterRead = null
            hook(fd)
        end if
        discard(registerReadCount.getAndIncrement())
        registeredReadFds.add(fd)
        registerReadArmBufs.add(scratch.armBuf)
        discard(callLogQueue.add(s"registerRead($fd)"))
        val rc = real.registerRead(pollerFd, fd, id, scratch)
        registeredRead(fd).completeDiscard(Result.succeed(()))
        rc
    end registerRead

    def registerWrite(pollerFd: Int, fd: Int, id: Long, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        discard(registerWriteCount.getAndIncrement())
        registeredWriteFds.add(fd)
        discard(callLogQueue.add(s"registerWrite($fd)"))
        val rc = real.registerWrite(pollerFd, fd, id, scratch)
        registeredWrite(fd).completeDiscard(Result.succeed(()))
        rc
    end registerWrite

    def deregister(pollerFd: Int, fd: Int, fdClosing: Boolean, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        deregisteredFds.add(fd)
        discard(callLogQueue.add(s"deregister($fd, fdClosing=$fdClosing)"))
        real.deregister(pollerFd, fd, fdClosing, scratch)
        deregisteredFd(fd).completeDiscard(Result.succeed(()))
    end deregister

    // Count of poll() calls that carried at least one batched change (nChanges > 0). On kqueue the changelist is submitted atomically
    // alongside the kevent call; this counter confirms that at least one poll cycle delivered interest changes via the batch, proving
    // the changelist accumulation path is live and not a no-op. On epoll the backend ignores the changelist, so this counter stays 0
    // on Linux, but the test that checks it gates with assumeKqueue().
    val pollWithChangesCount: AtomicInteger = new AtomicInteger(0)

    def poll(pollerFd: Int, timeoutMs: Int, changelist: kyo.ffi.Buffer[Byte], nChanges: Int, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        if throwOnPoll.compareAndSet(true, false) then
            throw new RuntimeException("injected poll failure (crash-containment guard)")
        lastPollTimeoutMs = timeoutMs.toLong
        pollEventsBufs.add(scratch.eventsBuffer)
        pollFdsArrays.add(scratch.fds)
        if nChanges > 0 then discard(pollWithChangesCount.getAndIncrement())
        // Fire the pre-poll latch before delegating so the waiting fiber can act before epoll_wait/kevent blocks.
        val raw = prePollLatch.getAndSet(null)
        if raw != null then
            discard(raw.asInstanceOf[Promise.Unsafe[Unit, Any]].completeDiscard(Result.succeed(())))
        // One-shot controllable hold: park this cycle on the test-controlled fiber instead of the real blocking syscall. The poll carrier owns
        // the scratch and is provably parked (not inside epoll_wait/kevent), so close() cannot wake it. Returned before any real.poll() call.
        val hold = prePollHold.getAndSet(null)
        if hold != null then return hold.asInstanceOf[Fiber.Unsafe[Int, Any]]
        // Consume the one-shot synthetic injection atomically before delegating so the CAS wins exactly once.
        val injFd     = syntheticErrorFd.get()
        val hasInject = injFd >= 0 && syntheticErrorFd.compareAndSet(injFd, -1)
        val realFiber = real.poll(pollerFd, timeoutMs, changelist, nChanges, scratch)
        if hasInject then
            // After the real poll returns n events, append one synthetic error-flag entry at index n for the target fd, guarded to stay
            // within the scratch arrays (MaxEvents). The driver's drainReady calls dispatchError(fd) which calls getsockopt(SO_ERROR) on the
            // real fd. A healthy fd returns SO_ERROR=0 and the event is dropped, proving the stale-error code path runs correctly.
            realFiber.map { n =>
                if n < MaxEvents then
                    scratch.fds(n) = injFd
                    scratch.flags(n) = PollFlags.Error
                    n + 1
                else n
            }
        else
            realFiber
        end if
    end poll

    def newPollScratch()(using AllowUnsafe): PollScratch =
        val s = real.newPollScratch()
        lastScratch = s
        s
    end newPollScratch

    // Count of wake() triggers (poll-loop wakeup), for tests asserting a submitted change triggers a prompt wake.
    val wakeCount: AtomicInteger = new AtomicInteger(0)

    // Number of wake() calls currently between their start and their delegate-to-real return (the wake-fd-in-flight window). The wake-fd lifecycle
    // guard must never let closeWake run while this is > 0: closeWake closes the wakeup eventfd, and a concurrent wake's eventfd_write on the same
    // (then-recycled) fd is the lazyFdDelete cross-fd corruption. Used by PollerWakeCloseRaceTest.
    val wakeInFlight: AtomicInteger = new AtomicInteger(0)

    // Set true if closeWake ever runs while a wake() is in flight (wakeInFlight > 0): the invariant violation the wake-fd guard prevents.
    val closeWakeWhileWaking: AtomicBoolean = new AtomicBoolean(false)

    // Count of closeWake() calls actually delegated (the wake-fd is closed exactly once across the close paths).
    val closeWakeCount: AtomicInteger = new AtomicInteger(0)

    // One-shot latch completed AFTER closeWake() delegates to real, a real-event signal that the wake-fd teardown has run (the driver's terminal
    // exit reached freeScratch). A test awaits it instead of polling a flag, so the close-vs-wake race is synchronized on a real event, not a sleep.
    // Lazily created (a class-level Promise.Unsafe.init would need a class-scoped AllowUnsafe); a test calls closeWakeDone() before triggering close.
    private val closeWakeDoneRef: AtomicReference[AnyRef] = new AtomicReference[AnyRef](null)

    /** A promise that completes when closeWake() runs on the driver's terminal exit. Created on first request so it is ready before the close. */
    def closeWakeDone()(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val existing = closeWakeDoneRef.get()
        if existing != null then existing.asInstanceOf[Promise.Unsafe[Unit, Any]]
        else
            val p = Promise.Unsafe.init[Unit, Any]()
            if closeWakeDoneRef.compareAndSet(null, p.asInstanceOf[AnyRef]) then p
            else closeWakeDoneRef.get().asInstanceOf[Promise.Unsafe[Unit, Any]]
        end if
    end closeWakeDone

    // One-shot callback fired at the START of wake() (after wakeInFlight is incremented, before delegating to real), so a test can hold the wake
    // mid-flight and drive a concurrent close into the wake-fd guard. null means none set; CAS to null before firing so it fires exactly once.
    @volatile var onWakeEnter: () => Unit = null

    // When true, the next poll() throws instead of delegating. CAS to false on use so it fires exactly once. The authorized injection for the
    // crash-containment guard: a driver cycle must contain a Throwable from anywhere in its body, run its terminal teardown, and complete its
    // done-fiber as a panic, rather than dying silently and leaving close() to hang.
    val throwOnPoll: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)

    // When true, the next registerWake returns false (forced failure) without calling real. CAS to false on use so it fires once.
    val forceRegisterWakeFail: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)

    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean =
        if forceRegisterWakeFail.compareAndSet(true, false) then false
        else real.registerWake(pollerFd, scratch)

    def wake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        discard(wakeCount.getAndIncrement())
        discard(wakeInFlight.getAndIncrement())
        try
            val hook = onWakeEnter
            if hook != null && onWakeEnter.eq(hook) then
                onWakeEnter = null
                hook()
            end if
            real.wake(pollerFd, scratch)
        finally discard(wakeInFlight.getAndDecrement())
        end try
    end wake

    def drainWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        real.drainWake(scratch)

    def isWakeFd(fd: Int, scratch: PollScratch): Boolean =
        real.isWakeFd(fd, scratch)

    def closeWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        if wakeInFlight.get() > 0 then closeWakeWhileWaking.set(true)
        discard(closeWakeCount.getAndIncrement())
        real.closeWake(scratch)
        val p = closeWakeDoneRef.get()
        if p != null then p.asInstanceOf[Promise.Unsafe[Unit, Any]].completeDiscard(Result.succeed(()))
    end closeWake

    def close(pollerFd: Int)(using AllowUnsafe, Frame): Unit =
        real.close(pollerFd)

end RecordingPollerBackend

/** Spy decorator over a real [[TlsEngine]].
  *
  * Delegates all 9 methods to the real engine (BoringSSL, OpenSSL, or JDK SSLEngine) and records call counts, buffer identities, and
  * free count. The real crypto runs on every call. freeCount is incremented BEFORE real.free() because after free() the native memory is
  * gone.
  *
  * The authorized single-injection hooks (onWritePlain, onFeedCiphertext) are one-shot re-entrant latches fired inside the
  * delegating method (after recording, before delegating) to enable deterministic close-during-encrypt/decrypt race tests.
  */
final class RecordingTlsEngine(real: TlsEngine) extends TlsEngine:

    // Count of free() calls. Expected to be exactly 1 after the engine is released.
    val freeCount: AtomicInteger = new AtomicInteger(0)

    // Set true if any engine method is entered after free() has run. The use-after-free signal for the close-during-op race tests.
    val usedAfterFree: AtomicBoolean = new AtomicBoolean(false)

    // Per-method call counts.
    val feedCalls: AtomicInteger       = new AtomicInteger(0)
    val drainCalls: AtomicInteger      = new AtomicInteger(0)
    val readPlainCalls: AtomicInteger  = new AtomicInteger(0)
    val writePlainCalls: AtomicInteger = new AtomicInteger(0)
    val handshakeCalls: AtomicInteger  = new AtomicInteger(0)

    // Live count of engine ops in flight and its observed peak, for the engine-FIFO single-owner overlap assertion (maxInFlight must be 1).
    val inFlight: AtomicInteger    = new AtomicInteger(0)
    val maxInFlight: AtomicInteger = new AtomicInteger(0)

    // Order log of the read-decrypt / write-encrypt / free engine ops as they entered, for the FIFO free-ordering assertion.
    val entries: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]()

    /** Recorded op order. */
    def order: List[String] =
        import scala.jdk.CollectionConverters.*
        entries.iterator().asScala.toList
    end order

    // Flip usedAfterFree if a method runs after free(). Called at the start of every delegating method, mirroring the SpyEngine.touch()
    // pattern.
    private def touch(): Unit =
        if freeCount.get() > 0 then usedAfterFree.set(true)

    // Track op entry/exit so overlap (maxInFlight) and run order (entries) are observable around the real engine call.
    private def enter(label: String): Unit =
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet(prev => math.max(prev, now))
        discard(entries.add(label))
    end enter

    private def exit(): Unit = discard(inFlight.decrementAndGet())

    // Buffer[Byte] instances passed to writePlain (buffer-identity assertions).
    val writePlainBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // Buffer[Byte] instances passed to feedCiphertext (recv-staging reuse assertions).
    val feedBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // The len argument of each feedCiphertext call (recv-staging byte-behavior pin).
    val feedLens: ConcurrentLinkedQueue[Int] = new ConcurrentLinkedQueue[Int]()

    // Buffer[Byte] instances passed to readPlain (decrypt drain-buffer reuse assertions).
    val readPlainBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // Buffer[Byte] instances passed to drainCiphertext (encrypt drain-buffer reuse assertions).
    val drainCipherBufs: ConcurrentLinkedQueue[Buffer[Byte]] = new ConcurrentLinkedQueue[Buffer[Byte]]()

    // One-shot re-entrant latch for the close-during-encrypt race.
    // Fired inside writePlain() AFTER recording but BEFORE delegating to real.
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onWritePlain: () => Unit = null

    // One-shot re-entrant latch for the close-during-decrypt race.
    // Fired inside feedCiphertext() AFTER recording but BEFORE delegating to real.
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onFeedCiphertext: () => Unit = null

    def handshakeStep()(using AllowUnsafe): Int =
        touch()
        discard(handshakeCalls.getAndIncrement())
        real.handshakeStep()
    end handshakeStep

    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        discard(feedCalls.getAndIncrement())
        feedBufs.add(buf)
        feedLens.add(len)
        val hook = onFeedCiphertext
        if hook != null then
            if onFeedCiphertext.eq(hook) then
                onFeedCiphertext = null
                hook()
        end if
        real.feedCiphertext(buf, len)
    end feedCiphertext

    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        discard(drainCalls.getAndIncrement())
        drainCipherBufs.add(buf)
        real.drainCiphertext(buf, len)
    end drainCiphertext

    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        discard(readPlainCalls.getAndIncrement())
        readPlainBufs.add(buf)
        enter("read")
        try real.readPlain(buf, len)
        finally exit()
    end readPlain

    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        discard(writePlainCalls.getAndIncrement())
        writePlainBufs.add(buf)
        val hook = onWritePlain
        if hook != null then
            if onWritePlain.eq(hook) then
                onWritePlain = null
                hook()
        end if
        enter("write")
        try real.writePlain(buf, len)
        finally exit()
    end writePlain

    def hasBufferedPlaintext(using AllowUnsafe): Boolean =
        touch()
        real.hasBufferedPlaintext
    end hasBufferedPlaintext

    def readBuffered()(using AllowUnsafe): Span[Byte] =
        touch()
        real.readBuffered()
    end readBuffered

    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]] =
        touch()
        real.certSha256()
    end certSha256

    def shutdownStep()(using AllowUnsafe): Int =
        touch()
        real.shutdownStep()
    end shutdownStep

    def free()(using AllowUnsafe): Unit =
        // Record the free in the op order, then increment freeCount before real.free() (after free() the native memory is gone). The free op
        // is not a use-after-free of itself; a double free is caught by freeCount, not usedAfterFree, so touch() is not called here.
        enter("free")
        try
            discard(freeCount.getAndIncrement())
            real.free()
        finally exit()
        end try
    end free

end RecordingTlsEngine

/** Spy decorator over a real [[IoDriver]][[[PosixHandle]]].
  *
  * Delegates all methods to the real driver and records call counts. Every behavioral method calls through to the real driver; no behavior
  * is scripted. Write-error scenarios (WriteResult.Error from a peer reset) are produced for real via PosixTestSockets.resetPeer, not by
  * injection. Defined in the shared posix test-util so all IoDriver-level tests use a single implementation.
  *
  * `throwOnStart` is the single controlled injection on this decorator: when set to true, the next call to `start()` throws a
  * `RuntimeException` instead of delegating, and every other method still delegates to the real driver. A real
  * `PollerIoDriver.start()` only sets a flag and spawns the poll-loop fiber, so it has no deterministic failure mode; this single-value
  * injection is the only way to exercise the pool's start-resilience path (`IoDriverPool.start` continues past one driver that fails to
  * start).
  *
  * The `onCancel` / `onCloseHandle` / `onClose` hooks let a test observe call order across drivers without a non-delegating subclass: each
  * fires after the call is recorded and before delegating to the real driver, so the decorator stays final and delegating.
  */
final class RecordingIoDriver(real: IoDriver[PosixHandle]) extends IoDriver[PosixHandle]:

    // Count of write() calls.
    val writeCalls: AtomicInteger = new AtomicInteger(0)

    // The (data span, offset) pair passed to each write() call, in order. The span is the reference the WritePump re-presents on a Partial
    // retry; recording it lets a test assert the same Span instance is re-presented at an advancing offset (no Span.drop allocation).
    val writeRegions: ConcurrentLinkedQueue[(Span[Byte], Int)] = new ConcurrentLinkedQueue[(Span[Byte], Int)]()

    // Count of awaitRead() calls.
    val awaitReadCalls: AtomicInteger = new AtomicInteger(0)

    // One-shot callback fired after the count is incremented but BEFORE delegating to the real driver.
    // Used by tests to latch on the Nth awaitRead call without racing.
    // null means no hook set; CAS to null before firing so it fires exactly once.
    @volatile var onAwaitRead: () => Unit = null

    // Count of awaitWritable() calls.
    val awaitWritableCalls: AtomicInteger = new AtomicInteger(0)

    // One-shot callback fired with the registered writable handle, after awaitWritable records the call and the real registration is in
    // place, on the same thread that called awaitWritable. Firing the failure here (before the poll loop can deliver a writable event)
    // makes the writable-wait outcome deterministic with no readiness race. null means no hook set; fires exactly once.
    @volatile var onAwaitWritable: PosixHandle => Unit = null

    // Count of cancel() calls.
    val cancelCalls: AtomicInteger = new AtomicInteger(0)

    // Count of closeHandle() calls.
    val closeHandleCalls: AtomicInteger = new AtomicInteger(0)

    // Count of close() calls (the driver's own shutdown, not closeHandle).
    val closeCalls: AtomicInteger = new AtomicInteger(0)

    // Callbacks fired after the corresponding call is recorded and before delegating to the real driver. They let a test record the order
    // of cancel/closeHandle/close (within a connection or across pool drivers) without a non-delegating subclass. null means none set.
    @volatile var onCancel: () => Unit      = null
    @volatile var onCloseHandle: () => Unit = null
    @volatile var onClose: () => Unit       = null

    // When true, the next start() call throws instead of delegating. The single authorized injection (see class scaladoc).
    @volatile var throwOnStart: Boolean = false

    // Optional label override. `label` is a pure identity accessor, not a behavioral I/O method; relabeling a fully-delegating spy lets a
    // test exercise label-keyed selection predicates (the non-"PollerIoDriver" branch) without a separate driver. Absent reports the real
    // driver's label.
    @volatile var labelOverride: Maybe[String] = Absent

    def label: String =
        labelOverride.getOrElse(real.label)

    def handleLabel(handle: PosixHandle): String =
        real.handleLabel(handle)

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        if throwOnStart then throw new RuntimeException(s"driver start failed (throwOnStart=true)")
        real.start()
    end start

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        discard(awaitReadCalls.getAndIncrement())
        val hook = onAwaitRead
        if hook != null then
            if onAwaitRead.eq(hook) then
                onAwaitRead = null
                hook()
        end if
        real.awaitRead(handle, promise)
    end awaitRead

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        discard(awaitWritableCalls.getAndIncrement())
        real.awaitWritable(handle, promise)
        val hook = onAwaitWritable
        if hook != null then
            onAwaitWritable = null
            hook(handle)
        end if
    end awaitWritable

    // When true, awaitConnect swallows the call: it neither submits nor completes the promise. Reproduces the transport's own asynchronous
    // path stalling with the connect still pending, which is what the connect deadline actually bounds (the OS connect itself always settles
    // promptly). The in-repo stall classes with this exact shape are an op stranded on the engine queue, a submission parked on a full SQ,
    // and a driver carrier that never gets scheduled.
    @volatile var stallConnect: Boolean = false

    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if stallConnect then ()
        else real.awaitConnect(handle, promise)

    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        real.awaitAccept(handle, promise)

    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        // Record the call then always delegate; the real write result (including WriteResult.Error on a reset peer) is returned as-is.
        discard(writeCalls.getAndIncrement())
        writeRegions.add((data, offset))
        real.write(handle, data, offset)
    end write

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        discard(cancelCalls.getAndIncrement())
        val hook = onCancel
        if hook != null then hook()
        real.cancel(handle)
    end cancel

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        discard(closeHandleCalls.getAndIncrement())
        val hook = onCloseHandle
        if hook != null then hook()
        real.closeHandle(handle)
    end closeHandle

    def close()(using AllowUnsafe, Frame): Unit =
        discard(closeCalls.getAndIncrement())
        val hook = onClose
        if hook != null then hook()
        real.close()
    end close

end RecordingIoDriver
