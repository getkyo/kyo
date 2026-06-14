package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** macOS/BSD kqueue arm of [[PollerBackend]], routed through the kyo-ffi [[KqueueBindings]].
  *
  * Unlike epoll there is no separate `epoll_ctl`: registration, deregistration, and polling all go through `kevent`. Interest is registered
  * one-shot (`EV_ADD | EV_ONESHOT`) so each registration fires at most once, matching the re-arm-on-every-await model the driver assumes.
  * Readiness is decoded from the returned event's `filter` (`EVFILT_READ` / `EVFILT_WRITE`), never a bitmask, so each event carries exactly
  * one direction; the watched fd is the event's `ident`.
  *
  * `kqueue` is a plain (non-blocking) downcall. Interest changes ([[registerRead]] / [[registerWrite]] / [[deregister]]) are register-only
  * `kevent` calls with a zero timeout that never block, so they use the non-blocking synchronous `keventNow` binding (one inline syscall, no
  * carrier park); this keeps them cheap even when the serial interest-change worker drains many of them under load (otherwise a parked-carrier
  * `@blocking` register, serialized one await at a time, collapses registration throughput and stalls large transfers). [[poll]] uses the
  * `@Ffi.blocking` `kevent` (a non-zero timeout that genuinely waits for events); the poll loop suspends across that bounded wait.
  *
  * Each `struct kevent` is encoded into and decoded out of a raw `Buffer[Byte]` through the manual [[KEvent$]] codec (the changelist and
  * eventlist buffers in `scratch.kqueueData.get`, the per-driver [[KqueuePollData]] allocated by [[newPollScratch]]), exactly as the epoll arm
  * marshals `struct epoll_event`. The poll loop reads only the `ident`, `filter`, and `flags` fields it needs via the codec's primitive
  * readers, so no `KEvent` object is allocated and no `Long` field is boxed per event. No per-call off-heap allocation occurs on the hot path.
  * [[rearm]] is a no-op: kqueue registers independent one-shot filters, so a fired filter never disturbs the other and no re-arm of the
  * survivor is needed.
  *
  * The poll path uses a 1-element memo keyed on `timeoutMs`. The memo lives in the per-driver [[KqueuePollData]] inside the passed-in
  * [[PollScratch]] (field `kqueueData`), which is allocated once per driver via [[newPollScratch]] and owned exclusively by that driver's
  * poll-loop carrier. The poll loop always calls `poll()` with the same `timeoutMs` (100), so after the first call every subsequent poll
  * reuses the cached [[Timespec]] with no per-poll allocation. Storing the memo in the per-driver scratch makes it genuinely single-owner:
  * one driver's poll-loop carrier never touches another driver's [[KqueuePollData]].
  */
private[net] object KqueuePollerBackend extends PollerBackend:

    private def kq(using AllowUnsafe): KqueueBindings = Ffi.load[KqueueBindings]

    def create()(using AllowUnsafe): Int = kq.kqueue().value

    def registerRead(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        change(pollerFd, fd, PosixConstants.EVFILT_READ, (PosixConstants.EV_ADD | PosixConstants.EV_ONESHOT).toShort, scratch.kqueueData)

    def registerWrite(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        change(pollerFd, fd, PosixConstants.EVFILT_WRITE, (PosixConstants.EV_ADD | PosixConstants.EV_ONESHOT).toShort, scratch.kqueueData)

    def deregister(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // Delete both read and write filters; a missing filter (already fired, or never registered) returns an error we ignore (EV_DELETE
        // on an absent filter is harmless). The closed fd auto-removes any surviving one-shot filter regardless.
        // Both calls are sequential (never concurrent: deregister runs on the single change worker), so the arm buffer is safe to reuse.
        discard(change(pollerFd, fd, PosixConstants.EVFILT_READ, PosixConstants.EV_DELETE, scratch.kqueueData))
        discard(change(pollerFd, fd, PosixConstants.EVFILT_WRITE, PosixConstants.EV_DELETE, scratch.kqueueData))
    end deregister

    /** No-op on kqueue: read and write are registered as independent one-shot filters (`EVFILT_READ` / `EVFILT_WRITE`), so a fired filter never
      * disables the survivor and there is nothing to re-arm. The re-arm seam exists for epoll, where one `EPOLLONESHOT` interest mask covers both
      * directions and the kernel disables the whole fd on any fire.
      */
    override def rearm(pollerFd: Int, fd: Int, firedRead: Boolean, firedWrite: Boolean, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Unit =
        ()

    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean =
        // Register the EVFILT_USER wake filter on the fixed wakeUserIdent with EV_CLEAR (auto-reset on delivery). No wake fd: the filter lives on
        // the kqueue fd and is released when it closes. wakeArmBuf is the reused one-element changelist the trigger encodes NOTE_TRIGGER into.
        scratch.wakeArmBuf = Buffer.alloc[Byte](KEvent.size)
        val empty = scratch.kqueueData match
            case Present(data) => data.emptyChangelist
            case Absent        => Buffer.alloc[Byte](0)
        KEvent.encodeUser(scratch.wakeArmBuf, scratch.wakeUserIdent, (PosixConstants.EV_ADD | PosixConstants.EV_CLEAR).toShort, 0)
        val rc = kq.keventNow(pollerFd, scratch.wakeArmBuf, 1, empty, 0, ZeroTimeout).value
        if scratch.kqueueData.isEmpty then empty.close()
        rc >= 0
    end registerWake

    def wake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // Fire the EVFILT_USER filter with NOTE_TRIGGER so a parked kevent returns. The caller serializes access to wakeArmBuf via the driver's
        // wakePending CAS (at most one trigger in flight), so re-encoding it here is race-free. keventNow is the non-blocking register-only syscall.
        if scratch.wakeArmBuf != null then
            val empty = scratch.kqueueData match
                case Present(data) => data.emptyChangelist
                case Absent        => Buffer.alloc[Byte](0)
            KEvent.encodeUser(scratch.wakeArmBuf, scratch.wakeUserIdent, 0, PosixConstants.NOTE_TRIGGER)
            discard(kq.keventNow(pollerFd, scratch.wakeArmBuf, 1, empty, 0, ZeroTimeout))
            if scratch.kqueueData.isEmpty then empty.close()
        end if
    end wake

    def drainWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // No-op: EV_CLEAR auto-resets the EVFILT_USER trigger state when the event is delivered, so there is nothing to drain (the epoll eventfd
        // counter analog).
        ()

    def isWakeFd(fd: Int, scratch: PollScratch): Boolean = fd.toLong == scratch.wakeUserIdent

    def closeWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // No wake fd on kqueue: the EVFILT_USER filter is released when the kqueue fd closes. wakeArmBuf is freed via PollScratch.close.
        ()

    /** Submit a single one-element register-only changelist and return the `kevent` rc. Uses the non-blocking synchronous `keventNow` (the
      * change applies and returns immediately at `timeout = 0`); it never blocks, so it does not park a carrier even when many changes are
      * drained serially by the interest-change worker.
      *
      * When `kqData` is `Present`, the caller-owned arm buffer (`kqData.armBuf`) and empty events buffer (`kqData.emptyChangelist`) are used
      * directly, with no per-call allocation. The arm buffer is written in-place; neither buffer is closed here (they are per-driver reused
      * buffers, freed via [[PollScratch.close]] when the driver closes).
      *
      * When `kqData` is `Absent` (test callers that build a driver without a kqueue-specific scratch), a fresh one-element changelist and
      * zero-element empty events buffer are allocated per call and closed in the `try/finally` (the baseline per-call path from the old code).
      */
    private def change(pollerFd: Int, fd: Int, filter: Short, flags: Short, kqData: Maybe[KqueuePollData])(using AllowUnsafe): Int =
        kqData match
            case Present(data) =>
                KEvent.encodeChange(data.armBuf, fd, filter, flags)
                // ZeroTimeout is a shared immutable constant (the change path always uses a zero timeout). The FFI marshals it by value,
                // so sharing across callers is safe.
                kq.keventNow(pollerFd, data.armBuf, 1, data.emptyChangelist, 0, ZeroTimeout).value
                // armBuf and emptyChangelist are NOT closed here: they are the per-driver reused buffers, freed via PollScratch.close.
            case Absent =>
                val changelist  = Buffer.alloc[Byte](KEvent.size)
                val emptyEvents = Buffer.alloc[Byte](0)
                try
                    KEvent.encodeChange(changelist, fd, filter, flags)
                    kq.keventNow(pollerFd, changelist, 1, emptyEvents, 0, ZeroTimeout).value
                finally
                    changelist.close()
                    emptyEvents.close()
                end try
        end match
    end change

    def poll(pollerFd: Int, timeoutMs: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Fiber.Unsafe[Int, Any] =
        scratch.kqueueData match
            case Present(data) => pollWithData(pollerFd, timeoutMs, scratch.fds, scratch.flags, data)
            case Absent        => pollFresh(pollerFd, timeoutMs, scratch.fds, scratch.flags)

    /** Poll using the caller-owned reused buffers from [[KqueuePollData]]. The events buffer is filled in-place; the empty changelist is
      * passed for the zero-nchanges argument. Neither buffer is closed here: they are the per-driver reused buffers, freed when the driver
      * closes via [[PollScratch.close]].
      *
      * Reuses the per-driver poll memo in `data` to avoid allocating a new [[Timespec]] on every call. The memo is owned by the poll-loop
      * carrier for this driver's scratch (see [[KqueuePollData]]). Since the poll loop always calls with the same `timeoutMs`, the memo
      * hits every time after the first call.
      */
    private def pollWithData(pollerFd: Int, timeoutMs: Int, fds: Array[Int], flags: Array[Int], data: KqueuePollData)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        val timeout =
            if data.pollMemoMs == timeoutMs then data.pollMemoTs
            else
                val ts = Timespec(timeoutMs.toLong / 1000L, (timeoutMs.toLong % 1000L) * 1000000L)
                data.pollMemoMs = timeoutMs
                data.pollMemoTs = ts
                ts
        kq.kevent(pollerFd, data.emptyChangelist, 0, data.eventsBuffer, MaxEvents, timeout).map { ready =>
            val n = ready.value
            if n <= 0 then 0
            else
                var i = 0
                while i < n do
                    // Read the three needed fields directly from the byte buffer via the KEvent codec; no per-event KEvent is allocated
                    // and no Long field is boxed.
                    fds(i) = KEvent.ident(data.eventsBuffer, i).toInt
                    val evFilter = KEvent.filter(data.eventsBuffer, i)
                    val evFlags  = KEvent.flags(data.eventsBuffer, i)
                    var f        = 0
                    if evFilter == PosixConstants.EVFILT_READ then f |= PollFlags.Read
                    if evFilter == PosixConstants.EVFILT_WRITE then f |= PollFlags.Write
                    if (evFlags & PosixConstants.EV_ERROR) != 0 || (evFlags & PosixConstants.EV_EOF) != 0 then f |= PollFlags.Error
                    flags(i) = f
                    i += 1
                end while
                n
            end if
            // eventsBuffer and emptyChangelist are NOT closed here: they are the caller-owned per-driver reused buffers.
        }
    end pollWithData

    /** Poll using freshly-allocated per-call buffers. Used when `PollScratch.kqueueData` is `Absent` (test callers that bypass the driver
      * scratch, e.g. `PollerBackendTest` direct calls). No per-driver scratch is available in this path, so a fresh [[Timespec]] is
      * computed on each call.
      */
    private def pollFresh(pollerFd: Int, timeoutMs: Int, fds: Array[Int], flags: Array[Int])(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        val emptyChange = Buffer.alloc[Byte](0)
        val events      = Buffer.alloc[Byte](MaxEvents * KEvent.size)
        val timeout     = Timespec(timeoutMs.toLong / 1000L, (timeoutMs.toLong % 1000L) * 1000000L)
        kq.kevent(pollerFd, emptyChange, 0, events, MaxEvents, timeout).map { ready =>
            try
                val n = ready.value
                if n <= 0 then 0
                else
                    var i = 0
                    while i < n do
                        fds(i) = KEvent.ident(events, i).toInt
                        val evFilter = KEvent.filter(events, i)
                        val evFlags  = KEvent.flags(events, i)
                        var f        = 0
                        if evFilter == PosixConstants.EVFILT_READ then f |= PollFlags.Read
                        if evFilter == PosixConstants.EVFILT_WRITE then f |= PollFlags.Write
                        if (evFlags & PosixConstants.EV_ERROR) != 0 || (evFlags & PosixConstants.EV_EOF) != 0 then f |= PollFlags.Error
                        flags(i) = f
                        i += 1
                    end while
                    n
                end if
            finally
                emptyChange.close()
                events.close()
            end try
        }
    end pollFresh

    def newPollScratch()(using AllowUnsafe): PollScratch =
        // Unsafe: off-heap allocations at driver init (called once; closed in driver.close via PollScratch.close).
        // kqueueData holds the raw Buffer[Byte] changelist/eventlist buffers passed to keventNow/kevent, sized in KEvent.size-byte slots and
        // accessed through the KEvent codec (no Buffer[KEvent] struct round-trip, so no per-event Long boxing).
        // The byte-level fields (eventsBuffer, armBuf) on PollScratch are zero-element sentinels (not used by kqueue code paths).
        val kqData = new KqueuePollData(
            armBuf = Buffer.alloc[Byte](KEvent.size),
            eventsBuffer = Buffer.alloc[Byte](MaxEvents * KEvent.size),
            emptyChangelist = Buffer.alloc[Byte](0) // zero-element: nchanges=0 in kevent poll calls
        )
        val sentinelEvents = Buffer.alloc[Byte](0) // unused on kqueue; closed via PollScratch.close
        val sentinelArm    = Buffer.alloc[Byte](0) // unused on kqueue; closed via PollScratch.close
        new PollScratch(sentinelEvents, new Array[Int](MaxEvents), new Array[Int](MaxEvents), sentinelArm, Present(kqData))
    end newPollScratch

    def close(pollerFd: Int)(using AllowUnsafe, Frame): Unit =
        // The close-discard rationale lives once on PollerBackend.close. MaxEvents is the inherited PollerBackend.MaxEvents.
        discard(kq.close(pollerFd))

    /** Shared immutable zero-timeout constant for the change path (register-only kevent calls always use a zero timeout). A single allocation
      * is reused across all interest-change calls on all kqueue drivers in the process. Read-only: safe to share across carriers because the
      * FFI binding marshals it by value (reads the struct fields, writes to the kernel). The poll path uses the per-driver memo in
      * [[KqueuePollData]] to avoid per-call allocation without requiring a mutable struct.
      */
    private[net] val ZeroTimeout: Timespec = Timespec(0L, 0L)

end KqueuePollerBackend
