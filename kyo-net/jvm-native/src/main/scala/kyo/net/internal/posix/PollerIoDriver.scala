package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetBackendInitException
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import kyo.net.internal.util.HandleId
import kyo.net.internal.util.IntLongMap
import kyo.net.internal.util.IntRefMap
import kyo.net.internal.util.MpscLongQueue
import kyo.net.internal.util.writeFromBuffer

/** Readiness-to-completion I/O driver over epoll (Linux) / kqueue (macOS/BSD), unified onto [[PosixHandle]] and the kyo-ffi bindings.
  *
  * It carries the readiness-driver machinery, pending-table / dispatch / cancel / stale-fd-id, keyed on [[PosixHandle]]'s
  * split fds (reads on `readFd`, writes on `writeFd`) and routed through [[PollerBackend]] (epoll or kqueue) plus [[SocketBindings]] for
  * `send` / `recv` / `close`. It presents the unchanged completion `IoDriver[PosixHandle]` contract: callers deposit a `Promise` and the poll
  * loop fulfils it when the fd is ready and the read/write has been performed.
  *
  * The poll loop runs on a dedicated carrier spawned via `Fiber.Unsafe.init` and drives a `while` loop that calls
  * `backend.poll(pollerFd, timeoutMs = -1, clBuf, clN, pollScratch)` each cycle (indefinite park; the wakeup event returns it early).
  * `backend.poll` returns a `Fiber.Unsafe` wrapping the
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
  * in submission order. The poll wait runs on its own fiber (a concurrent `epoll_wait` / `kevent` against a changelist is safe
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
final private[net] class PollerIoDriver private[posix] (
    backend: PollerBackend,
    pollerFd: Int,
    sockets: SocketBindings,
    closedFlag: AtomicBoolean.Unsafe,
    // Per-driver reused poll/arm scratch. Allocated once at driver init. Ownership:
    //   eventsBuffer + fds + flags: poll loop carrier (pollLoop + drainReady on the same fiber).
    //   armBuf (epoll) / kqueueData.armBuf (kqueue): change worker (drainChanges + dispatchCmd on one worker fiber).
    // The two workers never access each other's scratch slots.
    //
    // Free ownership (single owner): the scratch is freed by the poll loop carrier at its terminal exit, when the last poll has completed and
    // the buffer is provably not in use. close() does NOT free the scratch when the loop is running: the poll loop uses it on every cycle
    // inside backend.poll, where the indefinite epoll_wait/kevent holds the off-heap buffer in use for the whole native wait, and close() runs on
    // a different carrier. Freeing it from close() while the loop is mid-poll would close the shared off-heap arena while it is still acquired
    // (JVM: "Session is acquired by 1 clients"), or surface as a use-after-free on the next cycle. close() only signals termination via
    // closedFlag; the loop sees it between polls and frees the scratch on its way out. The never-started case (close() before start(), so the
    // loop never runs) is the only path where close() frees directly: no loop is using the scratch then. freeScratchOnce CASes so the scratch
    // is freed EXACTLY once across {poll-loop terminal exit, close()-never-started path}.
    // Exposed for allocation-seam tests: allows assertions on armBuf / eventsBuffer identity across calls.
    private[posix] val pollScratch: PollScratch,
    // True once start() has spawned the poll loop carrier. close() reads it to decide who frees the scratch: when the loop ran (or is running),
    // the loop owns the free at its terminal exit; when it never ran, close() frees directly (no loop is using the scratch).
    started: AtomicBoolean.Unsafe,
    // Frees pollScratch exactly once. CASed by the poll loop's terminal exit (the loop ran) and by close()'s never-started path (the loop never
    // ran); the CAS guarantees no double-free and no use-after-free between the two single owners.
    freeScratchOnce: AtomicBoolean.Unsafe,
    // Formerly a poll-loop wakeup coalescing flag: submitChange and submitEngineOp CASed it false->true and triggered backend.wake at most once
    // between poll cycles (the poll loop CASed it back to false at the top of each cycle, before the next park), to dedup a burst of submits to
    // one wake per cycle. Retired from that role: the poll loop clears it only at the TOP of each cycle, but drainFifos (which drains both FIFOs)
    // runs BEFORE that reset, so a submit landing between a cycle's drainFifos consuming a prior wake and the next cycle's reset observed a
    // STALE true and skipped its own wake -- a wake lost that way stranded submitEngineOp's write-only-delivery-attempt permanently (the B'
    // post-upgrade write strand) and, on the same latent gap, submitChange's connection-reuse reads when the read side's usual self-healing
    // (the next cycle's unconditional drainChanges) did not run in time. Both wakes are unconditional now (see submitChange / submitEngineOp).
    // Kept ONLY so a deterministic test can pre-set the historical stale condition directly and confirm neither wake path reads it anymore
    // (PollerWakeReadArmTest, PollerWakeEngineOpTest; mirrors NioIoDriver.wakeupPending). private[posix] for that test access.
    private[posix] val wakePending: AtomicBoolean.Unsafe,
    // True in every live driver: registerWake must succeed or start() throws before the loop is spawned (the poll parks indefinitely, making the
    // wakeup a liveness requirement). Set to true by start() after registerWake succeeds; never reset afterward.
    wakeArmed: AtomicBoolean.Unsafe,
    // Wake-fd lifecycle guard: makes the wake-fd teardown (backend.closeWake, which on epoll closes the wakeup eventfd) mutually exclusive with any
    // in-flight backend.wake (which on epoll writes the wakeup eventfd's counter). Encoded like PosixHandle.guard: the low WakeHolderMask bits count
    // the backend.wake calls currently touching the wake fd, and WakeClosingBit records that the wake fd is being torn down. backend.closeWake runs
    // EXACTLY ONCE, by whichever of {the close path, the last in-flight wake} observes the holder count reaching zero with the closing bit set.
    //
    // Without this guard backend.wake reads scratch.wakeFd and issues eventfd_write on it with no exclusion against backend.closeWake, which closes
    // that fd and sets wakeFd = -1 on the poll-loop carrier's terminal exit (freeScratch). A wake on an arbitrary carrier that has read wakeFd (a
    // valid eventfd) but not yet written it can be preempted while closeWake closes that fd; the OS then recycles the freed number into ANOTHER
    // driver's freshly-opened socket, and the resumed eventfd_write writes the 8-byte counter (1) INTO that recycled socket. The peer then recv's
    // the phantom 8-byte [1,0,0,0,0,0,0,0] ahead of its real data: the lazyFdDelete cross-fd stale-event failure under full-suite load. Gating the
    // close behind the in-flight-wake count closes the window: the eventfd is never closed while a wake holds it, so its number cannot be recycled
    // out from under an eventfd_write.
    wakeGuard: AtomicInt.Unsafe,
    // Set true as the FIRST step of the poll loop's terminal exit, strictly BEFORE the final drainFifos() call. Its ONLY remaining
    // consumer is `engineFreeSink` (deciding whether a PosixHandle.close's engine-free callback may run inline on the calling carrier
    // instead of round-tripping through submitEngineOp): every carrier that can reach `engineFreeSink` while `terminal` is true but
    // [[teardownComplete]] is not yet true is provably the poll-loop carrier itself (the queued close op, or sweepPendingCloses' first
    // pass, both run on this same carrier during drainFifos()), so inline is always safe there regardless of how far the drain has
    // progressed. NOT safe to treat as "self-close now" (a prior version of this doc claimed exactly that): reading `true` here means
    // only that the terminal exit has STARTED, not that it has FINISHED, so a self-close gated on this flag alone could run
    // concurrently with the terminal exit's own still-in-flight sweep of the SAME handle (both trying to drive `shutdownTls` on the
    // same TLS engine at once). closeHandle's self-close instead gates on [[teardownComplete]], which is set only at the END of the
    // terminal exit, after every legitimate submission window has closed.
    terminal: AtomicBoolean.Unsafe,
    // Set true as the LAST step of the terminal exit's obligation handling (after the final drainFifos(), the first sweepPendingCloses(),
    // and the closeReason claim attempt), strictly BEFORE the terminal exit's own re-sweep. Distinguishes "the terminal exit has
    // STARTED" ([[terminal]]) from "the terminal exit's own submission window has FULLY CLOSED": once this is true, drainFifos() will
    // never run again, so no normally-queued engine op for ANY handle can still be pending, and dischargeClose's single-discharger claim
    // (`pendingCloses.remove`) is the only thing left serializing the terminal exit's re-sweep against closeHandle's own self-close.
    // closeHandle's TLS branch (put pendingCloses, submit the deferred close op, then re-check this flag) uses it to decide whether to
    // self-close inline instead of trusting the queued op: reading `false` here happens-before the eventual write of `true` (a plain
    // AtomicBoolean read/write pair), which happens-before the re-sweep in the terminal exit's own program order, so a pendingCloses put
    // that observed `false` is guaranteed visible to the still-upcoming re-sweep (the Dekker pairing terminalTeardown's scaladoc
    // details). close()'s own post-submit recheck uses the same flag, paired symmetrically against terminalTeardown's own post-flag
    // re-read of `closeReason`, to cover the panic-exit ordering (see close()'s doc).
    teardownComplete: AtomicBoolean.Unsafe,
    // Exactly-once gate for closeTeardown: close()'s own teardown op can strand the same way a TLS closeHandle op can (e.g. the loop
    // observes closedFlag between close()'s CAS and its submitEngineOp call and runs its entire terminal exit before the op ever lands).
    // Attempted by the queued closure close() submits AND, unconditionally, by the terminal exit itself right after sweepPendingCloses:
    // whichever runs first wins the CAS: no waiting, no flag inference, both run on carriers where the map-confinement precondition
    // already holds (the queued closure via the single-consumer engineQueue drain; the terminal exit's own attempt because it IS the
    // poll-loop carrier, sequenced after the maps' last legitimate access).
    closeTeardownClaim: AtomicBoolean.Unsafe
) extends IoDriver[PosixHandle], TlsEngineIo:

    import PollerIoDriver.PendingWritable
    import PollerIoDriver.Registration
    import PollerIoDriver.RegKind

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

    // Poll-cycle liveness counter, incremented once per poll-loop iteration and exposed through the Diagnostics dump: a frozen
    // value when a leaf hangs means the poll loop is dead/stuck; an advancing value means it is live but not delivering to some fd.
    @volatile private var diagPollCycles: Long = 0L

    // This driver's Diagnostics registration, held from start() and closed from close() so a per-test driver's dumper/probe does not
    // outlive it (Diagnostics is a process-global registry; every driver ever built registers now, not just the process-shared
    // singleton, so an unclosed registration would accumulate for the life of the process). Null until start() registers it; start()
    // runs on the construction carrier before this driver is exposed to a concurrent close(), so no synchronization guards the write
    // itself, but the field is @volatile so close() (on any carrier) observes it.
    @volatile private var diagRegistration: kyo.internal.Diagnostics.Registration | Null = null

    // The driver-closed teardown reason, set by close() the instant it wins the closedFlag CAS (before submitEngineOp), so the pollLoop's
    // terminal exit (a different carrier than close()'s caller) can run closeTeardown itself if close()'s own submitted op never gets
    // drained. Null only when the driver was never closed (terminal exit ran from a backend failure/panic instead), in which case no
    // closeTeardown claim is attempted here.
    @volatile private var closeReason: Closed | Null = null

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

    // The deregister side queue, the deregister twin of regIntake (#362, R-065). The packed long deregister command carries only the fd, not the
    // handle's HandleId (the 32-bit generation does not fit the spare command bits), but the apply must remove an fd's entries ONLY when the fd is
    // still owned by the deregistering handle: a deregister whose fd was closed and recycled into a new handle must not evict the new handle's
    // registration. deregisterFds offers the handle here, paired with each OpDeregister command (offer-then-submit on one carrier, so the poll
    // carrier sees the handle by the time it observes the command), and the apply matches it by fd (readFd or writeFd) and compares activeFds(fd)
    // against handle.id.packed, removing nothing on a mismatch. As with regIntake, matching is by fd identity, not FIFO position, tolerating the
    // independent enqueue orders of the two queues under concurrent producers.
    private val deregIntake = new ConcurrentLinkedQueue[PosixHandle]()

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

    // Registered fd-close obligations for TLS handles (mirrors IoUringDriver.pendingCloses): closeHandle's TLS branch defers the real
    // sockets.close/PosixHandle.close behind an engine op (so the close_notify send is serialized behind any in-flight read/write for this
    // connection), which strands forever if the op lands in engineQueue after the poll loop's one-shot terminal drain has already run
    // (a deferred-close obligation whose sole executor has a terminal point). Every TLS closeHandle call PUTs its handle here
    // BEFORE submitting the deferred op; the op's own discharge removes it (dischargeClose). The terminal exit's sweepPendingCloses force-
    // discharges (claim-guarded, exactly-once via PosixHandle's own fd/resource claims) whatever is still here after the final drain, so a
    // stranded obligation is always reclaimed regardless of submission timing. A plain ConcurrentHashMap-backed set: put/remove/iterate from
    // multiple carriers, no poll-fiber confinement needed (unlike activeFds/pendingReads/etc; this touches only handle-scoped state).
    private val pendingCloses = java.util.concurrent.ConcurrentHashMap.newKeySet[PosixHandle]()

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

    // Companion to missedReads for the half-close case: records every fd whose dropped read edge ALSO carried eof (peer half-close). The plain
    // missedReads entry alone would re-dispatch with eofPending=false and lose the EOF, so on the consumer's next awaitRead the OpRegisterRead
    // branch advances halfClose to PeerHalfClosePending, ensuring the drain continues until recv returns 0. Same poll-carrier-only confinement
    // and lifecycle (cleared on the re-dispatch, on deregister, and on driver close) as missedReads.
    private val missedEof = new java.util.HashSet[Int]()

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
        // The poll loop runs on a DEDICATED daemon thread, NOT a kyo scheduler carrier. It is a non-preemptible, non-suspending loop (a plain
        // while over backend.poll; on JVM/Native the @Ffi.blocking wait fiber completes inline), so on a carrier it PINS that carrier: the
        // scheduler's doPreempt cannot reclaim a loop with no fiber safepoints, and under CPU contention a fiber continuation completed inline from
        // the loop (a ReadPump byte delivery waking a parked take) can be routed back onto the pinned carrier by the scheduler's fallback and STRAND
        // there, hanging the connection. A dedicated thread keeps the loop off the carrier pool, so every fiber it completes is scheduled
        // (Worker.current() == null) onto a real carrier and nothing strands. The thread is a daemon, so it never blocks a clean JVM exit and is
        // exempt from the non-daemon-thread leak check; it exits when close() sets closedFlag and triggers the wakeup via triggerWake().
        // The returned Fiber.Unsafe completes when the loop thread exits. (JS/WASM use a separate driver and are unaffected.)
        // The wakeup is a liveness requirement for the indefinite park: without it, a submitted change or engine op has no way to return
        // the parked poll, causing a permanent stall. Arm it before starting the loop; fail the driver here rather than starting a loop
        // that would wedge on the first park with no way to recover. Armed before the loop starts so the very first submitted change
        // can wake it.
        if !backend.registerWake(pollerFd, pollScratch) then
            // epoll's own wakeup eventfd is the same shared liveness primitive; no fallback can supply it, so this failure is non-recoverable.
            throw NetBackendInitException(
                label,
                "registerWake failed; the poll-loop wakeup is a liveness requirement for the indefinite park",
                recoverable = false
            )
        end if
        started.set(true)
        wakeArmed.set(true)
        // Every driver registers a diagnostics snapshot of its poll loop, not just the process-shared singleton's: the stranded-op
        // post-suite gate (kyo-test's runner) needs a probe for every driver a suite builds, and most of kyo-net's own suites build
        // their own per-config transport rather than using the singleton. Held in diagRegistration and closed from close() so a
        // per-test driver's entry does not accumulate in the process-global registry for the rest of the run.
        //
        // The registered name carries a `processSharedTransport` marker for the by-design process-lifetime singleton (never closed, so
        // it legitimately parks forever with a pending armed read on a kept-alive connection): the stranded-op gate matches this name
        // the same way LeakCheck's fiber-leak allowlist already does (LeakCheck.defaultAllowlist), so the one driver that is SUPPOSED
        // to look parked-with-pending-work forever is exempted by the same convention, not a second one.
        val diagName =
            "PollerIoDriver@" + java.lang.System.identityHashCode(this) +
                (if kyo.net.internal.ProcessSharedTransport.isBuilding then " processSharedTransport" else "")
        diagRegistration = kyo.internal.Diagnostics.register(diagName)(
            dump = () =>
                val reads = new StringBuilder
                pendingReads.foreach((fd, h) => discard(reads.append(fd).append("(id=").append(h.id).append(") ")))
                val writes = new StringBuilder
                pendingWritables.foreach((fd, _) => discard(writes.append(fd).append(' ')))
                val accepts = new StringBuilder
                pendingAccepts.foreach((fd, h) => discard(accepts.append(fd).append("(id=").append(h.id).append(") ")))
                s"closed=${closedFlag.get()} pollCycles=$diagPollCycles activeFds=${activeFds.size} " +
                    s"changeQueuePending=${changeQueue.peekNonEmpty()} engineQueuePending=${!engineQueue.isEmpty()} " +
                    s"pendingClosesSize=${pendingCloses.size()} wakePending=${wakePending.get()} " +
                    s"pendingReads=[$reads] pendingWritables=[$writes] pendingAccepts=[$accepts]"
            ,
            probe = () =>
                kyo.internal.Diagnostics.Probe(
                    closed = closedFlag.get(),
                    cycles = diagPollCycles,
                    // engineQueue and pendingCloses: a live driver stuck with a queued engine op or an undischarged fd-close
                    // obligation, with no cycle progress, is the same class of lost-wakeup this probe already catches for
                    // changeQueue/pendingReads/etc. engineQueue was omitted before, which is why a stranded-close obligation could hide
                    // behind a "pending = false" probe read even while the driver was still open. This is a LIVE-driver signal only: the
                    // authoritative closed-driver leak check is terminalTeardown's own post-completion re-sweep, reported directly to
                    // Diagnostics.reportViolation (see terminalTeardown's doc), since this probe's `closed => Ok` exemption in
                    // StrandedOpCheck cannot see a leak on an already-closed driver.
                    pending = changeQueue.peekNonEmpty() || !engineQueue.isEmpty() || !pendingCloses.isEmpty() ||
                        pendingReads.size > 0 || pendingWritables.size > 0 || pendingAccepts.size > 0
                )
        )
        val donePromise = Promise.Unsafe.init[Unit, Any]()
        val thread =
            new Thread(
                () =>
                    try
                        pollLoop()
                        donePromise.completeDiscard(Result.succeed(()))
                    catch
                        case t: Throwable =>
                            if !closedFlag.get() then Log.live.unsafe.error(s"$label poll loop crashed", t)
                            donePromise.completeDiscard(Result.panic(t))
                ,
                s"$label-poll-loop"
            )
        thread.setDaemon(true)
        thread.start()
        donePromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    /** Free the per-driver poll scratch exactly once. Called by the poll loop carrier at its terminal exit (the loop ran and its last poll has
      * completed, so the buffer is provably not in use) and by close()'s never-started path (the loop never ran). The CAS makes it idempotent
      * across those two single owners: no double-free, and no use-after-free (the loop frees only after its last poll; close() frees only when
      * no loop runs).
      */
    private def freeScratch()(using AllowUnsafe): Unit =
        if freeScratchOnce.compareAndSet(false, true) then
            // Close the wakeup fd (epoll eventfd; kqueue no-op) before freeing the scratch buffers, gated by the wake guard so the eventfd is closed
            // EXACTLY ONCE and ONLY when no in-flight backend.wake still holds it (closeWakeGuarded blocks the close until the last wake releases; a
            // wake that arrives after the closing bit is set is refused). This is what prevents the eventfd's fd number from being closed out from
            // under an in-flight eventfd_write and then recycled into another driver's socket (the lazyFdDelete cross-fd stale-event race). The
            // freeScratchOnce CAS still guards the buffer free below; the wake guard separately guards the wake-fd close.
            closeWakeGuarded()
            pollScratch.close()
    end freeScratch

    /** Acquire the wake fd for one [[PollerBackend.wake]] call: register this carrier as an in-flight wake holder so [[closeWakeGuarded]] cannot close
      * the wake fd while the wake is operating on it. Returns `false` once the wake fd is closing (or closed), in which case the caller skips the wake
      * rather than touching a fd that may be closed and recycled. Mirrors [[PosixHandle.acquire]]: CAS-increment the holder count unless the closing bit
      * is set; loops over CAS contention with a concurrent acquire/release/close.
      */
    private def acquireWake()(using AllowUnsafe): Boolean =
        var result = false
        var done   = false
        while !done do
            val g = wakeGuard.get()
            if (g & PollerIoDriver.WakeClosingBit) != 0 then done = true // closing/closed: refuse the wake
            else if wakeGuard.compareAndSet(g, g + 1) then
                result = true
                done = true
            end if
        end while
        result
    end acquireWake

    /** Release the wake fd after one [[PollerBackend.wake]] call. If this is the last in-flight wake AND the close path has set the closing bit, this
      * caller performs the deferred [[PollerBackend.closeWake]] exactly once (the last-releaser-frees handoff, mirroring [[PosixHandle.release]]).
      */
    private def releaseWake()(using AllowUnsafe): Unit =
        var done = false
        while !done do
            val g       = wakeGuard.get()
            val holders = g & PollerIoDriver.WakeHolderMask
            val closing = (g & PollerIoDriver.WakeClosingBit) != 0
            if holders == 1 && closing then
                // Last holder while closing: take the guard to its terminal value and run the wake-fd close exactly once.
                if wakeGuard.compareAndSet(g, PollerIoDriver.WakeClosed) then
                    backend.closeWake(pollScratch)(using summon[AllowUnsafe], Frame.internal)
                    done = true
            else if wakeGuard.compareAndSet(g, g - 1) then done = true
            end if
        end while
    end releaseWake

    /** Close the wake fd, coordinated against any in-flight [[PollerBackend.wake]] via the wake guard. Sets the closing bit so no new wake acquires; if
      * no wake holds the fd right now this performs [[PollerBackend.closeWake]] immediately, otherwise the last in-flight wake's [[releaseWake]] performs
      * it. Idempotent once the guard reaches its terminal [[PollerIoDriver.WakeClosed]] value. Called only from [[freeScratch]] (the poll-loop terminal
      * exit, or close()'s never-started path), so it runs at most twice across the two single owners and the CAS makes the actual close happen once.
      */
    private def closeWakeGuarded()(using AllowUnsafe): Unit =
        var done = false
        while !done do
            val g = wakeGuard.get()
            if g == PollerIoDriver.WakeClosed then done = true // already closed by a prior closeWakeGuarded / a last-releaser wake
            else
                val holders = g & PollerIoDriver.WakeHolderMask
                if holders == 0 then
                    // No wake holds the fd: close now and go terminal.
                    if wakeGuard.compareAndSet(g, PollerIoDriver.WakeClosed) then
                        backend.closeWake(pollScratch)(using summon[AllowUnsafe], Frame.internal)
                        done = true
                else
                    // A wake is in flight: set the closing bit and defer the close to that wake's releaseWake.
                    if wakeGuard.compareAndSet(g, g | PollerIoDriver.WakeClosingBit) then done = true
                end if
            end if
        end while
    end closeWakeGuarded

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
            // armSocketWritable -> submitChange) is registered with the kernel NOW, and the indefinite park that follows then returns immediately
            // when the fd is already ready (a loopback connect's socket is writable at once, so epoll_wait/kevent returns at once). This, paired
            // with the submitChange wake that returns a park when work arrives DURING it, is what keeps the connect write-readiness from being
            // stranded until the next I/O event past a short connect deadline (the NetConnectTimeoutException regression).
            // wakePending no longer gates either wake path (both submitChange and submitEngineOp are unconditional); this reset only keeps the
            // flag's per-cycle lifecycle consistent for the tests that pre-set it (see wakePending's own doc).
            diagPollCycles += 1L
            wakePending.set(false)
            drainChanges()
            // Pass the kqueue changelist (changelistBuf + nChanges) so kevent can submit pending changes (e.g. EV_DISABLE from
            // dispatchWritable) atomically with the wait. On epoll the changelist / nChanges arguments are ignored by EpollPollerBackend.poll.
            val (clBuf, clN) = pollScratch.kqueueData match
                case Present(kq) => (kq.changelistBuf, kq.nChanges)
                case Absent      => (pollScratch.armBuf, 0) // epoll: unused sentinel
            val waitFiber =
                backend.poll(pollerFd, timeoutMs = -1, clBuf, clN, pollScratch) // indefinite park; the wake event returns it early
            if waitFiber.done() then
                // JVM/Native inline-completion path: the @Ffi.blocking wait completed synchronously. Extract events and continue the
                // while loop without growing the stack (the while iteration is the tail-loop equivalent of @tailrec).
                waitFiber.poll() match
                    case Present(Result.Success(_)) =>
                        drainReady(pollScratch.fds, pollScratch.flags, pollScratch.ids, pollScratch.readyCount)
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
                        drainReady(pollScratch.fds, pollScratch.flags, pollScratch.ids, pollScratch.readyCount)
                        // Drain both FIFOs on the JS re-entry path too: JS is single-threaded so it never strands a drain, but the poll loop is the
                        // sole FIFO consumer on every platform, so the drain must run on whichever poll path executes. This also runs any
                        // close-teardown engine op (the maps are poll-fiber-confined; JS is single-threaded so the teardown never races the loop).
                        drainFifos()
                        if !closedFlag.get() then discard(Fiber.Unsafe.init { pollLoop() })
                        else
                            // Terminal JS exit: closed flag set, not re-entering, last poll done. terminalTeardown's own drainFifos is a
                            // harmless extra pass (JS is single-threaded, so there is no cross-thread ordering concern here: nothing else
                            // can run concurrently with the drainFifos above).
                            terminalTeardown()
                            backend.close(pollerFd)
                            freeScratch()
                        end if
                    case _ =>
                        // Terminal JS exit: wait failed, not re-entering.
                        terminalTeardown()
                        backend.close(pollerFd)
                        freeScratch()
                }
            end if
            // Drain both FIFOs once per poll cycle on the JVM/Native always-running carrier, whether or not events fired this cycle: the poll loop is
            // the sole consumer of the change/engine FIFOs, so a command or engine op enqueued by submitChange/submitEngineOp (or by this cycle's own
            // dispatch) is drained here within one cycle. Each submit triggers the poll-loop wakeup so an indefinitely-parked poll returns when work
            // arrives; a re-arm or engine op submitted while idle is never stranded. On the JS pending path the loop exited before reaching here, so
            // the JS drain runs in onComplete.
            if !enteredPending then drainFifos()
        end while
        // Terminal exit on JVM/Native (the while loop ended on closedFlag or a backend failure/panic): the last poll has completed and the maps +
        // scratch are provably not in use, so the poll loop carrier finishes the teardown here. terminalTeardown runs any close-teardown engine
        // op that close() submitted concurrently with the closedFlag set (so it is never stranded by the loop exiting first) and sweeps any
        // fd-close obligation a TLS closeHandle registered too late to be drained normally; since it runs on this poll-loop carrier, the map
        // access stays poll-fiber-confined. backend.close(pollerFd) runs AFTER the last poll, so the poller fd is never closed under an
        // in-flight epoll_wait/kevent. Skipped when the JS branch parked on a pending wait fiber (not terminal: it tears down from the
        // onComplete instead).
        if !enteredPending then
            terminalTeardown()
            backend.close(pollerFd)
            freeScratch()
        end if
    end pollLoop

    /** The poll loop's terminal-exit teardown, shared by every exit path (JVM/Native's terminal `while`-exit and both JS onComplete terminal
      * branches): set [[terminal]] before the final drain (the ordering [[closeHandle]]'s put-then-recheck relies on), drain once more
      * (picks up anything already queued), sweep any fd-close obligation that missed the drain, set [[teardownComplete]], then RE-SWEEP
      * and re-read [[closeReason]] before checking for a genuine invariant violation.
      *
      * The re-sweep and re-read are the Dekker half of two independent races, both closed by pairing a write here with a recheck on the
      * OTHER carrier:
      *
      *   - `pendingCloses` vs. closeHandle's self-close: closeHandle puts the handle in `pendingCloses`, submits the deferred op, THEN
      *     rechecks [[teardownComplete]] to decide whether to self-close. If that recheck reads `false`, the put happens-before this
      *     re-sweep's iteration (both are on the SAME `pendingCloses` map; the put preceded the read in closeHandle's own program order,
      *     and the read preceded this re-sweep in real time by definition of reading `false`), so the re-sweep is guaranteed to observe
      *     it. If the recheck instead reads `true`, closeHandle self-closes directly. Either way the obligation is discharged by
      *     someone; [[dischargeClose]]'s own `pendingCloses.remove` is what makes the two attempts (this re-sweep's and a concurrent
      *     self-close's) resolve to exactly one winner if both fire.
      *   - `closeReason` vs. close()'s post-submit recheck: this re-read happens strictly after `teardownComplete.set(true)` in this
      *     method's own program order; close() rechecks `teardownComplete` strictly after its own `closeReason` store (see close()'s
      *     doc). Whichever of {this read, close()'s recheck} runs later in real time is guaranteed to observe the other side's
      *     already-completed write, covering the panic-exit ordering (the loop dies from a backend failure before close() is ever
      *     called, so this method runs with `closeReason` still null; close() rechecks `teardownComplete` after the fact and finds it
      *     already true).
      *
      * Note: `ConcurrentHashMap`'s iteration is only weakly consistent, so the `pendingCloses` happens-before argument above is
      * not a strict JMM guarantee by itself. The actual safety net is the double sweep (before and after
      * `teardownComplete.set(true)`) plus the violation report below: a missed entry surfaces as a reported violation and a red
      * run, never a silent leak or corruption.
      *
      * A `pendingCloses` entry surviving THIS re-sweep is a genuine invariant violation, not a false-positive
      * candidate the way a single pre-flag sweep would be (that could still race a closeHandle call that had not yet reached its own
      * recheck). It can never be serviced (nothing will ever call `drainFifos` again), so it is reported to
      * [[kyo.internal.Diagnostics.reportViolation]], which fails the kyo-test run even though the driver is closed by then (unlike the
      * probe-based stranded-op check, which exempts a closed component by design). `engineQueue`'s own size stays in the human-readable
      * diagnostics dump only (see the `dump` thunk in `start()`): a queued-but-already-serviced op can legitimately still sit there after
      * being drained by identity, not value, so it is not a sound error predicate on its own.
      */
    private def terminalTeardown()(using AllowUnsafe, Frame): Unit =
        terminal.set(true)
        drainFifos()
        sweepPendingCloses()
        teardownComplete.set(true)
        sweepPendingCloses()
        val reason = closeReason
        if reason != null && closeTeardownClaim.compareAndSet(false, true) then closeTeardown(reason)
        if !pendingCloses.isEmpty() then
            val stranded = new StringBuilder
            pendingCloses.forEach(h => discard(stranded.append("fd=").append(h.readFd).append('/').append(h.writeFd).append(' ')))
            kyo.internal.Diagnostics.reportViolation(
                s"$label: ${pendingCloses.size()} close obligation(s) [$stranded] survived the terminal teardown's post-completion " +
                    "re-sweep (stranded-close class regression)"
            )
        end if
    end terminalTeardown

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        handle.pendingReadPromise = Present(promise)
        // The activeFds + pendingReads puts are applied on the poll fiber (single-writer): enqueue the registration, then submit the
        // change command. The poll fiber drains regIntake before processing the register command, so the map entry is in place when dispatchCmd
        // runs (the registration is published before the command via the change-queue happens-before, and the intake drain precedes the command).
        // rc<0 failure is handled inside dispatchCmd, which reads pendingReads and fails the stored promise. The pendingReadPromise store
        // happens-before the changeQueue.offer (the MpscLongQueue offer tail swap is the barrier), so the change worker sees it on rc<0.
        regIntake.offer(Registration(handle, RegKind.Read))
        submitChange(packCmd(OpRegisterRead, handle.readFd))
        // Offer-then-recheck against [[terminal]], mirroring the poll-fiber-confined idiom this file already uses elsewhere
        // (dischargeClose's own put-then-recheck). Once the poll loop's terminal exit has started, its own final drainFifos() call is
        // the LAST chance this registration will ever get to be applied; relying on that alone risks stranding this promise forever if
        // it landed too late even for that drain (the loop will never cycle again after `terminal` is set). Fail it directly instead:
        // `pendingReadPromise` is already held on the handle (deregisterFds's own synchronous-fail pattern), so no poll-fiber-confined
        // map access is needed here, and `completeDiscard` is idempotent against a legitimate concurrent dispatch or closeTeardown also
        // completing it. This drives the existing onFailed -> teardown() -> closeHandle -> self-close chain end-to-end for free.
        if terminal.get() then
            val closed = Closed(label, summon[Frame], s"fd=${handle.readFd} driver closed")
            handle.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
            handle.pendingReadPromise = Absent
        end if
    end awaitRead

    /** STARTTLS upgrade confinement: make the poll carrier the sole producer of the upgrade's ciphertext reads. The handshake parked a waiter on
      * [[PosixHandle.upgradeHandoff]] and calls this; we arm a read whose dispatch ([[dispatchReadPlain]], gated on [[PosixHandle.upgradeActive]])
      * reads the peer flight on the poll carrier and fulfils that waiter through the slot, never completing this promise with bytes. The promise is a
      * vehicle to hold the read armed and to be failed on close; it is never observed by the handshake. Setting [[PosixHandle.handshakeReading]]
      * admits this arm past [[applyRegistration]]'s stray-pump-rearm guard (the retiring plaintext ReadPump's own re-arm is rejected while
      * `upgradeActive && !handshakeReading`). Demand-driven: the handshake calls this once per read, so the producer arms one read per peer flight.
      */
    override def armUpgradeProducerRead(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        handle.pendingReadPromise = Present(producer)
        handle.handshakeReading = true
        // Edge-triggered recovery for the upgrade producer, on BOTH poller backends: a peer handshake flight ALREADY buffered when this arms
        // produces no fresh readiness edge that would re-dispatch the read, so the read never fires and the upgrade strands to the leaf timeout.
        // On epoll the register-once optimization skips the epoll_ctl(MOD) on an unchanged mask, so no new EPOLLET edge is queued. On kqueue the
        // read interest is EV_CLEAR (edge-triggered): the standing registration does not re-report bytes buffered before the arm, and a re-arm that
        // does not flip the armed mask carries no fresh edge either. Seed missedReads so the registration's apply force-dispatches one read: it
        // drains a buffered flight now, or EAGAINs and stays armed for the next edge, reusing the consumer-paced-drain recovery the normal read path
        // drives via readMightHaveMore. The speculative read is the canonical edge-triggered contract (drain to EAGAIN); a redundant dispatch with
        // no bytes hits EAGAIN and re-arms harmlessly, so it is safe on both backends.
        discard(missedReads.add(handle.readFd))
        regIntake.offer(Registration(handle, RegKind.Read))
        submitChange(packCmd(OpRegisterRead, handle.readFd))
    end armUpgradeProducerRead

    /** Force the first post-upgrade ReadPump arm to re-evaluate readiness so an application flight already buffered when the upgraded connection
      * starts is not stranded waiting for a fresh edge that never comes. Both poller backends are edge-triggered and need it: epoll's register-once
      * skips the MOD on the already-registered fd (no new EPOLLET edge), and kqueue's EV_CLEAR standing registration does not re-report bytes buffered
      * before the arm. Seed missedReads so the arm's apply force-dispatches one read, the same recovery [[armUpgradeProducerRead]] uses for the
      * producer arms. Runs on the poll carrier from `onFinished` (the handshake completes on the poll carrier), so the missedReads write stays
      * poll-fiber-confined.
      */
    override def forceReadRecovery(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        discard(missedReads.add(handle.readFd))

    /** STARTTLS handoff: the plaintext [[ReadPump]] pulled `bytes` off the socket, but by the time it tried to deliver them the inbound
      * channel was already closed. If the handle is upgrading, deliver them into [[PosixHandle.upgradeHandoff]] instead of dropping them:
      * mirrors [[NioIoDriver]]'s own override, and reuses [[deliverToUpgradeHandoff]] (the exact slot [[dispatchUpgradeRead]] already stages
      * into and [[kyo.net.internal.posix.PosixTransport.driveUpgradeRead]] already knows how to drain), so the handshake picks these bytes up
      * on its very next read with no other change needed.
      *
      * This is a DIFFERENT race than [[applyRegistration]]'s stray-pump-rearm guard (2125) already rejects: that guard covers a NEW `awaitRead`
      * registration attempt made AFTER `upgradeActive` is observed true. This covers a read that was ALREADY DISPATCHED (recv() already ran,
      * off the socket, via the normal `dispatchReadPlain` path) before `upgradeToTls` set `upgradeActive`, whose DELIVERY then runs later, on
      * whatever carrier the scheduler resumes `ReadPump.onComplete` on -- not necessarily inline with the read, and not necessarily before
      * `detachForUpgrade`'s `inbound.close()` (called from an arbitrary caller carrier) has already run. `dispatchRead`'s own `upgradeActive`
      * check (1451) is evaluated once, at dispatch time; it cannot see a flag flip that has not happened yet, so a read that raced ahead of the
      * flag is a real, reachable outcome, not a bug in that check. Without this override those bytes (the peer's first TLS flight, e.g. the
      * ClientHello) were silently discarded, stranding the handshake waiting for data that already arrived and was thrown away.
      *
      * A non-upgrade close (an ordinary teardown) leaves `upgrading` false and the bytes are discarded, exactly as before.
      */
    override def onInboundClosedDuringRead(handle: PosixHandle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        if handle.upgrading then
            val arr = bytes.toArrayUnsafe
            // One-shot claim on lastPlaintextRead: PosixTransport.feedCoalescedHandshake races this same chunk through the SAME slot (both
            // alias it for the peer's first upgrade flight). Whichever side wins the CAS is the sole feeder; the loser (this call arriving
            // after feedCoalescedHandshake already claimed it, or vice versa) skips, so the flight is never delivered to the engine twice.
            val delivered = handle.lastPlaintextRead.get() match
                case p @ Present(last) if last eq arr => handle.lastPlaintextRead.compareAndSet(p, Absent)
                case _                                => false
            if delivered then deliverToUpgradeHandoff(handle, arr)
    end onInboundClosedDuringRead

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
            handle.backpressurePromise = Present(promise)
            // Double-check on the FIFO worker: a flush may have drained the tail below the low-water mark between the check above and this
            // registration (flushPending runs on the FIFO worker, this runs on the pump carrier). Routing the re-check through the FIFO observes a
            // consistent tail snapshot (the tail fields are FIFO-worker-owned) and completes the just-registered waiter if the drain already
            // happened, so the pump is never stranded waiting on a drain that has already passed. A waiter parked while the handle is closing is
            // failed by PosixHandle.freeResources, so the close-vs-park race never strands the pump.
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
        // Same offer-then-recheck as awaitRead (see its doc); every caller of this primitive (awaitWritable's below-bound path,
        // awaitConnect, armWritableForFlush) inherits the protection from this one call site.
        if terminal.get() then
            val closed = Closed(label, summon[Frame], s"fd=${handle.writeFd} driver closed")
            handle.pendingWritablePromise.foreach(_.completeDiscard(Result.fail(closed)))
            handle.pendingWritablePromise = Absent
        end if
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
        // Same offer-then-recheck as awaitRead (see its doc).
        if terminal.get() then
            val closed = Closed(label, summon[Frame], s"fd=${handle.readFd} driver closed")
            handle.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
            handle.pendingAcceptPromise = Absent
        end if
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
        inline given Frame = Frame.internal
        if data.isEmpty || offset >= data.size then WriteResult.Done
        else if handle.unsentTailBytes >= PosixHandle.WriteTailHighWater then
            // Write-backpressure bound (CWE-400): the TLS write tail (pendingCipher) has reached the high-water mark because the peer is not draining
            // it fast enough. Do NOT encrypt and append more (which would grow the tail without limit toward OOM); report TailPartial so the WritePump
            // parks on the tail-bound backpressure path (Backpressured state) rather than on socket writability. The data is not consumed, so it is
            // re-presented unchanged on retry, and awaitWritable holds the pump until the in-flight flush drains the tail below the low-water mark
            // (releaseBackpressureWaiter). This folds the async TLS write tail into the same backpressure flow the synchronous raw path already obeys.
            // Checked before beginWrite so an over-bound write touches no guard / engine. (The raw path never reaches here: the poller's raw send is
            // inline and reports its own Partial straight from the send syscall, so its tail is always bounded by the kernel send buffer.)
            WriteResult.TailPartial(data, offset)
        else
            handle.tls match
                case Present(engine) =>
                    // Unlike the plaintext branch below, beginWrite is NOT acquired here. writeTls submits its work to the engine
                    // FIFO and acquires (and releases) the guard INSIDE that submitted op, not on this caller's carrier: see writeTls's doc.
                    writeTls(handle, data, engine)
                case Absent =>
                    if !handle.beginWrite() then
                        // The handle was closed (resources freed) before this write acquired them; bail without touching the buffers (the
                        // write twin of dispatchRead's !beginDispatch guard). The pump treats Error as teardown, which is correct for a
                        // write on a closed handle. Plaintext writeRaw runs synchronously on this same carrier (no engine FIFO hop), so
                        // acquiring here is safe: the guard is never held across a deferred/stranded op the way a submitted TLS op could.
                        WriteResult.Error
                    else
                        // For plaintext writes, endWrite is called here synchronously after writeRaw completes.
                        try writeRaw(handle, data, offset)
                        finally discard(handle.endWrite())
                        end try
                    end if
            end match
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
        inline given Frame = Frame.internal
        val len            = data.size - offset
        val flags          = PosixConstants.MSG_NOSIGNAL
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
      *
      * `beginWrite` is acquired INSIDE this submitted op, not by `write()`'s caller beforehand, mirroring [[armWritableForFlush]]'s
      * own re-submitted flush op: a stranded (never-drained) op then never acquires the write guard at all, rather than holding it from
      * the caller's synchronous acquire until a drain that may never come. A held-forever guard would also block
      * [[PosixHandle.freeResources]] forever (its `HandleGuard` requires no active write holder before it frees the engine), stranding
      * the engine free ON TOP of the original close obligation. FIFO ordering, not a cross-carrier guard hold, is what keeps the engine
      * alive for ops queued before a close op: this op's guard is acquired and released entirely within its own turn, before any close op
      * queued after it runs.
      */
    private def writeTls(handle: PosixHandle, data: Span[Byte], engine: TlsEngine)(using AllowUnsafe, Frame): WriteResult =
        submitEngineOp { () =>
            // A failed acquire means the handle was closed before this op got a turn (or this op was stranded and force-discharged by a
            // terminal sweep): silently skip the write rather than touching the (possibly freed) engine/buffers. The caller already saw
            // Done; there is no return path left to report this once the op is queued.
            if handle.beginWrite() then
                try
                    // Encrypt the plaintext through the shared engine loop, appending each drained ciphertext chunk to the pending tail; then
                    // send as much of the tail as the socket accepts (the poller's inline-send flush). The engine loop is shared with the
                    // io_uring driver; the inline send + writability re-arm below are the poller's send mechanism.
                    discard(encryptPlaintext(handle, data, engine)((drain, n) => appendPending(handle, drain, n)))
                    flushPending(handle)
                finally discard(handle.endWrite())
                end try
            end if
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
      * The double-arm guard ([[PosixHandle.flushReArmPending]]) has two writers, causally serialized: this method (on the engine FIFO worker) SETS it
      * true while arming, and the completion callback below CLEARS it false when the writable promise fires. It coalesces appends: if a flush is
      * already pending, an append that adds more bytes does NOT arm a second writable, because the already-pending flush re-submits a
      * [[flushPending]] that sends the unsent region (which now includes the appended bytes). On the writable readiness the completion callback
      * re-submits a fresh engine op that re-acquires the write guard (beginWrite), so the guard is never held across the awaitWritable suspension:
      * a slow peer cannot block a deferred engine free. A close/cancel fails the writable promise, whose Failure/Panic branch only clears the arm
      * flag and never touches the (possibly freed) pending buffer.
      */
    private def armWritableForFlush(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if handle.flushReArmPending then () // a flush re-arm is already pending; it will pick up any appended bytes
        else
            handle.flushReArmPending = true
            val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
            p.onComplete {
                case Result.Success(_) =>
                    handle.flushReArmPending = false
                    submitEngineOp { () =>
                        if handle.beginWrite() then
                            try flushPending(handle)
                            finally discard(handle.endWrite())
                            end try
                    }
                case _ =>
                    // Close / cancel failed the promise: bail without re-touching the (possibly freed) pending state.
                    handle.flushReArmPending = false
            }
            // Arm SOCKET write-readiness directly: the flush re-arm needs the kernel-send-buffer-has-room signal so it can send more of the tail.
            // It must NOT go through the tail-aware public awaitWritable, whose tail-bound branch would mis-route this flush promise into the
            // backpressure slot (the tail is high here, since the flush just EAGAINed), stranding the re-arm and the tail's only drain path.
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
        // pendingWritablePromise / backpressurePromise), so failing them needs no map access. completeDiscard is idempotent, so if the poll fiber's
        // deregister removal also tried to fail them (it does not), there would be no double-completion hazard.
        // Pair each OpDeregister with the handle on deregIntake so the apply can id-guard the removal (#362). The offer precedes the submitChange
        // on this one carrier, so the poll carrier sees the handle by the time it observes the command (the changeQueue offer publishes it).
        deregIntake.offer(handle)
        submitChange(packCmd(OpDeregister, handle.readFd, fdClosing))
        if handle.writeFd != handle.readFd then
            deregIntake.offer(handle)
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
        handle.backpressurePromise.foreach(_.completeDiscard(Result.fail(closed)))
        handle.backpressurePromise = Absent
    end deregisterFds

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Public IoDriver cancel: the fd is still open (live-fd withdrawal). EV_DELETE must execute on kqueue to prevent stale events.
        deregisterFds(handle, fdClosing = false)

    /** Claim this handle's fd close (the one-shot [[PosixHandle.claimFdClose]]) and, if won, shut it down immediately and install the deferred
      * real `close(fd)` as [[PosixHandle.fdCloseSink]] -- the shared claim-then-defer dance every abrupt (non-`close_notify`) close path on
      * this driver runs BEFORE calling `PosixHandle.close` / `requestClose` for the handle. Winning the claim proves the fd is still owned by
      * this handle, so the immediate shutdown is safe; the real close is deferred to `freeResources`, the same exactly-once, zero-holders
      * point that frees the engine and buffers, so a carrier still mid-syscall on this fd under a live `beginWrite` / `beginDispatch` hold is
      * never exposed to a fd number the kernel has already recycled to an unrelated connection. A no-op if the claim is already spent
      * (another path is handling this handle's fd) or `readFd != writeFd` (stdio, whose fds are never closed this way).
      *
      * Every caller MUST install this credit before its own call into `PosixHandle.close` / `requestClose` reaches the guard: `freeResources`
      * runs at most once per handle, so a `requestClose` that fires before this credit exists (e.g. a bare `handle.requestClose()` racing this
      * claim) permanently forfeits the fd close, since the terminal guard never runs `freeResources` a second time to pick the credit up later.
      */
    private def claimAndDeferFdClose(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if handle.readFd == handle.writeFd && handle.claimFdClose() then
            discard(sockets.shutdown(handle.readFd, PosixConstants.SHUT_RDWR))
            handle.fdCloseSink = Present(() => discard(takeNow(sockets.close(handle.readFd))))
    end claimAndDeferFdClose

    /** Discharge one TLS handle's deferred close obligation: best-effort close_notify (RFC 8446 6.1 / RFC 5246 7.2.1), claim-guarded fd close,
      * and PosixHandle.close (which frees the engine + buffers). Single-discharger: `pendingCloses.remove(handle)` IS the claim, gating the
      * WHOLE body, not just the fd close. At most three carriers ever call this for the same handle (the normal queued engine op, the
      * terminal exit's sweepPendingCloses / re-sweep, and closeHandle's own post-[[teardownComplete]] self-close), and exactly one of them
      * ever observes `true` from the removal: `ConcurrentHashMap`'s remove is atomic, so only the winner's body runs, and every other
      * caller's call is a safe no-op.
      *
      * This eliminates the overlap a prior version of this method allowed: closeHandle's self-close used to skip the graceful close_notify
      * specifically because it could run concurrently with the terminal sweep's own attempt on the SAME handle (two carriers both driving
      * `shutdownTls` on one TLS engine at once). With single-discharge there is no such overlap to guard against: whichever carrier wins
      * the claim is PROVABLY the only one inside this body for this handle, so it can always attempt the close_notify. The three
      * potential callers are also never live at once in practice: the normal queued op only runs during (or before) `drainFifos()`, which
      * has unconditionally finished by the time [[teardownComplete]] is set, so by the time a self-close or a re-sweep can even run, the
      * queued op is already a dead letter (already discharged, or never going to run again).
      */
    private def dischargeClose(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if pendingCloses.remove(handle) then
            handle.tls.foreach { engine =>
                if handle.beginWrite() then
                    try shutdownTls(handle, engine)
                    finally discard(handle.endWrite())
                    end try
            }
            // Shut the fd down (not close it) right after the (attempted) alert flush, so the FIN follows the close_notify on the wire when
            // both run; see claimAndDeferFdClose for why the real close is deferred instead of run here.
            claimAndDeferFdClose(handle)
            // Request the resource free here, after shutdownTls and the fd shutdown, so the CloseBit is set only after the alert is sent.
            PosixHandle.close(handle)
        end if
    end dischargeClose

    /** Visit every fd-close obligation currently registered in [[pendingCloses]] and offer each to [[dischargeClose]]. Does not itself
      * decide who wins a given handle: [[dischargeClose]]'s own `pendingCloses.remove` is the single-discharger claim, so visiting a
      * handle a concurrent self-close (or the other sweep pass) already claimed is a safe no-op here. Called from the terminal exit both
      * before and after [[teardownComplete]] is set (see `terminalTeardown`'s doc for why the second pass is required, not redundant).
      */
    private def sweepPendingCloses()(using AllowUnsafe, Frame): Unit =
        val it = pendingCloses.iterator()
        while it.hasNext do
            dischargeClose(it.next())
        end while
    end sweepPendingCloses

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Close path: the fd will be closed below. The OS auto-removes kqueue filters on close, so EV_DELETE is unnecessary and dangerous
        // (a recycled fd number would receive EV_DELETE intended for the old fd). Pass fdClosing=true to skip EV_DELETE on kqueue.
        deregisterFds(handle, fdClosing = true)
        // Route the engine free through the engine FIFO so it is serialized behind any read/write engine ops for this connection (no two
        // carriers touch one ssl). Installed before the engine op runs so freeResources sees the sink. Terminal-aware: once the poll loop's
        // terminal exit has started, submitting here would strand the free the same way an unguarded closeHandle op would (the loop's own
        // per-cycle drain is gone), so free inline instead. Safe either way: freeResources only invokes this once the handle's own
        // HandleGuard confirms no read/write holder is active, so whichever carrier ends up running it is already the sole owner.
        handle.engineFreeSink = op => if terminal.get() then op() else submitEngineOp(op)
        handle.tls match
            case Present(engine) =>
                // TLS close: emit this side's close_notify and flush it to the wire BEFORE closing the fd, then close the fd, all in ONE
                // engine-FIFO op so the alert and the fd close are serialized in order behind any in-flight read/write engine op and the
                // alert reaches the peer ahead of the FIN. Bounded + non-blocking: shutdownTls runs one shutdownStep and a best-effort
                // inline send (it never waits for the peer's close_notify and never blocks a carrier), so close() always completes.
                //
                // Registered in pendingCloses BEFORE the op is submitted (the teardown-fix invariant: register synchronously on the
                // calling carrier before any deferred step). If the poll loop's terminal exit has ALREADY finished its own submission
                // window by the time this checks [[teardownComplete]] (put-then-recheck), the queued op above will never be drained
                // (nothing will ever call `drainFifos` again): self-close now. Safe and always graceful now that [[dischargeClose]] is
                // single-discharger (see its doc): the claim guarantees this call cannot overlap the terminal exit's own re-sweep of the
                // same handle even though both may attempt it.
                pendingCloses.add(handle)
                submitEngineOp { () => dischargeClose(handle) }
                if teardownComplete.get() then dischargeClose(handle)
            case Absent =>
                // Plaintext close: no close_notify to emit. recv/send after this fail with 0/EPIPE, proving the close to any carrier still
                // mid-syscall on it (see claimAndDeferFdClose). The claimFdClose CAS makes this one-shot: a STARTTLS upgrade failure racing
                // this closeHandle targets the SAME fd, so without the claim the fd could be shut down/closed twice (and a recycled fd
                // belonging to another connection wrongly touched). Synchronous, on the calling carrier: never deferred via the engine FIFO,
                // so it needs no pendingCloses registration (nothing here can be stranded by a driver terminal exit).
                claimAndDeferFdClose(handle)
                PosixHandle.close(handle)
        end match
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
        missedEof.clear()
        // Drop any deregister-intake handles whose OpDeregister command never ran before the driver closed; their promises are failed above.
        deregIntake.clear()
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
                    activeFds.put(handle.readFd, handle.id.packed)
                    pendingReads.put(handle.readFd, handle)
                case RegKind.Accept =>
                    activeFds.put(handle.readFd, handle.id.packed)
                    pendingAccepts.put(handle.readFd, handle)
                case RegKind.Write =>
                    activeFds.put(handle.writeFd, handle.id.packed)
                    handle.pendingWritablePromise match
                        case Present(p) => pendingWritables.put(handle.writeFd, PendingWritable(p, handle.id))
                        case Absent     => ()
                    end match
            end match
            reg = regIntake.poll()
        end while
    end flushRegIntakeToLiveTables

    /** Close the driver: fail every pending op with `Closed` and reclaim the poller fd + scratch, exactly once (guarded by `closedFlag`).
      *
      * `closeReason` is stored BEFORE the `closedFlag` CAS, not after: any carrier that can OBSERVE `closedFlag == true` (in
      * particular the poll loop's own exit check) can only do so after this CAS has actually run, which is sequenced after the store on
      * THIS carrier's own program order. So the store happens-before any observation of the CAS's effect, closing the window a post-CAS
      * store left open (the loop could otherwise observe `closedFlag == true`, race through its entire terminal exit including its own
      * `closeReason` read, and see it still null because this carrier had not reached the store yet). A losing CAS (a concurrent `close()`
      * already won) leaves this store as a harmless, unread value.
      */
    def close()(using AllowUnsafe, Frame): Unit =
        val closed = Closed(label, summon[Frame], "driver closed")
        closeReason = closed
        if closedFlag.compareAndSet(false, true) then
            // Unregister this driver's Diagnostics dumper/probe now: registration happened in start() (if it ran) before the loop's
            // carrier was spawned, so by the time any carrier can observe closedFlag=true the field is either already set or was never
            // going to be (start() never called). Closing here, rather than at the poll loop's later terminal exit, is what keeps a
            // per-test driver's entry from outliving its own close() call in the process-global registry.
            val reg = diagRegistration
            if reg ne null then reg.close()
            if started.get() then
                // The poll loop is running (or ran). Its maps are poll-fiber-confined, so the teardown must run on the poll-loop carrier,
                // not this arbitrary close carrier (a direct map iteration here would race the poll loop's map access on the non-thread-safe maps).
                // Route the teardown through the engine FIFO (drained by the poll loop) and wake the parked poll so it drains it and exits promptly.
                // closeTeardownClaim makes this exactly-once against terminalTeardown's own unconditional attempt (see closeReason's doc):
                // whichever of {this op, the terminal exit} runs first wins the CAS, and both run on a carrier where the map-confinement
                // precondition already holds, so there is nothing to wait for and nothing to race.
                submitEngineOp(() => if closeTeardownClaim.compareAndSet(false, true) then closeTeardown(closed))
                triggerWake()
                // Panic-exit ordering: if the poll loop already died from a backend failure (not from observing closedFlag) before
                // this close() call ran at all, terminalTeardown ran with `closeReason` still null and skipped the claim entirely; no
                // further terminal exit will ever run to retry it (the loop is gone for good). This recheck is the Dekker pairing for
                // terminalTeardown's own post-`teardownComplete` re-read of `closeReason` (see its doc): it runs strictly after this
                // carrier's own `closeReason` store above, so if `teardownComplete` is observed true here, the terminal exit's re-read
                // (sequenced after ITS OWN `teardownComplete.set(true)`) is guaranteed to have already run relative to this store, and
                // this carrier is the only one left that can ever discharge the claim. Map-confinement still holds: the loop is provably
                // gone, so nothing else can touch the poll-fiber-confined maps closeTeardown clears.
                if teardownComplete.get() && closeTeardownClaim.compareAndSet(false, true) then closeTeardown(closed)
            else
                // start() was never called: no poll loop ran, so no carrier is using the maps or the scratch. Tear down directly.
                closeTeardown(closed)
                backend.close(pollerFd)
                freeScratch()
            end if
        end if
    end close

    /** Drain all ready events from one poll result, dispatching reads, writables, and error-only events in order.
      *
      * Called from the `while`-loop body of `pollLoop` after `backend.poll` completes (inline on JVM/Native, via `onComplete` on JS). Each event:
      *   - Dispatches read-ready events to `dispatchRead` (or `dispatchAccept` for listen fds); under edge-triggered the fd is persistently
      *     armed and there is no survivor re-arm to submit.
      *   - Dispatches write-ready events to `dispatchWritable`.
      *   - Dispatches Eof events (peer half-close) through `dispatchRead` with `eofPending=true` whether or not Read fired alongside it: the recv
      *     drains any buffered bytes first and surfaces Span.empty only once recv confirms the buffer is empty (n==0 / EAGAIN). Routing the eof-only
      *     case through `dispatchRead` (rather than completing the promise directly) also drains a TLS handle's engine and records a missed edge in
      *     `missedReads` when no read is pending, so a consumer that registers after the eof edge still surfaces EOF on its next read.
      *   - Dispatches error-ONLY events (no read/write/eof bit) to `dispatchError` so a peer reset surfaces immediately rather than being
      *     missed until a later op fails. When a read or write bit is also set, the normal dispatch runs and surfaces the error in-band.
      */
    private def drainReady(fds: Array[Int], flags: Array[Int], ids: Array[Long], n: Int)(using AllowUnsafe, Frame): Unit =
        var i = 0
        while i < n do
            val fd = fds(i)
            if backend.isWakeFd(fd, pollScratch) then
                // The poll-loop wakeup fired (a submitChange triggered backend.wake to cut this park short). It carries no connection readiness:
                // consume the signal so it does not immediately re-fire, then fall through to the cycle's drainFifos, which applies the change(s)
                // that prompted the wake. Not dispatched as a socket readiness (it is the eventfd / EVFILT_USER wake key, never a socket fd).
                backend.drainWake(pollScratch)
            else if ids(i) != PollScratch.IdNoCheck && (activeFds.getOrElse(fd, -1L) & 0xffffffffL) != (ids(i) & 0xffffffffL) then
                // Stale event for a closed-and-recycled fd, on BOTH backends. The per-event owner id (kqueue knote `udata`, epoll the high 32 bits of
                // epoll_event.data) names the registration that produced it (the owning handle id at register time); activeFds names the fd's CURRENT
                // owner. The compare is low-32 because epoll shares its one 64-bit data word between the fd and the id, so it carries id-low-32; that is
                // collision-free within any recycle window (the prior and new owner have near-adjacent monotonic ids, never 2^32 apart). A mismatch
                // means this fd was closed and its number recycled into a new connection AND a new owner re-armed it: either the kernel still queued an
                // event tagged with the OLD id (kqueue residual knote), or epoll_wait already drained the prior owner's event into this batch before the
                // close+recycle (epoll auto-removes a closed fd, but an already-dequeued event survives). This branch never runs the normal
                // read/eof/error/write dispatch for the stale event, which is what closes the connect-burst race: the prior owner's spurious EV_EOF /
                // EV_ERROR / read edge can no longer surface a phantom close on the NEW owner's fresh connection, and it never reaches dispatchRead's
                // missed-edge tracking to contaminate it.
                //
                // It does fail the stale event's OWN orphaned pending op: when a pending read / accept / writable for this fd is still the very
                // registration the stale event was produced for (its stored owner id equals the event's id), the fd was recycled out from under that
                // op without its handle being closed, so complete it Closed and remove it (it would otherwise hang). An op belonging to the CURRENT
                // owner (a different id) is left untouched: a stale event must never complete the new owner's op. In the connect-burst the prior
                // owner's pending op was already failed by its close, so pendingReads[fd] there is the new owner (different id) and is correctly left
                // alone; only the synthetic recycle-without-close path (the stale-read / stale-writable regression guards) has a matching-id op here.
                // Compared low-32 (matching the guard above): the epoll event carries id-low-32, so an orphaned op's full id is masked to match.
                // HandleId.packed = (fd << 32) | generation, so low-32 is the generation, which is what the event's udata low-32 carries.
                val staleId  = ids(i) & 0xffffffffL
                val staleErr = Closed(label, summon[Frame], s"stale event fd=$fd")
                Maybe(pendingReads.get(fd)).foreach { h =>
                    if (h.id.packed & 0xffffffffL) == staleId then
                        discard(pendingReads.remove(fd))
                        h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(staleErr)))
                        h.pendingReadPromise = Absent
                }
                Maybe(pendingAccepts.get(fd)).foreach { h =>
                    if (h.id.packed & 0xffffffffL) == staleId then
                        discard(pendingAccepts.remove(fd))
                        h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(staleErr)))
                        h.pendingAcceptPromise = Absent
                }
                Maybe(pendingWritables.get(fd)).foreach { entry =>
                    if (entry.id.packed & 0xffffffffL) == staleId then
                        discard(pendingWritables.remove(fd))
                        entry.promise.completeDiscard(Result.fail(staleErr))
                }
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
                    // Eof edge with no co-reported Read bit. There may STILL be buffered bytes this edge did not signal as readable: on a real
                    // socket the data-readiness and the peer half-close can surface in separate edges, and under load the eof edge can be drained
                    // before the read edge. Route through dispatchRead with eofPending=true so any buffered bytes are recv'd and delivered first,
                    // and Span.empty (EOF) is surfaced only once recv confirms the buffer is drained (n==0 / EAGAIN). Delivering EOF directly here
                    // dropped buffered bytes that a separate read edge then recv'd into an orphaned dispatch (the halfCloseDrainsRemaining race).
                    if pendingAccepts.contains(fd) then dispatchAccept(fd)
                    else dispatchRead(fd, eofPending = true, fromKernelEdge = true)
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
                                case Present(engine) => dispatchReadTls(fd, p, handle, engine, eofPending)
                                case Absent          =>
                                    // During a STARTTLS upgrade the engine is not yet attached (tls stays Absent until handshake completion), so the
                                    // upgrade's ciphertext reads land here. Route them to the producer path, which delivers the peer flight into the
                                    // handle's upgradeHandoff slot for the parked handshake waiter instead of completing this read promise.
                                    if handle.upgradeActive then dispatchUpgradeRead(fd, p, handle)
                                    else dispatchReadPlain(fd, p, handle, eofPending)
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
                if fromKernelEdge then
                    discard(missedReads.add(fd))
                    // Preserve the half-close bit of a dropped edge: a bare missedReads entry re-dispatches with eofPending=false, which would
                    // read the buffered bytes but never surface the EOF (the ET half-close edge does not re-fire). Record it so the next
                    // awaitRead advances halfClose to PeerHalfClosePending, ensuring the drain reaches recv == 0.
                    if eofPending then discard(missedEof.add(fd))
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
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        result: Result[Closed, ReadOutcome]
    )(using AllowUnsafe, Frame): Unit =
        if handle.endDispatch() then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read on closed handle fd=$fd")))
        else
            // Unsafe: erased-safe structural widening. `ReadOutcome` and its pending form are identical at runtime; the cast satisfies the
            // scheduler's promise contract without introducing a kyo-effect row in this method, and the type params are erased so it cannot throw.
            promise.asInstanceOf[kyo.scheduler.IOPromise[Any, Any]].completeDiscard(result)

    /** Release this dispatch's ownership and re-deposit the handle into `pendingReads` to keep waiting (the EAGAIN close-race guard), UNLESS a
      * `closeHandle` raced this dispatch, in which case the deferred free has happened and the read is failed `Closed` instead (a handle that
      * was closed during dispatch must not be re-deposited into pendingReads). Under edge-triggered (ET) registration the fd is persistently
      * armed at the kernel; this method does NOT submit a new RegisterRead command. The consumer calls `awaitRead` next, which submits an
      * OpRegisterRead; `dispatchCmd` then re-dispatches immediately if `readMightHaveMore` is set (residual bytes stranded on ET), or otherwise
      * parks until the kernel fires a new readiness edge.
      */
    private def rearmOwned(fd: Int, handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
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
            // Consume any read edge that fired while this read was IN FLIGHT. A TLS read decrypts on the engine FIFO, so the fd is out of
            // pendingReads for the duration of the async feed; a kernel edge arriving in that window hits dispatchRead's no-pending-read branch
            // and is parked in missedReads (with the bytes already in the kernel). This re-arm keeps the SAME parked promise, so the consumer
            // never re-registers and the OpRegisterRead drain that normally clears missedReads never runs; under EPOLLET no fresh edge re-fires
            // for the already-buffered bytes, so without consuming it here the read strands forever. Re-dispatch now to pull those bytes (the
            // plain read path is synchronous on the poll carrier, so no edge can interleave its dispatch and missedReads is empty there: a no-op).
            val missed     = missedReads.remove(fd)
            val missedEofd = missedEof.remove(fd)
            if missedEofd && handle.halfClose == HalfCloseState.Open then handle.halfClose = HalfCloseState.PeerHalfClosePending
            if missed || missedEofd || handle.readMightHaveMore then dispatchRead(fd)
            // ET: otherwise the fd stays armed at the kernel; the consumer-paced drain in dispatchCmd handles residual bytes on the next re-register.

    /** Re-arm a post-upgrade TLS read that yielded 0 plaintext, with the edge-recovery the bare [[rearmOwned]] lacks on both edge-triggered poller
      * backends. After such a read drains to EAGAIN, rearmOwned re-deposits the promise and trusts the next readiness edge to re-fire for the next
      * flight; a TLS 1.3 NewSessionTicket (or any post-handshake record) reads as 0 plaintext between FINISHED and the peer's first application flight
      * (the echo), and an edge-triggered poller can lose that next edge for an already-buffered echo, stranding it. While the handle's post-upgrade
      * window is open ([[PosixHandle.postUpgradeReadWindow]], cleared on the first application read), re-issue the read registration so the poller
      * re-evaluates current readiness: kqueue's `EV_ADD` re-evaluates on re-register (re-queuing an already-ready fd); epoll's `EPOLLET` register-once
      * skips the `EPOLL_CTL_MOD` on an unchanged mask, so it additionally seeds `missedReads` to make the OpRegisterRead apply force-dispatch one recv
      * probe (the same recovery `armUpgradeProducerRead` / `forceReadRecovery` use). A redundant force-dispatch on kqueue would double-dispatch and
      * strand the handshake, so the seed is epoll-only (`kqueueData` Empty). io_uring is completion-based and keeps the bare re-arm. Outside the
      * window, steady-state reads keep the bare rearmOwned (no per-read kevent / force-dispatch). Poll-carrier-confined, like rearmOwned.
      */
    private def rearmTlsRead(fd: Int, handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
        AllowUnsafe,
        Frame
    ): Unit =
        rearmOwned(fd, handle, promise)
        if pollScratch.kqueueData.isDefined then
            // KQUEUE (Class B, Decision 50(B) option 2): re-register a real EV_ADD on EVERY post-upgrade TLS-read re-arm, in-window AND
            // steady-state. The standing EV_CLEAR registration re-reports only a fresh empty->ready transition, so a record already buffered
            // at re-arm time (no fresh transition) is never re-reported and the read strands (the post-window steady-state edge-miss). A real
            // EV_ADD re-level-checks the socket buffer (sb_cc) at registration and queues an event when bytes are present, recovering it.
            // Demand-driven and spin-safe: EV_ADD fires once if buffered then drains to EAGAIN; the next EV_ADD finds the socket empty and
            // does not fire (no forced recvNow, so no Decision-44 EAGAIN spin). The epoll branch below is left byte-for-byte unchanged.
            regIntake.offer(Registration(handle, RegKind.Read))
            submitChange(packCmd(OpRegisterRead, handle.readFd))
        else if handle.postUpgradeReadWindow then
            // EPOLL: in-window recovery only (seed missedReads to force-dispatch an already-buffered flight, then re-register); the post-window
            // steady-state keeps the bare rearmOwned (Decision 45 close-window/drop-seed) so the register-once force-dispatch cannot hot-spin.
            discard(missedReads.add(handle.readFd))
            regIntake.offer(Registration(handle, RegKind.Read))
            submitChange(packCmd(OpRegisterRead, handle.readFd))
        end if
    end rearmTlsRead

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
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: PosixHandle,
        eofPending: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val result = recvNowWithRetry(fd, handle.readBuffer, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n      = result.value.toInt
        // Persist an observed half-close on the handle so the consumer-paced drain keeps re-reading until recv returns 0, even across multiple
        // re-dispatches and even if a later edge does not re-carry eofPending (an ET half-close edge fires once; a missed edge or a multi-read
        // drain ending on a partial recv would otherwise lose the EOF).
        if eofPending && handle.halfClose == HalfCloseState.Open then handle.halfClose = HalfCloseState.PeerHalfClosePending
        if n > 0 then
            val arr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
            // Keep a reference to this chunk so a subsequent STARTTLS upgrade can recover a handshake flight that arrived coalesced with
            // the upgrade signal in this same read (the consumer takes the whole chunk as the signal and discards the trailing bytes).
            handle.lastPlaintextRead.set(Present(arr))
            // Set readMightHaveMore when the kernel may have additional bytes with no new ET edge pending. Two cases:
            // (1) n == readBufferSize: the recv filled the buffer; residual bytes may remain in the kernel that epoll ET will not re-signal.
            // (2) PeerHalfClosePending: the peer half-closed; the next recv will either return more data or 0 (EOF). Re-dispatch is required
            //     regardless of buffer fill so the EOF surfaces on a later awaitRead rather than waiting for an edge that epoll ET will not
            //     re-fire (EPOLLRDHUP fires once per transition).
            val filled = n == handle.readBufferSize
            handle.readMightHaveMore = filled || (handle.halfClose == HalfCloseState.PeerHalfClosePending)
            // Adaptive receive-buffer growth: feed the fill ratio to the per-handle predictor. The bytes for THIS read are already copied
            // out into `arr` above, so a grow that closes the old buffer cannot lose them. Poll-fiber-confined and between reads (no recv is in
            // flight against the old buffer here), so the close-old-then-replace recipe is single-owner-safe. The grown buffer
            // serves the next recv; the value delivered for THIS read is unchanged.
            discard(handle.growReadBufferForFullRead(n))
            finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
        else if n == 0 then
            // Orderly peer close: recv(2) returns 0 when the kernel buffer is empty and the peer sent FIN.
            handle.readMightHaveMore = false
            handle.halfClose = HalfCloseState.PeerEof
            finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.PeerFin))
        else if isWouldBlock(result.errorCode) then
            // EAGAIN confirms the kernel buffer is empty: no residual possible.
            handle.readMightHaveMore = false
            if handle.halfClose == HalfCloseState.PeerHalfClosePending then
                // Buffer fully drained and the peer's half-close is confirmed empty. Deliver PeerFin and release the dispatch.
                handle.halfClose = HalfCloseState.PeerEof
                finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.PeerFin))
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

    /** STARTTLS upgrade producer (poll carrier): read one peer ciphertext flight and hand it to the parked handshake waiter through the handle's
      * [[PosixHandle.upgradeHandoff]] slot, so the handshake fiber never reads the socket itself (eliminating the cross-carrier recv race a
      * synchronous `recvNow` on the handshake fiber would have with this poll-carrier read). Runs only while [[PosixHandle.upgradeActive]] is set;
      * `promise` is the producer vehicle [[armUpgradeProducerRead]] armed and is never completed with bytes. Demand-driven: after a flight is
      * delivered the read is released (the handshake arms the next one); only an EAGAIN keeps it armed so the next readiness edge re-dispatches.
      */
    private def dispatchUpgradeRead(
        fd: Int,
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: PosixHandle
    )(using AllowUnsafe, Frame): Unit =
        val result = recvNowWithRetry(fd, handle.readBuffer, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n      = result.value.toInt
        if n > 0 then
            val arr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
            // Edge-triggered: a buffer-filling recv may leave more in the kernel with no new edge; readMightHaveMore makes the handshake's next
            // armUpgradeProducerRead re-dispatch immediately (dispatchCmd) rather than wait for an edge that will not re-fire.
            handle.readMightHaveMore = n == handle.readBufferSize
            deliverToUpgradeHandoff(handle, arr)
            // Demand-driven: do NOT re-arm here. The handshake parks the next waiter and re-arms via armUpgradeProducerRead. endDispatch releases the
            // dispatch guard (and runs the deferred free if a close raced, which fails the just-handed waiter via freeResources).
            discard(handle.endDispatch())
        else if n == 0 then
            handle.readMightHaveMore = false
            failUpgradeHandoff(handle, eof = true, errno = 0)
            discard(handle.endDispatch())
        else if isWouldBlock(result.errorCode) then
            // Socket confirmed empty: the peer flight has not arrived yet. Keep the producer armed (re-deposit the vehicle promise) so the next
            // readiness edge re-dispatches this producer read.
            handle.readMightHaveMore = false
            rearmOwned(fd, handle, promise)
        else
            handle.readMightHaveMore = false
            failUpgradeHandoff(handle, eof = false, errno = result.errorCode)
            discard(handle.endDispatch())
        end if
    end dispatchUpgradeRead

    /** Deliver `arr` (one peer ciphertext flight, read on the poll carrier or salvaged by [[onInboundClosedDuringRead]] on whatever arbitrary
      * carrier the scheduler resumes the parked put on) into the handle's [[PosixHandle.upgradeHandoff]] slot: fulfil a parked handshake
      * waiter, or stage a Carryover the handshake's next read consumes. When a Carryover is already staged (a peer flight landed before the
      * handshake parked its next waiter, e.g. a boringssl multi-record flight delivered across two dispatches), the new bytes are APPENDED to
      * it rather than overwriting it: a single-slot replace would drop every segment but the first, the upgrade-handoff drop. The two callers
      * are NOT confined to one carrier (the normal dispatch path and the salvage path can call this concurrently for the same handle), so the
      * CAS loop's retry is load-bearing, not defensive: a losing attempt re-reads and re-applies against whatever the winner left. Mirrors
      * [[NioIoDriver]]'s `deliverToUpgradeHandoff` Carryover-append.
      */
    @scala.annotation.tailrec
    private def deliverToUpgradeHandoff(handle: PosixHandle, arr: Array[Byte])(using AllowUnsafe): Unit =
        import PosixHandle.UpgradeHandoff
        handle.upgradeHandoff.get() match
            case parked: UpgradeHandoff.Waiter =>
                if handle.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle) then
                    parked.promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                else deliverToUpgradeHandoff(handle, arr)
            case staged: UpgradeHandoff.Carryover =>
                val combined = new Array[Byte](staged.bytes.length + arr.length)
                java.lang.System.arraycopy(staged.bytes, 0, combined, 0, staged.bytes.length)
                java.lang.System.arraycopy(arr, 0, combined, staged.bytes.length, arr.length)
                if !handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Carryover(combined)) then
                    deliverToUpgradeHandoff(handle, arr)
            case _ =>
                if !handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, UpgradeHandoff.Carryover(arr)) then
                    deliverToUpgradeHandoff(handle, arr)
        end match
    end deliverToUpgradeHandoff

    /** Fail a STARTTLS handshake parked on the upgrade handoff when the producer read hit EOF or a hard error. EOF (`eof`) completes the waiter with
      * an empty Span (the handshake renders it as ": peer closed during read"); an error completes it Closed. A no-op when no waiter is parked (the
      * handshake's own next read observes the closed fd). Mirrors [[IoUringDriver]]'s `complete` upgrade EOF/error branch.
      */
    private def failUpgradeHandoff(handle: PosixHandle, eof: Boolean, errno: Int)(using AllowUnsafe, Frame): Unit =
        import PosixHandle.UpgradeHandoff
        handle.upgradeHandoff.get() match
            case parked: UpgradeHandoff.Waiter =>
                discard(handle.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                if eof then parked.promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                else
                    parked.promise.completeDiscard(Result.fail(Closed(
                        label,
                        summon[Frame],
                        s"recv failed fd=${handle.readFd} errno=$errno"
                    )))
                end if
            case _ => ()
        end match
    end failUpgradeHandoff

    /** Return the per-handle recvStaging Buffer, lazily allocated on the first TLS read.
      *
      * Poll-carrier-only: called from dispatchReadTls before submitEngineOp. The lazy-alloc write (first TLS read) happens here
      * on the poll carrier and is visible to the FIFO worker via the engine-op enqueue as the happens-before barrier, exactly like
      * flushReArmPending. After the first alloc the field is stable. The at-most-one-in-flight guarantee (enforced by the engine-op enqueue
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
      *
      * `eofPending` is forwarded from `dispatchRead` when `PollFlags.Eof` fired alongside `PollFlags.Read`, signalling that the peer
      * half-closed but ciphertext bytes were still buffered at the time of the event. Under EPOLLET, the EPOLLRDHUP edge fires only once
      * per transition; after ciphertext is consumed and plaintext delivered, the missing re-edge would prevent EOF from surfacing. Mirrors
      * the same `eofPending` / `halfClose == PeerHalfClosePending` logic in `dispatchReadPlain`.
      */
    private def dispatchReadTls(
        fd: Int,
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: PosixHandle,
        engine: TlsEngine,
        eofPending: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        // stagingFor is called on the poll carrier; it lazily allocates recvStaging on the first TLS read.
        // The recv writes directly into staging, eliminating the per-read copy-out (no Buffer.copyToArray here).
        // EINTR is retried in place by recvNowWithRetry (bounded), so a signal mid-recv does not surface as Closed (POSIX recv(2)).
        val staging = stagingFor(handle)
        val result  = recvNowWithRetry(fd, staging, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n       = result.value.toInt
        if n > 0 then
            // Feed staging directly to feedCiphertext on the FIFO worker: no per-read re-fromArray.
            // The happens-before between the recvNow write (poll carrier) and the feedCiphertext read (FIFO worker) is the
            // submitEngineOp enqueue, the same mechanism as flushReArmPending. The at-most-one-in-flight guarantee ensures the next
            // recvNow write cannot happen before this feedCiphertext completes.
            // Persist the half-close observation on the handle before handing off to the engine FIFO worker, so a consumer-paced
            // re-dispatch triggered by readMightHaveMore (or a missed edge replayed via rearmOwned) carries the pending-EOF state and
            // eventually calls dispatchReadTls with eofPending=false but handle.halfClose==PeerHalfClosePending, keeping the drain alive
            // until the TCP FIN confirms the socket is empty. Mirrors dispatchReadPlain's eofPending guard.
            if eofPending && handle.halfClose == HalfCloseState.Open then handle.halfClose = HalfCloseState.PeerHalfClosePending
            submitEngineOp { () =>
                try
                    // A fatal TLS record is an abrupt, ungraceful close (the stream itself is corrupt, so no close_notify is attempted): it
                    // must claim-and-defer the fd close the SAME way every other close path here does, before calling requestClose. A bare
                    // requestClose() would run freeResources immediately here (this read dispatch is its own last holder once it releases
                    // moments later), forfeiting the fd close permanently, since freeResources never runs a second time for a handle whose
                    // credit is installed only after the guard has already gone terminal.
                    val onFatal = () =>
                        claimAndDeferFdClose(handle)
                        handle.requestClose()
                    var plain   = feedAndDecrypt(engine, staging, n, handle, onFatal)
                    var eof     = false
                    var errno   = 0
                    var drained = false
                    // Partial-record drain (edge-triggered): if the engine consumed ciphertext but produced no plaintext, a TLS record is split
                    // across recv boundaries and the remaining ciphertext may ALREADY be in the kernel with no new readiness edge to come. Under
                    // EPOLLET the ONLY definitive "socket empty" signal is an explicit EAGAIN: a short recv (fewer bytes than the buffer) does NOT
                    // prove the socket drained, because back-to-back ciphertext keeps arriving and more may already be queued. So recv+feed until the
                    // engine yields plaintext, the socket reports EAGAIN, or the peer closes, rather than inferring emptiness from a short recv and
                    // re-arming for an edge that never fires. Mirrors the io_uring driver's awaitRead re-arm; without it a multi-record / bulk TLS
                    // transfer strands on the poller under load.
                    while plain.length == 0 && !eof && errno == 0 && handle.halfClose != HalfCloseState.PeerCleanClose && !drained do
                        val r  = recvNowWithRetry(fd, staging, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
                        val rN = r.value.toInt
                        if rN > 0 then plain = feedAndDecrypt(engine, staging, rN, handle, onFatal)
                        else if rN == 0 then eof = true
                        else if isWouldBlock(r.errorCode) then drained = true
                        else errno = r.errorCode
                        end if
                    end while
                    // More ciphertext may remain in the kernel UNLESS the socket is confirmed empty (EAGAIN observed), the stream ended (EOF), the
                    // peer closed cleanly, or the recv errored. After delivering plaintext the socket is NOT confirmed empty (the loop stops the
                    // moment plaintext appears, before the next recv), so the consumer-paced drain must re-dispatch to pull the rest; under EPOLLET no
                    // fresh edge fires while the fd was still readable. A spurious re-dispatch (no actual bytes) hits EAGAIN and re-arms harmlessly.
                    // Also force re-dispatch when PeerHalfClosePending: the EPOLLRDHUP / EV_EOF edge fires once; after the ciphertext above is
                    // decrypted and delivered, the consumer-paced drain must call recv again to observe the FIN (recv returns 0) and surface
                    // PeerFin. Without this, a connection that half-closes with partial ciphertext in the same edge strands the consumer
                    // waiting for an EPOLLRDHUP edge that epoll ET will not re-fire.
                    handle.readMightHaveMore =
                        (!drained && !eof && errno == 0 && handle.halfClose != HalfCloseState.PeerCleanClose) ||
                            (handle.halfClose == HalfCloseState.PeerHalfClosePending)
                    if plain.length > 0 then
                        // First application plaintext after the upgrade: close the post-upgrade kqueue read window so steady-state reads keep the
                        // bare re-arm (no per-read kevent). A no-op outside an upgrade (the flag is only set at STARTTLS completion).
                        handle.postUpgradeReadWindow = false
                        finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(plain))))
                    else if handle.halfClose == HalfCloseState.PeerCleanClose then
                        // The peer's close_notify was consumed (RFC 8446 6.1 orderly close): deliver CleanClose so the ReadPump tears down
                        // cleanly, rather than re-arming for ciphertext the peer will never send.
                        finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.CleanClose))
                    else if eof then
                        // A bare FIN arrived mid-record (no close_notify): record the truncation and surface PeerFin.
                        handle.halfClose = HalfCloseState.PeerEof
                        finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.PeerFin))
                    else if errno != 0 then
                        finishDispatch(fd, handle, promise, Result.fail(Closed(label, summon[Frame], s"recv failed fd=$fd errno=$errno")))
                    else
                        // Only handshake/partial-record bytes consumed and the socket is drained (EAGAIN): wait for more ciphertext on the next edge.
                        // rearmTlsRead adds the kqueue edge-recovery the post-upgrade window needs (a 0-plaintext NewSessionTicket read before the echo).
                        // EPOLL hot-spin fix: the socket is confirmed empty (drained) and no plaintext was produced, so the post-upgrade window's
                        // register-once recovery (force-dispatch an ALREADY-buffered flight) is complete -- the first post-FINISHED arm already drained
                        // whatever was buffered to reach this EAGAIN. Keeping the window open here makes rearmTlsRead re-seed missedReads + re-register
                        // every cycle, and the seeded OpRegisterRead force-dispatch recvs EAGAIN and re-seeds in a tight CPU spin (the continuation has
                        // NOT arrived; it will come as a fresh EPOLLET edge). Close the window (epoll only) so rearmTlsRead does a bare rearmOwned (park,
                        // no re-register -> no register-once MOD-SKIP); the persistent EPOLLET arm + re-deposited pending read catch the continuation.
                        // kqueue's EV_ADD re-arm only re-queues a ready fd, so it never spins and is left untouched.
                        if pollScratch.kqueueData.isEmpty then handle.postUpgradeReadWindow = false
                        rearmTlsRead(fd, handle, promise)
                    end if
                catch
                    // A TLS engine op threw, almost always a fatal alert surfaced by the engine (feedAndDecrypt -> JdkSslEngine.readPlain ->
                    // SSLEngine.unwrap raising SSLHandshakeException, e.g. a peer's certificate_required). drainEngineOps deliberately catches
                    // a throwing op to keep the FIFO worker draining for other connections, with the contract "the throwing op's own promise
                    // handling is its concern" -- but this op held a pending read promise, so a bare escape strands it (the connection's read
                    // never completes -> a silent hang to the leaf timeout). Honor that contract: fail the read promise so the connection tears
                    // down with the engine error instead of hanging. finishDispatch has not run on this path (the throw precedes every branch).
                    case e: Throwable =>
                        Log.live.unsafe.warn(s"$label TLS engine read failed fd=$fd, closing connection: ${e.getMessage}")
                        finishDispatch(
                            fd,
                            handle,
                            promise,
                            Result.fail(Closed(label, summon[Frame], s"TLS engine read failed fd=$fd: ${e.getMessage}"))
                        )
                end try
            }
        else if n == 0 then
            // Peer close via a bare TCP FIN (no close_notify reached the engine, else the clean-close branch above delivered CleanClose first):
            // record the truncation condition so closeReason reports Truncated, then deliver PeerFin immediately without engine involvement.
            handle.readMightHaveMore = false
            handle.halfClose = HalfCloseState.PeerEof
            finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.PeerFin))
        else if isWouldBlock(result.errorCode) then
            // EAGAIN confirms the kernel socket buffer is empty: no residual possible. Engine plaintext may still be buffered (checked below).
            handle.readMightHaveMore = false
            // Persist a half-close observation from this edge before the engine FIFO op (same as the n>0 branch above).
            if eofPending && handle.halfClose == HalfCloseState.Open then handle.halfClose = HalfCloseState.PeerHalfClosePending
            // Socket buffer empty: check for already-buffered plaintext first (via the engine FIFO), then check half-close, then re-arm.
            // Capture the half-close state here on the poll carrier so the engine FIFO closure sees it without racing a concurrent
            // closeHandle or a future poll-carrier write (the field is poll-carrier-confined; capturing here is safe).
            val halfCloseNow = handle.halfClose == HalfCloseState.PeerHalfClosePending
            submitEngineOp { () =>
                try
                    if engine.hasBufferedPlaintext then
                        val buffered = engine.readBuffered()
                        if buffered.nonEmpty then
                            handle.postUpgradeReadWindow =
                                false // first application plaintext after the upgrade: close the kqueue read window
                            finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.Bytes(buffered)))
                        else rearmTlsRead(fd, handle, promise)
                        end if
                    else if halfCloseNow then
                        // Socket buffer confirmed empty (EAGAIN) AND the peer's half-close is known (EPOLLRDHUP / EV_EOF fired earlier,
                        // stored as PeerHalfClosePending). The engine has no buffered plaintext: the FIN is the definitive end. Deliver PeerFin.
                        handle.halfClose = HalfCloseState.PeerEof
                        finishDispatch(fd, handle, promise, Result.succeed(ReadOutcome.PeerFin))
                    else
                        // EPOLL hot-spin fix (companion to the n>0 drained branch): EAGAIN with no buffered plaintext means the socket is empty and the
                        // engine produced nothing, so the post-upgrade window's register-once recovery is complete. Close the window (epoll only) so
                        // rearmTlsRead parks via a bare rearmOwned instead of re-seeding missedReads + re-registering, which would force-dispatch -> recv
                        // EAGAIN -> re-seed in a CPU spin. The continuation arrives later as a fresh EPOLLET edge on the persistent arm. kqueue untouched.
                        if pollScratch.kqueueData.isEmpty then handle.postUpgradeReadWindow = false
                        rearmTlsRead(fd, handle, promise)
                    end if
                catch
                    // A buffered-plaintext engine op threw (a fatal alert in the buffered records); fail the read promise rather than letting
                    // the throw escape drainEngineOps and strand the connection (see the dispatchReadTls feed op above for the full rationale).
                    case e: Throwable =>
                        finishDispatch(
                            fd,
                            handle,
                            promise,
                            Result.fail(Closed(label, summon[Frame], s"TLS engine read failed fd=$fd: ${e.getMessage}"))
                        )
                end try
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
    private def isStaleId(fd: Int, id: HandleId): Boolean = activeFds.getOrElse(fd, -1L) != id.packed

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
            if r.value < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries then loop(attempt + 1)
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
            if r.value < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries then loop(attempt + 1)
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
                case Present(r) if r.value < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries =>
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
      * After the offer it triggers the poll-loop wakeup UNCONDITIONALLY (mirrors [[submitEngineOp]]). Without the wake, a change submitted while
      * the loop is parked (e.g. a connect's write-readiness arm, or a read re-arm on a connection whose peer has gone idle) would not be
      * registered with the kernel at all: the poll parks indefinitely (timeoutMs=-1) and never times out on its own. An earlier version of this
      * method coalesced the wake behind a `wakePending` CAS flag (dedup a burst of submits to at most one wake per poll cycle), but that flag is
      * cleared only at the TOP of each poll cycle, before `drainChanges()` and the blocking park; `drainFifos()` (which also drains the change
      * queue, after the park returns) runs BEFORE that reset. So between a cycle's `drainFifos()` consuming a prior wake and the NEXT cycle's
      * reset, `wakePending` reads `true` while STALE: it represents a wake whose OS-level signal was already delivered and cleared, not one
      * still in flight. A guarded `submitChange` whose CAS landed in that window would observe the stale `true`, skip its own wake, and leave
      * this command sitting in `changeQueue` with nothing left to wake the park: if no OTHER fd on this driver has any future activity to return
      * `backend.poll()` on its own, the command is never drained and the connection strands forever (the connection-reuse hang under kyo-http's
      * pool/concurrency stress leaves, e.g. `HttpClientTest` "concurrent contention with more fibers than pool slots" and "connection reuse with
      * varying data": under load `drainFifos()` does more work per cycle, widening the stale window and making a same-cycle cross-thread
      * `submitChange` from a different connection more likely to land in it). This is the exact class of bug `submitEngineOp` was already fixed
      * for (the B' post-upgrade write strand); `submitChange` shared the same coalescing and the same latent gap, just on the read/registration
      * side instead of the write side, and with a self-healing path (the next cycle's unconditional `drainChanges()`) that usually, but not
      * always, recovers a skipped wake before the park closes it off. `PollerWakeReadArmTest` pins this directly. The offer happens-before the
      * wake, so the poll loop, once woken, always observes the just-offered command in its drain.
      */
    private def submitChange(cmd: Long)(using AllowUnsafe, Frame): Unit =
        changeQueue.offer(cmd)
        triggerWake()
    end submitChange

    /** Trigger the poll-loop wakeup under the wake guard: register as an in-flight wake holder, fire [[PollerBackend.wake]], then release. If the wake
      * fd is already closing (the driver is tearing down), [[acquireWake]] refuses and this no-ops, so a wake never touches a wake fd that may be closed
      * and recycled. The acquire/wake/release bracket is what keeps the wake-fd close ([[closeWakeGuarded]]) from running while this wake is mid-flight.
      */
    private def triggerWake()(using AllowUnsafe, Frame): Unit =
        if acquireWake() then
            try backend.wake(pollerFd, pollScratch)
            finally releaseWake()
            end try
    end triggerWake

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

    /** Remove and return the first handle in `deregIntake` whose `readFd` or `writeFd` matches `fd` (the deregister twin of [[takeRegistration]],
      * #362). Each [[deregisterFds]] offers the handle once per OpDeregister command, so a socket (readFd == writeFd) offers once and stdio (0/1)
      * twice; matching by fd consumes the right entry for each command. Returns `null` when none matches (a deregister whose intake entry was never
      * offered, which the offer-before-submit ordering prevents in practice). Poll-fiber-confined consumption; the iterator is weakly consistent.
      */
    private def takeDeregister(fd: Int): PosixHandle | Null =
        val it                        = deregIntake.iterator()
        var found: PosixHandle | Null = null
        var scanning                  = true
        while scanning && it.hasNext do
            val h = it.next()
            if (h.readFd == fd || h.writeFd == fd) && deregIntake.remove(h) then
                found = h
                scanning = false
            end if
        end while
        found
    end takeDeregister

    /** Apply one register command's matching registration to the live poll-fiber-confined tables (single-writer), keyed by `(fd, kind)`. The
      * accept bit disambiguates an OpRegisterRead from awaitAccept vs awaitRead, so a recycled fd reused across an accept and a read is never
      * mismatched. Applies nothing when no registration matches (a cancel removed it before the command ran). The handle's `@volatile` promise/id
      * reads here pair with the await methods' stores (published before the registration offer; the queue offer/take is the happens-before barrier).
      */
    private def applyRegistration(fd: Int, kind: RegKind)(using AllowUnsafe, Frame): Long =
        Maybe(takeRegistration(fd, kind)) match
            case Present(reg) =>
                val handle = reg.handle
                if handle.isClosing() then
                    // A closing handle must never (re-)claim its fd. The ReadPump always re-arms (ReadPump.requestNextRead), so a read re-arm can
                    // race the connection close: by the time this registration applies on the poll carrier, the handle's fd may already be closed
                    // and recycled into a NEW connection. Applying it would overwrite the new owner's activeFds/pendingReads entry and (epoll)
                    // MOD-re-encode the kernel event under the dead handle's id, so the new connection's reads are evicted and never dispatch (a
                    // strand). Skip the registration entirely: fail the dangling promise Closed so its consumer tears down instead of hanging, then
                    // return IdNoCheck so the caller skips backend.registerRead and the missed-edge re-dispatch. This is the register-side dual of
                    // dispatchRead's beginDispatch guard and the OpDeregister id-guard (#362); a live handle still registers normally below.
                    val closed = Closed(label, summon[Frame], s"register on closing handle fd=$fd")
                    kind match
                        case RegKind.Read =>
                            handle.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                            handle.pendingReadPromise = Absent
                        case RegKind.Accept =>
                            handle.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                            handle.pendingAcceptPromise = Absent
                        case RegKind.Write =>
                            handle.pendingWritablePromise.foreach(_.completeDiscard(Result.fail(closed)))
                            handle.pendingWritablePromise = Absent
                    end match
                    PollScratch.IdNoCheck
                else if kind == RegKind.Read && handle.upgradeActive && !handle.handshakeReading then
                    // STARTTLS upgrade confinement (poller): an OpRegisterRead for the read side while the handle is upgrading and the handshake has
                    // not yet taken read ownership (handshakeReading false) is the retiring plaintext ReadPump's stray re-arm (its requestNextRead
                    // raced detachForUpgrade). Admitting it would deposit the pump's promise as the fd's read owner and let the next readability event
                    // deliver the peer's first TLS flight to the pump instead of the handshake. SKIP the backend register (return IdNoCheck) so the
                    // stray never owns the fd; the handshake's own arm (handshakeReading set in awaitReadCiphertext) is admitted by the branch below.
                    // Do NOT fail the pump's pendingReadPromise here: upgradeActive is set BEFORE detachForUpgrade flips the connection state to
                    // Upgrading, so failing the promise on this carrier could tear the pump down while the state is still Established, letting its
                    // closeFn win Established->Closing and close the fd the upgrade reuses (the peer then reads EOF mid-handshake). detachForUpgrade's
                    // deregisterFds fails the pump promise AFTER the state is Upgrading, where closeFn is a no-op, so leaving the promise to it keeps
                    // the teardown safe. This is the poller dual of NioIoDriver's dispatchReadPlain upgrade guard.
                    PollScratch.IdNoCheck
                else
                    kind match
                        case RegKind.Read =>
                            activeFds.put(fd, handle.id.packed)
                            pendingReads.put(fd, handle)
                        case RegKind.Accept =>
                            activeFds.put(fd, handle.id.packed)
                            pendingAccepts.put(fd, handle)
                        case RegKind.Write =>
                            activeFds.put(fd, handle.id.packed)
                            handle.pendingWritablePromise match
                                case Present(p) => pendingWritables.put(fd, PendingWritable(p, handle.id))
                                case Absent => () // the writable was already failed/cleared (cancel raced the registration); nothing to arm
                            end match
                    end match
                    handle.id.packed
                end if
            case Absent =>
                // No registration matched (a cancel removed it before the command ran). Return IdNoCheck so the caller skips the backend register:
                // arming a fd with no pending op (the pre-fix behavior) would leave an interest with no owner id to tag the knote, and there is no op
                // to deliver to anyway.
                PollScratch.IdNoCheck
        end match
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
                val id = applyRegistration(fd, RegKind.Accept)
                // Skip the backend register when no registration matched (id == IdNoCheck): there is no pending op to arm and no owner id to tag the
                // kqueue knote (the udata stale-event cookie). The id is the registering handle's monotonic id, passed to the backend as the knote udata.
                if id != PollScratch.IdNoCheck then
                    val rc = backend.registerRead(pollerFd, fd, id, pollScratch)
                    if rc < 0 then
                        Maybe(pendingAccepts.remove(fd)).foreach { h =>
                            h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                            h.pendingAcceptPromise = Absent
                        }
                    end if
                end if
            else
                val id = applyRegistration(fd, RegKind.Read)
                if id != PollScratch.IdNoCheck then
                    val rc = backend.registerRead(pollerFd, fd, id, pollScratch)
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
                        val missed     = missedReads.remove(fd)
                        val missedEofd = missedEof.remove(fd)
                        Maybe(pendingReads.get(fd)).foreach { h =>
                            // A dropped edge that carried eof advances halfClose to PeerHalfClosePending so re-dispatches drain to recv == 0
                            // and surface the EOF; the bare missedReads entry alone would re-dispatch with eofPending=false and lose it.
                            if missedEofd && h.halfClose == HalfCloseState.Open then h.halfClose = HalfCloseState.PeerHalfClosePending
                            if h.readMightHaveMore || missed || missedEofd then dispatchRead(fd)
                        }
                    end if
                end if
            end if
        else if op == OpRegisterWrite then
            val id = applyRegistration(fd, RegKind.Write)
            if id != PollScratch.IdNoCheck then
                val rc = backend.registerWrite(pollerFd, fd, id, pollScratch)
                if rc < 0 then
                    Maybe(pendingWritables.remove(fd)).foreach(_.promise.completeDiscard(Result.fail(closed)))
            end if
        else
            // OpDeregister (#362, R-065): remove this fd's entries from the poll-fiber-confined maps HERE, on the poll-loop carrier (single-writer),
            // but ONLY when activeFds still carries the DEREGISTERING handle's HandleId. The deregistering handle is taken from deregIntake (paired
            // with this command by deregisterFds). A stale deregister whose fd was closed and recycled into a new handle finds activeFds holding the
            // NEW handle's id, so the mismatch skips every removal and the kernel deregister, leaving the recycled fd's new registration intact (the
            // #362 wrong-entry eviction). A live withdrawal (fdClosing == false) never closes the fd, so its id always matches. The pending promises
            // were already failed synchronously by deregisterFds (held on the handle, not in the maps). Clearing missedReads/missedEof on a matched
            // deregister stops a recycled fd inheriting a stale entry; on a mismatch they belong to the new handle and are left.
            Maybe(takeDeregister(fd)) match
                case Present(h) =>
                    // Proceed when the fd is still owned by THIS handle (id matches) OR is unowned (no activeFds entry: a cancel before any register,
                    // or an already-removed registration; the deregister is then idempotent and harmless). Skip ONLY when activeFds carries a
                    // DIFFERENT, live HandleId: that is the #362 recycled-fd case, where the fd was closed and recycled into a new handle whose
                    // registration this stale deregister must not evict. -1L is the absent sentinel (no real id.packed is -1; same sentinel isStaleId uses).
                    val current = activeFds.getOrElse(fd, -1L)
                    if current == h.id.packed || current == -1L then
                        activeFds.remove(fd)
                        discard(pendingReads.remove(fd))
                        discard(pendingWritables.remove(fd))
                        discard(pendingAccepts.remove(fd))
                        discard(missedReads.remove(fd))
                        discard(missedEof.remove(fd))
                        backend.deregister(pollerFd, fd, fdClosing, pollScratch)
                        if fdClosing then
                            // The fd is being CLOSED, so any pending promise the handle carries must die. deregisterFds already fails them
                            // synchronously on the close carrier for low latency, but a dispatch's `rearmOwned` (also on THIS poll carrier) can
                            // re-deposit a read promise AFTER that synchronous read, leaving it orphaned: the STARTTLS upgrade read re-armed on a
                            // recvNow EAGAIN while a concurrent closeHandle tears the handle down. This apply is serialized with rearmOwned on the
                            // poll carrier, so failing the promise here catches the re-deposited one and the handshake aborts Closed instead of
                            // hanging. Gated on fdClosing: a live withdrawal (cancel / detachForUpgrade) keeps the fd open and the upgrade legitimately
                            // re-arms a read on it, which this must NOT kill; the synchronous fail already handled the read present at cancel time.
                            // completeDiscard is idempotent with the synchronous fail.
                            val closed = Closed(label, summon[Frame], s"fd=$fd closed")
                            h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                            h.pendingReadPromise = Absent
                            h.pendingWritablePromise.foreach(_.completeDiscard(Result.fail(closed)))
                            h.pendingWritablePromise = Absent
                            h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                            h.pendingAcceptPromise = Absent
                        end if
                    end if
                case Absent =>
                    // No paired handle (the offer-before-submit ordering prevents this in practice). Safe default: remove nothing rather than risk
                    // evicting a recycled fd's new owner.
                    ()
            end match
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
      *
      * After offering the op, triggers the poll-loop wakeup UNCONDITIONALLY, same as [[submitChange]]. Both used to gate the wake behind a CAS
      * on a shared `wakePending` flag the poll loop reset only at the top of each cycle, before draining the FIFOs and before the blocking
      * park: a submit whose CAS landed AFTER a prior wake was already consumed this cycle but BEFORE that reset observed a STALE `true` (a wake
      * whose OS-level signal was already delivered and cleared, not one still in flight), skipped its own wake, and the carrier parked having
      * never seen the newly-offered work. Unlike a read re-arm (which has a self-healing path: the coalesced-away command still sits in
      * `changeQueue` and the next cycle's unconditional `drainChanges()` usually recovers it if `backend.poll()` returns for some other reason),
      * a `writeTls` engine op is the write's only delivery attempt with no retry: a wake lost here strands the connection's write side
      * permanently (the STARTTLS-upgrade-tail B' strand this method was first made unconditional for). `submitChange` shared the identical gap
      * on the read/registration side and was later found to strand connection-reuse reads the same way when the self-healing path did not run
      * in time (`PollerWakeReadArmTest`), so both are unconditional now and `wakePending` is retired. Mirrors [[IoUringDriver.submitEngineOp]],
      * whose `wakeReapLoop()` is unconditional for the same reason. `triggerWake` -> `backend.wake` is safe to call concurrently from multiple
      * carriers: epoll's `wake` is a thread-safe atomic counter increment, and kqueue's `wake` encodes into a fresh per-call buffer (see
      * [[KqueuePollerBackend.wake]]) instead of a reused one, so concurrent wakes never touch the same memory and there is nothing to serialize.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(engineQueue.offer(op))
        triggerWake()

    /** Drain the engine-op FIFO to empty, running each op to completion before the next. Called once per poll cycle from [[drainFifos]] on the single
      * poll-loop carrier, so it is the FIFO's only consumer (the ConcurrentLinkedQueue single-consumer contract holds with no flag). An op offered
      * after this returns empty is drained on the next poll cycle.
      */
    @scala.annotation.tailrec
    private def drainEngineOps()(using AllowUnsafe, Frame): Unit =
        engineQueue.poll() match
            case null => ()
            case op =>
                try op()
                catch
                    // Contain ANY throw (not just NonFatal): the engine FIFO must not let one connection's
                    // engine throw kill the FIFO for all connections. SSLEngine.unwrap raises on a received
                    // fatal alert (a THROW, not the readPlain == -2 return), so the op's own connection is
                    // expected to have failed typed inside the op's inner catch; this backstop logs and
                    // continues so every other connection on this driver remains unaffected.
                    case ex: Throwable =>
                        Log.live.unsafe.error(s"$label engine op threw; the engine FIFO worker continues draining", ex)
                end try
                drainEngineOps()
    end drainEngineOps

    /** Drain both per-driver FIFOs once, on the poll-loop carrier. The poll loop calls this every cycle (see [[pollLoop]]), making it the single
      * authoritative consumer of both FIFOs: `submitChange` / `submitEngineOp` only enqueue, so a submitted command or engine op is always drained
      * within one poll cycle. Each submit triggers the poll-loop wakeup unconditionally so a parked indefinite poll returns promptly
      * when work arrives. This is the recovery for the Native TLS deadlock: the prior design drained on a `Fiber.Unsafe.init` task spawned per
      * burst, which a scheduler strand could lose, leaving the FIFO undrained forever; the poll loop is the one carrier running for the driver's
      * whole life and cannot be lost that way. Draining on the poll-loop carrier (not the submitting carrier) keeps the offload contract: a submit
      * never drains inline on the carrier that called it.
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

    // Wake-guard encoding (see PollerIoDriver.wakeGuard, acquireWake/releaseWake/closeWakeGuarded): the low WakeHolderMask bits count the in-flight
    // backend.wake calls touching the wake fd, WakeClosingBit records that the wake-fd teardown has begun, and WakeClosed is the terminal value once
    // backend.closeWake has run (a sentinel distinct from any holders|WakeClosingBit combination, so a repeat closeWakeGuarded is idempotent). Mirrors
    // the PosixHandle.guard encoding.
    final private val WakeClosingBit = 1 << 30
    final private val WakeHolderMask = WakeClosingBit - 1
    final private val WakeClosed     = -1

    /** A pending writable registration: the waiter's promise paired with the arming handle's monotonic id. The id lets `dispatchWritable`
      * apply the same stale-fd-id equality guard the read/accept paths use (the read path reads the id off the stored `PosixHandle`; the
      * writable path stores it here because the writable promise is not held on the handle), so a writable readiness event the kernel queued
      * for a fd's prior owner is dropped after the fd is recycled into a new owner rather than delivered to the new owner as `Success`.
      */
    final private case class PendingWritable(promise: Promise.Unsafe[Unit, Abort[Closed]], id: HandleId)

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
        init(backend, fd, Ffi.load[SocketBindings])
    end init

    /** Build a driver over a caller-supplied backend, poller fd, and socket bindings, allocating the driver's unsafe fields (the atomic flags and
      * the poll scratch) under the caller's `AllowUnsafe`: the construction site propagates the capability rather than each field bridging it, so the
      * class body never holds an ambient `AllowUnsafe` and every method keeps requiring its own. Shared by [[init]] and the test construction helpers
      * so the unsafe allocation lives in one place.
      */
    private[posix] def init(backend: PollerBackend, pollerFd: Int, sockets: SocketBindings)(using AllowUnsafe): PollerIoDriver =
        new PollerIoDriver(
            backend = backend,
            pollerFd = pollerFd,
            sockets = sockets,
            closedFlag = AtomicBoolean.Unsafe.init(false),
            pollScratch = backend.newPollScratch(),
            started = AtomicBoolean.Unsafe.init(false),
            freeScratchOnce = AtomicBoolean.Unsafe.init(false),
            wakePending = AtomicBoolean.Unsafe.init(false),
            wakeArmed = AtomicBoolean.Unsafe.init(false),
            wakeGuard = AtomicInt.Unsafe.init(0),
            terminal = AtomicBoolean.Unsafe.init(false),
            teardownComplete = AtomicBoolean.Unsafe.init(false),
            closeTeardownClaim = AtomicBoolean.Unsafe.init(false)
        )
    end init

end PollerIoDriver
