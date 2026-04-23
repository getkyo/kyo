package kyo.internal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.internal.transport.*
import kyo.internal.util.*
import scala.annotation.tailrec

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
  * Note: `tryUnwrapBuffered` is called at the start of every TLS read to drain any application data that OpenSSL already unwrapped (e.g.
  * from the last handshake record or a coalesced TCP segment). Without this, the selector may never fire again because the kernel buffer is
  * empty even though decrypted bytes are available.
  */
final private[kyo] class NioIoDriver private (private val selector: Selector)
    extends IoDriver[NioHandle]:

    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Pending read requests: channel -> handle (promise stored on handle.pendingReadPromise)
    private val pendingReads =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, NioHandle]()

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
        val fiber = Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped {
                Sync.Unsafe.defer {
                    @tailrec def loop(): Unit =
                        if !closedFlag.get() && pollOnce() then loop()
                    loop()
                }
            }
        }.unsafe

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

    def write(handle: NioHandle, data: Span[Byte])(using AllowUnsafe): WriteResult =
        if data.isEmpty then WriteResult.Done
        else
            handle.tls match
                case Present(tls) => writeTls(handle, data, tls)
                case Absent       => writePlain(handle, data)

    private def writePlain(handle: NioHandle, data: Span[Byte])(using AllowUnsafe): WriteResult =
        try
            val arr = data.toArrayUnsafe
            val buf = ByteBuffer.wrap(arr)
            val n   = handle.channel.write(buf)
            if n < 0 then
                WriteResult.Error
            else if n == arr.length then
                WriteResult.Done
            else
                WriteResult.Partial(Span.fromUnsafe(arr.drop(n)))
            end if
        catch
            case _: IOException => WriteResult.Error

    private def writeTls(handle: NioHandle, data: Span[Byte], tls: NioTlsState)(using AllowUnsafe): WriteResult =
        try
            // If there is pending ciphertext from a previous partial write, flush it first
            val canProceed =
                if tls.pendingCiphertext then
                    val n = handle.channel.write(tls.netOutBuf)
                    if n < 0 then Left(WriteResult.Error)
                    else if tls.netOutBuf.hasRemaining then
                        // Still can't flush — remain in pending state, return all data as remaining
                        Left(WriteResult.Partial(data))
                    else
                        tls.pendingCiphertext = false
                        Right(())
                    end if
                else Right(())
            canProceed match
                case Left(result) => result
                case Right(_) =>
                    val src = ByteBuffer.wrap(data.toArrayUnsafe)
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
                                // Try one write — if socket buffer is full, return Partial
                                val n = handle.channel.write(tls.netOutBuf)
                                if n < 0 then WriteResult.Error
                                else if tls.netOutBuf.hasRemaining then
                                    // Socket buffer full — save state and return remaining plaintext
                                    tls.pendingCiphertext = true
                                    val remaining = java.util.Arrays.copyOfRange(src.array(), src.position(), src.limit())
                                    WriteResult.Partial(Span.fromUnsafe(remaining))
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
                discard(selector.wakeup())
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
            discard(selector.wakeup())
            discard(handle.channel.register(selector, 0))
            given Frame = Frame.internal
            Log.live.unsafe.debug(s"$label registerChannel ${handleLabel(handle)}")
            true
        catch
            case _: java.nio.channels.ClosedChannelException       => false
            case _: java.nio.channels.ClosedSelectorException      => false
            case _: java.nio.channels.IllegalBlockingModeException => false

    /** Register a server channel for accept operations. */
    def registerServerChannel(serverChannel: ServerSocketChannel)(using AllowUnsafe): Boolean =
        try
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
                val newOps = key.interestOps() | ops
                discard(key.interestOps(newOps))
                discard(selector.wakeup())
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
                val newOps = key.interestOps() | ops
                discard(key.interestOps(newOps))
                discard(selector.wakeup())
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
            val n        = selector.select()
            val keyCount = selector.selectedKeys().size()
            if keyCount > 0 then
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label select returned $keyCount ready keys")
            dispatchReadyKeys()
            true
        catch
            case _: java.nio.channels.ClosedSelectorException => false

    private def dispatchReadyKeys()(using AllowUnsafe): Unit =
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
                case Absent =>
                    val buf = handle.readBuffer
                    buf.clear()
                    val n = channel.read(buf)
                    if n < 0 then
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
                            case Absent =>
                                // Got ciphertext but no complete TLS record yet — need more data
                                handle.pendingReadPromise = promise
                                pendingReads.put(channel, handle)
                                discard(registerInterest(channel, SelectionKey.OP_READ))
                        end match
                    end if
            end match
        catch
            case _: IOException =>
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
                        // Not ready — re-register
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
    private def tryUnwrapBuffered(tls: NioTlsState)(using AllowUnsafe): Maybe[Span[Byte]] =
        tls.netInBuf.flip()
        if !tls.netInBuf.hasRemaining then
            tls.netInBuf.compact()
            Absent
        else
            val plaintext = new GrowableByteBuffer
            @tailrec def unwrapLoop(): Unit =
                tls.appInBuf.clear()
                val result = tls.engine.unwrap(tls.netInBuf, tls.appInBuf)
                val status = result.getStatus
                if status eq SSLEngineResult.Status.OK then
                    tls.appInBuf.flip()
                    if tls.appInBuf.hasRemaining then
                        val arr = new Array[Byte](tls.appInBuf.remaining())
                        tls.appInBuf.get(arr)
                        plaintext.writeBytes(arr, 0, arr.length)
                    end if
                    unwrapLoop()
                end if
            end unwrapLoop
            unwrapLoop()
            tls.netInBuf.compact()
            if plaintext.size > 0 then
                Present(Span.fromUnsafe(plaintext.toByteArray))
            else
                Absent
            end if
        end if
    end tryUnwrapBuffered

end NioIoDriver

/** Factory for `NioIoDriver`. Opens a fresh `Selector` for each driver instance. */
private[kyo] object NioIoDriver:
    def init()(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(Selector.open())
end NioIoDriver
