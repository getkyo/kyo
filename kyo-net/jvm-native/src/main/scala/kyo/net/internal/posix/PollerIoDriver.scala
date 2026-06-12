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
  * The change FIFO carries packed `java.lang.Long` commands rather than closures: each command encodes an opcode (RegisterRead /
  * RegisterWrite / Rearm / Deregister) plus fd and direction bits into one `Long`. This eliminates the per-change closure allocation.
  * Note that `ConcurrentLinkedQueue[java.lang.Long]` boxes each `Long` (the JVM element type is a reference), so a residual boxing
  * allocation remains per enqueue; a zero-allocation unboxed MPSC queue would remove it but is a larger separate change. The previous
  * `Function0` closure allocation per change is replaced by a smaller boxed-`Long` allocation.
  *
  * The pending read and accept promises are stored directly on the [[PosixHandle]] (`pendingReadPromise`, `pendingAcceptPromise`) rather than
  * as `(promise, handle)` tuple pairs in the pending maps. This removes the per-await `Tuple2` allocation; the maps now hold `PosixHandle`
  * directly.
  *
  * The per-driver [[PollScratch]] holds the reused events buffer (epoll: `MaxEvents * EpollEvent.size` bytes; kqueue: `MaxEvents` KEvent
  * slots via `KqueuePollData`), the decoded fds/flags parallel arrays, and the arm buffer (epoll: one EpollEvent; kqueue: one KEvent slot via
  * `KqueuePollData`). All are allocated once at driver init and closed when the driver closes.
  *
  * Stale-event guard: each handle carries a unique `id`; `activeFds` maps fd to the current id so an event for a fd that was closed and
  * recycled into a different handle is recognised and dropped rather than delivered to the new handle.
  */
final private[net] class PollerIoDriver private[posix] (backend: PollerBackend, pollerFd: Int, sockets: SocketBindings)
    extends IoDriver[PosixHandle], TlsEngineIo:

    import PollerIoDriver.PendingWritable

    // Unsafe: the driver's atomic flags are created at construction with no ambient AllowUnsafe; the danger bridge builds each here and every
    // access runs under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Concurrent-collection audit: the fd-keyed maps below and the changeQueue/engineQueue (further down) are raw
    // java.util.concurrent types. kyo has no concurrent-map type, and its effect-based Queue/Channel cannot back this driver's non-parking poll
    // loop and lock-free change/engine FIFOs (the maps are touched on the poll-loop and change-worker carriers without suspension; the queues are
    // single-consumer FIFOs drained inline). Retained as documented no-equivalent exceptions; the per-field comments name each owning carrier.
    // fd -> current handle id. Used to discard stale poller events after fd reuse.
    private val activeFds = new ConcurrentHashMap[Int, Long]()

    // readFd -> handle (promise stored on handle.pendingReadPromise); writeFd -> writable entry. Keyed per direction.
    // The read promise is stored on the handle directly, eliminating the per-await (promise, handle) Tuple2 allocation.
    // The writable entry pairs the promise with the arming handle's monotonic id so dispatchWritable can apply the same
    // stale-fd-id equality guard the read/accept paths use (activeFds[writeFd] == id), instead of a presence-only check that
    // would deliver a writable readiness event the kernel queued for a now-recycled fd's prior owner to the new owner.
    private val pendingReads     = new ConcurrentHashMap[Int, PosixHandle]()
    private val pendingWritables = new ConcurrentHashMap[Int, PendingWritable]()

    // readFd -> handle (accept promise stored on handle.pendingAcceptPromise) for listen fds registered via awaitAccept.
    // Keyed separately from pendingReads: the listen fd must route to dispatchAccept, not dispatchRead.
    // The check in drainReady tries pendingAccepts first, then falls through to pendingReads.
    private val pendingAccepts = new ConcurrentHashMap[Int, PosixHandle]()

    // Backend interest changes (register / deregister) must run in submission order. epoll_ctl and kqueue's EV_ADD/EV_DELETE are last-write-wins
    // per fd+filter, so an out-of-order deregister can delete a freshly re-armed interest and strand the fd (no readiness event ever fires for
    // data that arrives afterward). The poll loop and every IoDriver method submit their changes here; a single worker fiber drains the FIFO and
    // runs each change to completion before the next, so on any given fd a deregister issued before a re-arm cannot land after it. The poll wait
    // itself runs on its own fiber (concurrent kevent/epoll_wait against a changelist is safe in the kernel); only the mutations are serialized.
    //
    // Element type: java.lang.Long. Each entry packs an opcode + fd + direction bits into one Long (see packCmd / OpXxx constants). This
    // eliminates the per-change Function0 closure allocation of the previous design. Note: ConcurrentLinkedQueue[java.lang.Long] boxes each
    // Long (the JVM reference-type element constraint), so a residual boxing allocation remains; an unboxed MPSC queue would eliminate it
    // but is a larger separate change.
    // Exposed for allocation-seam tests: the element type (java.lang.Long) proves no closure is allocated per change.
    private[posix] val changeQueue = new ConcurrentLinkedQueue[java.lang.Long]()
    private val changeWorkerActive = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Engine-op serialization: all TLS engine ops (handshakeStep, feedCiphertext, readPlain, writePlain, drainCiphertext,
    // hasBufferedPlaintext, readBuffered) for every connection on this driver route through this FIFO and are drained by one
    // dedicated worker carrier. This guarantees at most one engine op runs at a time per connection (the single-owner guarantee),
    // which allows the per-engine lock to be deleted. The recv/send syscalls stay outside the FIFO.
    private val engineQueue        = new ConcurrentLinkedQueue[() => Unit]()
    private val engineWorkerActive = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

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

    // Opcode constants for the packed Long change command:
    //   Bits 34+: opcode (2 bits: 0=RegisterRead, 1=RegisterWrite, 2=Rearm, 3=Deregister)
    //   Bit  33:  firedWrite (for Rearm opcode only; false for others)
    //   Bit  32:  firedRead  (for Rearm opcode only; false for others)
    //   Bits 31-0: fd (32-bit signed int; OS fds fit in 31 bits on Linux; the full 32-bit range is preserved in the low 32 bits)
    private val OpRegisterRead: Long  = 0L
    private val OpRegisterWrite: Long = 1L
    private val OpRearm: Long         = 2L
    private val OpDeregister: Long    = 3L

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
        Fiber.Unsafe.init { pollLoop() }
    end start

    /** Free the per-driver poll scratch exactly once. Called by the poll loop carrier at its terminal exit (the loop ran and its last poll has
      * completed, so the buffer is provably not in use) and by close()'s never-started path (the loop never ran). The CAS makes it idempotent
      * across those two single owners: no double-free, and no use-after-free (the loop frees only after its last poll; close() frees only when
      * no loop runs).
      */
    private def freeScratch()(using AllowUnsafe): Unit =
        if freeScratchOnce.compareAndSet(false, true) then pollScratch.close()

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
            val waitFiber = backend.poll(pollerFd, timeoutMs = 100, pollScratch) // sanctioned bounded park
            if waitFiber.done() then
                // JVM/Native inline-completion path: the @Ffi.blocking wait completed synchronously. Extract events and continue the
                // while loop without growing the stack (the while iteration is the tail-loop equivalent of @tailrec).
                waitFiber.poll() match
                    case Present(Result.Success(n)) => drainReady(pollScratch.fds, pollScratch.flags, n.eval)
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
                    case Result.Success(n) =>
                        drainReady(pollScratch.fds, pollScratch.flags, n.eval)
                        if !closedFlag.get() then discard(Fiber.Unsafe.init { pollLoop() })
                        else freeScratch() // terminal JS exit: closed flag set, not re-entering, last poll done -> free the scratch once.
                    case _ => freeScratch() // terminal JS exit: wait failed, not re-entering -> free the scratch once.
                }
            end if
        end while
        // Terminal exit on JVM/Native (the while loop ended on closedFlag or a backend failure/panic): the last poll has completed and the
        // scratch is provably not in use, so the poll loop carrier frees it here. Skipped when the JS branch parked on a pending wait fiber:
        // that loop is not terminal (it frees from the onComplete instead).
        if !enteredPending then freeScratch()
    end pollLoop

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        activeFds.put(handle.readFd, handle.id)
        handle.pendingReadPromise = Present(promise)
        discard(pendingReads.put(handle.readFd, handle))
        // rc<0 failure is handled inside dispatchCmd, which reads pendingReads and fails the stored promise.
        // The pendingReadPromise write happens-before the changeQueue.offer (the ConcurrentLinkedQueue offer is the
        // happens-before barrier): the change worker sees the stored promise when it processes rc<0.
        submitChange(packCmd(OpRegisterRead, handle.readFd, firedRead = false, firedWrite = false))
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
        activeFds.put(handle.writeFd, handle.id)
        // Store the arming handle's id alongside the promise so dispatchWritable can drop the event if the fd is recycled (mirrors the read
        // path, which reads the id off the stored handle and compares against activeFds[fd]).
        discard(pendingWritables.put(handle.writeFd, PendingWritable(promise, handle.id)))
        // rc<0 failure is handled inside dispatchCmd, which reads pendingWritables and fails the stored promise.
        submitChange(packCmd(OpRegisterWrite, handle.writeFd, firedRead = false, firedWrite = false))
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
        activeFds.put(handle.readFd, handle.id)
        handle.pendingAcceptPromise = Present(promise)
        discard(pendingAccepts.put(handle.readFd, handle))
        // rc<0 failure is handled inside dispatchCmd, which checks pendingAccepts and fails the stored promise.
        submitChange(packCmd(OpRegisterRead, handle.readFd, firedRead = false, firedWrite = false))
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
                val currentId = Maybe(activeFds.get(handle.readFd))
                if currentId != Present(handle.id) then
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
            val result: Maybe[Ffi.WithError[Long]] = sendBlockingWithRetry(handle.writeFd, mirror, len.toLong, flags)
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
    private def appendPending(handle: PosixHandle, drain: Buffer[Byte], len: Int): Unit =
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
                        val result: Maybe[Ffi.WithError[Long]] =
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

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        discard(activeFds.remove(handle.readFd))
        discard(activeFds.remove(handle.writeFd))
        submitChange(packCmd(OpDeregister, handle.readFd, firedRead = false, firedWrite = false))
        if handle.writeFd != handle.readFd then
            submitChange(packCmd(OpDeregister, handle.writeFd, firedRead = false, firedWrite = false))
        end if
        val closed = Closed(label, summon[Frame], s"fd=${handle.readFd}/${handle.writeFd} canceled")
        Maybe(pendingReads.remove(handle.readFd)).foreach { h =>
            h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
            h.pendingReadPromise = Absent
        }
        Maybe(pendingWritables.remove(handle.writeFd)).foreach(_.promise.completeDiscard(Result.fail(closed)))
        Maybe(pendingAccepts.remove(handle.readFd)).foreach { h =>
            h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
            h.pendingAcceptPromise = Absent
        }
        // Fail any WritePump promise parked at the write-backpressure high-water bound (it is not in pendingWritables: a tail-bound park is held on
        // the handle, not armed on socket readiness). Releasing it with Closed lets the pump tear down rather than hang on a tail that will never drain.
        handle.backpressureWaiter.foreach { case (p, _) => p.completeDiscard(Result.fail(closed)) }
        handle.backpressureWaiter = Absent
    end cancel

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        cancel(handle)
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

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            val closed = Closed(label, summon[Frame], "driver closed")
            pendingReads.forEach { (_, h) =>
                h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                h.pendingReadPromise = Absent
            }
            pendingReads.clear()
            pendingWritables.forEach((_, entry) => entry.promise.completeDiscard(Result.fail(closed)))
            pendingWritables.clear()
            pendingAccepts.forEach { (_, h) =>
                h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                h.pendingAcceptPromise = Absent
            }
            pendingAccepts.clear()
            activeFds.clear()
            backend.close(pollerFd)
            // The poll scratch is NOT freed here while the loop is running: the poll loop carrier uses it on every cycle (the bounded
            // epoll_wait/kevent holds the off-heap buffer in use for the whole native wait), so freeing it from this carrier mid-poll closes
            // the shared off-heap arena while it is still acquired. close() only signals termination via closedFlag (set above); the poll loop
            // sees it between polls and frees the scratch at its terminal exit (freeScratch), when the buffer is provably not in use. The only
            // case close() frees directly is when start() was never called: the loop never ran, so no carrier is using the scratch and it would
            // otherwise leak. freeScratch CASes so the loop's terminal free and this never-started free can never double-free.
            if !started.get() then freeScratch()
        end if
    end close

    /** Drain all ready events from one bounded poll result, dispatching reads, writables, and error-only events in order.
      *
      * Called from the `while`-loop body of `pollLoop` after `backend.poll` completes (inline on JVM/Native, via `onComplete` on JS). Each event:
      *   - Re-arms the still-pending OTHER direction first (epoll's EPOLLONESHOT disables the whole fd when any direction fires; kqueue
      *     `rearm` is a no-op). Submitted before dispatch so the survivor is ordered ahead of any re-arm the dispatch itself may issue.
      *   - Dispatches read-ready events to `dispatchRead` (or `dispatchAccept` for listen fds).
      *   - Dispatches write-ready events to `dispatchWritable`.
      *   - Dispatches error-ONLY events (no read/write bit) to `dispatchError` so a peer reset surfaces immediately rather than being
      *     missed until a later op fails. When a read or write bit is also set, the normal dispatch runs and surfaces the error in-band.
      */
    private def drainReady(fds: Array[Int], flags: Array[Int], n: Int)(using AllowUnsafe, Frame): Unit =
        var i = 0
        while i < n do
            val fd    = fds(i)
            val f     = flags(i)
            val read  = (f & PollFlags.Read) != 0
            val write = (f & PollFlags.Write) != 0
            val error = (f & PollFlags.Error) != 0
            // Re-arm the still-pending OTHER direction first (see scaladoc above).
            if read || write then rearmSurvivors(fd, firedRead = read, firedWrite = write)
            if read then
                // Prefer accept dispatch over read dispatch: a listen fd registered via awaitAccept must not be routed to dispatchRead.
                if pendingAccepts.containsKey(fd) then dispatchAccept(fd)
                else dispatchRead(fd)
            end if
            if write then dispatchWritable(fd)
            // Error-ONLY event: no read/write-ready bit carried it, so no recv/send will observe the error. Fail the pending op(s).
            if error && !read && !write then dispatchError(fd)
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

    private def dispatchRead(fd: Int)(using AllowUnsafe, Frame): Unit =
        Maybe(pendingReads.remove(fd)) match
            case Present(handle) =>
                val promise   = handle.pendingReadPromise
                val currentId = Maybe(activeFds.get(handle.readFd))
                if currentId != Present(handle.id) then
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
                                case Absent          => dispatchReadPlain(fd, p, handle)
                        case Absent =>
                            // Promise was already cleared (e.g. by a concurrent cancel); release the dispatch guard.
                            discard(handle.endDispatch())
                    end match
                end if
            case Absent =>
                // No pending read (a one-shot event arrived after cancel/close, or a stale-fd recycle whose new owner has none). Drop it.
                ()
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

    /** Release this dispatch's ownership and re-arm one-shot read interest to keep waiting (the EAGAIN / partial-record path), UNLESS a
      * `closeHandle` raced this dispatch, in which case the deferred free has happened and the read is failed `Closed` instead of re-armed (a
      * re-arm on a closed handle would leak interest and never fire).
      */
    private def rearmOwned(fd: Int, handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using
        AllowUnsafe,
        Frame
    ): Unit =
        if handle.endDispatch() then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read on closed handle fd=$fd")))
        else
            handle.pendingReadPromise = Present(promise)
            discard(pendingReads.put(fd, handle))
            rearmRead(fd)

    private def dispatchReadPlain(fd: Int, promise: Promise.Unsafe[Span[Byte], Abort[Closed]], handle: PosixHandle)(using
        AllowUnsafe,
        Frame
    ): Unit =
        // recvNow is the synchronous non-blocking recv; the fd is O_NONBLOCK and MSG_DONTWAIT guards against any momentarily
        // blocking fd, so recv never parks. Returns >0 (bytes), 0 (EOF), or -1 with errno EAGAIN/EWOULDBLOCK (spurious wakeup)
        // or a hard error. EINTR (a signal interrupted the call before any byte moved) is retried in place by recvNowWithRetry, bounded,
        // so a signal does not surface as Closed and drop a healthy connection (POSIX recv(2); the accept path's precedent).
        val result = recvNowWithRetry(fd, handle.readBuffer, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
        val n      = result.value.toInt
        if n > 0 then
            val arr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
            // Keep a reference to this chunk so a subsequent STARTTLS upgrade can recover a handshake flight that arrived coalesced with
            // the upgrade signal in this same read (the consumer takes the whole chunk as the signal and discards the trailing bytes).
            handle.lastPlaintextRead = Present(arr)
            finishDispatch(fd, handle, promise, Result.succeed(Span.fromUnsafe(arr)))
        else if n == 0 then
            // Orderly peer close: EOF is an empty Span, not a failure.
            finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
        else if isWouldBlock(result.errorCode) then
            // Spurious wakeup / drained buffer: re-arm the one-shot read interest and keep waiting.
            rearmOwned(fd, handle, promise)
        else
            // Hard error (e.g. peer reset / ECONNRESET): surface Closed.
            finishDispatch(
                fd,
                handle,
                promise,
                Result.fail(Closed(label, summon[Frame], s"recv failed fd=$fd errno=${result.errorCode}"))
            )
        end if
    end dispatchReadPlain

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
            handle.peerEof = true
            finishDispatch(fd, handle, promise, Result.succeed(Span.empty[Byte]))
        else if isWouldBlock(result.errorCode) then
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
                val currentId = Maybe(activeFds.get(fd))
                if currentId != Present(entry.id) then
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

    /** A non-blocking `recvNow` with a bounded in-place EINTR retry. POSIX recv(2): when a signal is delivered before any byte is transferred the
      * call returns -1 with errno EINTR; no data was moved, so the call is retried (the accept path's precedent). Only EINTR is retried; EAGAIN /
      * EWOULDBLOCK and a genuine error are returned to the caller unchanged so the existing would-block (park / re-arm) and hard-error (fail Closed)
      * branches still decide. The retry is bounded by [[maxTransientIoRetries]] so an EINTR storm cannot spin: past the bound the last EINTR result
      * is returned and falls through to the hard-error branch.
      */
    private def recvNowWithRetry(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.WithError[Long] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Ffi.WithError[Long] =
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
    private def sendNowWithRetry(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.WithError[Long] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Ffi.WithError[Long] =
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
    ): Maybe[Ffi.WithError[Long]] =
        @scala.annotation.tailrec
        def loop(attempt: Int): Maybe[Ffi.WithError[Long]] =
            takeNow(sockets.send(fd, buf, len, flags)) match
                case Present(r) if r.value.toInt < 0 && r.errorCode == PosixConstants.EINTR && attempt < maxTransientIoRetries =>
                    loop(attempt + 1)
                case other => other
        loop(0)
    end sendBlockingWithRetry

    /** Submit an interest change command to the serial worker. The packed Long command is appended to the FIFO and a single worker fiber is
      * started if none is running; changes therefore run strictly in submission order, never reordered against one another.
      */
    private def submitChange(cmd: Long)(using AllowUnsafe, Frame): Unit =
        discard(changeQueue.offer(java.lang.Long.valueOf(cmd)))
        if changeWorkerActive.compareAndSet(false, true) then
            discard(Fiber.Unsafe.init(drainChanges()))
    end submitChange

    /** Pack an opcode, fd, and direction bits into a single Long command for the change FIFO.
      *   Bits 34+: opcode (OpRegisterRead=0 / OpRegisterWrite=1 / OpRearm=2 / OpDeregister=3)
      *   Bit  33:  firedWrite (for OpRearm only; false for others)
      *   Bit  32:  firedRead  (for OpRearm only; false for others)
      *   Bits 31-0: fd as a signed 32-bit value (OS fds fit in 31 bits on Linux; the full 32-bit signed range is preserved in the low 32 bits)
      */
    private def packCmd(op: Long, fd: Int, firedRead: Boolean, firedWrite: Boolean): Long =
        (op << 34) |
            (fd.toLong & 0xffffffffL) |
            (if firedRead then 1L << 32 else 0L) |
            (if firedWrite then 1L << 33 else 0L)

    /** Decode a packed Long command and dispatch to the appropriate backend method. On rc<0 from registerRead/registerWrite, the pending
      * promise is failed via the pending tables. For OpRegisterRead, pendingAccepts is checked before pendingReads (matching the existing
      * drainReady accept-before-read priority so that a listen fd registered via awaitAccept is correctly handled on rc<0).
      */
    private def dispatchCmd(cmd: Long)(using AllowUnsafe, Frame): Unit =
        val op         = cmd >>> 34
        val fd         = (cmd & 0xffffffffL).toInt
        val firedRead  = (cmd & (1L << 32)) != 0
        val firedWrite = (cmd & (1L << 33)) != 0
        val closed     = Closed(label, summon[Frame], s"register failed fd=$fd")
        if op == OpRegisterRead then
            // awaitAccept and awaitRead both use OpRegisterRead. Check pendingAccepts first (accept takes priority, matching drainReady).
            if pendingAccepts.containsKey(fd) then
                val rc = backend.registerRead(pollerFd, fd, pollScratch)
                if rc < 0 then
                    Maybe(pendingAccepts.remove(fd)).foreach { h =>
                        h.pendingAcceptPromise.foreach(_.completeDiscard(Result.fail(closed)))
                        h.pendingAcceptPromise = Absent
                    }
                end if
            else
                val rc = backend.registerRead(pollerFd, fd, pollScratch)
                if rc < 0 then
                    Maybe(pendingReads.remove(fd)).foreach { h =>
                        h.pendingReadPromise.foreach(_.completeDiscard(Result.fail(closed)))
                        h.pendingReadPromise = Absent
                    }
                end if
            end if
        else if op == OpRegisterWrite then
            val rc = backend.registerWrite(pollerFd, fd, pollScratch)
            if rc < 0 then
                Maybe(pendingWritables.remove(fd)).foreach(_.promise.completeDiscard(Result.fail(closed)))
        else if op == OpRearm then
            backend.rearm(pollerFd, fd, firedRead, firedWrite, pollScratch)
        else
            // OpDeregister
            backend.deregister(pollerFd, fd, pollScratch)
        end if
    end dispatchCmd

    /** Drain the change FIFO one entry at a time, running each to completion before the next so the underlying `epoll_ctl` / `kevent`
      * syscalls execute in submission order. After the queue looks empty the worker stands down (CAS active->false) and re-checks once: a
      * change offered between the empty poll and the CAS is not lost because the offering thread restarts the worker only when it observes
      * active=false.
      */
    @scala.annotation.tailrec
    private def drainChanges()(using AllowUnsafe, Frame): Unit =
        changeQueue.poll() match
            case null =>
                changeWorkerActive.set(false)
                if changeQueue.peek() != null && changeWorkerActive.compareAndSet(false, true) then drainChanges()
            case cmd: java.lang.Long =>
                dispatchCmd(cmd.longValue)
                drainChanges()
    end drainChanges

    /** Re-arm one-shot read interest on `fd` through the serial worker (the poll loop's EAGAIN / partial-record re-register). Routing it through
      * the same FIFO as the synchronous register / deregister keeps a re-arm from being reordered against a concurrent cancel for the same fd.
      */
    private def rearmRead(fd: Int)(using AllowUnsafe, Frame): Unit =
        submitChange(packCmd(OpRegisterRead, fd, firedRead = false, firedWrite = false))

    /** After a readiness event fired on `fd`, re-arm the still-pending OTHER direction through the serial worker. On epoll a fired event under
      * `EPOLLONESHOT` disables the whole fd, so a read parked next to a writable (or the reverse) would otherwise be starved; the backend re-arms
      * the non-fired survivor here. On kqueue this is a no-op (the directions are independent filters). Routed through the same FIFO as register /
      * deregister so it stays ordered against a concurrent cancel for the same fd.
      */
    private def rearmSurvivors(fd: Int, firedRead: Boolean, firedWrite: Boolean)(using AllowUnsafe, Frame): Unit =
        submitChange(packCmd(OpRearm, fd, firedRead, firedWrite))

    /** Submit a TLS engine op to the serial engine worker and return immediately. The thunk runs to completion before the next, so no
      * two engine ops for any connection on this driver overlap. Mirrors the submitChange/drainChanges pattern; the recv/send syscalls
      * that surround engine ops stay outside the FIFO.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(engineQueue.offer(op))
        if engineWorkerActive.compareAndSet(false, true) then discard(Fiber.Unsafe.init(drainEngineOps()))
    end submitEngineOp

    /** Drain the engine-op FIFO one entry at a time, running each to completion before the next. The stand-down / re-check pattern
      * (same as drainChanges) ensures an op offered between the empty peek and the CAS is not lost.
      */
    @scala.annotation.tailrec
    private def drainEngineOps()(using AllowUnsafe, Frame): Unit =
        engineQueue.poll() match
            case null =>
                engineWorkerActive.set(false)
                if engineQueue.peek() != null && engineWorkerActive.compareAndSet(false, true) then drainEngineOps()
            case op =>
                // A throwing engine op must not kill the worker: that would leave engineWorkerActive stuck true with no replacement worker
                // spawned, stranding every other connection's engine ops on this driver (a multi-connection silent hang, Netty #7337 class).
                // Surface the failure and keep draining; the throwing op's own promise handling is its concern.
                try op()
                catch case ex: Throwable => Log.live.unsafe.error(s"$label engine op threw; the engine FIFO worker continues draining", ex)
                end try
                drainEngineOps()
    end drainEngineOps

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

    /** Build a driver over a fresh poller fd for the OS-appropriate backend (epoll on Linux, kqueue on macOS/BSD). */
    def init(config: kyo.net.TransportConfig)(using AllowUnsafe, Frame): PollerIoDriver =
        val backend = PollerBackend.default()
        val fd      = backend.create()
        new PollerIoDriver(backend, fd, Ffi.load[SocketBindings])
    end init

end PollerIoDriver
