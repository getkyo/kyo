package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** macOS/BSD kqueue arm of [[PollerBackend]], routed through the kyo-ffi [[KqueueBindings]].
  *
  * Unlike epoll there is no separate `epoll_ctl`: registration, deregistration, and polling all go through `kevent`. Read interest is
  * registered edge-triggered (`EV_ADD | EV_CLEAR`): the filter auto-resets after delivery (fires once per empty->ready transition) and stays
  * in the interest set without re-registration. Write interest is registered with `EV_ADD | EV_CLEAR | EV_ENABLE` and toggled off with
  * `EV_DISABLE` after a write completes to suppress spurious wakeups while no write is pending. Readiness is decoded from the returned event's
  * `filter` (`EVFILT_READ` / `EVFILT_WRITE`), never a bitmask; the watched fd is the event's `ident`.
  *
  * `kqueue` is a plain (non-blocking) downcall. Interest changes ([[registerRead]] / [[registerWrite]] / [[deregister]]) are batched into a
  * changelist and submitted atomically alongside the poll wait: `drainChanges` accumulates up to `MaxChanges * KEvent.size` bytes into
  * `scratch.kqueueData.get.changelistBuf` and passes it with `nChanges` to [[poll]], which forwards them to `kevent`. This reduces K interest
  * changes to 1 syscall per poll cycle. [[poll]] uses the `@Ffi.blocking` `kevent` (a negative timeout that waits indefinitely for events);
  * the poll loop suspends across that indefinite wait.
  *
  * Each `struct kevent` is encoded into and decoded out of a raw `Buffer[Byte]` through the manual [[KEvent$]] codec (the changelist and
  * eventlist buffers in `scratch.kqueueData.get`, the per-driver [[KqueuePollData]] allocated by [[newPollScratch]]), exactly as the epoll arm
  * marshals `struct epoll_event`. The poll loop reads only the `ident`, `filter`, and `flags` fields it needs via the codec's primitive
  * readers, so no `KEvent` object is allocated and no `Long` field is boxed per event. No per-call off-heap allocation occurs on the hot path.
  *
  * The poll path uses a 1-element memo keyed on `timeoutMs`. The memo lives in the per-driver [[KqueuePollData]] inside the passed-in
  * [[PollScratch]] (field `kqueueData`), which is allocated once per driver via [[newPollScratch]] and owned exclusively by that driver's
  * poll-loop carrier. The poll loop always calls `poll()` with the same `timeoutMs` (-1 for indefinite), so after the first call every
  * subsequent poll reuses the cached [[Timespec]] with no per-poll allocation. `timeoutMs < 0` maps to [[IndefiniteTimeout]] (a large
  * but valid [[Timespec]]) rather than a C NULL pointer, because the kyo-ffi kevent binding does not accept a null reference. The
  * [[EVFILT_USER]] wake event returns the kevent call promptly regardless of the timeout. Storing the memo in the per-driver scratch
  * makes it genuinely single-owner: one driver's poll-loop carrier never touches another driver's [[KqueuePollData]].
  */
private[net] object KqueuePollerBackend extends PollerBackend:

    // The kyo-ffi kevent binding takes Timespec by value, not by pointer, so NULL (the C sentinel for "block indefinitely") cannot be
    // passed directly. A very large but valid Timespec approximates indefinite: the EVFILT_USER wake event fires immediately regardless
    // of the timeout, so the practical behavior is identical to NULL for the poll loop's use case. Int.MaxValue / 1000 seconds ~= 24.8 days.
    private val IndefiniteTimeout: Timespec = Timespec(Int.MaxValue.toLong / 1000L, 0L)

    private def kq(using AllowUnsafe): KqueueBindings = Ffi.load[KqueueBindings]

    def create()(using AllowUnsafe): Int = kq.kqueue().value

    def registerRead(pollerFd: Int, fd: Int, id: Long, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        // EV_CLEAR: edge-triggered (auto-reset after delivery, filter stays armed). EV_ADD registers or re-enables if previously deleted. udata=id
        // tags the knote with the owning handle id so a stale event for a recycled fd (whose id no longer matches) is dropped by the poll loop.
        change(pollerFd, fd, PosixConstants.EVFILT_READ, (PosixConstants.EV_ADD | PosixConstants.EV_CLEAR).toShort, id, scratch.kqueueData)

    def registerWrite(pollerFd: Int, fd: Int, id: Long, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        // EV_CLEAR + EV_ENABLE: register enabled. After write completes, disableWrite issues EV_DISABLE to suppress spurious wakeups. udata=id tags
        // the knote with the owning handle id (the stale-event discriminator; EV_ADD on a fresh or recycled fd sets the current owner's id).
        change(
            pollerFd,
            fd,
            PosixConstants.EVFILT_WRITE,
            (PosixConstants.EV_ADD | PosixConstants.EV_CLEAR | PosixConstants.EV_ENABLE).toShort,
            id,
            scratch.kqueueData
        )

    def deregister(pollerFd: Int, fd: Int, fdClosing: Boolean, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        if fdClosing then
            // The fd is already closed. The OS auto-removes all kqueue filters on close; issuing EV_DELETE on the fd number would target a
            // recycled fd and must be skipped.
            ()
        else
            // Immediate delete: deregister must remove filters from the kernel BEFORE the next poll so that stale events from this fd are not
            // delivered. Uses changeNow (immediate keventNow) rather than the batch change path, because the batch is consumed at poll time
            // and a batch-path deregister could race with an event that fires before the next kevent call. The two changeNow calls are
            // sequential (never concurrent: deregister runs on the single poll-loop carrier in dispatchCmd), so armBuf is safe to reuse.
            // EV_DELETE matches the knote by ident+filter, so udata is irrelevant here; pass the fd as an inert value.
            discard(changeNow(pollerFd, fd, PosixConstants.EVFILT_READ, PosixConstants.EV_DELETE, fd.toLong, scratch.kqueueData))
            discard(changeNow(pollerFd, fd, PosixConstants.EVFILT_WRITE, PosixConstants.EV_DELETE, fd.toLong, scratch.kqueueData))
        end if
    end deregister

    /** Disable the EVFILT_WRITE filter on `fd` without removing it. Called from `dispatchWritable` after the write completes so the
      * write-ready filter does not fire spuriously while no write is pending. The filter remains registered (ready for `EV_ENABLE` when the
      * next awaitWritable arms the fd). Encodes into `changelistBuf` for delivery at the next poll call (the filter stays enabled until
      * the next kevent syscall completes the batch; no spurious event can fire before then since the poll loop is single-carrier).
      */
    override def disableWrite(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // EV_DISABLE matches the existing knote by ident+filter and does not create one, so udata is irrelevant; pass the fd as an inert value.
        discard(change(pollerFd, fd, PosixConstants.EVFILT_WRITE, PosixConstants.EV_DISABLE, fd.toLong, scratch.kqueueData))

    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean =
        // Register the EVFILT_USER wake filter on the fixed wakeUserIdent with EV_CLEAR (auto-reset on delivery). No wake fd: the filter lives on
        // the kqueue fd and is released when it closes. wakeArmBuf is the reused one-element changelist the trigger encodes NOTE_TRIGGER into.
        scratch.wakeArmBuf = Buffer.alloc[Byte](KEvent.size)
        val emptyEvents = Buffer.alloc[Byte](0)
        KEvent.encodeUser(scratch.wakeArmBuf, scratch.wakeUserIdent, (PosixConstants.EV_ADD | PosixConstants.EV_CLEAR).toShort, 0)
        val rc = kq.keventNow(pollerFd, scratch.wakeArmBuf, 1, emptyEvents, 0, ZeroTimeout).value
        emptyEvents.close()
        rc >= 0
    end registerWake

    def wake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // Fire the EVFILT_USER filter with NOTE_TRIGGER so a parked kevent returns. The caller serializes access to wakeArmBuf via the driver's
        // wakePending CAS (at most one trigger in flight), so re-encoding it here is race-free. keventNow is the non-blocking register-only syscall.
        if scratch.wakeArmBuf != null then
            val emptyEvents = Buffer.alloc[Byte](0)
            KEvent.encodeUser(scratch.wakeArmBuf, scratch.wakeUserIdent, 0, PosixConstants.NOTE_TRIGGER)
            discard(kq.keventNow(pollerFd, scratch.wakeArmBuf, 1, emptyEvents, 0, ZeroTimeout))
            emptyEvents.close()
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

    /** Encode an interest change into the batch changelist (when kqData is Present) or submit immediately via `keventNow` (when Absent).
      *
      * When `kqData` is `Present`, the change is appended to `kqData.changelistBuf` at slot `kqData.nChanges` and `nChanges` is incremented.
      * The change is NOT submitted immediately; it is batched with other changes accumulated during this `drainChanges` cycle and submitted
      * atomically in the next `backend.poll` call. This reduces K interest-change syscalls per poll cycle to 1 kevent syscall.
      * Returns 0 (success assumed; the actual rc comes back from the `kevent` poll call that submits the batch).
      *
      * When `kqData` is `Absent` (test callers that build a driver without a kqueue-specific scratch), submits immediately via a one-element
      * `keventNow` with fresh per-call allocation. This preserves the test path behavior without requiring a full scratch to be set up.
      */
    private def change(pollerFd: Int, fd: Int, filter: Short, flags: Short, udata: Long, kqData: Maybe[KqueuePollData])(using
        AllowUnsafe
    ): Int =
        kqData match
            case Present(data) =>
                val slot = data.nChanges
                KEvent.encodeChange(data.changelistBuf, slot, fd, filter, flags, udata)
                data.nChanges = slot + 1
                // changelistBuf is NOT closed here: it is the per-driver reused buffer, freed via PollScratch.close.
                0
            case Absent =>
                val changelist  = Buffer.alloc[Byte](KEvent.size)
                val emptyEvents = Buffer.alloc[Byte](0)
                try
                    KEvent.encodeChange(changelist, 0, fd, filter, flags, udata)
                    kq.keventNow(pollerFd, changelist, 1, emptyEvents, 0, ZeroTimeout).value
                finally
                    changelist.close()
                    emptyEvents.close()
                end try
        end match
    end change

    /** Submit a single one-element change immediately via `keventNow` (without batching). Used by `deregister` and `disableWrite` paths where the
      * change must be applied outside the normal `drainChanges`-to-`poll` batch cycle (or when kqData is Absent in the test path).
      */
    private def changeNow(pollerFd: Int, fd: Int, filter: Short, flags: Short, udata: Long, kqData: Maybe[KqueuePollData])(using
        AllowUnsafe
    ): Int =
        kqData match
            case Present(data) =>
                KEvent.encodeChange(data.armBuf, 0, fd, filter, flags, udata)
                val emptyEvents = Buffer.alloc[Byte](0)
                val rc          = kq.keventNow(pollerFd, data.armBuf, 1, emptyEvents, 0, ZeroTimeout).value
                emptyEvents.close()
                rc
            case Absent =>
                val changelist  = Buffer.alloc[Byte](KEvent.size)
                val emptyEvents = Buffer.alloc[Byte](0)
                try
                    KEvent.encodeChange(changelist, 0, fd, filter, flags, udata)
                    kq.keventNow(pollerFd, changelist, 1, emptyEvents, 0, ZeroTimeout).value
                finally
                    changelist.close()
                    emptyEvents.close()
                end try
        end match
    end changeNow

    def poll(pollerFd: Int, timeoutMs: Int, changelist: kyo.ffi.Buffer[Byte], nChanges: Int, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        scratch.kqueueData match
            case Present(data) => pollWithData(pollerFd, timeoutMs, changelist, nChanges, scratch, data)
            case Absent        => pollFresh(pollerFd, timeoutMs, scratch)

    /** Poll using the caller-owned reused buffers from [[KqueuePollData]]. The changelist batch (built by `drainChanges`) is passed alongside
      * the poll wait so interest changes and event collection happen in one atomic `kevent` syscall. After submission, `data.nChanges`
      * is reset to 0 so subsequent `change()` calls (e.g. from `disableWrite` during `drainReady`) accumulate fresh changes for the next cycle.
      *
      * Reuses the per-driver poll memo in `data` to avoid allocating a new [[Timespec]] on every call. The memo is owned by the poll-loop
      * carrier for this driver's scratch (see [[KqueuePollData]]). Since the poll loop always calls with the same `timeoutMs`, the memo
      * hits every time after the first call.
      */
    private def pollWithData(
        pollerFd: Int,
        timeoutMs: Int,
        changelist: kyo.ffi.Buffer[Byte],
        nChanges: Int,
        scratch: PollScratch,
        data: KqueuePollData
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Int, Any] =
        val timeout =
            if timeoutMs < 0 then IndefiniteTimeout
            else if data.pollMemoMs == timeoutMs then data.pollMemoTs
            else
                val ts = Timespec(timeoutMs.toLong / 1000L, (timeoutMs.toLong % 1000L) * 1000000L)
                data.pollMemoMs = timeoutMs
                data.pollMemoTs = ts
                ts
        // Submit the changelist alongside the wait; interest changes and the blocking wait happen atomically in one kevent syscall.
        data.nChanges = 0 // reset BEFORE the kevent call so disableWrite calls during drainReady accumulate into slots 0+
        val fiber = kq.kevent(pollerFd, changelist, nChanges, data.eventsBuffer, MaxEvents, timeout)
        fiber.poll() match
            case Present(result) =>
                // JVM/Native: the @Ffi.blocking kevent ran synchronously on this carrier, so the fiber is already complete. Decode the result
                // into scratch inline and return a fresh pre-completed fiber carrying the ready count. This deliberately avoids Fiber.Unsafe.map:
                // the poll loop runs on a single long-lived IOTask carrier, and map composes the result through a kyo `< S` step that the carrier's
                // Safepoint trampolines into a Defer the unsafe poll loop never evaluates, so the decode would silently never run and every
                // readiness event would be dropped. eval forces the already-pure Outcome with no `< S` composition, and the returned fiber's value
                // is the Int ready count the poll contract requires (decorators read it; the poll loop reads scratch.readyCount).
                val n =
                    result match
                        case Result.Success(outcome) => decodeReady(outcome.eval, scratch, data)
                        case _ =>
                            scratch.readyCount = 0
                            0
                val completed = Promise.Unsafe.init[Int, Any]()
                completed.completeDiscard(Result.succeed(n))
                completed
            case Absent =>
                // JS: the call is genuinely pending on a libuv worker. Its completion callback runs on a fresh stack rather than the poll loop's
                // Safepoint, so decoding inside map is safe and the decode runs before drainReady reads scratch.readyCount.
                fiber.map(outcome => decodeReady(outcome, scratch, data))
        end match
    end pollWithData

    /** Decode the events the kevent call wrote into `data.eventsBuffer` into the poll scratch (`readyCount`, `fds`, `flags`) and return the ready
      * count. The watched fd is each event's `ident`; readiness is the `filter` (`EVFILT_READ` / `EVFILT_WRITE`), with `EV_EOF` (peer half-close)
      * and `EV_ERROR` (hard error) folded into the flags. The three needed fields are read directly through the codec's primitive readers, so no
      * `KEvent` object is allocated and no `Long` field is boxed. `eventsBuffer` is NOT closed here: it is the caller-owned per-driver reused buffer.
      */
    private def decodeReady(outcome: Ffi.Outcome[Int], scratch: PollScratch, data: KqueuePollData)(using AllowUnsafe): Int =
        val raw   = outcome.value
        val n     = if raw <= 0 then 0 else raw
        val fds   = scratch.fds
        val flags = scratch.flags
        val ids   = scratch.ids
        scratch.readyCount = n
        var i = 0
        while i < n do
            fds(i) = KEvent.ident(data.eventsBuffer, i).toInt
            val evFilter = KEvent.filter(data.eventsBuffer, i)
            val evFlags  = KEvent.flags(data.eventsBuffer, i)
            var f        = 0
            if evFilter == PosixConstants.EVFILT_READ then f |= PollFlags.Read
            if evFilter == PosixConstants.EVFILT_WRITE then f |= PollFlags.Write
            if (evFlags & PosixConstants.EV_EOF) != 0 then f |= PollFlags.Eof
            if (evFlags & PosixConstants.EV_ERROR) != 0 then f |= PollFlags.Error
            flags(i) = f
            // The owning handle id from the knote's udata, for the stale-event guard. EVFILT_USER (the wake event) carries no socket owner, so use
            // the no-check sentinel for it (its udata is the wake ident, not a handle id); read/write events carry the registering handle's id.
            ids(i) = if evFilter == PosixConstants.EVFILT_USER then PollScratch.IdNoCheck else KEvent.udata(data.eventsBuffer, i)
            i += 1
        end while
        n
    end decodeReady

    /** Poll using freshly-allocated per-call buffers. Used when `PollScratch.kqueueData` is `Absent` (test callers that bypass the driver
      * scratch, e.g. `PollerBackendTest` direct calls). No per-driver scratch is available in this path, so a fresh [[Timespec]] is
      * computed on each call and changelist/nChanges are unused (empty changelist is used instead).
      */
    private def pollFresh(pollerFd: Int, timeoutMs: Int, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        val fds         = scratch.fds
        val flags       = scratch.flags
        val ids         = scratch.ids
        val emptyChange = Buffer.alloc[Byte](0)
        val events      = Buffer.alloc[Byte](MaxEvents * KEvent.size)
        val timeout = if timeoutMs < 0 then IndefiniteTimeout else Timespec(timeoutMs.toLong / 1000L, (timeoutMs.toLong % 1000L) * 1000000L)
        kq.kevent(pollerFd, emptyChange, 0, events, MaxEvents, timeout).map { ready =>
            try
                val raw = ready.value
                val n   = if raw <= 0 then 0 else raw
                scratch.readyCount = n
                var i = 0
                while i < n do
                    fds(i) = KEvent.ident(events, i).toInt
                    val evFilter = KEvent.filter(events, i)
                    val evFlags  = KEvent.flags(events, i)
                    var f        = 0
                    if evFilter == PosixConstants.EVFILT_READ then f |= PollFlags.Read
                    if evFilter == PosixConstants.EVFILT_WRITE then f |= PollFlags.Write
                    // EV_EOF signals peer half-close (distinct from EV_ERROR which is a hard error). Both can appear on EVFILT_READ/WRITE.
                    if (evFlags & PosixConstants.EV_EOF) != 0 then f |= PollFlags.Eof
                    if (evFlags & PosixConstants.EV_ERROR) != 0 then f |= PollFlags.Error
                    flags(i) = f
                    ids(i) = if evFilter == PosixConstants.EVFILT_USER then PollScratch.IdNoCheck else KEvent.udata(events, i)
                    i += 1
                end while
                n
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
        // changelistBuf holds up to MaxEvents batched interest changes; the poll call submits the batch atomically with the wait.
        // The byte-level fields (eventsBuffer, armBuf) on PollScratch are zero-element sentinels (not used by kqueue code paths).
        val kqData = new KqueuePollData(
            armBuf = Buffer.alloc[Byte](KEvent.size),                   // reused arm buffer for immediate changeNow calls (deregister path)
            eventsBuffer = Buffer.alloc[Byte](MaxEvents * KEvent.size), // poll eventlist buffer
            changelistBuf = Buffer.alloc[Byte](MaxEvents * KEvent.size) // batch changelist: up to MaxEvents changes per poll cycle
        )
        val sentinelEvents = Buffer.alloc[Byte](0) // unused on kqueue; closed via PollScratch.close
        val sentinelArm    = Buffer.alloc[Byte](0) // unused on kqueue; closed via PollScratch.close
        new PollScratch(
            sentinelEvents,
            new Array[Int](MaxEvents),
            new Array[Int](MaxEvents),
            sentinelArm,
            Present(kqData),
            new Array[Long](MaxEvents)
        )
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
