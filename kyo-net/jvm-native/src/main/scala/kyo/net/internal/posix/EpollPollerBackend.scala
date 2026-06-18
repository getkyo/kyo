package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Linux epoll arm of [[PollerBackend]], routed through the kyo-ffi [[EpollBindings]].
  *
  * Interest is registered edge-triggered (`EPOLLET | EPOLLRDHUP`): `epoll_ctl(ADD)` arms a fresh fd with `EPOLLIN | EPOLLOUT | EPOLLRDHUP |
  * EPOLLET`. The kernel fires readiness once per empty->ready state transition; the fd stays armed until explicitly deregistered with
  * `epoll_ctl(DEL)`. When a recv fills the read buffer exactly, the driver sets `readMightHaveMore` on the handle and re-dispatches
  * the next read immediately on re-registration (consumer-paced drain) rather than waiting for a new edge that may never arrive. When
  * `eofPending` is set alongside a partial recv, re-dispatch is also forced so the EOF surfaces on the next `awaitRead`. When an edge
  * fires while no pending read is present (the consumer is in a backpressure pause), the driver records the fd in `missedReads` and
  * re-dispatches on the consumer's next `awaitRead`, since `epoll_ctl(MOD)` with the same mask is a no-op that produces no new edge.
  * If the fd is already in the set, the kernel returns `EEXIST` and we re-arm with `epoll_ctl(MOD)` ONLY when the interest mask has
  * actually changed from the previously armed mask (skip the syscall when identical). The watched fd is carried in the event's `data`
  * key so the decoded event can name it without a side table.
  *
  * Unlike kqueue, where `EVFILT_READ` and `EVFILT_WRITE` are INDEPENDENT filters that coexist on one fd, epoll carries ONE interest mask per fd.
  * Registering write while a read was already armed would replace the mask and drop the pending direction's interest. To match kqueue semantics
  * this arm tracks the desired interest per fd in `scratch.epollDesired` and always arms the UNION (`EPOLLIN | EPOLLOUT | EPOLLRDHUP | EPOLLET`
  * when both are pending), so registering one direction never clears the other. The desired-interest map lives on the per-driver
  * [[PollScratch.epollDesired]], so each driver tracks only its own fds: the singleton object holds no cross-driver interest state.
  *
  * `epoll_create1` / `epoll_ctl` are plain (non-blocking) downcalls, so `create`, `registerRead`, `registerWrite`, and `deregister` all return
  * synchronous `Int` or `Unit` with no suspension. `epoll_wait` is `@Ffi.blocking`: [[poll]] returns a `Fiber.Unsafe` that is already `done()`
  * on JVM/Native (inline completion) and genuinely pending on JS (libuv worker thread).
  *
  * All arm/register/deregister/poll methods use the per-driver [[PollScratch]]: `scratch.armBuf` (sized for one EpollEvent) for arm calls,
  * `scratch.eventsBuffer` (sized `MaxEvents * EpollEvent.size`) for poll. No per-call off-heap allocation occurs on the hot path. The arch-
  * dependent `EpollEvent.size` is used at scratch construction time, so the buffer is correctly sized on both x86_64 (12 bytes) and aarch64
  * (16 bytes).
  */
private[net] object EpollPollerBackend extends PollerBackend:

    private def ep(using AllowUnsafe): EpollBindings = Ffi.load[EpollBindings]

    def create()(using AllowUnsafe): Int = ep.epoll_create1(0).value.toInt

    def registerRead(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        val prevUnion = scratch.epollDesired.getOrDefault(fd, 0)
        val union     = addInterest(scratch, fd, PosixConstants.EPOLLIN)
        arm(pollerFd, fd, union, prevUnion, scratch.armBuf)
    end registerRead

    def registerWrite(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        val prevUnion = scratch.epollDesired.getOrDefault(fd, 0)
        val union     = addInterest(scratch, fd, PosixConstants.EPOLLOUT)
        arm(pollerFd, fd, union, prevUnion, scratch.armBuf)
    end registerWrite

    /** OR `bit` into `fd`'s desired interest in this driver's scratch and return the new union. */
    private def addInterest(scratch: PollScratch, fd: Int, bit: Int): Int =
        val prev    = scratch.epollDesired.getOrDefault(fd, 0)
        val updated = prev | bit
        scratch.epollDesired.put(fd, updated)
        updated
    end addInterest

    /** Arm `fd` for `interest | EPOLLET | EPOLLRDHUP`: try `ADD`, and on `EEXIST` re-arm with `MOD` ONLY when the effective interest mask changed
      * from `prevUnion` (skip the syscall when the mask is identical, avoiding a wasted `epoll_ctl`). `armBuf` is the caller-owned arm buffer (sized for one
      * EpollEvent, from `PollScratch.armBuf`), reused across all arm calls; no per-call allocation. The ET flags are always ORed in so the fd is
      * never armed in level-triggered mode by mistake. The buffer is NOT closed here: it persists for the driver lifetime and is freed via
      * [[PollScratch.close]].
      */
    private def arm(pollerFd: Int, fd: Int, interest: Int, prevUnion: Int, armBuf: Buffer[Byte])(using AllowUnsafe): Int =
        val etInterest = interest | PosixConstants.EPOLLET | PosixConstants.EPOLLRDHUP
        EpollEvent.encode(armBuf, 0, EpollEvent(etInterest, fd.toLong))
        val added = ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_ADD, fd, armBuf)
        if added.value < 0 && added.errorCode == EEXIST then
            // Skip MOD if the effective mask (after adding ET flags) matches what was previously armed.
            val prevEtInterest = prevUnion | PosixConstants.EPOLLET | PosixConstants.EPOLLRDHUP
            if etInterest == prevEtInterest then 0
            else ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_MOD, fd, armBuf).value.toInt
            end if
        else added.value.toInt
        end if
    end arm

    def deregister(pollerFd: Int, fd: Int, fdClosing: Boolean, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        discard(scratch.epollDesired.remove(fd))
        if !fdClosing then
            // When the fd is still open, explicitly remove it from the epoll set. When fdClosing, the OS auto-removes it on close;
            // an EPOLL_CTL_DEL on a recycled fd number would target the wrong entry.
            EpollEvent.encode(scratch.armBuf, 0, EpollEvent(0, 0L))
            discard(ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_DEL, fd, scratch.armBuf))
        end if
        // scratch.armBuf is NOT closed here: it is the caller-owned per-driver reused buffer, freed via PollScratch.close.
    end deregister

    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean =
        // Create the eventfd counter and register it in the epoll set for LEVEL-triggered read interest (no EPOLLONESHOT): it stays armed across
        // polls, so every wake write is delivered and the loop drains the counter each time. A dedicated arm buffer is used (the eventfd is not a
        // socket fd and must not enter scratch.epollDesired / the one-shot re-arm machinery).
        val efd = ep.eventfd(0, PosixConstants.EFD_NONBLOCK | PosixConstants.EFD_CLOEXEC)
        if efd.value < 0 then false
        else
            scratch.wakeFd = efd.value.toInt
            scratch.wakeDrainBuf = Buffer.alloc[Byte](8) // eventfd counter is a uint64
            val armBuf = Buffer.alloc[Byte](EpollEvent.size)
            scratch.wakeArmBuf = armBuf // owned by registerWake/close only; reused for the (single) wake registration
            EpollEvent.encode(armBuf, 0, EpollEvent(PosixConstants.EPOLLIN, efd.value))
            val rc = ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_ADD, efd.value.toInt, armBuf)
            rc.value >= 0
        end if
    end registerWake

    def wake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // Atomic counter increment; thread-safe with no shared buffer, so concurrent callers are safe. Wakes any parked epoll_wait on this set.
        if scratch.wakeFd >= 0 then discard(ep.eventfd_write(scratch.wakeFd, 1L))

    def drainWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        // Read-drain the counter so the level-triggered eventfd does not immediately re-fire. With EFD_NONBLOCK a single read returns the whole
        // accumulated count (or EAGAIN if already drained), so one read clears it; the rc is not actionable.
        if scratch.wakeFd >= 0 && scratch.wakeDrainBuf != null then discard(ep.eventfd_read(scratch.wakeFd, scratch.wakeDrainBuf))

    def isWakeFd(fd: Int, scratch: PollScratch): Boolean = scratch.wakeFd >= 0 && fd == scratch.wakeFd

    def closeWake(scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        if scratch.wakeFd >= 0 then
            discard(ep.close(scratch.wakeFd))
            scratch.wakeFd = -1
    end closeWake

    def poll(pollerFd: Int, timeoutMs: Int, changelist: kyo.ffi.Buffer[Byte], nChanges: Int, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Int, Any] =
        // changelist / nChanges are unused: epoll has no changelist parameter in epoll_wait; changes go through epoll_ctl in drainChanges.
        // The eventsBuffer is NOT closed here: it is the per-driver PollScratch.eventsBuffer, reused across poll cycles, freed via PollScratch.close.
        val evBuf = scratch.eventsBuffer
        val fiber = ep.epoll_wait(pollerFd, evBuf, MaxEvents, timeoutMs)
        fiber.poll() match
            case Present(result) =>
                // JVM/Native: the @Ffi.blocking epoll_wait ran synchronously on this carrier, so the fiber is already complete. Decode the result
                // into scratch inline and return a fresh pre-completed fiber carrying the ready count. This deliberately avoids Fiber.Unsafe.map:
                // the poll loop runs on a single long-lived IOTask carrier, and map composes the result through a kyo `< S` step that the carrier's
                // Safepoint trampolines into a Defer the unsafe poll loop never evaluates, so the decode would silently never run and every
                // readiness event would be dropped. eval forces the already-pure Outcome with no `< S` composition, and the returned fiber's value
                // is the Int ready count the poll contract requires (decorators read it; the poll loop reads scratch.readyCount).
                val n =
                    result match
                        case Result.Success(outcome) => decodeReady(outcome.eval, scratch)
                        case _ =>
                            scratch.readyCount = 0
                            0
                val completed = Promise.Unsafe.init[Int, Any]()
                completed.completeDiscard(Result.succeed(n))
                completed
            case Absent =>
                // JS: the call is genuinely pending on a libuv worker. Its completion callback runs on a fresh stack rather than the poll loop's
                // Safepoint, so decoding inside map is safe and the decode runs before drainReady reads scratch.readyCount.
                fiber.map(outcome => decodeReady(outcome, scratch))
        end match
    end poll

    /** Decode the events epoll_wait wrote into `scratch.eventsBuffer` into the poll scratch (`readyCount`, `fds`, `flags`) and return the ready
      * count. Each `epoll_event`'s `data` is the watched fd and its `events` bitmask carries readiness: `EPOLLIN`/`EPOLLOUT` map to read/write,
      * `EPOLLERR`/`EPOLLHUP` to error, and `EPOLLRDHUP` (peer half-close, can co-occur with `EPOLLIN` when bytes are buffered before EOF) to eof.
      */
    private def decodeReady(outcome: Ffi.Outcome[Int], scratch: PollScratch)(using AllowUnsafe): Int =
        val raw   = outcome.value.toInt
        val n     = if raw <= 0 then 0 else raw
        val fds   = scratch.fds
        val flags = scratch.flags
        val evBuf = scratch.eventsBuffer
        scratch.readyCount = n
        var i = 0
        while i < n do
            val ev   = EpollEvent.decode(evBuf, i * EpollEvent.size)
            val bits = ev.events
            fds(i) = ev.data.toInt
            var f = 0
            if (bits & PosixConstants.EPOLLIN) != 0 then f |= PollFlags.Read
            if (bits & PosixConstants.EPOLLOUT) != 0 then f |= PollFlags.Write
            if (bits & (PosixConstants.EPOLLERR | PosixConstants.EPOLLHUP)) != 0 then f |= PollFlags.Error
            if (bits & PosixConstants.EPOLLRDHUP) != 0 then f |= PollFlags.Eof
            flags(i) = f
            i += 1
        end while
        n
    end decodeReady

    def newPollScratch()(using AllowUnsafe): PollScratch =
        // Unsafe: off-heap allocations at driver init (called once; closed in driver.close via PollScratch.close).
        val eventsBuffer = Buffer.alloc[Byte](MaxEvents * EpollEvent.size)
        val armBuf       = Buffer.alloc[Byte](EpollEvent.size)
        new PollScratch(eventsBuffer, new Array[Int](MaxEvents), new Array[Int](MaxEvents), armBuf, Absent)
    end newPollScratch

    def close(pollerFd: Int)(using AllowUnsafe, Frame): Unit =
        // The close-discard rationale lives once on PollerBackend.close. MaxEvents is the inherited PollerBackend.MaxEvents.
        discard(ep.close(pollerFd))

    // errno returned by epoll_ctl(ADD) when the fd is already registered; we then re-arm with MOD. Stable across Linux arches.
    private val EEXIST = 17

end EpollPollerBackend
