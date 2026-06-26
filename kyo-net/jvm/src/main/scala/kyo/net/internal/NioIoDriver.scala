package kyo.net.internal

import java.io.IOException
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.internal.transport.*
import kyo.net.internal.util.*
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** JVM I/O driver backed by a single `java.nio.channels.Selector`.
  *
  * Drives all I/O for a pool of non-blocking `SocketChannel` connections. Callers register interest (read / write / connect / accept) by
  * depositing a `Promise` into the appropriate pending map and calling the corresponding `awaitX` method, which adds the interest bit to
  * the channel's `SelectionKey`. The event loop (started via `start()`) calls `Selector.select()` in a tight loop, dispatches each ready
  * key, and completes the pending promise.
  *
  * For TLS connections, reads are dispatched through `dispatchReadTls`, which feeds raw ciphertext from the kernel into the `SSLEngine` and
  * delivers decrypted plaintext. Writes go through `writeTls`, which wraps plaintext into TLS records before writing to the channel.
  *
  * Note: `tryUnwrapBuffered` is called at the start of every TLS read to drain any application data the JDK SSLEngine already unwrapped (e.g.
  * from the last handshake record or a coalesced TCP segment). Without this, the selector may never fire again because the kernel buffer is
  * empty even though decrypted bytes are available.
  */
final private[kyo] class NioIoDriver private (private var selector: Selector)
    extends IoDriver[NioHandle]:

    // Unsafe: created at driver construction with no ambient AllowUnsafe; the danger bridge builds it here and every get/compareAndSet runs
    // under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Unsafe: mirrors closedFlag pattern. Guards selector.wakeup() so it fires only on the false->true transition; the
    // post-select re-check in pollOnce closes the race window where a wakeup request arrives while select() is returning.
    // private[net] so tests in kyo.net.internal can observe the flag state directly.
    private[net] val wakeupPending = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Count of consecutive selector.select() calls returning zero keys on the select-loop carrier.
    // Read and written only from pollOnce() on the single select-loop fiber (single-carrier-confined).
    private var zeroKeyReturns: Int = 0

    // Flat array-backed key set installed via reflection; Absent when InaccessibleObjectException blocks the install.
    private val installedKeySet: Maybe[SelectedSelectionKeySet] =
        // Unsafe: reflection install runs at construction under the danger bridge (same pattern as closedFlag).
        installSelectedKeySet(selector)(using AllowUnsafe.embrace.danger)

    // Concurrent-collection audit: the four pending-op maps below are raw java.util.concurrent.ConcurrentHashMap. kyo has no
    // concurrent-map type, and its effect-based collections cannot back this driver's non-parking selector path (these maps are read/written on
    // the selector carrier when arming and on the caller's carrier on cancel/close, with no suspension). The raw type is retained as a documented
    // no-equivalent exception; each map is a channel -> pending-state entry, removed by cleanupPending/closeHandle.
    // Pending read requests: channel -> handle (promise stored on handle.readArm)
    private val pendingReads =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, NioHandle]()

    /** Test-observability seam: whether a pending read is still registered for `handle`'s channel. A handshake teardown that reaps the handle
      * through `closeHandle` removes this entry; a bare channel close leaves it stranded (a pendingReads leak). Read-only, no mutation.
      */
    private[kyo] def hasPendingRead(handle: NioHandle)(using AllowUnsafe): Boolean =
        pendingReads.containsKey(handle.channel)

    // Pending writable requests: channel -> promise
    private val pendingWritables =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Pending connect requests: channel -> promise
    private val pendingConnects =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Pending accept requests: server channel -> promise
    private val pendingAccepts =
        new java.util.concurrent.ConcurrentHashMap[ServerSocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Concurrent-collection audit: registerChannel's deferred path enqueues a handle here for the poll carrier to register on its next cycle.
    // kyo has no concurrent-queue type, so a raw ConcurrentLinkedQueue is the documented no-equivalent exception (same justification as the
    // pending-op maps above): producers are arbitrary caller carriers (offer on the CancelledKeyException path of registerChannel), the single
    // consumer is the poll carrier (drainPendingRegistrations, called at the top of pollOnce AFTER select() has flushed the selector's
    // cancelled-key set). offer is the happens-before barrier the consumer relies on. Entries are NioHandles whose channel.register(selector, 0)
    // must be retried on the poll carrier; see registerChannel for why a non-poll carrier cannot flush the cancelled key itself.
    private val pendingRegistrations =
        new java.util.concurrent.ConcurrentLinkedQueue[NioHandle]()

    /** Test-observability seam: number of handles awaiting deferred registration on the poll carrier. A `registerChannel` that hit the
      * cancelled-key race enqueues here and is drained by the poll loop's next `select()` cycle; this count is non-zero only in that window.
      * Read-only, no mutation.
      */
    private[net] def pendingRegistrationCount(using AllowUnsafe): Int =
        pendingRegistrations.size()

    def label: String = s"NioIoDriver[sel=${selector.hashCode()}]"

    def handleLabel(handle: NioHandle): String = s"channel=${handle.channel.hashCode()}"

    private def opsToString(ops: Int): String =
        val parts = new StringBuilder
        if (ops & SelectionKey.OP_READ) != 0 then parts.append("READ ")
        if (ops & SelectionKey.OP_WRITE) != 0 then parts.append("WRITE ")
        if (ops & SelectionKey.OP_ACCEPT) != 0 then parts.append("ACCEPT ")
        if (ops & SelectionKey.OP_CONNECT) != 0 then parts.append("CONNECT ")
        if parts.isEmpty then "NONE" else parts.toString.trim
    end opsToString

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // The NIO selector loop runs on a DEDICATED daemon thread, NOT a kyo scheduler carrier. `selector.select()` is a blocking JVM syscall and
        // the loop also drives jdk SSLEngine handshakes inline (CPU-bound), so running it on a carrier PINS that carrier with a non-preemptible,
        // non-suspending task: the scheduler cannot reclaim it, and a fiber continuation completed inline from the loop (a ReadPump byte delivery
        // that wakes a parked take) can be routed back onto the pinned carrier by the scheduler's fallback and STRAND there, hanging the connection
        // under CPU contention (few carriers). A dedicated thread keeps the loop off the carrier pool entirely: every fiber the loop completes is
        // scheduled through the external-submit path (Worker.current() == null) onto a real carrier, so nothing strands. The thread is a daemon, so
        // it never blocks a clean JVM exit and is exempt from the non-daemon-thread leak check; it exits when close() sets closedFlag and closes the
        // selector (which unblocks an in-flight select() and is observed at the next loop check, bounded by SelectTimeoutMs). The returned
        // Fiber.Unsafe completes when the loop thread exits.
        val donePromise = Promise.Unsafe.init[Unit, Any]()
        val thread =
            new Thread(
                () =>
                    try
                        @tailrec def loop(): Unit =
                            if !closedFlag.get() && pollOnce() then loop()
                        loop()
                        if closedFlag.get() then Log.live.unsafe.debug(s"$label event loop exited cleanly")
                        else Log.live.unsafe.warn(s"$label event loop exited unexpectedly")
                        donePromise.completeDiscard(Result.succeed(()))
                    catch
                        case t: Throwable =>
                            if !closedFlag.get() then Log.live.unsafe.error(s"$label event loop crashed", t)
                            donePromise.completeDiscard(Result.panic(t))
                ,
                s"$label-select-loop"
            )
        thread.setDaemon(true)
        thread.start()
        donePromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    def awaitRead(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Check for buffered TLS data before registering with selector.
        // After handshake, netInBuf may already contain application data.
        handle.tls match
            case Present(tls) =>
                tryUnwrapBuffered(tls) match
                    case Present(buffered) =>
                        Log.live.unsafe.debug(s"$label awaitRead ${handleLabel(handle)} found buffered TLS data size=${buffered.size}")
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(buffered)))
                    case Absent if tls.peerCleanClose =>
                        // Buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose so the
                        // ReadPump tears down instead of waiting on the selector for ciphertext the peer will never send.
                        promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                    case Absent =>
                        armRead(handle, promise)
            case Absent =>
                armRead(handle, promise)
        end match
    end awaitRead

    // Install a fresh ReadArmCell into the read-arm owner slot and register selector interest. Each arm
    // wraps the caller's promise in a freshly allocated ReadArmCell object; the selector carrier completes
    // only the current owner's promise via a reference-equality CAS on the stored cell. A stale arm holds
    // an older ReadArmCell heap object (distinct from the current arm's object even when both carry the
    // same promise), so its CAS fails.
    private def armRead(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        val newCell = Present(ReadArmCell(promise))
        handle.readArm.set(newCell)
        pendingReads.put(handle.channel, handle)
        Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
        if !registerInterest(handle.channel, SelectionKey.OP_READ) then
            discard(handle.readArm.compareAndSet(newCell, Absent))
            discard(pendingReads.remove(handle.channel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerRead failed for ${handleLabel(handle)}")))
        end if
    end armRead

    def awaitWritable(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        pendingWritables.put(handle.channel, promise)
        Log.live.unsafe.debug(s"$label awaitWritable registered ${handleLabel(handle)}")
        if !registerInterest(handle.channel, SelectionKey.OP_WRITE) then
            discard(pendingWritables.remove(handle.channel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerWrite failed for ${handleLabel(handle)}")))
        end if
    end awaitWritable

    def awaitConnect(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        Maybe(pendingConnects.putIfAbsent(handle.channel, promise)) match
            case Absent =>
                Log.live.unsafe.debug(s"$label awaitConnect registered ${handleLabel(handle)}")
                if !registerInterest(handle.channel, SelectionKey.OP_CONNECT) then
                    discard(pendingConnects.remove(handle.channel))
                    promise.completeDiscard(Result.fail(Closed(
                        label,
                        summon[Frame],
                        s"registerConnect failed for ${handleLabel(handle)}"
                    )))
                end if
            case Present(_) =>
                promise.completeDiscard(Result.panic(new IllegalStateException(
                    s"$label duplicate awaitConnect for ${handleLabel(handle)}"
                )))
        end match
    end awaitConnect

    /** IoDriver contract stub for the NIO driver. The NIO driver uses its own private accept seam (ServerSocketChannel-based); callers must
      * use the ServerSocketChannel overload below, not this one. Fails loudly so an accidental caller gets an immediate error rather than
      * silently receiving fd 0 (stdin), which is a latent misuse bug.
      */
    def awaitAccept(handle: NioHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        promise.completeDiscard(Result.fail(Closed(
            label,
            summon[Frame],
            "awaitAccept not supported on NioIoDriver; the Nio transport drives its own accept loop"
        )))

    /** Run a TLS engine op on the NIO driver. The NIO driver's selector-carrier read path (dispatchReadTls) and caller-carrier write path
      * (writeTls) acquire the per-connection engine ownership gate directly at their call sites, so no two carriers touch one connection's
      * JDK SSLEngine concurrently. NEED_TASK delegated tasks run inline inside the held ownership (a Fiber-per-task would stall indefinitely
      * waiting for the selector-carrier to schedule it while the selector-carrier is blocked waiting for the NEED_TASK result).
      *
      * This override exists to document the NIO ownership model. External callers (PosixTransport, tests) that reach NioIoDriver via the
      * IoDriver interface call op() directly; the gate is already held by whichever NIO call site is currently executing.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    def write(handle: NioHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        if data.isEmpty || offset >= data.size then WriteResult.Done
        else
            handle.tls match
                case Present(tls) => writeTls(handle, data, offset, tls)
                case Absent       => writePlain(handle, data, offset)

    private def writePlain(handle: NioHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        try
            val arr = data.toArrayUnsafe
            val len = arr.length - offset
            // Wrap with offset so no arr.drop(n) allocation is needed on partial writes.
            val buf = ByteBuffer.wrap(arr, offset, len)
            val n   = handle.channel.write(buf)
            if n < 0 then
                WriteResult.Error
            else if n >= len then
                WriteResult.Done
            else
                WriteResult.Partial(data, offset + n) // same span, advanced offset, no arr.drop
            end if
        catch
            case _: IOException => WriteResult.Error

    private def writeTls(handle: NioHandle, data: Span[Byte], offset: Int, tls: NioTlsState)(using AllowUnsafe): WriteResult =
        // Acquire per-connection engine ownership before any SSLEngine call. The selector-carrier
        // unwrap path (dispatchReadTls) and this caller-carrier wrap path both need exclusive access
        // to the JDK SSLEngine; the gate serializes them. The engine op is brief (one wrap + socket
        // write per call), so a spin avoids a park and the associated scheduling overhead.
        @tailrec def spinAcquire(): Unit =
            if !handle.engineGate.compareAndSet(false, true) then spinAcquire()
        spinAcquire()
        try
            // If there is pending ciphertext from a previous partial write, flush it first
            val canProceed =
                if tls.pendingCiphertext then
                    val n = handle.channel.write(tls.netOutBuf)
                    if n < 0 then Left(WriteResult.Error)
                    else if tls.netOutBuf.hasRemaining then
                        // Still can't flush: remain in pending state, return the original span at the current offset
                        Left(WriteResult.Partial(data, offset))
                    else
                        tls.pendingCiphertext = false
                        Right(())
                    end if
                else Right(())
            canProceed match
                case Left(result) => result
                case Right(_)     =>
                    // src starts at offset so the wrap loop consumes only the unsent region [offset, data.size).
                    val src = ByteBuffer.wrap(data.toArrayUnsafe, offset, data.size - offset)
                    // Loop wrapping: SSLEngine wraps one TLS record per call (~16KB),
                    // so large payloads need multiple wrap+flush iterations
                    @tailrec def wrapLoop(): WriteResult =
                        if !src.hasRemaining then WriteResult.Done
                        else
                            tls.netOutBuf.clear()
                            val result = tls.engine.wrap(src, tls.netOutBuf)
                            tls.netOutBuf.flip()
                            if result.getStatus eq javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW then
                                WriteResult.Error
                            else
                                // Try one write: if socket buffer is full, return Partial
                                val n = handle.channel.write(tls.netOutBuf)
                                if n < 0 then WriteResult.Error
                                else if tls.netOutBuf.hasRemaining then
                                    // Socket buffer full: save state and return the original span at the current ByteBuffer position.
                                    // src.position() tracks how many bytes from data[offset..] have been consumed, so the Partial offset is
                                    // src.position() (ByteBuffer positions are absolute within the wrapped array: position() == consumed so far
                                    // from data[offset..], i.e. the new absolute offset into data).
                                    tls.pendingCiphertext = true
                                    WriteResult.Partial(data, src.position())
                                else wrapLoop()
                                end if
                            end if
                    wrapLoop()
            end match
        catch
            case _: IOException => WriteResult.Error
        finally
            handle.engineGate.set(false)
        end try
    end writeTls

    /** Remove pending operations for a channel and fail their promises with Closed. */
    private def cleanupPending(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label cleanupPending ${handleLabel(handle)} reads=${
                if pendingReads.containsKey(handle.channel) then 1 else 0
            } writes=${if pendingWritables.containsKey(handle.channel) then 1 else 0} connects=${
                if pendingConnects.containsKey(handle.channel) then 1 else 0
            }")
        val closed = Closed(label, summon[Frame], s"${handleLabel(handle)} closed")
        Maybe(pendingReads.remove(handle.channel)).foreach { h =>
            h.readArm.getAndSet(Absent).foreach { armCell =>
                armCell.promise.completeDiscard(Result.fail(closed))
            }
        }
        Maybe(pendingWritables.remove(handle.channel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
        Maybe(pendingConnects.remove(handle.channel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
    end cleanupPending

    def cancel(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                key.cancel()
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
            end if
        catch
            case _: CancelledKeyException => ()
        end try
        cleanupPending(handle)
    end cancel

    /** STARTTLS detach that KEEPS the channel's SelectionKey. The same channel is re-driven as a TLS handshake immediately after, so instead of
      * cancelling the key (which marks it for removal at the next select() and makes the subsequent channel.register throw CancelledKeyException
      * and defer, opening the no-live-key window a concurrent handshake arm fails on), this resets the key's interest to 0 and keeps it. The
      * handshake then re-arms OP_READ/OP_WRITE on the SAME live key via registerInterest, with no re-registration race. Pending plaintext promises
      * are still failed (cleanupPending) so the plaintext pumps tear down, exactly as cancel does.
      */
    override def detachForUpgrade(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                discard(key.interestOps(0))
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
            end if
        catch
            case _: CancelledKeyException => ()
        end try
        cleanupPending(handle)
    end detachForUpgrade

    def closeHandle(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label closeHandle ${handleLabel(handle)}")
        cleanupPending(handle)
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                key.cancel()
        catch
            case _: CancelledKeyException => ()
        end try
        NioHandle.close(handle)
    end closeHandle

    /** Remove a pending accept entry for a server channel and fail its promise with Closed. */
    def cleanupAccept(serverChannel: ServerSocketChannel)(using AllowUnsafe, Frame): Unit =
        val closed = Closed(label, summon[Frame], s"server channel=${serverChannel.hashCode()} closed")
        Maybe(pendingAccepts.remove(serverChannel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
    end cleanupAccept

    /** Wake the selector so a deferred deregistration/close is processed on the next `select()` cycle even when the loop would otherwise park
      * with no timeout. A closed channel's `SelectionKey` is only deregistered (and, on JDK 11+, its fd actually `kill()`ed) during a `select()`
      * pass; nothing else wakes the selector on a listener close, so an idle driver would leave the cancelled key, and the listening socket,
      * pending indefinitely. Mirrors the wakeup coalescing the interest-change paths use (`wakeupPending` is cleared post-select).
      */
    def wakeup()(using AllowUnsafe): Unit =
        if wakeupPending.compareAndSet(false, true) then
            discard(selector.wakeup())
    end wakeup

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            Log.live.unsafe.debug(
                s"$label closing driver, failing ${pendingReads.size()} reads, ${pendingWritables.size()} writes, ${pendingConnects.size()} connects, ${pendingAccepts.size()} accepts"
            )
            val closed = Closed(label, summon[Frame], "driver closed")
            pendingReads.forEach { (_, h) =>
                h.readArm.getAndSet(Absent).foreach { armCell =>
                    armCell.promise.completeDiscard(Result.fail(closed))
                }
            }
            pendingReads.clear()
            pendingWritables.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingWritables.clear()
            pendingConnects.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingConnects.clear()
            pendingAccepts.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingAccepts.clear()
            // Drop any handles awaiting deferred registration: the driver is gone, so the poll carrier will never drain them. Their downstream
            // awaitX promises (if any were armed during the deferred window) are already failed by the pending-op-map cleanup above; the channels
            // are owned and closed by the caller (the upgrade teardown). Clearing prevents a stranded queue entry from outliving the driver.
            pendingRegistrations.clear()
            try selector.close()
            catch case _: IOException => ()
        end if
    end close

    /** Register a channel with this driver's selector. Must be called before awaitRead/awaitWritable. */
    def registerChannel(handle: NioHandle)(using AllowUnsafe): Boolean =
        try
            if wakeupPending.compareAndSet(false, true) then
                discard(selector.wakeup())
            discard(handle.channel.register(selector, 0))
            given Frame = Frame.internal
            Log.live.unsafe.debug(s"$label registerChannel ${handleLabel(handle)}")
            true
        catch
            case _: java.nio.channels.CancelledKeyException =>
                // A cancelled key for this channel is still pending in the selector's cancelled-key set. The set is flushed only by the poll
                // carrier at the start of each `select()`, and `channel.register` throws `CancelledKeyException` until then. Retrying inline
                // would force this caller carrier to wait for the flush: either a `selectNow()` (which deadlocks against the poll carrier that
                // holds the SelectorImpl monitor for the whole native poll, with unfair JVM locks giving it no progress guarantee) or an
                // OS-thread park (the forbidden block). The only sanctioned OS-thread block in the module is the poll carrier's `select()`
                // head-of-line, so neither is allowed here.
                //
                // Instead, hand the registration to the selector's owner: enqueue the handle, wake the poll carrier, and report success. The
                // poll carrier drains `pendingRegistrations` at the top of `pollOnce`, AFTER `select()` has flushed the cancelled-key set, and
                // completes the `channel.register(selector, 0)` there. The registration is therefore deferred but guaranteed: no inline park, no
                // spin. Triggers via Postgres SSLRequest upgrade (`NioTransport.upgradeToTls` re-registers the same channel after the plaintext
                // pump's `detachForUpgrade` cancelled its key).
                discard(pendingRegistrations.offer(handle))
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label registerChannel deferred to poll carrier ${handleLabel(handle)}")
                true
            case _: java.nio.channels.ClosedChannelException       => false
            case _: java.nio.channels.ClosedSelectorException      => false
            case _: java.nio.channels.IllegalBlockingModeException => false

    /** Register a server channel for accept operations. */
    def registerServerChannel(serverChannel: ServerSocketChannel)(using AllowUnsafe): Boolean =
        try
            if wakeupPending.compareAndSet(false, true) then
                discard(selector.wakeup())
            discard(serverChannel.register(selector, 0))
            true
        catch
            case _: java.nio.channels.ClosedChannelException       => false
            case _: java.nio.channels.ClosedSelectorException      => false
            case _: java.nio.channels.IllegalBlockingModeException => false

    /** Wait for server channel to have pending connections. Promise completes when accept() will not block. */
    def awaitAccept(serverChannel: ServerSocketChannel, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        pendingAccepts.put(serverChannel, promise)
        Log.live.unsafe.debug(s"$label awaitAccept registered server=${serverChannel.hashCode()}")
        if !registerServerInterest(serverChannel, SelectionKey.OP_ACCEPT) then
            discard(pendingAccepts.remove(serverChannel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerAccept failed")))
        end if
    end awaitAccept

    private def registerServerInterest(channel: ServerSocketChannel, ops: Int)(using AllowUnsafe): Boolean =
        try
            val key = channel.keyFor(selector)
            if (key ne null) && key.isValid then
                val current = key.interestOps()
                val newOps  = current | ops
                if newOps != current then
                    discard(key.interestOps(newOps))
                    if wakeupPending.compareAndSet(false, true) then
                        discard(selector.wakeup())
                end if
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerServerInterest channel=${channel.hashCode()} ops=${opsToString(ops)} newOps=${opsToString(newOps)}"
                )
                true
            else
                false
            end if
        catch
            case _: CancelledKeyException => false

    /** True while `channel` is still in the deferred-registration queue: its `registerChannel` hit the cancelled-key race and the poll carrier
      * has not yet completed the registration. In this window the channel has no `SelectionKey`, so an `awaitX` arming interest cannot touch a
      * key; the interest is held in the pending-op map and applied by `drainPendingRegistrations` when it reconstructs interest at registration.
      */
    private def isPendingRegistration(channel: java.nio.channels.SelectableChannel): Boolean =
        val iter  = pendingRegistrations.iterator()
        var found = false
        while !found && iter.hasNext do
            if iter.next().channel eq channel then found = true
        found
    end isPendingRegistration

    private def registerInterest(channel: SocketChannel, ops: Int)(using AllowUnsafe): Boolean =
        try
            val key = channel.keyFor(selector)
            if (key ne null) && key.isValid then
                val current = key.interestOps()
                val newOps  = current | ops
                if newOps != current then
                    discard(key.interestOps(newOps))
                    if wakeupPending.compareAndSet(false, true) then
                        discard(selector.wakeup())
                end if
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerInterest channel=${channel.hashCode()} ops=${opsToString(ops)} newOps=${opsToString(newOps)}"
                )
                true
            else if isPendingRegistration(channel) then
                // Deferred registration in flight: the channel has no key yet. The interest is already in the pending-op map (the calling awaitX
                // put it there before this call), and drainPendingRegistrations reconstructs interest from those maps when it registers the
                // channel on the poll carrier, so report success here. Wake the poll carrier so it drains promptly. Mirrors the rebuildSelector
                // interest-reconstruction invariant: the pending-op maps are the source of truth for what is armed.
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerInterest deferred channel=${channel.hashCode()} ops=${opsToString(ops)}"
                )
                true
            else
                false
            end if
        catch
            case _: CancelledKeyException => false

    /** Re-assert each pending op's interest bit on its channel's key, deriving from the pending-op maps (the source of truth, the same maps
      * rebuildSelector reconstructs from). Add-only: a fired op clears its own bit in dispatchReadyKeys, so this only restores a bit that a
      * cross-carrier interestOps race or a coalesced/lost wakeup dropped while the op is still pending. Poll-carrier-only (called from pollOnce).
      */
    private def reassertPendingInterest()(using AllowUnsafe): Unit =
        reassertOp(pendingReads.keySet().iterator(), SelectionKey.OP_READ)
        reassertOp(pendingWritables.keySet().iterator(), SelectionKey.OP_WRITE)
        reassertOp(pendingConnects.keySet().iterator(), SelectionKey.OP_CONNECT)
    end reassertPendingInterest

    private def reassertOp(it: java.util.Iterator[SocketChannel], op: Int)(using AllowUnsafe): Unit =
        while it.hasNext do
            val ch  = it.next()
            val key = ch.keyFor(selector)
            if (key ne null) && key.isValid then
                try
                    val cur = key.interestOps()
                    if (cur & op) == 0 then discard(key.interestOps(cur | op))
                catch case _: CancelledKeyException => ()
            end if
        end while
    end reassertOp

    private def pollOnce()(using AllowUnsafe): Boolean =
        try
            val t0 = java.lang.System.nanoTime()
            // Bounded select (not blocking-forever): the loop wakes at least every SelectTimeoutMs to re-assert armed interest from the pending-op
            // maps, so a lost wakeup or a cross-carrier interest-clear cannot strand an armed read/write indefinitely. A bounded park, not a spin.
            val n = selector.select(NioIoDriver.SelectTimeoutMs)
            // Post-select re-check: clear the pending flag now that select() has returned.
            discard(wakeupPending.compareAndSet(true, false))
            // Drain deferred registrations now that select() has flushed the selector's cancelled-key set: any
            // channel whose register() raced a lingering cancelled key (the STARTTLS upgrade re-registration) can
            // now be registered cleanly. Done before key dispatch so a freshly registered channel can have its
            // interest armed (awaitX) and reported on the next cycle.
            drainPendingRegistrations()
            // Re-assert armed interest from the pending-op maps (the source of truth): restores any OP_READ/OP_WRITE/OP_CONNECT bit dropped by a
            // cross-carrier interestOps race (a dispatch-clear racing an arm) or a coalesced/lost wakeup. This is what makes the bounded select
            // liveness-preserving: an armed op whose interest bit was lost is re-armed within one cycle instead of stranding the connection.
            reassertPendingInterest()
            if n > 0 then
                zeroKeyReturns = 0
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label select returned $n ready keys")
                dispatchReadyKeys()
            else
                // Distinguish a genuine selector spin (immediate zero-key return) from a normal idle bounded-timeout return: only an immediate
                // zero-return counts toward the spin-rebuild heuristic; an idle timeout (~SelectTimeoutMs elapsed) is expected and resets it.
                val elapsedMs = (java.lang.System.nanoTime() - t0) / 1000000L
                if elapsedMs < NioIoDriver.SelectTimeoutMs / 2 then
                    zeroKeyReturns += 1
                    if shouldRebuild(zeroKeyReturns) then
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label selector spin detected ($zeroKeyReturns zero-key returns), rebuilding selector")
                        rebuildSelector()
                    end if
                else
                    zeroKeyReturns = 0
                end if
            end if
            true
        catch
            case _: java.nio.channels.ClosedSelectorException => false

    /** Drain the deferred-registration queue on the poll carrier. Each enqueued handle hit the cancelled-key race in `registerChannel`; the
      * `select()` that just returned flushed the selector's cancelled-key set, so its `channel.register` can now succeed, registered with the
      * interest reconstructed from the pending-op maps. A registration that STILL throws `CancelledKeyException` (the key has not been flushed
      * yet, e.g. its cancel landed after this `select()` began) leaves the handle at the queue head and stops the drain for this cycle, arming a
      * wakeup so the next `select()` flushes the key and the retry happens promptly rather than blocking until an unrelated event. A closed
      * channel / selector is dropped (the upgrade or driver is gone; the caller already observed success, and the downstream `awaitX` fails the
      * parked promise with `Closed`). Called only from the poll carrier (single-carrier-confined consumer).
      */
    private def drainPendingRegistrations()(using AllowUnsafe): Unit =
        // The poll carrier is the only consumer, so peek-then-poll is a safe atomic dequeue here: a handle is removed from the queue ONLY after
        // its register() has created the SelectionKey (or it is being dropped). This ordering closes the race with a concurrent registerInterest on
        // a caller carrier: once a channel is absent from the queue, its key already exists, so registerInterest never sees the (no-key AND
        // not-pending) gap that would fail the awaitX. A still-cancelled key (register throws CancelledKeyException again) stops the drain with the
        // handle left at the head, retried on the next select() cycle (which re-flushes the cancelled-key set); a wakeup is armed so that cycle
        // comes promptly. The loop is bounded by the snapshot size so it never spins on a re-enqueued or un-flushable head within one cycle.
        var remaining  = pendingRegistrations.size()
        var keyBlocked = false
        while remaining > 0 && !keyBlocked do
            remaining -= 1
            val handle = pendingRegistrations.peek()
            if handle ne null then
                try
                    // Reconstruct armed interest from the pending-op maps, exactly as rebuildSelector does: an awaitX issued during the deferred
                    // window recorded its interest in the map (registerInterest returned the deferred-success path), so registering with interest 0
                    // here would drop it and the operation's promise would never complete. Connect is the upgrade path's relevant op; read/write
                    // are included for completeness and to mirror the rebuild invariant.
                    val sc  = handle.channel
                    var ops = 0
                    if pendingReads.containsKey(sc) then ops = ops | SelectionKey.OP_READ
                    if pendingWritables.containsKey(sc) then ops = ops | SelectionKey.OP_WRITE
                    if pendingConnects.containsKey(sc) then ops = ops | SelectionKey.OP_CONNECT
                    discard(sc.register(selector, ops))
                    // Registration succeeded (the key now exists): only now remove the handle from the queue.
                    discard(pendingRegistrations.poll())
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label registerChannel (deferred) ${handleLabel(handle)} ops=${opsToString(ops)}")
                catch
                    case _: java.nio.channels.CancelledKeyException =>
                        // Key not flushed yet: leave the handle at the queue head and stop draining this cycle. Wake the poll loop so the next
                        // select() flushes the cancelled-key set and the retry happens promptly rather than blocking until an unrelated event.
                        keyBlocked = true
                        if wakeupPending.compareAndSet(false, true) then
                            discard(selector.wakeup())
                    case _: java.nio.channels.ClosedChannelException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped closed channel ${handleLabel(handle)}")
                    case _: java.nio.channels.ClosedSelectorException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped on closed selector ${handleLabel(handle)}")
                    case _: java.nio.channels.IllegalBlockingModeException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped blocking-mode channel ${handleLabel(handle)}")
                end try
            end if
        end while
    end drainPendingRegistrations

    private def dispatchReadyKeys()(using AllowUnsafe): Unit =
        installedKeySet match
            case Present(flatSet) =>
                val arr   = flatSet.filledKeys
                val total = flatSet.size()
                var i     = 0
                while i < total do
                    val key = arr(i)
                    i += 1
                    if key ne null then
                        try
                            if key.isValid then
                                val ready = key.readyOps()
                                if (ready & SelectionKey.OP_ACCEPT) != 0 then
                                    discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_ACCEPT))
                                    dispatchAccept(key.channel().asInstanceOf[ServerSocketChannel])
                                else
                                    val channel = key.channel().asInstanceOf[SocketChannel]
                                    if (ready & SelectionKey.OP_CONNECT) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT))
                                        dispatchConnect(channel)
                                    end if
                                    if (ready & SelectionKey.OP_READ) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
                                        dispatchRead(channel)
                                    end if
                                    if (ready & SelectionKey.OP_WRITE) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE))
                                        dispatchWritable(channel)
                                    end if
                                end if
                            end if
                        catch
                            case _: CancelledKeyException => ()
                        end try
                    end if
                end while
                flatSet.reset()
            case Absent =>
                val keys = selector.selectedKeys()
                val iter = keys.iterator()
                dispatchKeysLoop(iter)
    end dispatchReadyKeys

    @tailrec
    private def dispatchKeysLoop(iter: java.util.Iterator[SelectionKey])(using AllowUnsafe): Unit =
        if iter.hasNext then
            val key = iter.next()
            iter.remove()
            try
                if key.isValid then
                    val ready = key.readyOps()
                    if (ready & SelectionKey.OP_ACCEPT) != 0 then
                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_ACCEPT))
                        dispatchAccept(key.channel().asInstanceOf[ServerSocketChannel])
                    else
                        val channel = key.channel().asInstanceOf[SocketChannel]
                        if (ready & SelectionKey.OP_CONNECT) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT))
                            dispatchConnect(channel)
                        end if
                        if (ready & SelectionKey.OP_READ) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
                            dispatchRead(channel)
                        end if
                        if (ready & SelectionKey.OP_WRITE) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE))
                            dispatchWritable(channel)
                        end if
                    end if
                end if
            catch
                case _: CancelledKeyException => ()
            end try
            dispatchKeysLoop(iter)
        end if
    end dispatchKeysLoop

    /** Pure predicate: true when the accumulated consecutive zero-key returns exceed the rebuild threshold.
      *
      * `private[net]` so tests in `kyo.net.internal` can call it directly with concrete inputs.
      */
    private[net] def shouldRebuild(consecutiveZeroReturns: Int): Boolean =
        consecutiveZeroReturns >= NioIoDriver.SelectorRebuildThreshold

    /** Test-observability seam: the interest ops currently registered for `channel` on the live selector, or -1
      * when `channel` has no valid key. After a selector rebuild this reflects the new selector, so a test can
      * assert that an in-flight operation's armed interest survived the rebuild.
      */
    private[net] def interestOpsFor(channel: java.nio.channels.SelectableChannel)(using AllowUnsafe): Int =
        val key = channel.keyFor(selector)
        if (key ne null) && key.isValid then key.interestOps() else -1

    /** Rebuild the selector by snapshotting registered channels, opening a fresh selector, closing the old one,
      * and re-registering every channel with its in-flight interest reconstructed from the pending-op maps.
      * Resets the zero-key counter.
      *
      * The fresh selector is opened BEFORE the old one is closed: if `Selector.open()` fails (for example on
      * file-descriptor exhaustion) the old selector stays live and the rebuild is skipped this cycle, rather
      * than tearing down the only selector the driver has and stranding every channel.
      *
      * Interest is reconstructed from `pendingReads` / `pendingWritables` / `pendingConnects` / `pendingAccepts`,
      * the source of truth for what is armed: a channel re-registered with interest 0 would never report
      * readiness for an operation already pending at rebuild time, so that operation's promise would hang forever.
      *
      * `private[net]` so the rebuild path is directly exercisable by tests in `kyo.net.internal`. Called only
      * from the select-loop carrier, or before that loop starts (single-carrier-confined either way).
      */
    private[net] def rebuildSelector()(using AllowUnsafe, Frame): Unit =
        // Snapshot channel references BEFORE closing the old selector (closing cancels all keys,
        // after which key.channel() may still work but the key set is no longer reliable).
        val channels = selector.keys().iterator().asScala
            .flatMap { k =>
                val ch = k.channel()
                if ch.isOpen then Some(ch) else None
            }
            .toArray

        val opened: Maybe[Selector] =
            try Present(Selector.open())
            catch case _: IOException => Absent

        opened match
            case Absent =>
                // Open failed: keep the current selector and retry at the next threshold rather than
                // leaving the driver with no selector at all.
                zeroKeyReturns = 0
                Log.live.unsafe.warn(s"$label selector rebuild skipped: Selector.open() failed, keeping the current selector")
            case Present(newSelector) =>
                try selector.close()
                catch case _: IOException => ()

                // Re-install the flat key set on the new selector if the original install succeeded.
                installedKeySet match
                    case Present(flatSet) => installFlatSetInto(newSelector, flatSet)
                    case Absent           => ()

                selector = newSelector
                zeroKeyReturns = 0

                // Re-register every channel on the new selector, restoring its armed interest from the
                // pending-op maps so an in-flight read, write, connect, or accept survives the rebuild.
                var i = 0
                while i < channels.length do
                    val ch = channels(i)
                    i += 1
                    try
                        ch match
                            case sc: SocketChannel =>
                                var ops = 0
                                if pendingReads.containsKey(sc) then ops = ops | SelectionKey.OP_READ
                                if pendingWritables.containsKey(sc) then ops = ops | SelectionKey.OP_WRITE
                                if pendingConnects.containsKey(sc) then ops = ops | SelectionKey.OP_CONNECT
                                discard(sc.register(newSelector, ops))
                            case ssc: ServerSocketChannel =>
                                val ops = if pendingAccepts.containsKey(ssc) then SelectionKey.OP_ACCEPT else 0
                                discard(ssc.register(newSelector, ops))
                            case _ => ()
                    catch
                        case _: java.nio.channels.ClosedChannelException  => ()
                        case _: java.nio.channels.ClosedSelectorException => ()
                    end try
                end while
        end match
    end rebuildSelector

    /** Install `flatSet` into the `SelectorImpl.selectedKeys` and `SelectorImpl.publicSelectedKeys` fields of `sel`.
      * Used both at initial construction and when rebuilding the selector.
      */
    private def installFlatSetInto(sel: Selector, flatSet: SelectedSelectionKeySet)(using AllowUnsafe): Unit =
        try
            val implClass = Class.forName("sun.nio.ch.SelectorImpl")
            val skField   = implClass.getDeclaredField("selectedKeys")
            skField.setAccessible(true)
            val pskField = implClass.getDeclaredField("publicSelectedKeys")
            pskField.setAccessible(true)
            skField.set(sel, flatSet)
            pskField.set(sel, flatSet)
        catch
            // InaccessibleObjectException (JDK 9+) extends RuntimeException, not ReflectiveOperationException.
            case _: ReflectiveOperationException                  => () // graceful: no-op if reflection fails on the new selector
            case _: java.lang.reflect.InaccessibleObjectException => ()

    /** Try to install a flat `SelectedSelectionKeySet` into `selector` via reflection.
      *
      * Returns `Present(set)` when the reflection succeeds; returns `Absent` when an `InaccessibleObjectException` or any other reflection
      * failure is thrown. Callers must treat `Absent` as a graceful fallback to the default `HashSet`-backed path.
      *
      * `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` is required for the reflection to succeed on JDK 17+. The build's test JVM options
      * include that flag; production JVMs may not, so the `Absent` path must remain fully functional.
      */
    private def installSelectedKeySet(sel: Selector)(using AllowUnsafe): Maybe[SelectedSelectionKeySet] =
        try
            val implClass = Class.forName("sun.nio.ch.SelectorImpl")
            val skField   = implClass.getDeclaredField("selectedKeys")
            skField.setAccessible(true)
            val pskField = implClass.getDeclaredField("publicSelectedKeys")
            pskField.setAccessible(true)
            val flatSet = new SelectedSelectionKeySet()
            skField.set(sel, flatSet)
            pskField.set(sel, flatSet)
            Present(flatSet)
        catch
            // InaccessibleObjectException (JDK 9+) extends RuntimeException, not ReflectiveOperationException.
            case _: ReflectiveOperationException                  => Absent
            case _: java.lang.reflect.InaccessibleObjectException => Absent

    private def dispatchRead(channel: SocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingReads.remove(channel)) match
            case Present(handle) =>
                // Read the current owner cell. The cell object IS the identity for the CAS in
                // dispatchReadPlain/dispatchReadTls: AtomicReference.compareAndSet uses reference equality,
                // so only the carrier that read THIS exact ReadArmCell object can CAS-clear it. A stale
                // dispatch from an earlier arm holds a different ReadArmCell instance and its CAS fails.
                // A concurrent cancel/close calls getAndSet(Absent), which races with the CAS; only one
                // side wins (the other's CAS fails or finds Absent and skips the completion).
                val cell = handle.readArm.get()
                cell match
                    case Present(armCell) =>
                        handle.tls match
                            case Present(tls) => dispatchReadTls(channel, cell, armCell.promise, handle, tls)
                            case Absent       => dispatchReadPlain(channel, cell, armCell.promise, handle)
                    case Absent =>
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label dispatchRead ${handleLabel(handle)} readArm already cleared (cancelled)")
                end match
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchRead for channel=${channel.hashCode()} with no pending promise")
        end match
    end dispatchRead

    private def dispatchReadPlain(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle
    )(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        try
            // Reuse handle's buffer
            val buf = handle.readBuffer
            buf.clear()
            val n = channel.read(buf)
            if n > 0 then
                buf.flip()
                val arr = new Array[Byte](n)
                buf.get(arr)
                Log.live.unsafe.debug(s"$label dispatchRead ${handleLabel(handle)} bytes=$n")
                // CAS-clear the owner cell before completing. Pass the SAME cell object read in dispatchRead
                // so AtomicReference.compareAndSet's reference check succeeds.
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
            else if n < 0 then
                // Orderly peer EOF (recv == 0 with no local shutdown).
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
            else
                // n == 0: no data ready (selector spurious wakeup). Re-arm: restore the entry and interest.
                pendingReads.put(channel, handle)
                discard(registerInterest(channel, SelectionKey.OP_READ))
            end if
        catch
            case _: IOException =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        end try
    end dispatchReadPlain

    private def dispatchReadTls(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle,
        tls: NioTlsState
    )(using AllowUnsafe): Unit =
        // All compareAndSet calls pass the SAME cell object read in dispatchRead: AtomicReference.compareAndSet
        // uses reference equality (eq), so the exact ReadArmCell reference is the CAS key.
        // Acquire per-connection engine ownership before any SSLEngine call so the selector-carrier
        // unwrap path (here) and the caller-carrier wrap path (writeTls) never touch the SSLEngine
        // concurrently. If the write path holds the gate, re-arm for the next selector wakeup rather
        // than spinning on the selector carrier (which would block other connections' read events).
        if !handle.engineGate.compareAndSet(false, true) then
            pendingReads.put(channel, handle)
            discard(registerInterest(channel, SelectionKey.OP_READ))
        else
            // Collect the completion thunk; the gate is released in finally BEFORE the thunk runs so
            // synchronous teardown callbacks (ReadPump.onComplete -> closeHandle -> NioHandle.close ->
            // spinAcquire) never try to re-acquire the gate while the selector carrier still holds it.
            var complete: () => Unit = () => ()
            try
                // Check for buffered data from a previous read (e.g. post-handshake leftover)
                tryUnwrapBuffered(tls) match
                    case Present(buffered) =>
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label dispatchReadTls ${handleLabel(handle)} buffered plaintext=${buffered.size}")
                        if handle.readArm.compareAndSet(cell, Absent) then
                            complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(buffered)))
                    case Absent if tls.peerCleanClose =>
                        // The buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose so the
                        // ReadPump tears down, rather than re-arming for ciphertext the peer will never send.
                        if handle.readArm.compareAndSet(cell, Absent) then
                            complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                    case Absent =>
                        val buf = handle.readBuffer
                        buf.clear()
                        val n = channel.read(buf)
                        if n < 0 then
                            // Peer ended the TCP stream. If a close_notify was already consumed (peerCleanClose set by tryUnwrapBuffered) this FIN
                            // follows an orderly close; otherwise it is a bare FIN with no close_notify, the truncation-attack condition (RFC 8446
                            // 6.1). Record peerEof for the bare-FIN case so closeReason reports Truncated; do not overwrite an already-observed clean
                            // close. Mirrors the engine path's recv == 0 -> peerEof handling (PollerIoDriver / IoUringDriver).
                            if !tls.peerCleanClose then tls.peerEof = true
                            if handle.readArm.compareAndSet(cell, Absent) then
                                complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                        else if n == 0 then
                            // Spurious wakeup: no data ready. Restore entry and re-arm.
                            pendingReads.put(channel, handle)
                            discard(registerInterest(channel, SelectionKey.OP_READ))
                        else
                            buf.flip()
                            // Grow netInBuf if needed to hold existing data + new data
                            val needed = tls.netInBuf.position() + buf.remaining()
                            if needed > tls.netInBuf.capacity() then
                                val grown = ByteBuffer.allocate(needed)
                                tls.netInBuf.flip()
                                grown.put(tls.netInBuf)
                                tls.netInBuf = grown
                            end if
                            tls.netInBuf.put(buf)
                            // Try to unwrap the newly fed ciphertext
                            tryUnwrapBuffered(tls) match
                                case Present(plaintext) =>
                                    given Frame = Frame.internal
                                    Log.live.unsafe.debug(s"$label dispatchReadTls ${handleLabel(handle)} plaintext=${plaintext.size}")
                                    if handle.readArm.compareAndSet(cell, Absent) then
                                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(plaintext)))
                                case Absent if tls.peerCleanClose =>
                                    // The newly fed ciphertext was the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose
                                    // immediately so the ReadPump tears down, rather than re-arming for ciphertext the peer will never send.
                                    if handle.readArm.compareAndSet(cell, Absent) then
                                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                                case Absent =>
                                    // Got ciphertext but no complete TLS record yet: need more data, re-arm.
                                    pendingReads.put(channel, handle)
                                    discard(registerInterest(channel, SelectionKey.OP_READ))
                            end match
                        end if
                end match
            catch
                case _: IOException =>
                    // A read IOException (e.g. a TCP RST) ends the inbound stream abruptly with no close_notify: a truncation, not an orderly close.
                    // Record peerEof unless a close_notify was already consumed, so closeReason reports Truncated rather than Active.
                    if !tls.peerCleanClose then tls.peerEof = true
                    if handle.readArm.compareAndSet(cell, Absent) then
                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
            finally
                handle.engineGate.set(false)
            end try
            // Run the completion thunk after the gate is released. Synchronous callbacks on the promise
            // (e.g. ReadPump.onComplete -> teardownHandle -> NioHandle.close -> spinAcquire) are now
            // safe: the gate is free when they run on the selector carrier.
            complete()
        end if
    end dispatchReadTls

    private def dispatchWritable(channel: SocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingWritables.remove(channel)) match
            case Present(promise) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchWritable channel=${channel.hashCode()}")
                promise.completeDiscard(Result.succeed(()))
            case Absent =>
                () // No pending promise - may have been completed by race condition fix
        end match
    end dispatchWritable

    private def dispatchConnect(channel: SocketChannel)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        Maybe(pendingConnects.remove(channel)) match
            case Present(promise) =>
                try
                    if channel.finishConnect() then
                        Log.live.unsafe.debug(s"$label dispatchConnect channel=${channel.hashCode()} connected")
                        promise.completeDiscard(Result.succeed(()))
                    else
                        // Not ready: re-register
                        pendingConnects.put(channel, promise)
                        discard(registerInterest(channel, SelectionKey.OP_CONNECT))
                catch
                    case e: IOException =>
                        promise.completeDiscard(Result.fail(Closed(
                            label,
                            summon[Frame],
                            s"finishConnect failed for channel=${channel.hashCode()}: ${e.getMessage}"
                        )))
            case Absent =>
                Log.live.unsafe.debug(s"$label dispatchConnect channel=${channel.hashCode()} no pending promise")
        end match
    end dispatchConnect

    private def dispatchAccept(serverChannel: ServerSocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingAccepts.remove(serverChannel)) match
            case Present(promise) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchAccept server=${serverChannel.hashCode()}")
                promise.completeDiscard(Result.succeed(()))
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchAccept for serverChannel=${serverChannel.hashCode()} with no pending promise")
        end match
    end dispatchAccept

    /** Try to unwrap any buffered ciphertext already sitting in tls.netInBuf.
      *
      * After a TLS handshake, the last socket read may have contained both the final handshake record AND application data. The application
      * data remains in netInBuf. If we only wait for the selector, the kernel buffer is empty (data already read), so the selector never
      * fires and the connection hangs.
      *
      * Returns Present(plaintext) if any application data was unwrapped, Absent otherwise.
      */
    private[kyo] def tryUnwrapBuffered(tls: NioTlsState)(using AllowUnsafe): Maybe[Span[Byte]] =
        tls.netInBuf.flip()
        if !tls.netInBuf.hasRemaining then
            tls.netInBuf.compact()
            Absent
        else
            val plaintext = tls.decryptAcc
            plaintext.reset()
            @tailrec def unwrapLoop(): Unit =
                tls.appInBuf.clear()
                val result = tls.engine.unwrap(tls.netInBuf, tls.appInBuf)
                val status = result.getStatus
                if status eq SSLEngineResult.Status.OK then
                    tls.appInBuf.flip()
                    val n = tls.appInBuf.remaining()
                    if n > 0 then
                        // Bulk get: ensureCapacityFor before reading array (growth may reallocate arr), then direct ByteBuffer.get.
                        plaintext.ensureCapacityFor(n)
                        tls.appInBuf.get(plaintext.array, plaintext.size, n)
                        plaintext.advance(n)
                    end if
                    unwrapLoop()
                else if status eq SSLEngineResult.Status.CLOSED then
                    // The unwrap that consumed the peer's close_notify record reports CLOSED and makes the inbound side done (RFC 8446 6.1
                    // orderly close). Record it so the connection's closeReason reports CleanClose rather than Truncated: this is the orderly
                    // counterpart to a bare TCP FIN. Mirrors JdkSslEngine.readPlain's Status.CLOSED / isInboundDone -> -3 clean-close return,
                    // converging the inline NIO path with the engine-driver path. The loop stops here: a close_notify is the last record on the
                    // inbound stream, so there is nothing further to drain.
                    tls.peerCleanClose = true
                end if
            end unwrapLoop
            unwrapLoop()
            // Belt-and-suspenders: a close_notify could be consumed by an unwrap that also surfaced as a non-CLOSED status (e.g. delivered
            // coalesced behind the last app record). isInboundDone becomes true once the peer's close_notify has been processed, so checking it
            // after the loop catches the clean close in every position. Mirrors JdkSslEngine.readPlain's isInboundDone clean-close detection.
            if tls.engine.isInboundDone then tls.peerCleanClose = true
            tls.netInBuf.compact()
            if plaintext.size > 0 then
                Present(Span.fromUnsafe(plaintext.toByteArray))
            else
                Absent
            end if
        end if
    end tryUnwrapBuffered

end NioIoDriver

private[kyo] object NioIoDriver:

    /** Number of consecutive zero-key `select()` returns that trigger a selector rebuild. */
    private[net] val SelectorRebuildThreshold: Int = 512

    /** Bounded `select(timeout)` budget for the poll loop (milliseconds). The loop wakes at least this often to re-assert armed interest from the
      * pending-op maps, so a lost wakeup or a cross-carrier interest-clear can never strand an armed read/write indefinitely (the liveness the
      * readiness pollers get from their bounded `poll(..., 100, ...)`). A bounded park, not a busy-spin: it blocks in `select` and returns early on
      * any event.
      */
    private[net] val SelectTimeoutMs: Long = 100L

    /** Factory for `NioIoDriver`. Opens a fresh `Selector` for each driver instance. */
    def init()(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(Selector.open())
end NioIoDriver
