package kyo.net.internal.transport

import kyo.*

/** Completion-based I/O driver. The driver performs reads internally and delivers results via promise completion. This model unifies
  * poll-based platforms (Native epoll/kqueue, JVM NIO Selector) with callback-based platforms (JS Node.js).
  *
  * #### Completion model
  *
  * Unlike readiness-based APIs (epoll, kqueue) where the caller is notified "fd is readable" and then calls read(), this driver does the
  * read internally and completes the caller's promise with the actual bytes. This matches how libuv, Netty, and Node.js work.
  *
  * #### Promise passing
  *
  * Callers pass a `Promise.Unsafe` to `awaitRead`/`awaitWritable`. The driver stores the promise and completes it when the operation
  * finishes. This avoids allocation per operation when the caller reuses a promise (e.g., pumps extend IOPromise and pass themselves).
  *
  * #### Platform implementations
  *
  *   - Native (PollerIoDriver/IoUringDriver): epoll/kqueue poll loop or io_uring completion does the read, completes promise
  *   - JVM (PollerIoDriver/IoUringDriver above the NioIoDriver floor): poll/completion loop does the read, completes promise
  *   - JS (JsIoDriver): Node.js "data" event delivers bytes, completes promise
  *
  * @tparam Handle
  *   Platform's connection identifier: `PosixHandle` (fd) for posix Native and JVM, `SocketChannel` for the JVM Nio floor, `JsHandle` for JS
  */
abstract private[kyo] class IoDriver[Handle]:

    /** Start the driver's event loop. Returns a fiber representing the event loop lifetime.
      *
      * Poll-based drivers spawn a fiber that loops on the poll mechanism. JS returns a sentinel fiber that completes on close().
      */
    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

    /** Request a read. The driver performs the read and completes the promise with a typed
      * [[ReadOutcome]], so a zero-length read is never overloaded:
      *
      *   - `Success(ReadOutcome.Bytes(span))`: data read
      *   - `Success(ReadOutcome.WouldBlock)`: EAGAIN, re-arm (NOT EOF)
      *   - `Success(ReadOutcome.PeerFin)`: orderly peer EOF (`recv == 0`, no local shutdown)
      *   - `Success(ReadOutcome.LocalShutdown)`: `recv == 0` after our own `shutdown(SHUT_RD)`
      *   - `Success(ReadOutcome.CleanClose)`: a TLS close_notify was consumed
      *   - `Success(ReadOutcome.Failed(cause))`: a typed hard error
      *   - `Failure(Closed)`: the read was cancelled (detach/close)
      *
      * Only one read request per handle at a time. Calling again before completion is undefined.
      */
    def awaitRead(handle: Handle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Request a read armed by the STARTTLS handshake itself (the ciphertext recv it drives during an upgrade), distinct from the application
      * ReadPump's [[awaitRead]]. Behaves exactly like `awaitRead` except that the io_uring driver tags the recv so its reap keeps a stray
      * plaintext-pump recv (reaping during the upgrade window) off the raw read path (routing it through the upgrade handoff) while still feeding
      * the handshake's own recv to the engine. The default delegates to `awaitRead`: the pollers need no distinction (their reads are synchronous,
      * with no reaped-recv routing ambiguity), so only the io_uring driver overrides it.
      */
    // TODO can we remove this kind of indirection?
    def awaitReadHandshake(handle: Handle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        awaitRead(handle, promise)

    /** Request writable notification. The driver completes the promise when the handle becomes writable.
      *
      * Used after `write` returns `Partial` to know when to retry.
      */
    def awaitWritable(handle: Handle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Request connect completion notification. The driver completes the promise when the non-blocking connect finishes.
      *
      * On JVM this uses OP_CONNECT which fires reliably when the TCP handshake completes, eliminating the race condition that exists with
      * OP_WRITE-based connect detection. On Native, delegates to awaitWritable (epoll/kqueue signal connect via write-readiness). On JS,
      * completes immediately (Node.js handles connect via callback).
      */
    def awaitConnect(handle: Handle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Register interest in ONE accepted client fd on the (non-blocking, poller-armed) listen fd `handle`. Completes `promise` with the
      * accepted fd (`>= 0`) or `Closed`. The caller re-arms once per accepted fd (epoll/kqueue drain extra ready fds via acceptNow;
      * io_uring yields one fd per IORING_OP_ACCEPT).
      */
    def awaitAccept(handle: Handle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Synchronous write attempt of `data` starting at `offset`. Done: all bytes from offset written. Partial(data, newOffset): socket buffer
      * full, await writable and retry the same span from newOffset. Error: connection failed.
      */
    def write(handle: Handle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult

    /** Cancel pending read/write requests for handle. Completes pending promises with Closed. */
    def cancel(handle: Handle)(using AllowUnsafe, Frame): Unit

    /** Detach `handle` for a STARTTLS upgrade: fail its pending plaintext read/write promises so the plaintext pumps tear down, while the SAME
      * connection is about to be re-driven as a TLS handshake over the same fd. The default is [[cancel]]: drivers whose handshake re-establishes
      * its own I/O registration need no special handling (io_uring re-arms via SQEs; the readiness pollers re-register synchronously). The NIO
      * driver overrides this to KEEP the channel's `SelectionKey` (resetting interest to 0) instead of cancelling it. Cancelling marks the key for
      * removal at the next `select()`, and `channel.register` before that async flush throws `CancelledKeyException` and defers, opening a window
      * where a concurrent handshake read-arm finds the channel with no live key and spuriously fails the handshake (the STARTTLS-under-concurrency
      * flake). Keeping the key removes that window entirely.
      */
    def detachForUpgrade(handle: Handle)(using AllowUnsafe, Frame): Unit =
        cancel(handle)

    /** Called by the plaintext [[kyo.net.internal.transport.ReadPump]] when bytes it read off the socket cannot be delivered because the inbound
      * channel is already closed. The default DROPS them: the connection is closing, so the bytes are discarded with the teardown. The NIO driver
      * overrides this for the STARTTLS handoff: while a handle is upgrading, the close that failed the offer is `detachForUpgrade`'s inbound close,
      * so these bytes are the peer's first TLS flight (e.g. the ClientHello) the pump pulled off the socket a moment before the upgrade detached.
      * Dropping them strands the handshake (it reads the socket, which no longer holds them); the NIO override salvages them for the handshake to
      * replay. Captured at the offer-failure point, so it is robust to the read/detach interleaving regardless of when the upgrade flag was observed.
      */
    def onInboundClosedDuringRead(handle: Handle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit = ()

    /** Tear down a listener: cancel its pending accept and close its listen fd via `closeFd`, sequenced so that no accept registration for
      * `handle` can ever run against a RECYCLED fd number. The default (readiness drivers, where `cancel` clears the accept state
      * synchronously) cancels and then closes the fd immediately, today's behavior. The io_uring driver overrides this to run the whole
      * teardown on its reap carrier BEHIND any accept arm still queued on the engine FIFO: that arm preps its SQE while the fd still names
      * the listener's socket, the prepped SQEs are flushed, and only then does `closeFd` release the fd number for reuse. Without that
      * sequencing, a queued arm outlives the fd close, preps an accept against whatever socket RECYCLED the number (typically the next
      * listener), and each such ghost accept steals one incoming connection for the closed listener's handler.
      */
    def closeListener(handle: Handle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        cancel(handle)
        closeFd()
    end closeListener

    /** Whether a read for `handle` is still in flight at the OS layer (a kernel-owned recv that cannot be cancelled). True only on the io_uring
      * completion driver, whose `awaitRead` submits a recv SQE: after a STARTTLS `detachForUpgrade` that recv stays kernel-owned and will consume
      * the peer's next flight, so the upgrade must route its bytes through the handle's carry-over rather than issuing a second, racing recv. The
      * readiness drivers (epoll/kqueue, the JS Node driver) read synchronously on readiness and hold no in-flight recv, so they return false and
      * the upgrade reads normally.
      */
    def hasInFlightRead(handle: Handle)(using AllowUnsafe): Boolean = false

    /** Arm the I/O carrier as the sole producer of a STARTTLS upgrade's ciphertext reads, so the handshake never reads the socket itself. While
      * a handle is upgrading on a readiness poller the handshake parks a waiter on the handle's upgrade handoff slot and calls this; the poll
      * carrier then reads each peer flight and fulfils the waiter, the readiness-driver analog of the io_uring stale recv the reap carrier
      * delivers. Confining every recv to the one I/O carrier removes the cross-carrier recv race a synchronous `recvNow` on the handshake fiber
      * would otherwise have with the poll carrier's own read of the same fd. The default is a no-op: io_uring already has a kernel-owned recv in
      * flight (the upgrade reads through the handoff slot the reap fulfils, not a fresh arm), and JS reads via its data callback, so neither needs
      * a poll-carrier producer.
      */
    def armUpgradeProducerRead(handle: Handle)(using AllowUnsafe, Frame): Unit = ()

    /** Retire the STARTTLS upgrade producer at handshake completion, the counterpart to [[armUpgradeProducerRead]]. On a readiness poller that
      * kept a standing producer read armed across the upgrade (so every peer flight was read on the one I/O carrier), this drops that arm before
      * the upgraded connection's pumps start, so no post-completion readiness dispatch routes the connection's first application bytes to the dead
      * producer instead of the ReadPump. The default is a no-op: io_uring and JS never armed a standing producer (they read through the handoff
      * slot the reap/data callback fulfils), so there is nothing to retire.
      */
    def stopUpgradeProducer(handle: Handle)(using AllowUnsafe): Unit = ()

    /** Force the next read arm for `handle` to re-evaluate socket readiness, recovering a peer flight ALREADY buffered when the arm registers.
      * Called at STARTTLS completion (before the upgraded connection's pumps start) so the first ReadPump read picks up the peer's first
      * application flight even when it arrived before the read armed. Only the epoll readiness driver needs this: epoll is edge-triggered and skips
      * the `epoll_ctl(MOD)` on an unchanged-mask re-arm (register-once), so an already-buffered flight produces no new edge and the read would
      * strand. The default is a no-op: kqueue re-evaluates readiness on every `EV_ADD`, the JDK NIO selector is level-triggered (a ready channel is
      * re-reported), and io_uring is completion-based, so none of them can miss a buffered flight on a re-arm.
      */
    def forceReadRecovery(handle: Handle)(using AllowUnsafe, Frame): Unit = ()

    /** Whether the TLS handshake may use the synchronous `recvNow` probe (a direct `recv(2)`) to read ciphertext off the socket. The readiness
      * drivers (epoll/kqueue, JS Node) read synchronously and hold no kernel-owned recv, so the probe is safe and returns true (the default). The
      * io_uring completion driver overrides this to false: its `awaitRead` submits a recv SQE, so a connection's reads are kernel-owned, and mixing
      * a direct `recv(2)` with io_uring recvs on the same fd races the socket stream into the same `readBuffer` under load, corrupting a handshake
      * record (a fabricated record the peer never sent) and failing the TLS handshake. On io_uring the handshake therefore reads exclusively through
      * `awaitRead`.
      */
    def inlineRecvSafe: Boolean = true

    /** Close the handle (fd, socket, channel). Idempotent. Cleans up any pending operations. */
    def closeHandle(handle: Handle)(using AllowUnsafe, Frame): Unit

    /** Shutdown the driver. Closes the poll mechanism and completes the event loop fiber. Idempotent. */
    def close()(using AllowUnsafe, Frame): Unit

    /** Run a TLS engine op holding the connection's engine ownership, so no two carriers touch one native ssl or JDK SSLEngine at once on any
      * backend. The posix drivers (PollerIoDriver, IoUringDriver) override this with their per-driver engine FIFO (the worker is the single
      * owner). The NIO driver overrides it with a per-connection CAS ownership gate held for the op's duration, so the selector-carrier read
      * path and the caller-carrier write path each touch the JDK SSLEngine only while holding it, one carrier at a time. The default
      * (JsIoDriver, BlockingReaderDriver) runs inline: those backends are already single-owner.
      *
      * The op runs inside a containment boundary: a thrown engine op (a received fatal alert raising from SSLEngine.unwrap) is converted to
      * the op's own connection's typed failure and that connection is torn down, while the FIFO continues for the others. The override carries
      * the "Contain ANY throw (not just NonFatal): the engine FIFO must not let one connection's engine throw kill the FIFO for all
      * connections" rationale.
      */
    def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    /** Driver label for logging (e.g., "PollerIoDriver"). */
    def label: String

    /** Handle label for logging (e.g., "fd=42"). */
    def handleLabel(handle: Handle): String

end IoDriver
