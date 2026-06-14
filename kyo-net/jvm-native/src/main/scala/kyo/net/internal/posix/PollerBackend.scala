package kyo.net.internal.posix

import kyo.*

/** The epoll/kqueue uniformity seam (the unification of the native poller).
  *
  * Abstracts the few points where epoll (Linux) and kqueue (macOS/BSD) differ behind one interface so that [[PollerIoDriver]]'s
  * pending-table / dispatch / cancel machinery is shared across both. Epoll and kqueue diverge in five places: the fd-create syscall, how
  * read/write interest is registered, whether an explicit deregister is needed, the readiness encoding returned by a poll, and the poll
  * syscall itself. Everything above this seam (the pending tables, the dispatch loop, the stale-fd id guard, cancel semantics) is identical.
  *
  * [[poll]] fills the caller-owned [[PollScratch]]'s parallel arrays (`fds`, `flags`) with decoded event data and returns the ready count
  * `n`. The scratch is allocated once per driver and reused across all poll cycles and arm/register/deregister calls, so no per-call
  * off-heap allocation occurs.
  *
  * The driver's `while`-loop body consumes the returned `n` via `done()`/`poll()` (inline on JVM/Native) or `onComplete` re-enter (JS),
  * never `.safe.get`. All other methods are synchronous: `epoll_create1` / `epoll_ctl`, kqueue's register-only `keventNow` (zero timeout),
  * and `close` all complete inline.
  */
private[net] trait PollerBackend:

    /** Create a new poller fd (epoll fd or kqueue fd). Returns -1 on failure. */
    def create()(using AllowUnsafe): Int

    /** Register one-shot read interest on `fd`. `scratch` is the per-driver [[PollScratch]], whose `armBuf` field (epoll) or
      * `kqueueData.armBuf` field (kqueue) is used as the reused arm buffer. Owned by the change worker (single owner: drainChanges runs on
      * one worker). Returns the underlying register syscall rc (<0 = failure).
      */
    def registerRead(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int

    /** Register one-shot write interest on `fd`. `scratch` is the per-driver [[PollScratch]] (see [[registerRead]]). Returns the underlying
      * register syscall rc (<0 = failure).
      */
    def registerWrite(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int

    /** Re-arm the still-desired direction(s) on `fd` after a readiness event fired, given which directions the event delivered (`firedRead` /
      * `firedWrite`). This exists for epoll only: epoll carries ONE interest mask per fd plus `EPOLLONESHOT`, so when any direction fires the
      * kernel disables the WHOLE fd, dropping the OTHER direction's still-pending interest. The epoll arm re-arms that survivor here so a read
      * parked next to a writable (and vice versa) is not starved. kqueue registers read and write as independent one-shot filters, so a fired
      * filter never disturbs the other and this is a no-op there. The fired direction is NOT re-armed (the driver re-expresses continued read
      * interest with a fresh [[registerRead]], and a writable is one-shot), so a consumed direction never busy-loops. Runs on the driver's
      * serial interest-change worker, in submission order with [[registerRead]] / [[registerWrite]] / [[deregister]]. `scratch` is the
      * per-driver [[PollScratch]] (see [[registerRead]]).
      */
    def rearm(pollerFd: Int, fd: Int, firedRead: Boolean, firedWrite: Boolean, scratch: PollScratch)(using AllowUnsafe, Frame): Unit

    /** Remove `fd` from the poller. Epoll issues `EPOLL_CTL_DEL`; kqueue deletes both filters via `kevent`. `scratch` is the per-driver
      * [[PollScratch]] (see [[registerRead]]).
      */
    def deregister(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit

    /** Fill the `scratch.fds` and `scratch.flags` arrays with decoded event data after a bounded `timeoutMs` wait. The `@Ffi.blocking`
      * `epoll_wait` / `kevent` runs inline on JVM/Native (fiber is already `done()` on return) and on a libuv worker on JS (fiber is
      * genuinely pending). The driver's `while`-loop body consumes it via `done()`/`poll()` or `onComplete`; it never calls `.safe.get`.
      *
      * `scratch.eventsBuffer` (epoll) or `scratch.kqueueData.get.eventsBuffer` (kqueue) is the reused raw event buffer, filled in-place.
      * Returns the ready count `n` (0 if no events, negative on error). Ownership: `eventsBuffer`, `fds`, and `flags` in `scratch` are
      * owned exclusively by the poll loop carrier; `armBuf` is owned exclusively by the change worker. The two workers never share a slot.
      */
    def poll(pollerFd: Int, timeoutMs: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Fiber.Unsafe[Int, Any]

    /** Arm the poll-loop wakeup so a [[wake]] call makes a parked [[poll]] return promptly (instead of waiting out the bounded timeout). Called
      * once at driver start, after [[newPollScratch]]. epoll: create an eventfd and register it in the epoll set for read interest, storing it
      * on `scratch.wakeFd`. kqueue: register an `EVFILT_USER` filter (EV_ADD | EV_CLEAR) on the fixed `scratch.wakeUserIdent`. Initializes the
      * scratch wake buffers. Returns true on success; false (best-effort) if the wakeup could not be armed, in which case the driver still works
      * via the bounded-park drain (only the prompt-wake latency improvement is lost). Idempotent is NOT required (called exactly once at start).
      */
    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean

    /** Trigger the poll-loop wakeup: make a parked [[poll]] on `pollerFd` return now so the driver drains its change/engine FIFOs without waiting
      * out the bounded park. Thread-safe and callable from any carrier (it is the cross-carrier wake): epoll writes the eventfd counter (an atomic
      * syscall, no shared buffer); kqueue submits a one-element `NOTE_TRIGGER` changelist via the non-blocking `keventNow` into `scratch.wakeArmBuf`
      * (the caller serializes access to that buffer through the driver's `wakePending` CAS, so at most one trigger is in flight). A no-op if the
      * wakeup was not armed ([[registerWake]] returned false) or `scratch.wakeFd`/the filter is gone after close.
      */
    def wake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit

    /** Consume the wakeup signal after a [[poll]] returned it, so it does not immediately re-fire. epoll: read-drain the eventfd counter into
      * `scratch.wakeDrainBuf` (with EFD_NONBLOCK a drained counter returns EAGAIN, ending the drain). kqueue: a no-op (EV_CLEAR auto-resets the
      * EVFILT_USER trigger on delivery). The poll loop calls this when a decoded event matches the wake fd/ident; the wake event carries no
      * connection work, so after draining it the loop just proceeds to its FIFO drain.
      */
    def drainWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit

    /** True when the decoded event fd/ident at the given decoded value is the wakeup signal (epoll: equals `scratch.wakeFd`; kqueue: equals
      * `scratch.wakeUserIdent`). The poll loop uses it in `drainReady` to recognize and consume the wake event rather than dispatching it as a
      * socket readiness.
      */
    def isWakeFd(fd: Int, scratch: PollScratch): Boolean

    /** Close the epoll eventfd (the wakeup fd) if one was created. Called from the driver close path on the poll-loop carrier's terminal exit (or
      * the never-started close), alongside the scratch free. kqueue has no wake fd (the EVFILT_USER filter is released when the kqueue fd closes),
      * so this is a no-op there.
      */
    def closeWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit

    /** Allocate the per-driver poll scratch (events buffer, fds/flags arrays, arm buffer, and any backend-specific extras). Called once at
      * driver init. The scratch is closed when the driver closes.
      */
    def newPollScratch()(using AllowUnsafe): PollScratch

    /** Close the poller fd.
      *
      * The `@Ffi.blocking` close fiber completes inline on JVM/Native; the fd is released by the OS on the downcall itself. The fiber result is
      * discarded (the close rc is not actionable at shutdown). On JS the fiber is genuinely pending but the OS close has already been dispatched;
      * the fiber is GC'd when the callback fires.
      */
    def close(pollerFd: Int)(using AllowUnsafe, Frame): Unit

    /** The maximum number of readiness events drained per `poll` call, and the size of the reused per-driver event/fd/flag buffers. One home for
      * both poller backends (epoll and kqueue), which size their scratch buffers and bound their `epoll_wait` / `kevent` batch to this value.
      */
    private[posix] val MaxEvents = 64

end PollerBackend

/** Kqueue-specific buffers held inside [[PollScratch]], allocated once at driver init and closed when the driver closes.
  *
  * `keventNow` and `kevent` take raw `Buffer[Byte]` changelists / eventlists (the same `Buffer[Byte]` the epoll arm uses), sized as a whole
  * number of `KEvent.size`-byte slots and accessed field-by-field through the manual [[KEvent$]] codec; a generic `Buffer[KEvent]` would box
  * every `Long` field on each poll-hot-path read/write. These are allocated once per driver (via `KqueuePollerBackend.newPollScratch`) and
  * closed via `PollScratch.close`. Owned by the same carriers as the corresponding `PollScratch` fields: `armBuf` by the change worker,
  * `eventsBuffer` and `emptyChangelist` by the poll loop.
  *
  * `pollMemoMs` and `pollMemoTs` form a 1-element poll-timeout memo keyed on `timeoutMs`. They are written and read exclusively by the
  * single poll-loop carrier that owns this scratch (one per driver). No concurrent access is possible: the poll loop runs on one carrier,
  * and no other fiber touches these fields. A sentinel of -1 on `pollMemoMs` means no entry is cached yet.
  */
final private[net] class KqueuePollData(
    val armBuf: kyo.ffi.Buffer[Byte],
    val eventsBuffer: kyo.ffi.Buffer[Byte],
    val emptyChangelist: kyo.ffi.Buffer[Byte]
):
    // Poll-loop-carrier-owned memo: one entry per driver. Single owner: the poll-loop carrier for this driver's scratch.
    var pollMemoMs: Int      = -1
    var pollMemoTs: Timespec = null.asInstanceOf[Timespec]

    def close()(using AllowUnsafe): Unit =
        armBuf.close()
        eventsBuffer.close()
        emptyChangelist.close()
    end close
end KqueuePollData

/** Per-driver reused poll scratch, allocated once at driver init and closed when the driver closes.
  *
  * Ownership: `eventsBuffer`, `fds`, and `flags` are owned exclusively by the poll loop carrier (used only during `pollLoop` and
  * `drainReady`, which run on the same poll-loop fiber). `armBuf` is owned exclusively by the change worker (used only during
  * `drainChanges` and `dispatchCmd`, which run on the single change-worker fiber). The two workers never share a scratch slot.
  * `kqueueData` is `Present` on kqueue hosts and `Absent` on epoll hosts; when present its buffers follow the same ownership as the
  * corresponding `PollScratch` fields. Both `eventsBuffer` and `armBuf` are off-heap and must be closed when the driver closes
  * (via [[close]]); `fds` and `flags` are heap arrays, collected by GC.
  *
  * On epoll: `eventsBuffer` holds `MaxEvents * EpollEvent.size` bytes; `armBuf` holds `EpollEvent.size` bytes; `kqueueData` is `Absent`.
  * On kqueue: `eventsBuffer` and `armBuf` are zero-element `Buffer[Byte]` sentinels (not used by kqueue code); the actual `Buffer[Byte]`
  * changelist / eventlist buffers (sized in `KEvent.size`-byte slots) live in `kqueueData` (Present).
  */
final private[net] class PollScratch(
    val eventsBuffer: kyo.ffi.Buffer[Byte],
    val fds: Array[Int],
    val flags: Array[Int],
    val armBuf: kyo.ffi.Buffer[Byte],
    val kqueueData: Maybe[KqueuePollData]
):
    /** Per-driver fd -> currently-armed epoll direction bits (a union of EPOLLIN / EPOLLOUT). Used only by the epoll arm: mutated by
      * registerRead / registerWrite / rearm / deregister, every one of which the driver runs on its serial interest-change worker, so a plain
      * (non-concurrent) map is correct and the interest of one driver's fds never leaks into another driver's. A heap map, collected by GC.
      */
    val epollDesired: java.util.HashMap[Int, Int] = new java.util.HashMap[Int, Int]()

    /** Poll-loop wakeup state, initialized by [[PollerBackend.registerWake]] at driver start.
      *
      * epoll: `wakeFd` is the eventfd (created with EFD_NONBLOCK | EFD_CLOEXEC, registered in the epoll set for read interest); `wakeDrainBuf`
      * is the reused 8-byte buffer the poll loop reads the eventfd counter into when the wake fires. kqueue: `wakeFd` stays -1 (EVFILT_USER is
      * keyed on a fixed ident on the kqueue fd, not a separate fd); `wakeArmBuf` is the reused one-element changelist the trigger encodes the
      * NOTE_TRIGGER into. The wake buffers are off-heap and freed in [[close]]. Single-writer per buffer: the poll-loop carrier owns
      * `wakeDrainBuf`; the wake trigger owns `wakeArmBuf`, serialized by the driver's `wakePending` CAS so at most one trigger is in flight.
      */
    var wakeFd: Int                        = -1
    var wakeDrainBuf: kyo.ffi.Buffer[Byte] = null
    var wakeArmBuf: kyo.ffi.Buffer[Byte]   = null

    /** The fixed kqueue `EVFILT_USER` ident used as the wakeup key. Distinct from any socket fd (a large sentinel), so a delivered wake event
      * is recognized and consumed by the poll loop rather than dispatched to a connection. Unused on epoll.
      */
    val wakeUserIdent: Long = 0x7fffffffL

    def close()(using AllowUnsafe): Unit =
        eventsBuffer.close()
        armBuf.close()
        kqueueData.foreach(_.close())
        if wakeDrainBuf != null then wakeDrainBuf.close()
        if wakeArmBuf != null then wakeArmBuf.close()
        // fds and flags are heap arrays, collected by GC. wakeFd (epoll eventfd) is closed by the backend's closeWake via the driver close path.
    end close
end PollScratch

/** Flag bits packed into `PollScratch.flags(i)` for decoded poll events. */
private[net] object PollFlags:
    val Read: Int  = 1
    val Write: Int = 2
    val Error: Int = 4
end PollFlags

private[net] object PollerBackend:

    /** Select the backend for the host OS: epoll on Linux, kqueue on macOS/BSD (the same choice the legacy `PollerBackend.default` made). */
    def default()(using AllowUnsafe): PollerBackend =
        if PosixConstants.isLinux then EpollPollerBackend
        else KqueuePollerBackend
end PollerBackend
