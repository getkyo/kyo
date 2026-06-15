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
    // Pending read requests: channel -> handle (promise stored on handle.pendingReadPromise)
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
        // Unsafe: Fiber.Unsafe.init spawns the NIO event-loop carrier without re-entering the effect system.
        // The @tailrec poll loop is plain Scala (closedFlag.get() and pollOnce() are both synchronous);
        // no < Async computation is inside the thunk, so Fiber.Unsafe.init is correct here.
        val fiber = Fiber.Unsafe.init {
            @tailrec def loop(): Unit =
                if !closedFlag.get() && pollOnce() then loop()
            loop()
        }

        fiber.onComplete { result =>
            result match
                case Result.Success(_) =>
                    if closedFlag.get() then
                        Log.live.unsafe.info(s"$label event loop exited cleanly")
                    else
                        Log.live.unsafe.warn(s"$label event loop exited unexpectedly")
                case Result.Failure(e) =>
                    Log.live.unsafe.error(s"$label event loop failed: $e")
                case Result.Panic(t) =>
                    if !closedFlag.get() then
                        Log.live.unsafe.error(s"$label event loop crashed", t)
        }

        fiber
    end start

    def awaitRead(handle: NioHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Check for buffered TLS data before registering with selector.
        // After handshake, netInBuf may already contain application data.
        handle.tls match
            case Present(tls) =>
                tryUnwrapBuffered(tls) match
                    case Present(buffered) =>
                        Log.live.unsafe.debug(s"$label awaitRead ${handleLabel(handle)} found buffered TLS data size=${buffered.size}")
                        promise.completeDiscard(Result.succeed(buffered))
                    case Absent if tls.peerCleanClose =>
                        // Buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver EOF (empty Span) so the
                        // ReadPump tears down instead of waiting on the selector for ciphertext the peer will never send. closeReason then
                        // reports CleanClose. Mirrors the engine path's clean-close EOF delivery.
                        promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                    case Absent =>
                        // Buffer is in handle, no allocation here
                        handle.pendingReadPromise = promise
                        pendingReads.put(handle.channel, handle)
                        Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
                        if !registerInterest(handle.channel, SelectionKey.OP_READ) then
                            discard(pendingReads.remove(handle.channel))
                            promise.completeDiscard(Result.fail(Closed(
                                label,
                                summon[Frame],
                                s"registerRead failed for ${handleLabel(handle)}"
                            )))
                        end if
            case Absent =>
                // Buffer is in handle, no allocation here
                handle.pendingReadPromise = promise
                pendingReads.put(handle.channel, handle)
                Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
                if !registerInterest(handle.channel, SelectionKey.OP_READ) then
                    discard(pendingReads.remove(handle.channel))
                    promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerRead failed for ${handleLabel(handle)}")))
                end if
        end match
    end awaitRead

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

    /** Remove pending operations for a channel and fail their promises with Closed. */
    private def cleanupPending(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label cleanupPending ${handleLabel(handle)} reads=${
                if pendingReads.containsKey(handle.channel) then 1 else 0
            } writes=${if pendingWritables.containsKey(handle.channel) then 1 else 0} connects=${
                if pendingConnects.containsKey(handle.channel) then 1 else 0
            }")
        val closed = Closed(label, summon[Frame], s"${handleLabel(handle)} closed")
        Maybe(pendingReads.remove(handle.channel)).foreach { h =>
            h.pendingReadPromise.completeDiscard(Result.fail(closed))
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

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            Log.live.unsafe.debug(
                s"$label closing driver, failing ${pendingReads.size()} reads, ${pendingWritables.size()} writes, ${pendingConnects.size()} connects, ${pendingAccepts.size()} accepts"
            )
            val closed = Closed(label, summon[Frame], "driver closed")
            pendingReads.forEach { (_, h) =>
                h.pendingReadPromise.completeDiscard(Result.fail(closed))
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
                // A cancelled key for this channel is still pending in the selector's cancelled-key
                // set. Calling `selectNow()` from a non-polling thread to flush it would deadlock
                // against the polling thread, which holds the SelectorImpl monitor for the entire
                // duration of `KQueue.poll(...)` (a native call). Java's intrinsic locks aren't fair,
                // so even after `wakeup()`, the polling thread can re-acquire the monitor before the
                // registering thread, leaving the latter blocked indefinitely.
                //
                // Instead, kick the polling thread with `wakeup()` and retry `register()`. The
                // polling thread flushes the cancelled-key set at the start of each `doSelect`
                // iteration, so within one or two cycles the conflicting key is gone and the retry
                // succeeds. Triggers via Postgres SSLRequest upgrade (`NioTransport.upgradeToTls`
                // re-registers the same channel after the plaintext pump tears it down).
                @scala.annotation.tailrec
                def retry(attemptsLeft: Int): Boolean =
                    if attemptsLeft <= 0 then false
                    else
                        if wakeupPending.compareAndSet(false, true) then
                            discard(selector.wakeup())
                        // Unsafe: sanctioned bounded driver-carrier wait (1ms). Backs off the cancelled-key retry
                        // so the polling thread can flush the selector's cancelled-key set before the next
                        // register() attempt. This is a JVM-NIO driver-internal backoff against a selector
                        // cancelled-key race, NOT an orchestration park and NOT one of the forbidden constructs
                        // (.safe.get / .block / synchronized / Thread.sleep / CountDownLatch).
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L) // 1ms
                        try
                            discard(handle.channel.register(selector, 0))
                            given Frame = Frame.internal
                            Log.live.unsafe.debug(s"$label registerChannel (after retry) ${handleLabel(handle)}")
                            true
                        catch
                            case _: java.nio.channels.CancelledKeyException        => retry(attemptsLeft - 1)
                            case _: java.nio.channels.ClosedChannelException       => false
                            case _: java.nio.channels.ClosedSelectorException      => false
                            case _: java.nio.channels.IllegalBlockingModeException => false
                        end try
                retry(100)
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
            else
                false
            end if
        catch
            case _: CancelledKeyException => false

    private def pollOnce()(using AllowUnsafe): Boolean =
        try
            val n = selector.select()
            // Post-select re-check: clear the pending flag now that select() has returned. Any interest
            // registration that set the flag while select() was returning is already reflected in the
            // ready-key set this cycle; the next select() will pick up any newly added interest.
            discard(wakeupPending.compareAndSet(true, false))
            if n > 0 then
                zeroKeyReturns = 0
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label select returned $n ready keys")
                dispatchReadyKeys()
            else
                zeroKeyReturns += 1
                if shouldRebuild(zeroKeyReturns) then
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label selector spin detected ($zeroKeyReturns zero-key returns), rebuilding selector")
                    rebuildSelector()
                end if
            end if
            true
        catch
            case _: java.nio.channels.ClosedSelectorException => false

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
                val promise = handle.pendingReadPromise
                handle.tls match
                    case Present(tls) => dispatchReadTls(channel, promise, handle, tls)
                    case Absent       => dispatchReadPlain(channel, promise, handle)
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchRead for channel=${channel.hashCode()} with no pending promise")
        end match
    end dispatchRead

    private def dispatchReadPlain(
        channel: SocketChannel,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: NioHandle
    )(using AllowUnsafe): Unit =
        try
            // Reuse handle's buffer
            val buf = handle.readBuffer
            buf.clear()
            val n = channel.read(buf)
            if n > 0 then
                buf.flip()
                val arr = new Array[Byte](n)
                buf.get(arr)
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchRead ${handleLabel(handle)} bytes=$n")
                promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
            else if n < 0 then
                // EOF
                promise.completeDiscard(Result.succeed(Span.empty[Byte]))
            else
                // n == 0: no data (shouldn't happen after select), treat as retry needed
                // Re-register for read
                handle.pendingReadPromise = promise
                pendingReads.put(channel, handle)
                discard(registerInterest(channel, SelectionKey.OP_READ))
            end if
        catch
            case _: IOException =>
                promise.completeDiscard(Result.succeed(Span.empty[Byte]))
    end dispatchReadPlain

    private def dispatchReadTls(
        channel: SocketChannel,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: NioHandle,
        tls: NioTlsState
    )(using AllowUnsafe): Unit =
        try
            // Check for buffered data from a previous read (e.g. post-handshake leftover)
            tryUnwrapBuffered(tls) match
                case Present(buffered) =>
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label dispatchReadTls ${handleLabel(handle)} buffered plaintext=${buffered.size}")
                    promise.completeDiscard(Result.succeed(buffered))
                case Absent if tls.peerCleanClose =>
                    // The buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver EOF (empty Span) so the
                    // ReadPump tears down, rather than re-arming for ciphertext the peer will never send. closeReason then reports CleanClose, not
                    // Truncated. Mirrors PollerIoDriver / IoUringDriver's clean-close EOF delivery on the engine path.
                    promise.completeDiscard(Result.succeed(Span.empty[Byte]))
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
                        promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                    else if n == 0 then
                        handle.pendingReadPromise = promise
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
                                promise.completeDiscard(Result.succeed(plaintext))
                            case Absent if tls.peerCleanClose =>
                                // The newly fed ciphertext was the peer's close_notify (orderly close, RFC 8446 6.1): deliver EOF (empty Span)
                                // immediately so the ReadPump tears down, rather than re-arming for ciphertext the peer will never send.
                                // closeReason then reports CleanClose. Mirrors the engine path's clean-close EOF delivery.
                                promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                            case Absent =>
                                // Got ciphertext but no complete TLS record yet: need more data
                                handle.pendingReadPromise = promise
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
                promise.completeDiscard(Result.succeed(Span.empty[Byte]))
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

    /** Factory for `NioIoDriver`. Opens a fresh `Selector` for each driver instance. */
    def init()(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(Selector.open())
end NioIoDriver
