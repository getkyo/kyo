package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import kyo.net.internal.util.IntLongMap
import kyo.net.internal.util.IntRefMap
import kyo.net.internal.util.MpscLongQueue

/** Readiness-to-completion I/O driver over epoll (Linux) / kqueue (macOS/BSD), unified onto [[PosixHandle]] and the kyo-ffi bindings.
  *
  * It carries the readiness-driver machinery, pending-table / dispatch / cancel / stale-fd-id, keyed on [[PosixHandle]]'s
  * split fds (reads on `readFd`, writes on `writeFd`) and routed through [[PollerBackend]] (epoll or kqueue) plus [[SocketBindings]] for
  * `send` / `recv` / `close`. It presents the unchanged completion `IoDriver[PosixHandle]` contract: callers deposit a `Promise` and the poll
  * loop fulfils it when the fd is ready and the read/write has been performed.
  *
  * The poll loop runs on a dedicated carrier spawned via `Fiber.Unsafe.init` and drives a `while` loop that calls
  * `backend.poll(pollerFd, 100, pollScratch)` each cycle (bounded 100ms wait). `backend.poll` returns a `Fiber.Unsafe` wrapping the
  * `@Ffi.blocking` `epoll_wait` / `kevent`. On JVM/Native the wait fiber is already `done()` inline; the loop reads events via `poll()` and
  * continues the next `while` iteration without growing the stack. On JS the wait fiber is genuinely pending; the loop exits the `while`,
  * registers an `onComplete` callback that re-enters from the libuv event loop via `Fiber.Unsafe.init { pollLoop() }` on a fresh stack
  * frame. The `done()/poll()` inline path prevents the `StackOverflowError` that naive self-recursive `onComplete` would cause
  * (IOPromise.eval is not a trampoline).
  *
  * Ordered interest changes: `epoll_ctl` and kqueue's `EV_ADD` / `EV_DELETE` are last-write-wins per fd+filter, so an interest change that runs
  * out of order can clobber a live one. Concretely, a STARTTLS upgrade detaches the plaintext pump (which `cancel`s the fd, i.e. submits a
  * deregister) and then immediately re-arms the same fd for the handshake read; if the deregister ran AFTER the re-arm, it would delete the
  * handshake's read interest and the fd would never report ready again, deadlocking the handshake. To prevent this, every register / deregister
  * (from the `IoDriver` methods AND the poll loop's EAGAIN re-arms) goes through one FIFO drained by a single worker fiber, so changes execute
  * in submission order. The bounded poll wait keeps running on its own fiber (a concurrent `epoll_wait` / `kevent` against a changelist is safe
  * in the kernel); only the mutations are serialized.
  *
  * The change FIFO carries packed primitive `long` commands rather than closures: each command encodes an opcode (RegisterRead /
  * RegisterWrite / Rearm / Deregister) plus fd and direction bits into one `long`. This eliminates the per-change closure allocation. The
  * FIFO is an unboxed [[kyo.net.internal.util.MpscLongQueue]] (multi-producer, single change-worker consumer) that stores the raw `long` and
  * recycles drained nodes, so enqueueing a change allocates nothing in steady state; a `ConcurrentLinkedQueue[java.lang.Long]` would box each
  * command on every offer (a `java.lang.Long` per enqueue a JFR alloc profile flagged on the poller hot path).
  *
  * The pending read and accept promises are stored directly on the [[PosixHandle]] (`pendingReadPromise`, `pendingAcceptPromise`) rather than
  * as `(promise, handle)` tuple pairs in the pending maps. This removes the per-await `Tuple2` allocation; the maps now hold `PosixHandle`
  * directly.
  *
  * The per-driver [[PollScratch]] holds the reused events buffer (epoll: `MaxEvents * EpollEvent.size` bytes; kqueue: `MaxEvents * KEvent.size`
  * bytes via `KqueuePollData`), the decoded fds/flags parallel arrays, and the arm buffer (epoll: one EpollEvent; kqueue: one KEvent's bytes via
  * `KqueuePollData`). All are allocated once at driver init and closed when the driver closes.
  *
  * Stale-event guard: each handle carries a unique `id`; `activeFds` maps fd to the current id so an event for a fd that was closed and
  * recycled into a different handle is recognised and dropped rather than delivered to the new handle.
  */
final private[net] class PollerIoDriver private[posix] (backend: PollerBackend, pollerFd: Int, sockets: SocketBindings)
    extends IoDriver[PosixHandle], TlsEngineIo:

    import PollerIoDriver.PendingWritable
    import PollerIoDriver.Registration
    import PollerIoDriver.RegKind

    // Unsafe: the driver's atomic flags are created at construction with no ambient AllowUnsafe; the danger bridge builds each here and every
    // access runs under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // The fd-keyed tables below are primitive open-addressing maps (int key, no Integer boxing; activeFds also has a primitive long value, no
    // Long box). They are NOT thread-safe and are safe ONLY because every mutation is applied on the poll-loop carrier (single-writer):
    // callers ENQUEUE a registration (regIntake) or a change command (changeQueue) and the poll fiber applies the put/remove from its drain path,
    // never the caller. This confinement is the prerequisite the primitive swap rides (an unconfined primitive map is a data race).
    // kyo has no primitive-keyed map, so the raw maps are the documented no-equivalent exception, each on the per-driver instance.
    // fd -> current handle id. Used to discard stale poller events after fd reuse.
    private val activeFds = new IntLongMap()

    // readFd -> handle (read promise stored on handle.pendingReadPromise); writeFd -> writable entry (held on handle.pendingWritablePromise too,
    // so the cancel/close paths can fail the promise synchronously without touching this non-thread-safe map). The writable entry pairs the
    // promise with the arming handle's monotonic id so dispatchWritable can apply the same stale-fd-id equality guard the read/accept paths use
    // (activeFds[writeFd] == id), instead of a presence-only check that would deliver a recycled fd's prior owner's readiness to the new owner.
    private val pendingReads     = new IntRefMap[PosixHandle]()
    private val pendingWritables = new IntRefMap[PendingWritable]()

    // readFd -> handle (accept promise stored on handle.pendingAcceptPromise) for listen fds registered via awaitAccept.
    // Keyed separately from pendingReads: the listen fd must route to dispatchAccept, not dispatchRead.
    // The check in drainReady tries pendingAccepts first, then falls through to pendingReads.
    private val pendingAccepts = new IntRefMap[PosixHandle]()

    // Registration intake: many fibers enqueue a pending registration (awaitRead/armSocketWritable/awaitAccept) here; the poll-loop carrier consumes
    // it and applies the activeFds + pendingReads/pendingWritables/pendingAccepts puts on its own carrier, so the maps are written by ONE carrier.
    // A ConcurrentLinkedQueue (single poll-fiber consumer) mirrors the engineQueue; the small Registration record per await replaces the
    // prior per-await Integer key-box on the activeFds put. The handle reference cannot be packed into the unboxed long change command, so it travels
    // through this side queue and is matched to its register command on the poll carrier.
    //
    // regIntake and changeQueue are two independent MPSC queues, and each producer offers its Registration and submits its packed command as two
    // separate steps. Under concurrent producers (every connection runs an independent ReadPump and WritePump fiber, each arming reads/writes), the
    // two queues' enqueue orders can diverge: producer A may win the regIntake offer while producer B wins the changeQueue offer, so a register
    // command at position k in changeQueue does NOT correspond to the entry at position k in regIntake. The poll carrier therefore MATCHES each
    // register command to its registration by (fd, kind) IDENTITY (takeRegistration scans regIntake head-first for the first entry with that fd and
    // kind), never by FIFO position. The command carries its kind explicitly (the opcode plus the accept discriminator bit, see packCmd), so an
    // OpRegisterRead from awaitAccept and one from awaitRead are never confused even when a recycled fd is reused across an accept and a read.
    // Matching by identity also makes a rapid arm/cancel/re-arm on one fd correct: two registrations of the same (fd, kind) sit in the queue in offer
    // order, and consecutive register commands consume them head-first in that order, so neither is lost.
    private val regIntake = new ConcurrentLinkedQueue[Registration]()

    // Backend interest changes (register / deregister) must run in submission order. epoll_ctl and kqueue's EV_ADD/EV_DELETE are last-write-wins
    // per fd+filter, so an out-of-order deregister can delete a freshly re-armed interest and strand the fd (no readiness event ever fires for
    // data that arrives afterward). The poll loop and every IoDriver method submit their changes here; a single worker fiber drains the FIFO and
    // runs each change to completion before the next, so on any given fd a deregister issued before a re-arm cannot land after it. The poll wait
    // itself runs on its own fiber (concurrent kevent/epoll_wait against a changelist is safe in the kernel); only the mutations are serialized.
    //
    // Element type: primitive long, in an unboxed MpscLongQueue (many producers, the single change worker as consumer). Each entry packs an
    // opcode + fd + direction bits into one long (see packCmd / OpXxx constants). This eliminates BOTH the per-change Function0 closure
    // allocation of the original design AND the java.lang.Long boxing a ConcurrentLinkedQueue[java.lang.Long] would incur per offer (the queue
    // recycles drained nodes, so a steady-state enqueue allocates nothing). The MpscLongQueue.offer is the happens-before barrier the awaitRead
    // promise-store relies on, exactly as the prior ConcurrentLinkedQueue.offer was.
    // Exposed for allocation-seam tests: the unboxed long element type proves neither a closure nor a boxed Long is allocated per change.
    //
    // Single-consumer drain model (mirrors IoUringDriver's engine FIFO over its reap loop): the change FIFO and the engine FIFO below are drained
    // ONLY by the always-running poll-loop carrier, once per poll cycle (see drainFifos, called from pollLoop). `submitChange` / `submitEngineOp`
    // are pure offers that return immediately, so the offload contract (submit never drains on the submitting carrier; draining is on the separate
    // poll-loop carrier) holds, and there is exactly one consumer (the poll loop), so the MpscLongQueue / ConcurrentLinkedQueue single-consumer
    // contract is preserved with no flag. This replaces the prior fire-and-forget `Fiber.Unsafe.init` drain task, whose loss under a scheduler
    // strand left the FIFO undrained forever (the Native TLS deadlock): the poll loop cannot be stranded the way an ephemeral spawned task can,
    // because it is the one carrier running for the driver's whole life, so a submitted op is always drained within one poll cycle.
    private[posix] val changeQueue = new MpscLongQueue()

    // Engine-op serialization: all TLS engine ops (handshakeStep, feedCiphertext, readPlain, writePlain, drainCiphertext, hasBufferedPlaintext,
    // readBuffered) for every connection on this driver route through this FIFO and are drained by the single poll-loop carrier (see above). This
    // guarantees at most one engine op runs at a time per connection (the single-owner guarantee), so a stateful TLS engine is never touched by two
    // carriers at once.
    private val engineQueue = new ConcurrentLinkedQueue[() => Unit]()

    // Missed-readiness tracker for the dropped-edge case under epoll EPOLLET register-once.
    //
    // When an EPOLLIN edge fires on an armed fd and dispatchRead finds no pending read (the consumer is in a backpressure pause, so
    // pendingReads.remove(fd) returns Absent), the edge is lost. The fd stays armed at the kernel, but EPOLLET only fires once per
    // empty->ready state transition, so no new edge fires when the consumer eventually calls awaitRead. That awaitRead issues an
    // epoll_ctl(MOD) with the same mask, which the kernel accepts as a no-op (the mask did not change), leaving the buffered data
    // stranded until more data arrives or the peer closes.
    //
    // This set records every fd whose kernel read edge was dropped because no pending read was present. On the consumer's next
    // awaitRead, dispatchCmd's OpRegisterRead branch checks and clears the entry, then re-dispatches immediately via dispatchRead,
    // which drains the buffered data without waiting for a new edge. The entry is also cleared on deregister and driver close.
    //
    // Poll-carrier-only: drainReady (which calls dispatchRead) and dispatchCmd (which is called from drainChanges) both run exclusively
    // on the single poll-loop carrier. No synchronization is needed; java.util.HashSet is sufficient.
    private val missedReads = new java.util.HashSet[Int]()

    // Per-driver reused poll/arm scratch. Allocated once at driver init. Ownership:
    //   eventsBuffer + fds + flags: poll loop carrier (pollLoop + drainReady on the same fiber).
    //   armBuf (epoll) / kqueueData.armBuf (kqueue): change worker (drainChanges + dispatchCmd on one worker fiber).
    // The two workers never access each other's scratch slots.
    //
    // Free ownership (single owner): the scratch is freed by the poll loop carrier at its terminal exit, when the last poll has completed and
    // the buffer is provably not in use. close() does NOT free the scratch when the loop is running: the poll loop uses it on every cycle
    // inside backend.poll, where the bounded epoll_wait/kevent holds the off-heap buffer in use for the whole native wait, and close() runs on
    // a different carrier. Freeing it from close() while the loop is mid-poll would close the shared off-heap arena while it is still acquired
    // (JVM: "Session is acquired by 1 clients"), or surface as a use-after-free on the next cycle. close() only signals termination via
    // closedFlag; the loop sees it between polls and frees the scratch on its way out. The never-started case (close() before start(), so the
    // loop never runs) is the only path where close() frees directly: no loop is using the scratch then. freeScratchOnce CASes so the scratch
    // is freed EXACTLY once across {poll-loop terminal exit, close()-never-started path}.
    // Unsafe: no ambient AllowUnsafe at field init; the init runs under AllowUnsafe.embrace.danger, matching the precedent for
    // ConcurrentHashMap, AtomicBoolean, and other driver-field inits at class construction time.
    // Exposed for allocation-seam tests: allows assertions on armBuf / eventsBuffer identity across calls.
    private[posix] val pollScratch: PollScratch = backend.newPollScratch()(using AllowUnsafe.embrace.danger)

    // True once start() has spawned the poll loop carrier. close() reads it to decide who frees the scratch: when the loop ran (or is running),
    // the loop owns the free at its terminal exit; when it never ran, close() frees directly (no loop is using the scratch).
    private val started = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Frees pollScratch exactly once. CASed by the poll loop's terminal exit (the loop ran) and by close()'s never-started path (the loop never
    // ran); the CAS guarantees no double-free and no use-after-free between the two single owners.
    private val freeScratchOnce = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Poll-loop wakeup coalescing flag. submitChange CASes it false->true and triggers backend.wake exactly once between poll cycles; the poll
    // loop CASes it back to false at the top of each cycle (before the next park). This (1) dedups wakes so a burst of submits triggers at most one
    // wake per cycle, and (2) serializes the kqueue wake-arm buffer to a single in-flight trigger (the buffer is written only by the CAS winner).
    // Without the prompt wake, a change submitted while the loop is parked in the bounded epoll_wait/kevent waits out the whole park (up to ~100ms,
    // longer under load) before it is registered with the kernel; the connect write-readiness arm then misses a short connect deadline (the
    // NetConnectTimeoutException regression). The wake makes the parked poll return at once so the change is registered within microseconds.
    private val wakePending = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // True while the wakeup mechanism is armed (registerWake succeeded at start). When false (best-effort arm failed) the driver still works via the
    // bounded-park drain; only the prompt-wake latency improvement is lost, so submitChange skips the wake rather than calling into a dead fd.
    private val wakeArmed = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Opcode constants for the packed Long change command:
    //   Bits 34-35: opcode (2 bits: 0=RegisterRead, 1=RegisterWrite, 3=Deregister; value 2 RETIRED from OpRearm, not reassigned)
    //   Bit  36:    fdClosing (for Deregister opcode only; 0 for all other opcodes)
    //   Bits 32-33: unused (formerly firedRead/firedWrite for the retired OpRearm opcode; zeroed for all current opcodes)
    //   Bits 31-0:  fd (32-bit signed int; OS fds fit in 31 bits on Linux; the full 32-bit range is preserved in the low 32 bits)
    // OpDeregister=3 occupies both bits 34 and 35 of the opcode field (3 = 0b11); bit 35 is part of the opcode, NOT a spare bit.
    // The fdClosing flag therefore uses bit 36, which is the first bit above the 2-bit opcode field and never set by any current opcode.
    private val OpRegisterRead: Long  = 0L
    private val OpRegisterWrite: Long = 1L
    // OpRearm = 2L retired. Edge-triggered registration eliminates per-event re-arm; value 2 is intentionally left as a gap
    // to avoid encoding collisions with any in-flight commands that may have been packed before the switch.
    private val OpDeregister: Long = 3L

    // Bound on in-place EINTR retries for one recv/send call. POSIX recv(2)/send(2): a non-blocking call interrupted by a signal before any byte
    // is transferred returns -1 with errno EINTR and MUST be retried (no data was moved, the socket is unchanged), exactly as PosixTransport.accept
    // already retries EINTR in place. The retry is bounded so a pathological EINTR storm (a process flooded with signals) cannot spin this carrier:
    // past the bound the loop returns the last EINTR result, which falls through to the existing hard-error branch (fail Closed for recv, Error for
    // send), mirroring how acceptAll falls back to a normal re-arm once its transient-retry budget is spent. 8 matches maxTransientAcceptRetries.
    private val maxTransientIoRetries = 8

    def label: String = "PollerIoDriver"

    def handleLabel(handle: PosixHandle): String = s"fd=${handle.readFd}/${handle.writeFd}"

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Unsafe: Fiber.Unsafe.init spawns the poll-loop carrier without re-entering the effect system. The while loop is
        // the JVM/Native inline-completion path: backend.poll returns a Fiber.Unsafe that is already done(), so we extract events via
        // poll() and loop without growing the stack (the while replaces @tailrec since the JS branch cannot be in tail position).
        // On JS the @Ffi.blocking fiber is genuinely pending; we exit the while loop, register an onComplete callback, and re-enter via
        // a fresh Fiber.Unsafe.init from the libuv event loop (fresh stack frame, no stack growth). The onComplete path is NOT taken on
        // JVM/Native because done() is always true: this avoids the StackOverflowError that naive self-recursive onComplete would cause
        // (IOPromise.eval fires onComplete INLINE when the fiber is already done; it is NOT a trampoline).
        started.set(true)
        // Arm the poll-loop wakeup (epoll eventfd / kqueue EVFILT_USER) so submitChange can make a parked poll return promptly instead of waiting
        // out the bounded park. Best-effort: if it fails to arm, the driver still drains every cycle via the bounded park, only losing the
        // prompt-wake latency improvement. Armed before the loop starts so the very first submitted change can wake it.
        wakeArmed.set(backend.registerWake(pollerFd, pollScratch))
        Fiber.Unsafe.init { pollLoop() }
    end start

    /** Free the per-driver poll scratch exactly once. Called by the poll loop carrier at its terminal exit (the loop ran and its last poll has
      * completed, so the buffer is provably not in use) and by close()'s never-started path (the loop never ran). The CAS makes it idempotent
      * across those two single owners: no double-free, and no use-after-free (the loop frees only after its last poll; close() frees only when
      * no loop runs).
      */
    private def freeScratch()(using AllowUnsafe): Unit =
        if freeScratchOnce.compareAndSet(false, true) then
            // Close the wakeup fd (epoll eventfd; kqueue no-op) before freeing the scratch buffers, under the same single-owner CAS so the eventfd
            // is closed exactly once. backend.closeWake needs a Frame; use the internal frame (no effectful suspension, a plain fd close).
            backend.closeWake(pollScratch)(using summon[AllowUnsafe], Frame.internal)
            pollScratch.close()
    end freeScratch

    /** Run the poll loop body on the current fiber. Called from `start()` and from the JS onComplete re-entry path. The while loop runs
      * the JVM/Native inline-completion path without stack growth. On JS, where the wait fiber is genuinely pending, we exit the while
      * loop and register an onComplete that calls `Fiber.Unsafe.init { pollLoop() }` to restart on a fresh libuv stack frame.
      */
    private def pollLoop()(using AllowUnsafe, Frame): Unit =
        var running = !closedFlag.get()
        // True only when the JS branch parked on a genuinely-pending wait fiber and registered an onComplete: the while loop exits but the loop
        // is NOT terminal (it re-enters, or frees, from the onComplete). On JVM/Native the wait fiber is always done(), so this stays false and
        // the free below runs after the while loop.
        var enteredPending = false
        while running do
            // Apply any queued interest changes BEFORE parking so a change submitted since the last drain (e.g. a connect's write-interest arm via
            // armSocketWritable -> submitChange) is registered with the kernel NOW. The bounded poll that follows then returns its readiness
            // immediately when the fd is already ready (a loopback connect's socket is writable at once, so epoll_wait/kevent returns without waiting
            // out the timeout). This, paired with the submitChange wake that cuts a park short when work arrives DURING it, is what keeps the connect
            // write-readiness from being stranded behind the ~100ms park past a short connect deadline (the NetConnectTimeoutException regression).
            // Clearing wakePending right before the park arms the wake for any change submitted from here on.
            wakePending.set(false)
            drainChanges()
            // Pass the kqueue changelist (changelistBuf + nChanges) so kevent can submit pending changes (e.g. EV_DISABLE from
            // dispatchWritable) atomically with the wait. On epoll the changelist / nChanges arguments are ignored by EpollPollerBackend.poll.
            val (clBuf, clN) = pollScratch.kqueueData match
                case Present(kq) => (kq.changelistBuf, kq.nChanges)
                case Absent      => (pollScratch.armBuf, 0) // epoll: unused sentinel
            val waitFiber = backend.poll(pollerFd, timeoutMs = 100, clBuf, clN, pollScratch) // sanctioned bounded park
            if waitFiber.done() then
                // JVM/Native inline-completion path: the @Ffi.blocking wait completed synchronously. Extract events and continue the
                // while loop without growing the stack (the while iteration is the tail-loop equivalent of @tailrec).
                waitFiber.poll() match
                    case Present(Result.Success(_)) => drainReady(pollScratch.fds, pollScratch.flags, pollScratch.readyCount)
                    case Present(Result.Failure(_)) => running = false // backend closed or error; exit loop
                    case Present(Result.Panic(_))   => running = false // fatal backend error; exit loop
                    case Absent                     => ()              // unreachable when done(); continue
                end match
                if running then running = !closedFlag.get()
            else
                // JS path: the @Ffi.blocking fiber is genuinely pending. Exit the while loop and register a callback; libuv fires the
                // callback on a fresh stack frame when the wait completes, avoiding unbounded stack growth.
                running = false
                enteredPending = true
                waitFiber.onComplete {
                    case Result.Success(_) =>
                        drainReady(pollScratch.fds, pollScratch.flags, pollScratch.readyCount)
                        // Drain both FIFOs on the JS re-entry path too: JS is single-threaded so it never strands a drain, but the poll loop is the
                        // sole FIFO consumer on every platform, so the drain must run on whichever poll path executes. This also runs any
                        // close-teardown engine op (the maps are poll-fiber-confined; JS is single-threaded so the teardown never races the loop).
                        drainFifos()
                        if !closedFlag.get() then discard(Fiber.Unsafe.init { pollLoop() })
                        else
                            // Terminal JS exit: closed flag set, not re-entering, last poll done. Close the poller fd after the last poll and free
                            // the scratch once (the teardown op was drained by the drainFifos above).
                            backend.close(pollerFd)
                            freeScratch()
                        end if
                    case _ =>
                        // Terminal JS exit: wait failed, not re-entering. Close the poller fd and free the scratch once.
                        backend.close(pollerFd)
                        freeScratch()
                }
            end if
            // Drain both FIFOs once per poll cycle on the JVM/Native always-running carrier, whether or not events fired this cycle: the poll loop is
            // the sole consumer of the change/engine FIFOs, so a command or engine op enqueued by submitChange/submitEngineOp (or by this cycle's own
            // dispatch) is drained here within one cycle. The poll's bounded ~100ms park guarantees the cycle runs even with no readiness, so a re-arm
            // submitted while idle is never stranded. On the JS pending path the loop exited before reaching here, so the JS drain runs in onComplete.
            if !enteredPending then drainFifos()
        end while
        // Terminal exit on JVM/Native (the while loop ended on closedFlag or a backend failure/panic): the last poll has completed and the maps +
        // scratch are provably not in use, so the poll loop carrier finishes the teardown here. The FINAL drainFifos runs any close-teardown engine
        // op that close() submitted concurrently with the closedFlag set (so it is never stranded by the loop exiting first); since it runs on this
        // poll-loop carrier, the teardown's map access stays poll-fiber-confined. backend.close(pollerFd) runs AFTER the last poll, so the
        // poller fd is never closed under an in-flight epoll_wait/kevent. Skipped when the JS branch parked on a pending wait fiber (not terminal:
        // it tears down from the onComplete instead).
        if !enteredPending then
            drainFifos()
            backend.close(pollerFd)
            freeScratch()
        end if
    end pollLoop

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        handle.pendingReadPromise = Present(promise)
        // The activeFds + pendingReads puts are applied on the poll fiber (single-writer): enqueue the registration, then submit the
        // change command. The poll fiber drains regIntake before processing the register command, so the map entry is in place when dispatchCmd
        // runs (the registration is published before the command via the change-queue happens-before, and the intake drain precedes the command).
        // rc<0 failure is handled inside dispatchCmd, which reads pendingReads and fails the stored promise. The pendingReadPromise store
        // happens-before the changeQueue.offer (the MpscLongQueue offer tail swap is the barrier), so the change worker sees it on rc<0.
        regIntake.offer(Registration(handle, RegKind.Read))
        submitChange(packCmd(OpRegisterRead, handle.readFd))
    end awaitRead

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.unsentTailBytes >= PosixHandle.WriteTailLowWater then
            // Tail-bound park (CWE-400): the WritePump suspended because the TLS write tail hit the high-water mark, NOT because the kernel send
            // buffer is full. Those are different signals: the socket may be writable while the tail is still large, so arming raw write-readiness
            // would wake the pump too early and let it append again (re-growing the tail). Instead, hold the promise on the handle; the drain path
            // (flushPending, driven by the flush re-arm's OWN socket-readiness loop) completes it via releaseBackpressureWaiter once the tail falls
            // below the low-water mark. A drain is guaranteed in progress: the tail only reaches the high-water mark through writeTls, which always
            // ends with flushPending, which arms armWritableForFlush (a socket-readiness re-arm) when it EAGAINs with bytes pending. This branch is
            // reached ONLY from the public WritePump path; the internal flush re-arm and connect call armSocketWritable directly (below), so they
            // are never mis-routed here.
            handle.backpressureWaiter = Present((promise, summon[Frame]))
            // Double-check on the FIFO worker: a flush may have drained the tail below the low-water mark between the check above and this
            // registration (flushPending runs on the FIFO worker, this runs on the pump carrier). Routing the re-check through the FIFO observes a
            // consistent tail snapshot (the tail fields are FIFO-worker-owned) and completes the just-registered waiter if the drain already
            // happened, so the pump is never stranded waiting on a drain that has already passed. A waiter parked while the handle is closing is
            // failed by PosixHandle.freeResources (it completes this promise with Closed using the frame captured here), so the close-vs-park race
            // never strands the pump.
            submitEngineOp(() => handle.releaseBackpressureWaiter())
        else
            armSocketWritable(handle, promise)
        end if
    end awaitWritable

    /** Arm epoll/kqueue write-readiness for `handle`, completing `promise` when the kernel send buffer has room. The raw "wait until the socket is
      * writable" primitive: used by the WritePump's below-bound park, the connect path, AND the internal flush re-arm ([[armWritableForFlush]]).
      * Distinct from the tail-bound park in [[awaitWritable]], which waits for the write-backpressure tail to drain (a different condition); the flush
      * re-arm needs the SOCKET signal (so it can send more of the tail), so it calls this directly rather than the tail-aware public method.
      */
    private def armSocketWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Store the writable promise on the handle so the cancel/close paths can fail it synchronously without touching the poll-fiber-confined
        // pendingWritables map. The activeFds + pendingWritables puts are applied on the poll fiber from the registration (single-writer);
        // the entry pairs the promise with the arming handle's id (handle.id) so dispatchWritable drops a recycled fd's prior owner's readiness.
        // rc<0 failure is handled inside dispatchCmd, which reads pendingWritables and fails the stored promise.
        handle.pendingWritablePromise = Present(promise)
        regIntake.offer(Registration(handle, RegKind.Write))
        submitChange(packCmd(OpRegisterWrite, handle.writeFd))
    end armSocketWritable

    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // epoll/kqueue signal connect completion via write-readiness. A fresh connect has no write tail, so this arms socket readiness directly
        // (the tail-aware awaitWritable would take the same path, but calling armSocketWritable is explicit about the intent and avoids the tail read).
        armSocketWritable(handle, promise)

    /** Register interest in ONE accepted client fd on the listen fd `handle`. When the listen fd becomes read-ready (a client connection is
      * pending), `drainReady` routes the event to `dispatchAccept`, which completes `promise` with -1 as a readiness sentinel so the
      * transport can drain via `acceptNow`. The caller re-arms after each accept.
      */
    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        handle.pendingAcceptPromise = Present(promise)
        // The activeFds + pendingAccepts puts are applied on the poll fiber from the registration (single-writer).
        // rc<0 failure is handled inside dispatchCmd, which checks pendingAccepts and fails the stored promise. The accept=true bit routes the
        // command to the accept staging map on the poll carrier (an awaitRead on the same fd would clear it and stage a Read instead).
        regIntake.offer(Registration(handle, RegKind.Accept))
        submitChange(packCmd(OpRegisterRead, handle.readFd, accept = true))
    end awaitAccept

    /** Dispatch a read-ready event on a listen fd registered via `awaitAccept`. Completes the accept promise with -1 as a readiness
      * sentinel (the transport drains the actual accepted fds via `acceptNow`; -1 is never a valid fd so it cannot collide with a real
      * accepted fd). Applies the stale-event guard (same activeFds id check as `dispatchRead`) to avoid delivering a recycled fd's
      * readiness to the wrong waiter.
      */
    private def dispatchAccept(fd: Int)(using AllowUnsafe, Frame): Unit =
        Maybe(pendingAccepts.remove(fd)) match
            case Present(handle) =>
                val promise = handle.pendingAcceptPromise
                handle.pendingAcceptPromise = Absent
                if isStaleId(handle.readFd, handle.id) then
                    // Stale event: fd was recycled. Fail the accept promise.
                    promise.foreach(_.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale accept event fd=$fd"))))
                else
                    // Signal the transport that the listen fd is accept-ready (-1 = readiness sentinel; transport calls acceptNow to drain).
                    promise.foreach(_.completeDiscard(Result.succeed(-1)))
                end if
            case Absent =>
                // No pending accept (one-shot event arrived after cancel/close). Drop it.
                ()
        end match
    end dispatchAccept

    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        given Frame = Frame.internal
        if data.isEmpty || offset >= data.size then WriteResult.Done
        else if handle.unsentTailBytes >= PosixHandle.WriteTailHighWater then
            // Write-backpressure bound (CWE-400): the TLS write tail (pendingCipher) has reached the high-water mark because the peer is not draining
            // it fast enough. Do NOT encrypt and append more (which would grow the tail without limit toward OOM); report Partial so the WritePump
            // parks on writability instead of pulling the next outbound span. The data is not consumed, so it is re-presented unchanged on retry, and
            // awaitWritable holds the pump until the in-flight flush drains the tail below the low-water mark (releaseBackpressureWaiter). This folds
            // the async TLS write tail into the same backpressure flow the synchronous raw path already obeys. Checked before beginWrite so an
            // over-bound write touches no guard / engine. (The raw path never reaches here: the poller's raw send is inline and reports its own
            // Partial straight from the send syscall, so its tail is always bounded by the kernel send buffer.)
            WriteResult.Partial(data, offset)
        else if !handle.beginWrite() then
            // The handle was closed (resources freed) before this write acquired them; bail without touching the engine / buffers (the write
            // twin of dispatchRead's !beginDispatch guard). The pump treats Error as teardown, which is correct for a write on a closed handle.
            WriteResult.Error
        else
            handle.tls match
                case Present(engine) =>
                    // For TLS writes, endWrite is called from INSIDE the FIFO thunk after engine ops complete. This keeps the write guard
                    // held until the engine is done: a concurrent closeHandle defers the engine free until endWrite fires from the FIFO
                    // worker, so the engine is never freed while writePlain / drainCiphertext are running.
                    writeTls(handle, data, engine)
                case Absent =>
                    // For plaintext writes, endWrite is called here synchronously after writeRaw completes.
                    try writeRaw(handle, data, offset)
                    finally discard(handle.endWrite())
                    end try
        end if
    end write

    /** Plaintext send: one `send` syscall on `writeFd`, returning Done / Partial / Error from the byte count and errno.
      *
      * `write` is a synchronous `IoDriver` method, so the byte count must be in hand without suspending. The two backends reach it through
      * different `send` bindings, selected at compile time:
      *
      *   - JVM / Native: the `@Ffi.blocking` `send` runs inline on the carrier (the scheduler's blocking monitor recognises the parked carrier
      *     and compensates), so its result fiber is already done and `takeNow` reads the count directly via a non-parking `poll()`. That inline
      *     `@Ffi.blocking` round-trip is load-bearing for fiber scheduling here: routing this path through a plain synchronous downcall instead
      *     deadlocks the surrounding write/handshake fibers (the TLS round-trips hang). So JVM/Native must keep the `@Ffi.blocking` send.
      *   - JS: the same `@Ffi.blocking` `send` is dispatched to a libuv worker and is genuinely pending, so its count can never be read
      *     synchronously (`takeNow` would yield `Absent` and the write would falsely report 0 bytes). JS instead calls the synchronous
      *     `sendNow` binding (a non-blocking `send` on the same fd, which the driver only ever drives non-blocking), getting the count inline on
      *     the single event-loop thread with no async hop and no double send.
      *
      * The per-write off-heap buffer allocation is eliminated by reusing the per-handle `sendMirror`: the unsent region `[offset, data.size)`
      * is copied element-wise into the mirror once per write. The mirror is never closed between writes; it persists for the handle lifetime
      * and is freed in freeResources. This is the pump-carrier analog of the FIFO-worker-owned flushMirror for TLS flush.
      *
      * Both JS and JVM/Native copy `data[offset..data.size)` element-wise into the reused per-handle `sendMirror`, then call `send` on the
      * mirror. On JS the synchronous `sendNow` binding is used (the `@Ffi.blocking` `send` is dispatched to a libuv worker and cannot be
      * read back synchronously). On a partial send, `Partial(data, offset + n)` re-presents the original `data` span at the new offset so
      * the pump copies only the remaining unsent bytes on the next call, without allocating a new span.
      */
    private def writeRaw(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        given Frame = Frame.internal
        val len     = data.size - offset
        val flags   = PosixConstants.MSG_NOSIGNAL
        if kyo.internal.Platform.isJS then
            // JS: copy the unsent region into the reused sendMirror, then call sendNow (synchronous, non-blocking).
            val mirror = sendMirrorFor(handle, len)
            val arr    = data.toArrayUnsafe
            var i      = 0
            while i < len do
                mirror.set(i, arr(offset + i))
                i += 1
            end while
            // EINTR (a signal interrupted the send before any byte moved) is retried in place by sendNowWithRetry, bounded, so a signal does not
            // surface as Error and drop a healthy connection (POSIX send(2)); EAGAIN still parks via Partial, a genuine error still bails Error.
            val r = sendNowWithRetry(handle.writeFd, mirror, len.toLong, flags)
            val n = r.value.toInt
            if n < 0 then
                if isWouldBlock(r.errorCode) then WriteResult.Partial(data, offset)
                else WriteResult.Error
            else if n >= len then WriteResult.Done
            else WriteResult.Partial(data, offset + n)
            end if
        else
            // JVM/Native: copy unsent region into the reused per-handle sendMirror, then send from it.
            val mirror = sendMirrorFor(handle, len)
            val arr    = data.toArrayUnsafe
            var i      = 0
            while i < len do
                mirror.set(i, arr(offset + i))
                i += 1
            end while
            // EINTR is retried in place by sendBlockingWithRetry (bounded), so a signal mid-send does not surface as Error (POSIX send(2)).
            val result: Maybe[Ffi.Outcome[Long]] = sendBlockingWithRetry(handle.writeFd, mirror, len.toLong, flags)
            result match
                case Present(r) =>
                    val n = r.value.toInt
                    if n < 0 then
                        if isWouldBlock(r.errorCode) then WriteResult.Partial(data, offset)
                        else WriteResult.Error
                    else if n >= len then WriteResult.Done
                    else WriteResult.Partial(data, offset + n) // same span, advanced offset, no Span.drop
                    end if
                case Absent =>
                    // Unreachable: the JVM/Native @Ffi.blocking send inline-completes. Treat a stray pending fiber as transient
                    // backpressure so the caller awaits writability rather than mis-reporting a completed write.
                    WriteResult.Partial(data, offset)
            end match
        end if
        // mirror is NOT closed here: it is the per-handle reused buffer, freed only in freeResources.
    end writeRaw

    /** Return the per-handle sendMirror Buffer, grown to at least `size` bytes if needed. Pump-carrier-owned (writeRaw runs synchronously
      * under beginWrite/endWrite guard). Lazily allocated on the first writeRaw call; grown on demand, never shrunk. When growth is needed,
      * the old buffer is closed before allocating the new one to prevent a native-memory leak.
      */
    private def sendMirrorFor(handle: PosixHandle, size: Int)(using AllowUnsafe): Buffer[Byte] =
        handle.sendMirror match
            case Present(buf) if buf.size >= size => buf
            case _ =>
                handle.sendMirror.foreach(_.close())
                val buf = Buffer.alloc[Byte](size)
                handle.sendMirror = Present(buf)
                buf
    end sendMirrorFor

    /** TLS send: submit the encrypt-then-flush operation to the serial engine worker and return Done immediately. The FIFO worker runs
      * writePlain / drainCiphertext to completion before the next engine op for any connection on this driver. This removes the write-path
      * engine ops from the write-pump carrier, so the write pump and the read-dispatch path cannot touch the same engine concurrently. Write
      * ordering is preserved because the FIFO processes each write's engine ops in submission order.
      *
      * A single writePlain does not necessarily consume the whole plaintext (the JDK SSLEngine.wrap arm encrypts one record per call),
      * so the thunk loops writePlain until every byte is consumed, draining each call's ciphertext and APPENDING it to the handle's pending
      * tail ([[appendPending]]) rather than sending it inline. After the encryption loop the thunk calls [[flushPending]] once: it sends as
      * much of the tail as the socket buffer accepts in a progress-bounded loop, and on EAGAIN with bytes still pending it arms a writable
      * re-arm ([[armWritableForFlush]]) that re-submits the flush when the socket drains. The write pump always sees Done and proceeds to the
      * next take; the actual send (and any backpressured remainder) happens asynchronously on the FIFO worker carrier. There is NO busy-spin:
      * a full send buffer parks on writability instead of retrying the same bytes, so one slow connection never stalls the shared FIFO worker.
      */
    private def writeTls(handle: PosixHandle, data: Span[Byte], engine: TlsEngine)(using AllowUnsafe, Frame): WriteResult =
        submitEngineOp { () =>
            // endWrite is called here (inside the FIFO thunk, after all engine ops) rather than in the write method's finally block.
            // This keeps the write guard held until engine ops complete: a concurrent closeHandle defers the engine free until this
            // endWrite fires, preventing a use-after-free between the guard release and the writePlain / drainCiphertext execution. The
            // guard is acquired (beginWrite) and released (endWrite) within THIS one op; the flush re-arm runs as a SEPARATE later op that
            // re-acquires its own guard, so the guard is never held across the awaitWritable suspension.
            try
                // Encrypt the plaintext through the shared engine loop, appending each drained ciphertext chunk to the pending tail; then send
                // as much of the tail as the socket accepts (the poller's inline-send flush). The engine loop is shared with the io_uring
                // driver; the inline send + writability re-arm below are the poller's send mechanism.
                discard(encryptPlaintext(handle, data, engine)((drain, n) => appendPending(handle, drain, n)))
                flushPending(handle)
            finally discard(handle.endWrite())
            end try
        }
        // The pump always sees Done; the actual send runs on the FIFO worker carrier after engine ops complete.
        WriteResult.Done
    end writeTls

    /** Append freshly-encrypted ciphertext to the handle's pending tail. FIFO-worker-only: the engine FIFO is the single owner of every write
      * engine op for this connection, so this is never called concurrently. The [[GrowableByteBuffer]] is allocated lazily on the first
      * append so a one-pass write (all ciphertext flushed inline) allocates nothing extra.
      *
      * `drain` is the per-handle reused encryptDrain buffer (never closed here). `len` is the number of bytes written by drainCiphertext.
      * Uses [[GrowableByteBuffer.writeFromBuffer]] for a zero-intermediate-allocation copy from the off-heap drain buffer (direct off-heap
      * to heap copy, no intermediate Array).
      */
    private def appendPending(handle: PosixHandle, drain: Buffer[Byte], len: Int)(using AllowUnsafe): Unit =
        val buf =
            handle.pendingCipher match
                case Present(b) => b
                case Absent =>
                    val b = new GrowableByteBuffer
                    handle.pendingCipher = Present(b)
                    b
        buf.writeFromBuffer(drain, len) // zero intermediate heap array (direct off-heap -> heap copy)
    end appendPending

    /** Send as much of the handle's pending ciphertext tail as the socket buffer accepts, then either reset the tail (fully drained) or arm a
      * writable re-arm ([[armWritableForFlush]]) for the remainder. FIFO-worker-only.
      *
      * This is PROGRESS-BOUNDED, not a busy-spin: each loop iteration sends the unsent region `[pendingCipherSent, size)` once. On a positive
      * send count it advances `pendingCipherSent` (strictly-positive progress, so the loop terminates), the defining difference from the
      * previous busy-spin approach which retried the same bytes up to 4096 times on EAGAIN. On EAGAIN (or a 0-count send) it BREAKS without
      * retrying and leaves the remainder for the writable re-arm. On a hard send error it discards the tail and returns: the peer-reset
      * surfaces to the ReadPump via the recv error through the normal teardown path, so this does NOT call closeHandle from inside the op.
      *
      * Allocation notes: the unsent region is copied element-wise into the per-handle reused [[PosixHandle.flushMirror]] buffer (no per-
      * syscall copyOfRange + fromArray allocation). The mirror is never closed between iterations; it persists for the handle's lifetime and
      * is freed in [[PosixHandle.freeResources]].
      */
    private def flushPending(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        handle.pendingCipher match
            case Absent => () // nothing buffered (the common one-pass write never allocated a tail)
            case Present(buf) =>
                val flags    = PosixConstants.MSG_NOSIGNAL
                var continue = true
                while continue && handle.pendingCipherSent < buf.size do
                    val unsentLen = buf.size - handle.pendingCipherSent
                    val mirror    = flushMirrorFor(handle, unsentLen)
                    // Copy the unsent region element-wise into the reused mirror (no per-syscall copyOfRange + fromArray).
                    var ci = 0
                    while ci < unsentLen do
                        mirror.set(ci, buf.array(handle.pendingCipherSent + ci))
                        ci += 1
                    end while
                    val sent =
                        // EINTR is retried in place by the *WithRetry helpers (bounded), so a signal mid-send does not look like a hard error and
                        // discard the tail; EAGAIN still parks the flush via the 0 (would-block) branch (POSIX send(2)).
                        val result: Maybe[Ffi.Outcome[Long]] =
                            if kyo.internal.Platform.isJS then
                                Present(sendNowWithRetry(handle.writeFd, mirror, unsentLen.toLong, flags))
                            else sendBlockingWithRetry(handle.writeFd, mirror, unsentLen.toLong, flags)
                        result match
                            case Present(r) =>
                                val n = r.value.toInt
                                if n < 0 then
                                    if isWouldBlock(r.errorCode) then 0 else -1
                                else n
                            case Absent => 0 // stray pending fiber: treat as backpressure, park on writability
                        end match
                    end sent
                    // mirror is NOT closed here: it is the per-handle reused buffer, freed only in freeResources.
                    if sent < 0 then
                        // Hard send error (peer reset): discard the tail and stop. The recv side surfaces Closed through normal teardown; do
                        // NOT closeHandle from inside the engine op.
                        Log.live.unsafe.debug(s"flushPending hard send error fd=${handle.writeFd}; discarding pending ciphertext tail")
                        buf.reset()
                        handle.pendingCipherSent = 0
                        continue = false
                    else if sent == 0 then continue = false // EAGAIN / stray pending: stop, arm writability below
                    else handle.pendingCipherSent += sent   // strictly-positive progress; the loop terminates
                    end if
                end while
                if handle.pendingCipherSent >= buf.size then
                    // Fully drained (or the tail was discarded on a hard error, leaving size 0): reset so the next write starts clean and a
                    // later flush sees nothing pending.
                    buf.reset()
                    handle.pendingCipherSent = 0
                else
                    // Bytes remain after EAGAIN: park on writability and re-submit the flush when the socket drains. The FIFO worker is the
                    // single owner of this method, so the buffer is still Present here.
                    armWritableForFlush(handle)
                end if
        end match
        // The tail just advanced (or fully drained): if a WritePump parked at the high-water bound and the tail has fallen below the low-water mark,
        // release it so the pump retries the deferred write. A no-op when no waiter is parked or the tail is still over the mark.
        handle.releaseBackpressureWaiter()
    end flushPending

    /** Return the per-handle flushMirror Buffer, grown to at least `size` bytes if needed. FIFO-worker-owned.
      * Lazily allocated on the first backpressured flush; grown on demand (never shrunk). When growth is needed, the old buffer is closed
      * before allocating the new one to prevent a native-memory leak. Owned by the engine FIFO worker (single owner): no cross-fiber
      * synchronization is needed beyond the engine-op FIFO's sequencing.
      */
    private def flushMirrorFor(handle: PosixHandle, size: Int)(using AllowUnsafe): Buffer[Byte] =
        handle.flushMirror match
            case Present(buf) if buf.size >= size => buf
            case _ =>
                handle.flushMirror.foreach(_.close())
                val buf = Buffer.alloc[Byte](size)
                handle.flushMirror = Present(buf)
                buf
    end flushMirrorFor

    /** Arm a one-shot writable re-arm that re-submits [[flushPending]] when the socket drains.
      *
      * The double-arm guard ([[PosixHandle.writableArmed]]) has two writers, causally serialized: this method (on the engine FIFO worker) SETS it
      * true while arming, and the completion callback below CLEARS it false when the writable promise fires. It coalesces appends: if a flush is
      * already pending, an append that adds more bytes does NOT arm a second writable, because the already-pending flush re-submits a
      * [[flushPending]] that sends the unsent region (which now includes the appended bytes). On the writable readiness the completion callback
      * re-submits a fresh engine op that re-acquires the write guard (beginWrite), so the guard is never held across the awaitWritable suspension:
      * a slow peer cannot block a deferred engine free. A close/cancel fails the writable promise, whose Failure/Panic branch only clears the arm
      * flag and never touches the (possibly freed) pending buffer.
      */
    private def armWritableForFlush(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if handle.writableArmed then () // a flush re-arm is already pending; it will pick up any appended bytes
        else
            handle.writableArmed = true
            val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
            p.onComplete {
                case Result.Success(_) =>
                    handle.writableArmed = false
                    submitEngineOp { () =>
                        if handle.beginWrite() then
                            try flushPending(handle)
                            finally discard(handle.endWrite())
                            end try
                    }
                case _ =>
                    // Close / cancel failed the promise: bail without re-touching the (possibly freed) pending state.
                    handle.writableArmed = false
            }
            // Arm SOCKET write-readiness directly: the flush re-arm needs the kernel-send-buffer-has-room signal so it can send more of the tail.
            // It must NOT go through the tail-aware public awaitWritable, whose tail-bound branch would mis-route this flush promise into the
            // backpressure-waiter slot (the tail is high here, since the flush just EAGAINed), stranding the re-arm and the tail's only drain path.
            armSocketWritable(handle, p)
    end armWritableForFlush

    /** Remove this handle's fds from the poller interest set and fail any pending promises with Closed.
      *
      * @param fdClosing
      *   false when the fd is still open (live withdrawal: kqueue must issue EV_DELETE); true when the fd is being closed (the OS auto-removes
      *   kqueue filters on close, so EV_DELETE is unnecessary and targeting a recycled fd number by mistake is avoided).
      */
    private def deregisterFds(handle: PosixHandle, fdClosing: Boolean)(using AllowUnsafe, Frame): Unit =
        // deregisterFds runs on an ARBITRARY carrier (cancel / closeHandle). The four map REMOVALS (activeFds, pendingReads, pendingWritables,
        // pendingAccepts) and the missedReads clear move into the OpDeregister apply on the poll-loop carrier (single-writer): the
        // backend.deregister handler removes the map entries there. The PROMISE FAILS stay SYNCHRONOUS here so a cancel delivers Closed at once
        // rather than waiting up to one poll cycle (EC-1): every pending promise is held on the handle (pendingReadPromise / pendingAcceptPromise /
        // pendingWritablePromise / backpressureWaiter), so failing them needs no map access. completeDiscard is idempotent, so if the poll fiber's
        // deregister removal also tried to fail them (it does not), there would be no double-completion hazard.
        submitChange(packCmd(OpDeregister, handle.readFd, fdClosing))
        if handle.writeFd != handle.readFd then
            submitChange(packCmd(OpDeregister, handle.writeFd, fdClosing))
        end if
        val closed = Closed(label, summon[Frame], s"fd=${handle.readFd}/${handle.writeFd} canceled")
        handle.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
        handle.pendingReadPromise = Absent
        handle.pendingWritablePromise.foreach(_.completeDiscard(Result.fail(closed)))
        handle.pendingWritablePromise = Absent
        handle.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
        handle.pendingAcceptPromise = Absent
        // Fail any WritePump promise parked at the write-backpressure high-water bound (it is not in pendingWritables: a tail-bound park is held on
        // the handle, not armed on socket readiness). Releasing it with Closed lets the pump tear down rather than hang on a tail that will never drain.
        handle.backpressureWaiter.foreach { case (p, _) => p.completeDiscard(Result.fail(closed)) }
        handle.backpressureWaiter = Absent
    end deregisterFds

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Public IoDriver cancel: the fd is still open (live-fd withdrawal). EV_DELETE must execute on kqueue to prevent stale events.
        deregisterFds(handle, fdClosing = false)

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Close path: the fd will be closed below. The OS auto-removes kqueue filters on close, so EV_DELETE is unnecessary and dangerous
        // (a recycled fd number would receive EV_DELETE intended for the old fd). Pass fdClosing=true to skip EV_DELETE on kqueue.
        deregisterFds(handle, fdClosing = true)
        // Route the engine free through the engine FIFO so it is serialized behind any read/write engine ops for this connection (no two
        // carriers touch one ssl). Installed before requestClose can fire so whoever runs freeResources (here, or a deferred endDispatch /
        // endWrite on the FIFO worker) sees the sink.
        handle.engineFreeSink = op => submitEngineOp(op)
        handle.tls match
            case Present(engine) =>
                // TLS close: emit this side's close_notify (RFC 8446 6.1 / RFC 5246 7.2.1: MUST send before closing the write side) and flush it
                // to the wire BEFORE closing the fd, then close the fd, all in ONE engine-FIFO op so the alert and the fd close are serialized in
                // order behind any in-flight read/write engine op (the single-owner-of-ssl invariant) and the alert reaches the peer ahead of the
                // FIN. Bounded + non-blocking: shutdownTls runs one shutdownStep and a best-effort inline send (it never waits for the peer's
                // close_notify and never blocks a carrier), so close() always completes. PosixHandle.close (below) requests the resource free,
                // which routes engine.free through the SAME FIFO, ordered AFTER this op, so the engine is alive for the shutdown emit.
                submitEngineOp { () =>
                    if handle.beginWrite() then
                        try shutdownTls(handle, engine)
                        finally discard(handle.endWrite())
                        end try
                    end if
                    // Close the fd after the alert flush, inside this op so the FIN follows the close_notify on the wire.
                    if handle.readFd == handle.writeFd && handle.claimFdClose() then discard(takeNow(sockets.close(handle.readFd)))
                }
            case Absent =>
                // Plaintext close: no close_notify to emit. Close the socket fd (sockets set readFd == writeFd; stdio leaves 0/1 untouched).
                // recv/send after this fail, proving the close. The claimFdClose CAS makes the close one-shot: a STARTTLS upgrade failure racing
                // this closeHandle targets the SAME fd, so without the claim the fd could be closed twice (and a recycled fd belonging to another
                // connection wrongly closed). The single claim winner issues the close.
                if handle.readFd == handle.writeFd && handle.claimFdClose() then discard(takeNow(sockets.close(handle.readFd)))
        end match
        PosixHandle.close(handle)
    end closeHandle

    /** Emit this side's TLS close_notify and flush it to the wire (best-effort, bounded). FIFO-worker-only (called from the closeHandle engine
      * op under the write guard), so it never races a concurrent read/write engine op on the same `ssl`.
      *
      * One-directional close (RFC 8446 6.1): run a single [[TlsEngine.shutdownStep]] to emit this side's close_notify, drain the produced alert
      * record into the reused [[TlsEngineIo.encryptDrainFor]] buffer, and send each chunk in one best-effort non-blocking pass. It does NOT
      * wait for the peer's close_notify and does NOT block a carrier (no writability re-arm, no retry: the connection is closing, so a single
      * send pass is the whole budget), so a slow or gone peer cannot stall close(). A `shutdownStep` fatal (`-2`, e.g. the session is already
      * torn down) is ignored, and a backpressured / failed send drops the alert: in every case the caller proceeds to close the fd.
      */
    private def shutdownTls(handle: PosixHandle, engine: TlsEngine)(using AllowUnsafe, Frame): Unit =
        if engine.shutdownStep() != -2 then
            val drain = encryptDrainFor(handle)
            val flags = PosixConstants.MSG_NOSIGNAL
            var more  = true
            while more do
                val n = engine.drainCiphertext(drain, handle.readBufferSize)
                if n <= 0 then more = false
                else
                    // Best-effort single non-blocking send of this alert chunk; ignore the count (a backpressured close_notify is dropped, the
                    // interop-safe bound). On JS use the synchronous sendNow; elsewhere takeNow extracts the inline-completed @Ffi.blocking send.
                    if kyo.internal.Platform.isJS then discard(sockets.sendNow(handle.writeFd, drain, n.toLong, flags))
                    else discard(takeNow(sockets.send(handle.writeFd, drain, n.toLong, flags)))
                end if
            end while
        end if
    end shutdownTls

    /** Fail every pending promise with `closed` and clear the poll-fiber-confined maps + missedReads. Poll-fiber-confined: called ONLY from the
      * close-teardown engine op (which runs on the poll-loop carrier) and from close()'s never-started path (no poll fiber exists then). The maps are
      * not thread-safe, so this must never run concurrently with the poll loop's map access. Flushes every registration still in regIntake
      * (offered but whose register command had not yet run) into the live tables first, so its handle-held promise is then failed by the matching
      * live-table foreach below rather than stranded.
      */
    private def closeTeardown(closed: Closed)(using AllowUnsafe): Unit =
        flushRegIntakeToLiveTables()
        pendingReads.foreach { (_, h) =>
            h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
            h.pendingReadPromise = Absent
        }
        pendingReads.clear()
        pendingWritables.foreach { (_, entry) =>
            entry.promise.completeDiscard(Result.fail(closed))
        }
        pendingWritables.clear()
        pendingAccepts.foreach { (_, h) =>
            h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
            h.pendingAcceptPromise = Absent
        }
        pendingAccepts.clear()
        activeFds.clear()
        missedReads.clear()
    end closeTeardown

    /** Drain every remaining registration from regIntake into the live pending tables, so [[closeTeardown]]'s foreach loops fail those promises with
      * `closed`. A registration sits in regIntake from the moment its await offered it until its register command runs; at close, any registration
      * whose command never ran must still have its promise (held on the handle) failed, which the live-table foreach does, so it is flushed here first.
      * Poll-fiber-confined.
      */
    private def flushRegIntakeToLiveTables()(using AllowUnsafe): Unit =
        var reg = regIntake.poll()
        while reg != null do
            val handle = reg.handle
            reg.kind match
                case RegKind.Read =>
                    activeFds.put(handle.readFd, handle.id)
                    pendingReads.put(handle.readFd, handle)
                case RegKind.Accept =>
                    activeFds.put(handle.readFd, handle.id)
                    pendingAccepts.put(handle.readFd, handle)
                case RegKind.Write =>
                    activeFds.put(handle.writeFd, handle.id)
                    handle.pendingWritablePromise match
                        case Present(p) => pendingWritables.put(handle.writeFd, PendingWritable(p, handle.id))
                        case Absent     => ()
                    end match
            end match
            reg = regIntake.poll()
        end while
    end flushRegIntakeToLiveTables

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            val closed = Closed(label, summon[Frame], "driver closed")
            if started.get() then
                // The poll loop is running (or ran). Its maps are poll-fiber-confined, so the teardown must run on the poll-loop carrier,
                // not this arbitrary close carrier (a direct map iteration here would race the poll loop's map access on the non-thread-safe maps).
                // Route the teardown through the engine FIFO (drained by the poll loop) and wake the parked poll so it drains it and exits promptly.
                // The poll loop runs one final drainFifos at its terminal exit, so a teardown op submitted concurrently with the closedFlag set is
                // still drained before the loop frees the scratch. backend.close(pollerFd) + freeScratch run at the poll loop's terminal exit, AFTER
                // its last poll, so the poller fd is never closed out from under an in-flight epoll_wait/kevent.
                submitEngineOp(() => closeTeardown(closed))
                if wakeArmed.get() then backend.wake(pollerFd, pollScratch)
            else
                // start() was never called: no poll loop ran, so no carrier is using the maps or the scratch. Tear down directly.
                closeTeardown(closed)
                backend.close(pollerFd)
                freeScratch()
            end if
        end if
    end close

    /** Drain all ready events from one bounded poll result, dispatching reads, writables, and error-only events in order.
      *
      * Called from the `while`-loop body of `pollLoop` after `backend.poll` completes (inline on JVM/Native, via `onComplete` on JS). Each event:
      *   - Dispatches read-ready events to `dispatchRead` (or `dispatchAccept` for listen fds); under edge-triggered the fd is persistently
      *     armed and there is no survivor re-arm to submit.
      *   - Dispatches write-ready events to `dispatchWritable`.
      *   - Dispatches Eof events (peer half-close): when Eof fires alongside Read, `dispatchRead` handles buffered bytes first (passing
      *     `eofPending=true` so EAGAIN surfaces as Span.empty rather than a silent stop). When Eof fires without Read, the fd's read-promise
      *     is completed directly with Span.empty (no bytes to drain first).
      *   - Dispatches error-ONLY events (no read/write/eof bit) to `dispatchError` so a peer reset surfaces immediately rather than being
      *     missed until a later op fails. When a read or write bit is also set, the normal dispatch runs and surfaces the error in-band.
      */
    private def drainReady(fds: Array[Int], flags: Array[Int], n: Int)(using AllowUnsafe, Frame): Unit =
        var i = 0
        while i < n do
            val fd = fds(i)
            if backend.isWakeFd(fd, pollScratch) then
                // The poll-loop wakeup fired (a submitChange triggered backend.wake to cut this park short). It carries no connection readiness:
                // consume the signal so it does not immediately re-fire, then fall through to the cycle's drainFifos, which applies the change(s)
                // that prompted the wake. Not dispatched as a socket readiness (it is the eventfd / EVFILT_USER wake key, never a socket fd).
                backend.drainWake(pollScratch)
            else
                val f     = flags(i)
                val read  = (f & PollFlags.Read) != 0
                val write = (f & PollFlags.Write) != 0
                val error = (f & PollFlags.Error) != 0
                val eof   = (f & PollFlags.Eof) != 0
                if read then
                    // Prefer accept dispatch over read dispatch: a listen fd registered via awaitAccept must not be routed to dispatchRead.
                    // Pass eofPending=eof so the read path surfaces half-close after the buffered bytes are consumed.
                    // Pass fromKernelEdge=true so a dropped edge (Absent case) is recorded in missedReads for the consumer-resume path.
                    if pendingAccepts.contains(fd) then dispatchAccept(fd)
                    else dispatchRead(fd, eofPending = eof, fromKernelEdge = true)
                else if eof then
                    // Eof without Read: no buffered bytes before the EOF marker (or already drained). Surface the half-close directly.
                    dispatchEof(fd)
                end if
                if write then dispatchWritable(fd)
                // Error-ONLY event: no read/write/eof bit carried it, so no recv/send will observe the error. Fail the pending op(s).
                if error && !read && !write && !eof then dispatchError(fd)
            end if
            i += 1
        end while
    end drainReady

    /** Fail the fd's pending read and/or write with `Closed` in response to an error-only readiness event (`EPOLLERR` / `EPOLLHUP` on epoll,
      * `EV_ERROR` / `EV_EOF` on kqueue), but ONLY after confirming the fd actually carries a pending error via `SO_ERROR`.
      *
      * The driver shares one poller across every connection on it, and a closed fd's number is recycled into the next connection (a connect, an
      * accept) almost immediately. An error-only readiness event the kernel queued for the PRIOR owner of the fd can still be in the poll batch
      * the loop is draining when the recycled fd already has a fresh pending op (a connect's writable, a new read). Failing that op on the bare
      * error bit would surface a connect that never failed as `Closed` (the in-process-server burst regression): the event belongs to the dead
      * connection, not the live one. A genuine connect failure does NOT reach here at all (it arrives write-ready + error and is handled by
      * `dispatchWritable` plus the transport's own `SO_ERROR` check), so an error-only event on a connecting fd is, by construction, either stale
      * or a hangup the following `recv` would report as EOF.
      *
      * `SO_ERROR` is the authoritative discriminator the kernel keeps per socket: a real pending error (`ECONNRESET`, a failed connect) reads
      * non-zero and the pending op(s) are failed; a healthy / still-connecting recycled fd reads zero and the event is dropped, leaving the
      * pending op to resolve through its normal readiness. Reading `SO_ERROR` also clears it, which is correct: the error is consumed exactly once
      * here rather than re-firing. This touches no engine or read buffer, so it needs no read guard (unlike the data-bearing read dispatch).
      */
    private def dispatchError(fd: Int)(using AllowUnsafe, Frame): Unit =
        // Only fail the pending op(s) when the socket actually has a pending error. A zero SO_ERROR means this error-only event is stale (a
        // recycled fd whose live owner is healthy or still connecting), so dropping it keeps a concurrent connect from failing Closed.
        if soError(fd) != 0 then
            val closed = Closed(label, summon[Frame], s"error event fd=$fd")
            Maybe(pendingReads.remove(fd)).foreach { h =>
                h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                h.pendingReadPromise = Absent
            }
            Maybe(pendingWritables.remove(fd)).foreach(_.promise.completeDiscard(Result.fail(closed)))
        end if
    end dispatchError

    /** Read `SO_ERROR` for `fd`: 0 when the socket has no pending error, otherwise the queued `errno` (and reading it clears the kernel's stored
      * value). A `getsockopt` failure returns -1 so the caller treats it as a real error rather than masking it. The 4-byte int is read
      * little-endian (every supported target is LE), mirroring `PosixTransport.soError`, the connect path's post-writable confirmation.
      */
    private def soError(fd: Int)(using AllowUnsafe): Int =
        val opt = Buffer.alloc[Byte](4)
        val len = Buffer.alloc[Int](1)
        len.set(0, 4)
        try
            if sockets.getsockopt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_ERROR, opt, len).value != 0 then -1
            else
                (opt.get(0) & 0xff) |
                    ((opt.get(1) & 0xff) << 8) |
                    ((opt.get(2) & 0xff) << 16) |
                    ((opt.get(3) & 0xff) << 24)
        finally
            opt.close()
            len.close()
        end try
    end soError

    private def dispatchRead(fd: Int, eofPending: Boolean = false, fromKernelEdge: Boolean = false)(using AllowUnsafe, Frame): Unit =
        Maybe(pendingReads.remove(fd)) match
            case Present(handle) =>
                val promise = handle.pendingReadPromise
                if isStaleId(handle.readFd, handle.id) then
                    // Stale event: this fd was closed and recycled into a different handle. Drop it; do not deliver to the new handle.
                    promise.foreach(_.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale read event fd=$fd"))))
                    handle.pendingReadPromise = Absent
                else if !handle.beginDispatch() then
                    // The handle was closed (resources freed) before this dispatch acquired them; bail without touching them (race fix).
                    promise.foreach(_.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read on closed handle fd=$fd"))))
                    handle.pendingReadPromise = Absent
                else
                    handle.pendingReadPromise = Absent // clear before dispatch (the promise is now in-flight)
                    // Ownership of the read buffer + engine is held until finishDispatch / rearmOwned releases it, so a concurrent
                    // closeHandle defers its free and never races the in-flight recv / engine feed below.
                    promise match
                        case Present(p) =>
                            handle.tls match
                                case Present(engine) => dispatchReadTls(fd, p, handle, engine)
                                case Absent          => dispatchReadPlain(fd, p, handle, eofPending)
                        case Absent =>
                            // Promise was already cleared (e.g. by a concurrent cancel); release the dispatch guard.
                            discard(handle.endDispatch())
                    end match
                end if
            case Absent =>
                // No pending read. Under the register-once (EPOLLET) model, if this is a real kernel read edge (fromKernelEdge=true),
                // the edge is being dropped because no consumer is waiting (the consumer is in a backpressure pause or cancelled). Record
                // the fd so dispatchCmd's OpRegisterRead branch can re-dispatch when the consumer's next awaitRead arrives, rather than
                // letting the consumer park on a MOD-skip that produces no new kernel edge. Stale-fd recycles are also safe: a recycled
                // fd gets a fresh activeFds entry and the stale missedReads entry is cleared on deregister; at worst it triggers one
                // spurious EAGAIN probe on the new owner's first awaitRead, which the driver already handles correctly.
                if fromKernelEdge then discard(missedReads.add(fd))
        end match
    end dispatchRead

    /** Release this dispatch's ownership of the handle's read resources, then deliver `result` to `promise`, UNLESS a `closeHandle` raced this
      * dispatch: in that case `endDispatch` performed the deferred resource free and `result` is discarded in favour of a stale `Closed`, so the
      * caller never observes data delivered from a closed handle (the dispatch-vs-close use-after-free fix). The completion is the single atomic
      * point at which deliver-or-bail is decided.
      */
    private def finishDispatch(
        fd: Int,
        handle: PosixHandle,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        result: Result[Closed, Span[Byte]]
    )(using AllowUnsafe, Frame): Unit =
        if handle.endDispatch() then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read on closed handle fd=$fd")))
        else
            // Unsafe: erased-safe structural widening. `Span[Byte]` and its pending form are identical at runtime; the cast satisfies the
            // scheduler's promise contract without introducing a kyo-effect row in this method, and the type params are erased so it cannot throw.
            promise.asInstanceOf[kyo.scheduler.IOPromise[Any, Any]].completeDiscard(result)

    /** Release this dispatch's ownership and re-deposit the handle into `pendingReads` to keep waiting (the EAGAIN close-race guard), UNLESS a
      * `closeHandle` raced this dispatch, in which case the deferred free has happened and the read is failed `Closed` instead (a handle that
      * was closed during dispatch must not be re-deposited into pendingReads). Under edge-triggered (ET) registration the fd is persistently
      * armed at the kernel; this method does NOT submit a new RegisterRead command. The consumer calls `awaitRead` next, which submits an
      * OpRegisterRead; `dispatchCmd` then re-dispatches immediately if `readMightHaveMore` is set (residual bytes stranded on ET), or otherwise
      * parks until the kernel fires a new readiness edge.
      */
    private def rearmOwned(fd: Int, handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using
        AllowUnsafe,
        Frame
    ): Unit =
        if handle.endDispatch() then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read on closed handle fd=$fd")))
        else
            handle.pendingReadPromise = Present(promise)
            // Poll-fiber-confined: rearmOwned runs only from the dispatch paths on the poll-loop carrier, so this re-deposit is a direct put.
            // activeFds(fd) is still set from the original awaitRead, so no re-put is needed.
            pendingReads.put(fd, handle)
            // ET: fd stays armed at the kernel. The consumer-paced drain in dispatchCmd handles any residual bytes.

    /** Consumer-paced plain (non-TLS) read under edge-triggered registration.
      *
      * Under ET the kernel fires once per empty->ready transition. A recv that fills the read buffer exactly (n == readBufferSize) may
      * leave residual bytes in the kernel that the ET backend will never re-signal (no new edge occurs when the buffer was already non-empty
      * when the consumer re-registered). The consumer-paced drain handles this: when n == readBufferSize, `readMightHaveMore` is set true on
      * the handle; when the consumer re-registers (OpRegisterRead in `dispatchCmd`), the driver calls `dispatchRead` immediately rather than
      * waiting for an edge that may never arrive.
      *
      * Behaviour:
      *   - n > 0: deliver the chunk; set `readMightHaveMore = (n == readBufferSize) || eofPending`. `finishDispatch` releases ownership;
      *     the consumer re-registers before issuing the next read. If the buffer was full OR `eofPending` is set, `dispatchCmd`
      *     re-dispatches immediately on re-register. The eofPending case forces re-dispatch because EPOLLRDHUP fires only once per
      *     transition; after consuming data, the EPOLLRDHUP condition persists but epoll will not fire again without a new transition.
      *     The forced re-dispatch calls recv a second time, which returns 0 (FIN) and surfaces Span.empty.
      *   - n == 0 (recv returned 0): orderly peer close (TCP FIN, kernel buffer empty). Surface as Span.empty (not Closed).
      *   - EAGAIN / EWOULDBLOCK with eofPending: kernel buffer confirmed empty, peer half-closed. Surface Span.empty.
      *   - EAGAIN / EWOULDBLOCK without eofPending: buffer confirmed empty; fd stays armed, re-deposit handle.
      *   - Hard error: surface Closed.
      *
      * `eofPending` is forwarded from `drainReady` when `PollFlags.Eof` fired alongside `PollFlags.Read`, signalling that the peer
      * half-closed but bytes were still buffered at the time of the event (EPOLLRDHUP can fire with EPOLLIN; EV_EOF can fire with EV_READ).
      * After an EAGAIN with eofPending=true, the buffer is confirmed empty and Span.empty is the correct EOF delivery.
      */
    private def dispatchReadPlain(
        fd: Int,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: PosixHandle,
        eofPending: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val result = recvNowWithRetry(fd, handle.readBuffer, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n      = result.value.toInt
        if n > 0 then
            val arr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
            // Keep a reference to this chunk so a subsequent STARTTLS upgrade can recover a handshake flight that arrived coalesced with
            // the upgrade signal in this same read (the consumer takes the whole chunk as the signal and discards the trailing bytes).
            handle.lastPlaintextRead = Present(arr)
            // Set readMightHaveMore when the kernel may have additional bytes with no new ET edge pending. Two cases:
            // (1) n == readBufferSize: the recv filled the buffer; residual bytes may remain in the kernel that epoll ET will not re-signal.
            // (2) eofPending && n > 0: the peer half-closed; the next recv will either return more data or 0 (EOF). Re-dispatch is
            //     required regardless of buffer fill so the EOF surfaces on the next awaitRead rather than waiting for an edge that
            //     epoll ET will not fire (EPOLLRDHUP was already signalled and consumed with the EPOLLIN edge).
            val filled = n == handle.readBufferSize
            handle.readMightHaveMore = filled || eofPending
            // Adaptive receive-buffer growth: feed the fill ratio to the per-handle predictor. The bytes for THIS read are already copied
            // out into `arr` above, so a grow that closes the old buffer cannot lose them. Poll-fiber-confined and between reads (no recv is in
            // flight against the old buffer here), so the close-old-then-replace recipe is single-owner-safe. The grown buffer
            // serves the next recv; the value delivered for THIS read is unchanged.
            discard(handle.growReadBufferForFullRead(n))
            finishDispatch(fd, handle, promise, Result.succeed(Span.fromUnsafe(arr)))
        else if n == 0 then
            // Orderly peer close: recv(2) returns 0 when the kernel buffer is empty and the peer sent FIN. Surface as empty Span (not error).
            handle.readMightHaveMore = false
            finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
        else if isWouldBlock(result.errorCode) then
            // EAGAIN confirms the kernel buffer is empty: no residual possible.
            handle.readMightHaveMore = false
            if eofPending then
                // Buffer fully drained and the peer's half-close is confirmed empty. Deliver EOF (Span.empty) and release the dispatch.
                finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
            else
                // Buffer drained with no pending EOF: the fd stays armed (ET keeps it in the kernel's interest set). Re-deposit the handle.
                rearmOwned(fd, handle, promise)
            end if
        else
            // Hard error (e.g. ECONNRESET, ECONNABORTED): surface Closed.
            handle.readMightHaveMore = false
            finishDispatch(
                fd,
                handle,
                promise,
                Result.fail(Closed(label, summon[Frame], s"recv failed fd=$fd errno=${result.errorCode}"))
            )
        end if
    end dispatchReadPlain

    /** Surface a peer half-close (Eof-without-Read) to the fd's pending read promise as an empty Span.
      *
      * Called from `drainReady` when `PollFlags.Eof` fires without `PollFlags.Read`, meaning the peer's TCP FIN arrived while the kernel
      * receive buffer was already empty (no bytes to drain before delivering EOF). The read promise is completed with `Span.empty` to signal
      * orderly half-close to the consumer (not a failure; the consumer decides whether to close the write side or continue writing).
      *
      * When the fd has no pending read promise (the consumer did not issue a read before the FIN arrived, or a cancel raced this event),
      * the event is dropped silently: the next read the consumer issues will call recv and get 0 immediately, surfacing the EOF in-band.
      */
    private def dispatchEof(fd: Int)(using AllowUnsafe, Frame): Unit =
        Maybe(pendingReads.remove(fd)) match
            case Present(handle) =>
                val promise = handle.pendingReadPromise
                if isStaleId(handle.readFd, handle.id) then
                    // Stale event: fd recycled. Drop it.
                    promise.foreach(_.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale eof event fd=$fd"))))
                    handle.pendingReadPromise = Absent
                else if !handle.beginDispatch() then
                    // Handle closed before dispatch acquired it. Drop it.
                    promise.foreach(_.completeDiscard(Result.fail(Closed(label, summon[Frame], s"eof on closed handle fd=$fd"))))
                    handle.pendingReadPromise = Absent
                else
                    handle.pendingReadPromise = Absent
                    promise match
                        case Present(p) =>
                            // Surface the orderly half-close as Span.empty (not Closed); the consumer decides next steps.
                            finishDispatch(fd, handle, p, Result.succeed(Span.empty[Byte]))
                        case Absent =>
                            discard(handle.endDispatch())
                    end match
                end if
            case Absent =>
                // No pending read: the consumer will see EOF on the next recv call (returns 0). Nothing to signal here.
                ()
        end match
    end dispatchEof

    /** Return the per-handle recvStaging Buffer, lazily allocated on the first TLS read.
      *
      * Poll-carrier-only: called from dispatchReadTls before submitEngineOp. The lazy-alloc write (first TLS read) happens here
      * on the poll carrier and is visible to the FIFO worker via the engine-op enqueue as the happens-before barrier, exactly like
      * writableArmed. After the first alloc the field is stable. The at-most-one-in-flight guarantee (enforced by the engine-op enqueue
      * ordering) ensures the FIFO worker's feedCiphertext read from the staging buffer completes before the poll carrier's next recvNow
      * write into it (one in-flight ciphertext per handle).
      */
    private def stagingFor(handle: PosixHandle)(using AllowUnsafe): Buffer[Byte] =
        handle.recvStaging match
            case Present(buf) => buf
            case Absent =>
                val buf = Buffer.alloc[Byte](handle.readBufferSize)
                handle.recvStaging = Present(buf)
                buf
    end stagingFor

    /** TLS read: recv ciphertext on the poll carrier (non-blocking, returns instantly), then hand all engine ops to the serial engine
      * worker via submitEngineOp. This splits the fast syscall (recv) from the CPU-bound TLS shim calls (feedCiphertext, readPlain,
      * hasBufferedPlaintext, readBuffered), so the engine FIFO worker serializes all engine access while the poll loop continues on its
      * own carrier without touching the engine. An engine fast path (plaintext already buffered from a prior recv) also goes through the
      * FIFO because the hasBufferedPlaintext / readBuffered check must be atomic with respect to concurrent write-path engine ops.
      *
      * Recv goes directly into the per-handle recvStaging buffer (lazily allocated by stagingFor) instead of the shared readBuffer,
      * eliminating the per-read copy-out and re-fromArray allocations. The staging buffer is fed directly to feedCiphertext on the FIFO
      * worker. Ownership: poll carrier writes (recvNow), FIFO worker reads (feedCiphertext); causally serialized via the engine-op enqueue,
      * which ensures the FIFO worker's feedCiphertext read completes before the next recvNow write.
      */
    private def dispatchReadTls(
        fd: Int,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: PosixHandle,
        engine: TlsEngine
    )(using AllowUnsafe, Frame): Unit =
        // stagingFor is called on the poll carrier; it lazily allocates recvStaging on the first TLS read.
        // The recv writes directly into staging, eliminating the per-read copy-out (no Buffer.copyToArray here).
        // EINTR is retried in place by recvNowWithRetry (bounded), so a signal mid-recv does not surface as Closed (POSIX recv(2)).
        val staging = stagingFor(handle)
        val result  = recvNowWithRetry(fd, staging, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n       = result.value.toInt
        if n > 0 then
            // A full-buffer recv may leave residual ciphertext in the kernel with no new ET edge. Record that for the re-register path.
            handle.readMightHaveMore = n == handle.readBufferSize
            // Feed staging directly to feedCiphertext on the FIFO worker: no per-read re-fromArray.
            // The happens-before between the recvNow write (poll carrier) and the feedCiphertext read (FIFO worker) is the
            // submitEngineOp enqueue, the same mechanism as writableArmed. The at-most-one-in-flight guarantee ensures the next
            // recvNow write cannot happen before this feedCiphertext completes.
            submitEngineOp { () =>
                val plain = feedAndDecrypt(engine, staging, n, handle)
                if plain.length > 0 then
                    finishDispatch(fd, handle, promise, Result.succeed(Span.fromUnsafe(plain)))
                else if handle.peerCleanClose then
                    // The peer's close_notify was consumed (RFC 8446 6.1 orderly close): deliver EOF (empty Span) so the ReadPump tears down,
                    // rather than re-arming for ciphertext the peer will never send. closeReason then reports CleanClose, not Truncated.
                    finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
                else
                    // Only handshake/partial-record bytes consumed; wait for more ciphertext rather than signalling EOF.
                    rearmOwned(fd, handle, promise)
                end if
            }
        else if n == 0 then
            // Peer close via a bare TCP FIN (no close_notify reached the engine, else the clean-close branch above delivered EOF first): record
            // the truncation condition so closeReason reports Truncated, then deliver EOF immediately without engine involvement.
            handle.readMightHaveMore = false
            handle.peerEof = true
            finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
        else if isWouldBlock(result.errorCode) then
            // EAGAIN confirms the kernel socket buffer is empty: no residual possible. Engine plaintext may still be buffered (checked below).
            handle.readMightHaveMore = false
            // Socket buffer empty: check for already-buffered plaintext first (via the engine FIFO), then re-arm if none.
            submitEngineOp { () =>
                if engine.hasBufferedPlaintext then
                    val buffered = engine.readBuffered()
                    if buffered.nonEmpty then finishDispatch(fd, handle, promise, Result.succeed(buffered))
                    else rearmOwned(fd, handle, promise)
                    end if
                else rearmOwned(fd, handle, promise)
                end if
            }
        else
            handle.readMightHaveMore = false
            finishDispatch(
                fd,
                handle,
                promise,
                Result.fail(Closed(label, summon[Frame], s"recv failed fd=$fd errno=${result.errorCode}"))
            )
        end if
    end dispatchReadTls

    private def dispatchWritable(fd: Int)(using AllowUnsafe, Frame): Unit =
        Maybe(pendingWritables.remove(fd)) match
            case Present(entry) =>
                if isStaleId(fd, entry.id) then
                    // Stale event: this fd was closed and recycled into a different handle. Drop it; do not deliver to the new handle.
                    // (Same monotonic-id guard as dispatchRead/dispatchAccept; a presence-only check would deliver the prior owner's
                    // writable readiness to the recycled fd's new owner as Success.)
                    entry.promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale writable event fd=$fd")))
                else entry.promise.completeDiscard(Result.succeed(()))
                end if
            case Absent => ()
        end match
    end dispatchWritable

    private def isWouldBlock(errno: Int): Boolean =
        errno == PosixConstants.EAGAIN || errno == PosixConstants.EWOULDBLOCK

    /** Whether a readiness event for `fd` is stale: the fd's current armed handle id (from `activeFds`) is not `id`, meaning the fd was closed and
      * recycled into a different handle since the event was queued. Reads `activeFds` with a `-1` absent sentinel; handle ids are always `>= 0`, so
      * an absent fd (sentinel) is correctly treated as stale. Poll-fiber-confined (activeFds is poll-fiber-owned).
      */
    private def isStaleId(fd: Int, id: Long): Boolean = activeFds.getOrElse(fd, -1L) != id

    /** A non-blocking `recvNow` with a bounded in-place EINTR retry. POSIX recv(2): when a signal is delivered before any byte is transferred the
      * call returns -1 with errno EINTR; no data was moved, so the call is retried (the accept path's precedent). Only EINTR is retried; EAGAIN /
      * EWOULDBLOCK and a genuine error are returned to the caller unchanged so the existing would-block (park / re-arm) and hard-error (fail Closed)
      * branches still decide. The retry is bounded by [[maxTransientIoRetries]] so an EINTR storm cannot spin: past the bound the last EINTR result
      * is returned and falls through to the hard-error branch.
      */
    private def recvNowWithRetry(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Ffi.Outcome[Long] =
            val r = sockets.recvNow(fd, buf, len, flags)
            if r.value.toInt < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries then loop(attempt + 1)
            else r
        end loop
        loop(0)
    end recvNowWithRetry

    /** A non-blocking `sendNow` with a bounded in-place EINTR retry (the JS send path and the JS flush path). POSIX send(2): EINTR before any byte
      * is sent means nothing was transferred, so the send is retried. Only EINTR is retried; EAGAIN / EWOULDBLOCK and genuine errors return unchanged
      * so the would-block (park on writability) and hard-error (Error / discard tail) branches still decide. Bounded by [[maxTransientIoRetries]].
      */
    private def sendNowWithRetry(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Ffi.Outcome[Long] =
            val r = sockets.sendNow(fd, buf, len, flags)
            if r.value.toInt < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries then loop(attempt + 1)
            else r
        end loop
        loop(0)
    end sendNowWithRetry

    /** The JVM/Native `@Ffi.blocking` `send` (inline-completed, read via [[takeNow]]) with a bounded in-place EINTR retry. Same POSIX send(2)
      * contract as [[sendNowWithRetry]]: only EINTR is retried, bounded by [[maxTransientIoRetries]]; a would-block or genuine error returns to the
      * caller for the existing branches. An `Absent` (a stray pending fiber, not an interruption) is returned as-is so the caller's backpressure
      * fallback applies.
      */
    private def sendBlockingWithRetry(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
        AllowUnsafe,
        Frame
    ): Maybe[Ffi.Outcome[Long]] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Maybe[Ffi.Outcome[Long]] =
            takeNow(sockets.send(fd, buf, len, flags)) match
                case Present(r) if r.value.toInt < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries =>
                    loop(attempt + 1)
                case other => other
        loop(0)
    end sendBlockingWithRetry

    /** Submit an interest change command to the change FIFO and return immediately. The packed `long` command is appended to the unboxed FIFO (no
      * boxing, no closure); the poll-loop carrier drains it in submission order on its next cycle (see [[drainFifos]]). A pure offer with no spawn:
      * the prior design spawned a `Fiber.Unsafe.init` drain task here, which a scheduler strand could lose, leaving the FIFO undrained forever (the
      * Native TLS deadlock). The poll loop is the always-running carrier and cannot be stranded that way, so routing the drain through it makes the
      * delivery self-healing while keeping the offload contract (this returns immediately; the drain runs on the separate poll-loop carrier).
      *
      * After the offer it triggers the poll-loop wakeup (coalesced via `wakePending`) so a parked poll returns and drains this change promptly,
      * rather than waiting out the bounded ~100ms park. Without the wake, a change submitted while the loop is parked (e.g. a connect's write-
      * readiness arm) is not registered with the kernel until the current park times out, so a short connect deadline fires first (the
      * NetConnectTimeoutException regression). `wakePending.compareAndSet(false, true)` makes a burst of submits trigger at most one wake per poll
      * cycle (the poll loop clears the flag before each park) and serializes the kqueue wake-arm buffer to a single in-flight trigger. The offer
      * happens-before the wake, so the poll loop, once woken, always observes the just-offered command in its drain.
      */
    private def submitChange(cmd: Long)(using AllowUnsafe, Frame): Unit =
        changeQueue.offer(cmd)
        if wakeArmed.get() && wakePending.compareAndSet(false, true) then backend.wake(pollerFd, pollScratch)
    end submitChange

    /** Pack an opcode, fd, fdClosing flag, and accept discriminator into a single Long command for the change FIFO.
      *   Bits 34-35: opcode (OpRegisterRead=0 / OpRegisterWrite=1 / OpDeregister=3; value 2 retired from OpRearm)
      *   Bit  36:    fdClosing (for OpDeregister only: true when the fd is being closed; 0 for all other opcodes)
      *   Bit  37:    accept (for OpRegisterRead only: true when the registration came from awaitAccept, false from awaitRead; 0 otherwise)
      *   Bits 32-33: unused (formerly firedRead/firedWrite for the retired OpRearm opcode; always 0 for current opcodes)
      *   Bits 31-0:  fd as a signed 32-bit value (OS fds fit in 31 bits on Linux; the full 32-bit signed range is preserved)
      * OpDeregister=3 (binary 11) occupies both bits 34 and 35. The fdClosing flag uses bit 36 and the accept flag uses bit 37, both above
      * the 2-bit opcode field, so neither is ever set by any opcode value (0, 1, or 3 all fit in 2 bits). The accept bit lets the poll carrier
      * take an OpRegisterRead's registration from the correct kind (awaitRead vs awaitAccept) without a separate side channel, so a recycled fd
      * reused across an accept and a read is never mismatched.
      */
    private def packCmd(op: Long, fd: Int, fdClosing: Boolean = false, accept: Boolean = false): Long =
        (op << 34) |
            (fd.toLong & 0xffffffffL) |
            (if fdClosing then 1L << 36 else 0L) |
            (if accept then 1L << 37 else 0L)

    /** Whether `reg`'s fd matches `fd` for the given `kind`. Read and Accept key on `readFd`, Write on `writeFd` (matching the await methods). */
    private def regMatches(reg: Registration, fd: Int, kind: RegKind): Boolean =
        reg.kind == kind && (kind match
            case RegKind.Read | RegKind.Accept => reg.handle.readFd == fd
            case RegKind.Write                 => reg.handle.writeFd == fd)

    /** Remove and return the first registration in `regIntake` matching `(fd, kind)`, scanning head-first so registrations for the same `(fd, kind)`
      * are consumed in offer order (a rapid arm/cancel/re-arm leaves two such entries; consecutive register commands take them oldest-first). Returns
      * `null` when none matches (a cancel removed the await's promise before its command ran). Poll-fiber-confined consumption (the poll carrier is the
      * queue's single consumer); `ConcurrentLinkedQueue.iterator` is weakly consistent and tolerates concurrent producer offers.
      *
      * Matching by identity (not by FIFO position) is what makes this correct under concurrent producers: regIntake and changeQueue can interleave so
      * a command's registration is not at the head, so the scan finds the right entry regardless of cross-producer ordering.
      */
    private def takeRegistration(fd: Int, kind: RegKind): Registration | Null =
        val it                         = regIntake.iterator()
        var found: Registration | Null = null
        var scanning                   = true
        while scanning && it.hasNext do
            val reg = it.next()
            // remove(Object) removes the first structurally-equal element; no two live registrations are structurally equal here (distinct handles,
            // or the same handle re-arming only after its prior op on this direction completed), so this removes exactly the scanned entry.
            if regMatches(reg, fd, kind) && regIntake.remove(reg) then
                found = reg
                scanning = false
            end if
        end while
        found
    end takeRegistration

    /** Apply one register command's matching registration to the live poll-fiber-confined tables (single-writer), keyed by `(fd, kind)`. The
      * accept bit disambiguates an OpRegisterRead from awaitAccept vs awaitRead, so a recycled fd reused across an accept and a read is never
      * mismatched. Applies nothing when no registration matches (a cancel removed it before the command ran). The handle's `@volatile` promise/id
      * reads here pair with the await methods' stores (published before the registration offer; the queue offer/take is the happens-before barrier).
      */
    private def applyRegistration(fd: Int, kind: RegKind)(using AllowUnsafe): Unit =
        Maybe(takeRegistration(fd, kind)).foreach { reg =>
            val handle = reg.handle
            kind match
                case RegKind.Read =>
                    activeFds.put(fd, handle.id)
                    pendingReads.put(fd, handle)
                case RegKind.Accept =>
                    activeFds.put(fd, handle.id)
                    pendingAccepts.put(fd, handle)
                case RegKind.Write =>
                    activeFds.put(fd, handle.id)
                    handle.pendingWritablePromise match
                        case Present(p) => pendingWritables.put(fd, PendingWritable(p, handle.id))
                        case Absent     => () // the writable was already failed/cleared (cancel raced the registration); nothing to arm
                    end match
            end match
        }
    end applyRegistration

    /** Decode a packed Long command and dispatch to the appropriate backend method. On rc<0 from registerRead/registerWrite, the pending promise is
      * failed via the pending tables. For OpRegisterRead the accept bit selects whether this command applies an accept or a read registration (so a
      * listen fd registered via awaitAccept and a data fd registered via awaitRead are never confused, even on a recycled fd).
      */
    private def dispatchCmd(cmd: Long)(using AllowUnsafe, Frame): Unit =
        val op        = (cmd >>> 34) & 0x3L // 2-bit opcode: mask off bits 36/37 (fdClosing/accept) to avoid confusion with op values
        val fd        = (cmd & 0xffffffffL).toInt
        val fdClosing = (cmd & (1L << 36)) != 0
        val accept    = (cmd & (1L << 37)) != 0
        // Built lazily: only the rare rc<0 register-failure branches below consume it, but dispatchCmd runs once per
        // readiness command (register/deregister) on the hot path, so an eager val allocated a Closed plus its
        // interpolated message on every successful command and on every deregister, which never use it.
        def closed = Closed(label, summon[Frame], s"register failed fd=$fd")
        if op == OpRegisterRead then
            // awaitAccept and awaitRead both use OpRegisterRead; the accept bit (set by awaitAccept) selects which registration this command takes
            // (RegKind.Accept vs RegKind.Read), so the live entry is applied to pendingAccepts vs pendingReads for THIS command, never confused with a
            // concurrently-pending registration of the other kind on the same fd.
            if accept then
                applyRegistration(fd, RegKind.Accept)
                val rc = backend.registerRead(pollerFd, fd, pollScratch)
                if rc < 0 then
                    Maybe(pendingAccepts.remove(fd)).foreach { h =>
                        h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                        h.pendingAcceptPromise = Absent
                    }
                end if
            else
                applyRegistration(fd, RegKind.Read)
                val rc = backend.registerRead(pollerFd, fd, pollScratch)
                if rc < 0 then
                    Maybe(pendingReads.remove(fd)).foreach { h =>
                        h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                        h.pendingReadPromise = Absent
                    }
                else
                    // Consumer-paced drain: re-dispatch immediately when the kernel may hold bytes with no new ET edge pending. Two cases:
                    // (1) readMightHaveMore=true: the previous recv filled the read buffer exactly; the kernel holds residual bytes
                    //     that EPOLLET will never re-signal (no new empty->ready transition when data was already present).
                    // (2) missedReads contains fd: a kernel read edge fired while no pending read was present (consumer was in a
                    //     backpressure pause). The edge was dropped by dispatchRead's Absent branch. On re-registration, epoll_ctl(MOD)
                    //     with the same mask is a no-op (no new edge fires). Remove clears the entry so it applies exactly once per
                    //     missed edge. A spurious re-dispatch (no actual bytes in the kernel) hits EAGAIN immediately and is harmless.
                    val missed = missedReads.remove(fd)
                    Maybe(pendingReads.get(fd)).foreach { h =>
                        if h.readMightHaveMore || missed then dispatchRead(fd)
                    }
                end if
            end if
        else if op == OpRegisterWrite then
            applyRegistration(fd, RegKind.Write)
            val rc = backend.registerWrite(pollerFd, fd, pollScratch)
            if rc < 0 then
                Maybe(pendingWritables.remove(fd)).foreach(_.promise.completeDiscard(Result.fail(closed)))
        else
            // OpDeregister: remove this fd's entries from the four poll-fiber-confined maps HERE, on the poll-loop carrier (single-writer).
            // The pending promises were already failed synchronously by deregisterFds (held on the handle, not in the maps), so this
            // only drops the now-cleared entries; a readiness event in the small window before this removal finds a handle whose promise is
            // already Absent and is handled by the dispatch Absent branch. Pass fdClosing to the backend so kqueue can skip EV_DELETE when the OS
            // already closed the fd. Clear any missed-readiness entry so a recycled fd does not inherit a stale entry.
            activeFds.remove(fd)
            discard(pendingReads.remove(fd))
            discard(pendingWritables.remove(fd))
            discard(pendingAccepts.remove(fd))
            discard(missedReads.remove(fd))
            backend.deregister(pollerFd, fd, fdClosing, pollScratch)
        end if
    end dispatchCmd

    /** Drain the change FIFO to empty, running each command to completion before the next so the underlying `epoll_ctl` / `kevent` syscalls execute
      * in submission order. Called once per poll cycle from [[drainFifos]] on the single poll-loop carrier, so it is the FIFO's only consumer (the
      * MpscLongQueue single-consumer contract holds with no flag). A command offered after this returns empty is drained on the next poll cycle.
      *
      * Each register command takes its matching registration from `regIntake` by (fd, kind) identity as it is processed (see [[applyRegistration]]),
      * so the registration's map puts are applied between the prior command and this one (an intervening OpDeregister therefore cannot clear a
      * registration whose register command has not yet run). A command offered after this returns empty is drained on the next poll cycle.
      */
    @scala.annotation.tailrec
    private def drainChanges()(using AllowUnsafe, Frame): Unit =
        val cmd = changeQueue.poll()
        if cmd != MpscLongQueue.Empty then
            dispatchCmd(cmd)
            drainChanges()
    end drainChanges

    // rearmRead and rearmSurvivors removed: edge-triggered registration (EPOLLET / EV_CLEAR) keeps the fd persistently armed;
    // re-arming after a readiness event is neither necessary nor correct under ET. OpRearm (value 2) is retired; see opcode constant comment.

    /** Submit a TLS engine op to the engine FIFO and return immediately. The poll-loop carrier runs the thunk in submission order on its next cycle
      * (see [[drainFifos]]), so no two engine ops for any connection on this driver overlap (one carrier is the engine-serialization invariant). A
      * pure offer with no spawn, for the same reason as [[submitChange]]: the prior `Fiber.Unsafe.init` drain task could be lost by a scheduler
      * strand, leaving engine ops undrained forever (the Native TLS deadlock); the always-running poll loop cannot be stranded that way. The
      * recv/send syscalls that surround engine ops stay outside the FIFO.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(engineQueue.offer(op))

    /** Drain the engine-op FIFO to empty, running each op to completion before the next. Called once per poll cycle from [[drainFifos]] on the single
      * poll-loop carrier, so it is the FIFO's only consumer (the ConcurrentLinkedQueue single-consumer contract holds with no flag). An op offered
      * after this returns empty is drained on the next poll cycle.
      */
    @scala.annotation.tailrec
    private def drainEngineOps()(using AllowUnsafe, Frame): Unit =
        engineQueue.poll() match
            case null => ()
            case op   =>
                // A throwing engine op must not kill the drain: that would strand every later op on this driver (a multi-connection silent hang,
                // Netty #7337 class). Surface the failure and keep draining; the throwing op's own promise handling is its concern.
                try op()
                catch case ex: Throwable => Log.live.unsafe.error(s"$label engine op threw; the engine FIFO worker continues draining", ex)
                end try
                drainEngineOps()
    end drainEngineOps

    /** Drain both per-driver FIFOs once, on the poll-loop carrier. The poll loop calls this every cycle (see [[pollLoop]]), making it the single
      * authoritative consumer of both FIFOs: `submitChange` / `submitEngineOp` only enqueue, so a submitted command or engine op is always drained
      * within one poll cycle, even when no readiness event fired (the poll's bounded ~100ms park guarantees the cycle runs). This is the recovery for
      * the Native TLS deadlock: the prior design drained on a `Fiber.Unsafe.init` task spawned per burst, which a scheduler strand could lose, leaving
      * the FIFO undrained forever; the poll loop is the one carrier running for the driver's whole life and cannot be lost that way. Draining on the
      * poll-loop carrier (not the submitting carrier) keeps the offload contract: a submit never drains inline on the carrier that called it.
      *
      * `private[posix]` so a deterministic leaf can drive exactly one poll cycle's drain after enqueuing work without starting the poll loop;
      * production calls it only from the poll loop.
      */
    private[posix] def drainFifos()(using AllowUnsafe, Frame): Unit =
        drainChanges()
        drainEngineOps()
    end drainFifos

    /** Extract the value of an already-inline-completed `@Ffi.blocking` fiber without parking (no `.block` / `LockSupport.park`).
      * `poll()` is the non-parking peek; it returns `Present` when the fiber is done (always true on JVM/Native for `@Ffi.blocking`) and
      * `Absent` on JS where the fiber may be genuinely pending (not reachable in the write path, which uses `sendNow` on JS).
      */
    private def takeNow[A](fiber: Fiber.Unsafe[A, Any])(using AllowUnsafe, Frame): Maybe[A] =
        // Unsafe: poll() peeks the result without parking. Used only for @Ffi.blocking fibers that complete inline on JVM/Native.
        if fiber.done() then
            fiber.poll() match
                case Present(Result.Success(v)) => Present(v.eval)
                case _                          => Absent
        else Absent

end PollerIoDriver

private[net] object PollerIoDriver:

    /** A pending writable registration: the waiter's promise paired with the arming handle's monotonic id. The id lets `dispatchWritable`
      * apply the same stale-fd-id equality guard the read/accept paths use (the read path reads the id off the stored `PosixHandle`; the
      * writable path stores it here because the writable promise is not held on the handle), so a writable readiness event the kernel queued
      * for a fd's prior owner is dropped after the fd is recycled into a new owner rather than delivered to the new owner as `Success`.
      */
    final private case class PendingWritable(promise: Promise.Unsafe[Unit, Abort[Closed]], id: Long)

    /** Kind of a pending interest registration carried through [[regIntake]] to the poll fiber, which applies the matching map put on its own
      * carrier (the single-writer confinement). Read and Accept share `OpRegisterRead` at the backend but route to different maps
      * (`pendingReads` vs `pendingAccepts`); Write routes to `pendingWritables`.
      */
    private enum RegKind derives CanEqual:
        case Read, Write, Accept

    /** A pending interest registration: the handle to bind plus the direction. Offered by the await methods before their change command and
      * drained by the poll fiber, which does the activeFds + pending-table put. The read/accept/writable promises are already on the handle
      * (`pendingReadPromise` / `pendingAcceptPromise` / `pendingWritablePromise`), so this carries only the handle and the kind.
      */
    final private case class Registration(handle: PosixHandle, kind: RegKind)

    /** Build a driver over a fresh poller fd for the OS-appropriate backend (epoll on Linux, kqueue on macOS/BSD). */
    def init(config: kyo.net.TransportConfig)(using AllowUnsafe, Frame): PollerIoDriver =
        val backend = PollerBackend.default()
        val fd      = backend.create()
        new PollerIoDriver(backend, fd, Ffi.load[SocketBindings])
    end init

end PollerIoDriver
