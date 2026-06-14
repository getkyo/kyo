package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Linux epoll arm of [[PollerBackend]], routed through the kyo-ffi [[EpollBindings]].
  *
  * Interest is registered one-shot (`EPOLLONESHOT`): each registration fires at most one event, matching the re-arm-on-every-await model the
  * driver's pending tables assume. `epoll_ctl(ADD)` arms a fresh fd; if the fd is already in the set the kernel returns `EEXIST` and we re-arm
  * with `epoll_ctl(MOD)`. The watched fd is carried in the event's `data` key so the decoded event can name it without a side table.
  *
  * Unlike kqueue, where `EVFILT_READ` and `EVFILT_WRITE` are INDEPENDENT one-shot filters that coexist on one fd, epoll carries ONE interest
  * mask per fd: `EPOLL_CTL_MOD` REPLACES the whole mask. Registering write while a read was already armed (or the reverse) would therefore drop
  * the pending direction's interest, and on a socket (`readFd == writeFd`) a TLS handshake that legitimately parks a read next to a writable
  * would then deadlock (the parked direction's readiness can never be delivered). To match kqueue's independent-direction semantics this arm
  * tracks the desired interest per fd in [[desired]] and always arms the UNION (`EPOLLIN | EPOLLOUT` when both are pending), so registering one
  * direction never clears the other. That desired-interest map lives on the per-driver [[PollScratch.epollDesired]], so each driver tracks only
  * its own fds: the singleton object holds no cross-driver interest state.
  *
  * `EPOLLONESHOT` then introduces the dual problem on the firing side: when any direction fires, the kernel disables the WHOLE fd, so the OTHER
  * still-pending direction's interest is lost. [[rearm]] restores that survivor (see its doc). The fired direction is left to the driver's normal
  * re-arm (a fresh [[registerRead]]) so a consumed direction never busy-loops.
  *
  * `epoll_create1` / `epoll_ctl` are plain (non-blocking) downcalls, so `create`, `registerRead`, `registerWrite`, `rearm`, and `deregister`
  * all return synchronous `Int` or `Unit` with no suspension. `epoll_wait` is `@Ffi.blocking`: [[poll]] returns a `Fiber.Unsafe` that is
  * already `done()` on JVM/Native (inline completion) and genuinely pending on JS (libuv worker thread).
  *
  * All arm/register/deregister/poll methods use the per-driver [[PollScratch]]: `scratch.armBuf` (sized for one EpollEvent) for arm calls,
  * `scratch.eventsBuffer` (sized `MaxEvents * EpollEvent.size`) for poll. No per-call off-heap allocation occurs on the hot path. The arch-
  * dependent `EpollEvent.size` is used at scratch construction time, so the buffer is correctly sized on both x86_64 (12 bytes) and aarch64
  * (16 bytes).
  */
private[net] object EpollPollerBackend extends PollerBackend:

    private def ep(using AllowUnsafe): EpollBindings = Ffi.load[EpollBindings]

    def create()(using AllowUnsafe): Int = ep.epoll_create1(0).value

    def registerRead(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        val union = addInterest(scratch, fd, PosixConstants.EPOLLIN)
        arm(pollerFd, fd, union, scratch.armBuf)
    end registerRead

    def registerWrite(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Int =
        val union = addInterest(scratch, fd, PosixConstants.EPOLLOUT)
        arm(pollerFd, fd, union, scratch.armBuf)
    end registerWrite

    /** Re-arm the still-pending direction(s) on `fd` after an event fired. The fired bits are removed from [[desired]] (the driver re-expresses
      * continued read interest with its own [[registerRead]], and a writable is one-shot), and whatever interest remains is re-armed under
      * `EPOLLONESHOT`. This is what keeps a read parked next to a writable (or the reverse) alive after `EPOLLONESHOT` disabled the whole fd: the
      * non-fired survivor is re-armed here. If nothing remains pending (both directions fired, or the fd was deregistered meanwhile) it is a
      * no-op, so a consumed-and-not-re-armed direction never busy-loops.
      */
    override def rearm(pollerFd: Int, fd: Int, firedRead: Boolean, firedWrite: Boolean, scratch: PollScratch)(using
        AllowUnsafe,
        Frame
    ): Unit =
        var firedBits = 0
        if firedRead then firedBits |= PosixConstants.EPOLLIN
        if firedWrite then firedBits |= PosixConstants.EPOLLOUT
        val remaining = clearInterest(scratch, fd, firedBits)
        if remaining != 0 then discard(arm(pollerFd, fd, remaining, scratch.armBuf))
    end rearm

    /** OR `bit` into `fd`'s desired interest in this driver's scratch and return the new union. */
    private def addInterest(scratch: PollScratch, fd: Int, bit: Int): Int =
        val prev    = scratch.epollDesired.getOrDefault(fd, 0)
        val updated = prev | bit
        scratch.epollDesired.put(fd, updated)
        updated
    end addInterest

    /** Clear `bits` from `fd`'s desired interest in this driver's scratch and return what remains (0 if the fd is absent or now has no interest). */
    private def clearInterest(scratch: PollScratch, fd: Int, bits: Int): Int =
        if !scratch.epollDesired.containsKey(fd) then 0
        else
            val remaining = scratch.epollDesired.get(fd) & ~bits
            scratch.epollDesired.put(fd, remaining)
            remaining
    end clearInterest

    /** Arm `fd` for `interest | EPOLLONESHOT`: try `ADD`, and on `EEXIST` (the fd is already registered) re-arm with `MOD`. `armBuf` is the
      * caller-owned arm buffer (sized for one EpollEvent, from `PollScratch.armBuf`), reused across all arm calls; no per-call allocation.
      * The buffer is NOT closed here: it persists for the driver lifetime and is freed via [[PollScratch.close]].
      */
    private def arm(pollerFd: Int, fd: Int, interest: Int, armBuf: Buffer[Byte])(using AllowUnsafe): Int =
        EpollEvent.encode(armBuf, 0, EpollEvent(interest | PosixConstants.EPOLLONESHOT, fd.toLong))
        val added = ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_ADD, fd, armBuf)
        if added.value < 0 && added.errorCode == EEXIST then
            ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_MOD, fd, armBuf).value
        else added.value
        end if
    end arm

    def deregister(pollerFd: Int, fd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Unit =
        discard(scratch.epollDesired.remove(fd))
        EpollEvent.encode(scratch.armBuf, 0, EpollEvent(0, 0L))
        discard(ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_DEL, fd, scratch.armBuf))
        // scratch.armBuf is NOT closed here: it is the caller-owned per-driver reused buffer, freed via PollScratch.close.
    end deregister

    def registerWake(pollerFd: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Boolean =
        // Create the eventfd counter and register it in the epoll set for LEVEL-triggered read interest (no EPOLLONESHOT): it stays armed across
        // polls, so every wake write is delivered and the loop drains the counter each time. A dedicated arm buffer is used (the eventfd is not a
        // socket fd and must not enter scratch.epollDesired / the one-shot re-arm machinery).
        val efd = ep.eventfd(0, PosixConstants.EFD_NONBLOCK | PosixConstants.EFD_CLOEXEC)
        if efd.value < 0 then false
        else
            scratch.wakeFd = efd.value
            scratch.wakeDrainBuf = Buffer.alloc[Byte](8) // eventfd counter is a uint64
            val armBuf = Buffer.alloc[Byte](EpollEvent.size)
            scratch.wakeArmBuf = armBuf // owned by registerWake/close only; reused for the (single) wake registration
            EpollEvent.encode(armBuf, 0, EpollEvent(PosixConstants.EPOLLIN, efd.value.toLong))
            val rc = ep.epoll_ctl(pollerFd, PosixConstants.EPOLL_CTL_ADD, efd.value, armBuf)
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

    def poll(pollerFd: Int, timeoutMs: Int, scratch: PollScratch)(using AllowUnsafe, Frame): Fiber.Unsafe[Int, Any] =
        // Unsafe: the @Ffi.blocking wait fills scratch.eventsBuffer (the caller-owned per-driver reused buffer). Decode inline inside .map.
        // The buffer is NOT closed here: it is the per-driver PollScratch.eventsBuffer, reused across poll cycles, freed via PollScratch.close.
        // On JVM/Native the @Ffi.blocking fiber is already done and .map fires synchronously. On JS the fiber is genuinely pending.
        val fds   = scratch.fds
        val flags = scratch.flags
        val evBuf = scratch.eventsBuffer
        ep.epoll_wait(pollerFd, evBuf, MaxEvents, timeoutMs).map { ready =>
            val n = ready.value
            if n <= 0 then 0
            else
                var i = 0
                while i < n do
                    val ev   = EpollEvent.decode(evBuf, i * EpollEvent.size)
                    val bits = ev.events
                    fds(i) = ev.data.toInt
                    var f = 0
                    if (bits & PosixConstants.EPOLLIN) != 0 then f |= PollFlags.Read
                    if (bits & PosixConstants.EPOLLOUT) != 0 then f |= PollFlags.Write
                    if (bits & (PosixConstants.EPOLLERR | PosixConstants.EPOLLHUP)) != 0 then f |= PollFlags.Error
                    flags(i) = f
                    i += 1
                end while
                n
            end if
        }
    end poll

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
