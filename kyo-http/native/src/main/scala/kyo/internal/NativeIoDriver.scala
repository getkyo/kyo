package kyo.internal

import kyo.*
import kyo.internal.transport.*
import scala.annotation.tailrec
import scala.scalanative.unsafe.*

/** Native I/O driver backed by epoll (Linux) or kqueue (macOS/BSD) via `PollerBackend`.
  *
  * Like `NioIoDriver`, this driver is completion-based: callers deposit a `Promise` and the poll loop fulfils it when the fd becomes ready.
  * The poll loop runs in a dedicated fiber started by `start()`.
  *
  * Key differences from the JVM NIO driver:
  *   - Read buffer is a `malloc`-allocated `Ptr[Byte]` — one syscall delivers bytes directly into native memory.
  *   - Stale-event detection: each handle carries a unique `id`; `activeFds` maps fd → current id so recycled file descriptors are
  *     recognised and their stale events are discarded.
  *   - Accept readiness arrives as a normal `EPOLLIN`/`EVFILT_READ` event on the listening socket; `dispatchRead` checks `pendingAccepts`
  *     first before treating the event as a data read.
  *   - TLS I/O calls OpenSSL through `TlsBindings` rather than `SSLEngine`; the same drain-before-register trick applies: if OpenSSL
  *     already has buffered plaintext, `awaitRead` completes the promise immediately instead of registering with the poller.
  *
  * Note: `pollOnce` calls the `@blocking`-annotated `epollWaitTimeout` / `kqueueWait` functions directly (not via virtual dispatch) so the
  * Kyo scheduler can recognise the blocking call and park the OS thread correctly.
  */
final private[kyo] class NativeIoDriver private (backend: PollerBackend, pollerFd: CInt, isLinux: Boolean)
    extends IoDriver[NativeHandle]:

    import NativeIoDriver.*
    import PosixBindings.*

    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Maps fd -> current handle id. Used to discard stale poller events after fd reuse.
    private val activeFds = new java.util.concurrent.ConcurrentHashMap[Int, Long]()

    // Pending read requests: fd -> promise (buffer is in handle)
    private val pendingReads =
        new java.util.concurrent.ConcurrentHashMap[Int, (Promise.Unsafe[Span[Byte], Abort[Closed]], NativeHandle)]()

    // Pending writable requests: fd -> promise
    private val pendingWritables =
        new java.util.concurrent.ConcurrentHashMap[Int, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Pending accept requests: server fd -> promise
    private val pendingAccepts =
        new java.util.concurrent.ConcurrentHashMap[Int, Promise.Unsafe[Unit, Abort[Closed]]]()

    def label: String = "NativeIoDriver"

    def handleLabel(handle: NativeHandle): String = s"fd=${handle.fd}"

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        val fiber = Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped {
                Loop.foreach {
                    Sync.Unsafe.defer {
                        if closedFlag.get() then
                            Loop.done(())
                        else if pollOnce() then
                            Loop.continue
                        else
                            Loop.done(())
                        end if
                    }
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

    def awaitRead(handle: NativeHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // For TLS handles, check if OpenSSL has buffered plaintext from handshake or previous read.
        // The poller won't fire if the kernel buffer is empty but OpenSSL has data.
        handle.tls match
            case Present(tls) =>
                // Try reading directly from OpenSSL — may have data from handshake coalesced read
                val plainN = TlsBindings.tlsRead(tls.ssl, tls.tlsReadBuf, tls.bufSize)
                if plainN > 0 then
                    val arr = new Array[Byte](plainN)
                    copyFromPtr(tls.tlsReadBuf, arr, plainN)
                    Log.live.unsafe.debug(s"$label awaitRead TLS buffered data ${handleLabel(handle)} plaintext=$plainN")
                    promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                else
                    // Buffer is in handle, no allocation here
                    activeFds.put(handle.fd, handle.id)
                    pendingReads.put(handle.fd, (promise, handle))
                    Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
                    if backend.registerRead(pollerFd, handle.fd) < 0 then
                        discard(pendingReads.remove(handle.fd))
                        promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerRead failed for fd=${handle.fd}")))
                    end if
                end if
            case Absent =>
                // Buffer is in handle, no allocation here
                activeFds.put(handle.fd, handle.id)
                pendingReads.put(handle.fd, (promise, handle))
                Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
                if backend.registerRead(pollerFd, handle.fd) < 0 then
                    discard(pendingReads.remove(handle.fd))
                    promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerRead failed for fd=${handle.fd}")))
                end if
        end match
    end awaitRead

    def awaitWritable(handle: NativeHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        activeFds.put(handle.fd, handle.id)
        pendingWritables.put(handle.fd, promise)
        Log.live.unsafe.debug(s"$label awaitWritable registered ${handleLabel(handle)}")
        if backend.registerWrite(pollerFd, handle.fd) < 0 then
            discard(pendingWritables.remove(handle.fd))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerWrite failed for fd=${handle.fd}")))
        end if
    end awaitWritable

    def awaitConnect(handle: NativeHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Native (epoll/kqueue) signals connect completion via write-readiness
        Log.live.unsafe.debug(s"$label awaitConnect registered ${handleLabel(handle)}")
        awaitWritable(handle, promise)
    end awaitConnect

    def awaitAccept(fd: Int, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Use put (not putIfAbsent) — fd reuse across listener lifecycles is normal
        activeFds.put(fd, Long.MinValue) // Server fds use sentinel id — they are long-lived
        pendingAccepts.put(fd, promise)
        Log.live.unsafe.debug(s"$label awaitAccept registered fd=$fd")
        // Incoming connections signal as EVFILT_READ/EPOLLIN on the listening socket
        if backend.registerRead(pollerFd, fd) < 0 then
            discard(pendingAccepts.remove(fd))
            promise.completeDiscard(Result.fail(Closed(
                label,
                summon[Frame],
                s"registerAccept failed for fd=$fd"
            )))
        end if
    end awaitAccept

    def write(handle: NativeHandle, data: Span[Byte])(using AllowUnsafe): WriteResult =
        handle.tls match
            // Must check pendingCipher before isEmpty: writeTls may have returned
            // Partial(Span.empty) when all plaintext was encrypted but ciphertext
            // couldn't be flushed (EAGAIN). The pending ciphertext must be sent.
            case Present(tls) if tls.pendingCipher.length > 0 =>
                writeTls(handle, data, tls)
            case _ if data.isEmpty => WriteResult.Done
            case Present(tls)      => writeTls(handle, data, tls)
            case Absent            => writePlain(handle, data)

    private def writePlain(handle: NativeHandle, data: Span[Byte])(using AllowUnsafe): WriteResult =
        Zone {
            val arr = data.toArrayUnsafe
            val ptr = alloc[Byte](arr.length)
            copyToPtr(arr, ptr, arr.length)
            val n = tcpWrite(handle.fd, ptr, arr.length)
            if n < 0 then
                WriteResult.Error
            else if n == arr.length then
                WriteResult.Done
            else
                // n == 0 means EAGAIN (nothing written), n > 0 means partial write
                WriteResult.Partial(Span.fromUnsafe(arr.drop(n)))
            end if
        }
    end writePlain

    private def writeTls(handle: NativeHandle, data: Span[Byte], tls: NativeTlsState)(using AllowUnsafe): WriteResult =
        Zone {
            // 1. Flush any pending ciphertext from a previous EAGAIN
            if tls.pendingCipher.length > 0 then
                val pending    = tls.pendingCipher
                val pendingPtr = alloc[Byte](pending.length)
                copyToPtr(pending, pendingPtr, pending.length)
                val written = tcpWrite(handle.fd, pendingPtr, pending.length)
                if written < 0 then
                    WriteResult.Error
                else if written < pending.length then
                    // Still can't flush all — save remainder
                    tls.pendingCipher = pending.drop(if written == 0 then 0 else written)
                    WriteResult.Partial(data) // retry with same plaintext
                else
                    tls.pendingCipher = Array.emptyByteArray
                    // 2. Encrypt plaintext via OpenSSL
                    val arr = data.toArrayUnsafe
                    val ptr = alloc[Byte](arr.length)
                    copyToPtr(arr, ptr, arr.length)
                    val consumed = TlsBindings.tlsWrite(tls.ssl, ptr, arr.length)
                    if consumed < 0 then
                        WriteResult.Error
                    else if consumed == 0 then
                        WriteResult.Partial(data)
                    else
                        // 3. Get ciphertext from OpenSSL's write BIO and flush to socket
                        val flushed = flushBioToSocket(handle, tls)
                        if !flushed then
                            // EAGAIN on ciphertext — remaining is saved in pendingCipher
                            // Return Partial with remaining plaintext (if any)
                            if consumed == arr.length then WriteResult.Partial(Span.empty[Byte])
                            else WriteResult.Partial(Span.fromUnsafe(arr.drop(consumed)))
                        else if consumed == arr.length then
                            WriteResult.Done
                        else
                            WriteResult.Partial(Span.fromUnsafe(arr.drop(consumed)))
                        end if
                    end if
                end if
            else
                // 2. Encrypt plaintext via OpenSSL
                val arr = data.toArrayUnsafe
                val ptr = alloc[Byte](arr.length)
                copyToPtr(arr, ptr, arr.length)
                val consumed = TlsBindings.tlsWrite(tls.ssl, ptr, arr.length)
                if consumed < 0 then
                    WriteResult.Error
                else if consumed == 0 then
                    WriteResult.Partial(data)
                else
                    // 3. Get ciphertext from OpenSSL's write BIO and flush to socket
                    val flushed = flushBioToSocket(handle, tls)
                    if !flushed then
                        // EAGAIN on ciphertext — remaining is saved in pendingCipher
                        // Return Partial with remaining plaintext (if any)
                        if consumed == arr.length then WriteResult.Partial(Span.empty[Byte])
                        else WriteResult.Partial(Span.fromUnsafe(arr.drop(consumed)))
                    else if consumed == arr.length then
                        WriteResult.Done
                    else
                        WriteResult.Partial(Span.fromUnsafe(arr.drop(consumed)))
                    end if
                end if
            end if
        }
    end writeTls

    /** Flush OpenSSL's write BIO to the socket. Returns true if fully flushed, false if EAGAIN (saves remainder in pendingCipher). */
    private def flushBioToSocket(handle: NativeHandle, tls: NativeTlsState)(using AllowUnsafe): Boolean =
        @tailrec def drainRemainingBio(bioBuf: scala.collection.mutable.ArrayBuilder.ofByte): Unit =
            val moreN = TlsBindings.tlsGetOutput(tls.ssl, tls.tlsWriteBuf, tls.bufSize)
            if moreN > 0 then
                val arr = new Array[Byte](moreN)
                copyFromPtr(tls.tlsWriteBuf, arr, moreN)
                bioBuf ++= arr
                drainRemainingBio(bioBuf)
            end if
        end drainRemainingBio
        @tailrec def writeChunk(offset: Int, cipherN: Int): Boolean =
            if offset >= cipherN then true // fully written, continue outer loop
            else
                val written = tcpWrite(handle.fd, tls.tlsWriteBuf + offset, cipherN - offset)
                if written > 0 then writeChunk(offset + written, cipherN)
                else if written < 0 then false // error
                else
                    // EAGAIN — save unwritten ciphertext for later
                    val remaining = new Array[Byte](cipherN - offset)
                    copyFromPtr(tls.tlsWriteBuf + offset, remaining, cipherN - offset)
                    val bioBuf = new scala.collection.mutable.ArrayBuilder.ofByte
                    bioBuf ++= remaining
                    drainRemainingBio(bioBuf)
                    tls.pendingCipher = bioBuf.result()
                    false
                end if
        @tailrec def getLoop(): Boolean =
            val cipherN = TlsBindings.tlsGetOutput(tls.ssl, tls.tlsWriteBuf, tls.bufSize)
            if cipherN <= 0 then true // no more BIO data
            else if writeChunk(0, cipherN) then getLoop()
            else false
        end getLoop
        getLoop()
    end flushBioToSocket

    def cancel(handle: NativeHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label cancel ${handleLabel(handle)} reads=${
                if pendingReads.containsKey(handle.fd) then 1 else 0
            } writes=${if pendingWritables.containsKey(handle.fd) then 1 else 0} accepts=${
                if pendingAccepts.containsKey(handle.fd) then 1 else 0
            }")
        discard(activeFds.remove(handle.fd))
        backend.deregister(pollerFd, handle.fd)
        val closed = Closed(label, summon[Frame], s"fd=${handle.fd} canceled")
        Maybe(pendingReads.remove(handle.fd)).foreach { case (promise, _) =>
            promise.completeDiscard(Result.fail(closed))
        }
        Maybe(pendingWritables.remove(handle.fd)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
        Maybe(pendingAccepts.remove(handle.fd)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
    end cancel

    def closeHandle(handle: NativeHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label closeHandle ${handleLabel(handle)}")
        discard(activeFds.remove(handle.fd))
        NativeHandle.close(handle)
    end closeHandle

    def cleanupAccept(fd: Int)(using AllowUnsafe, Frame): Unit =
        val closed = Closed(label, summon[Frame], s"fd=$fd server closed")
        Maybe(pendingAccepts.remove(fd)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
        discard(activeFds.remove(fd))
    end cleanupAccept

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            Log.live.unsafe.debug(
                s"$label closing driver, failing ${pendingReads.size()} reads, ${pendingWritables.size()} writes, ${pendingAccepts.size()} accepts"
            )
            val closed = Closed(label, summon[Frame], "driver closed")
            pendingReads.forEach { (_, entry) =>
                val (promise, _) = entry
                promise.completeDiscard(Result.fail(closed))
            }
            pendingReads.clear()
            pendingWritables.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingWritables.clear()
            pendingAccepts.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingAccepts.clear()
            activeFds.clear()
            backend.close(pollerFd)
        end if
    end close

    private def pollOnce()(using AllowUnsafe): Boolean =
        val outFds  = stackalloc[CInt](PollBatchSize)
        val outMeta = stackalloc[CInt](PollBatchSize)
        // Call @extern @blocking functions directly instead of through backend.poll virtual dispatch.
        // The @blocking annotation is not visible through trait virtual dispatch, which prevents the
        // GC from recognizing the thread is in a blocking native call and causes safepoint hangs.
        val n =
            if isLinux then PosixBindings.epollWaitTimeout(pollerFd, outFds, outMeta, PollBatchSize)
            else PosixBindings.kqueueWait(pollerFd, outFds, outMeta, PollBatchSize)
        if n < 0 then
            false
        else
            if n > 0 then
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label poll returned $n events")
            end if
            dispatchEvents(outFds, outMeta, n, 0)
            true
        end if
    end pollOnce

    @tailrec
    private def dispatchEvents(outFds: Ptr[CInt], outMeta: Ptr[CInt], n: Int, i: Int)(using AllowUnsafe): Unit =
        if i < n then
            val fd   = outFds(i)
            val meta = outMeta(i)
            if backend.isRead(meta) then
                dispatchRead(fd)
            end if
            if backend.isWrite(meta) then
                dispatchWritable(fd)
            end if
            dispatchEvents(outFds, outMeta, n, i + 1)
        end if
    end dispatchEvents

    private def dispatchRead(fd: Int)(using AllowUnsafe): Unit =
        // Check accept first — server sockets fire read-readiness for incoming connections
        Maybe(pendingAccepts.remove(fd)) match
            case Present(promise) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchAccept fd=$fd")
                promise.completeDiscard(Result.succeed(()))
            case Absent =>
                // Normal data read
                Maybe(pendingReads.remove(fd)) match
                    case Present((promise, handle)) =>
                        val currentId = Maybe(activeFds.get(handle.fd))
                        if currentId != Present(handle.id) then
                            // Stale event — fd was reused by a different connection
                            given Frame = Frame.internal
                            Log.live.unsafe.debug(s"$label dispatchRead fd=$fd STALE (handle.id=${handle.id} current=$currentId)")
                            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale read event fd=$fd")))
                        else
                            handle.tls match
                                case Present(tls) => dispatchReadTls(fd, promise, handle, tls)
                                case Absent       => dispatchReadPlain(fd, promise, handle)
                        end if
                    case Absent =>
                        given Frame = Frame.internal
                        Log.live.unsafe.warn(s"$label dispatchRead for fd=$fd with no pending promise")
                end match
        end match
    end dispatchRead

    private def dispatchReadPlain(
        fd: Int,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: NativeHandle
    )(using AllowUnsafe): Unit =
        // Read directly into handle's persistent buffer (no allocation)
        val n = tcpRead(fd, handle.readBuffer, handle.readBufferSize)
        if n > 0 then
            given Frame = Frame.internal
            Log.live.unsafe.debug(s"$label dispatchRead fd=$fd bytes=$n")
            // Copy from Ptr to result Array (one copy, right-sized)
            val result = new Array[Byte](n)
            copyFromPtr(handle.readBuffer, result, n)
            val bytes = Span.fromUnsafe(result)
            promise.completeDiscard(Result.succeed(bytes))
        else if n == 0 then
            // EOF
            promise.completeDiscard(Result.succeed(Span.empty[Byte]))
        else
            // EAGAIN or transient error — re-register for read
            given Frame = Frame.internal
            Log.live.unsafe.debug(s"$label dispatchRead fd=$fd EAGAIN, re-registering")
            pendingReads.put(fd, (promise, handle))
            discard(backend.registerRead(pollerFd, fd))
        end if
    end dispatchReadPlain

    private def dispatchReadTls(
        fd: Int,
        promise: Promise.Unsafe[Span[Byte], Abort[Closed]],
        handle: NativeHandle,
        tls: NativeTlsState
    )(using AllowUnsafe): Unit =
        // Try to read from OpenSSL's internal buffer first (e.g. from handshake or coalesced reads).
        // The poller won't fire if the kernel buffer is empty but OpenSSL has data.
        tryReadBufferedTls(tls) match
            case Present(buffered) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchReadTls fd=$fd plaintext=${buffered.size} (buffered)")
                promise.completeDiscard(Result.succeed(buffered))
            case Absent =>
                // 1. Read ciphertext from socket into handle's read buffer
                val n = tcpRead(fd, handle.readBuffer, handle.readBufferSize)
                if n < 0 then
                    // EAGAIN or transient error — re-register for read
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label dispatchReadTls fd=$fd EAGAIN, re-registering")
                    pendingReads.put(fd, (promise, handle))
                    discard(backend.registerRead(pollerFd, fd))
                else if n == 0 then
                    // EOF
                    promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                else
                    // 2. Feed ciphertext to OpenSSL
                    val fed = TlsBindings.tlsFeedInput(tls.ssl, handle.readBuffer, n)
                    if fed < 0 then
                        given Frame = Frame.internal
                        Log.live.unsafe.error(s"$label dispatchReadTls fd=$fd tlsFeedInput failed")
                        promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                    else
                        // 3. Read plaintext from OpenSSL
                        tryReadBufferedTls(tls) match
                            case Present(plaintext) =>
                                given Frame = Frame.internal
                                Log.live.unsafe.debug(s"$label dispatchReadTls fd=$fd plaintext=${plaintext.size}")
                                promise.completeDiscard(Result.succeed(plaintext))
                            case Absent =>
                                // Got ciphertext but no complete TLS record yet — need more data
                                pendingReads.put(fd, (promise, handle))
                                discard(backend.registerRead(pollerFd, fd))
                        end match
                    end if
                end if
        end match
    end dispatchReadTls

    /** Try to read buffered plaintext from OpenSSL. Returns Present(data) or Absent if no data available. */
    private def tryReadBufferedTls(tls: NativeTlsState)(using AllowUnsafe): Maybe[Span[Byte]] =
        @tailrec def readLoop(total: Int, arrays: List[(Array[Byte], Int)]): (Int, List[(Array[Byte], Int)]) =
            val plainN = TlsBindings.tlsRead(tls.ssl, tls.tlsReadBuf, tls.bufSize)
            if plainN > 0 then
                val arr = new Array[Byte](plainN)
                copyFromPtr(tls.tlsReadBuf, arr, plainN)
                readLoop(total + plainN, (arr, plainN) :: arrays)
            else (total, arrays)
            end if
        end readLoop
        val (totalPlaintext, plaintextArrays) = readLoop(0, Nil)
        if totalPlaintext > 0 then
            val result = plaintextArrays match
                case (singleArr, _) :: Nil => Span.fromUnsafe(singleArr)
                case _ =>
                    val merged   = new Array[Byte](totalPlaintext)
                    val reversed = plaintextArrays.reverse
                    @tailrec def merge(remaining: List[(Array[Byte], Int)], offset: Int): Unit =
                        remaining match
                            case (arr, len) :: tail =>
                                java.lang.System.arraycopy(arr, 0, merged, offset, len)
                                merge(tail, offset + len)
                            case Nil => ()
                    merge(reversed, 0)
                    Span.fromUnsafe(merged)
            Present(result)
        else Absent
        end if
    end tryReadBufferedTls

    private def dispatchWritable(fd: Int)(using AllowUnsafe): Unit =
        Maybe(pendingWritables.remove(fd)) match
            case Present(promise) =>
                if !activeFds.containsKey(fd) then
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label dispatchWritable fd=$fd STALE")
                    promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"stale writable event fd=$fd")))
                else
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label dispatchWritable fd=$fd")
                    promise.completeDiscard(Result.succeed(()))
                end if
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchWritable for fd=$fd with no pending promise")
        end match
    end dispatchWritable

    @tailrec
    private def copyToPtr(arr: Array[Byte], ptr: Ptr[Byte], remaining: Int, i: Int = 0): Unit =
        if i < remaining then
            ptr(i) = arr(i)
            copyToPtr(arr, ptr, remaining, i + 1)
        end if
    end copyToPtr

    @tailrec
    private def copyFromPtr(ptr: Ptr[Byte], arr: Array[Byte], remaining: Int, i: Int = 0): Unit =
        if i < remaining then
            arr(i) = ptr(i)
            copyFromPtr(ptr, arr, remaining, i + 1)
        end if
    end copyFromPtr

end NativeIoDriver

/** Factory for `NativeIoDriver`. Detects the host OS and creates an epoll or kqueue fd via the backend. */
private[kyo] object NativeIoDriver:
    val PollBatchSize: Int = 64

    def init(backend: PollerBackend)(using AllowUnsafe): NativeIoDriver =
        val isLinux = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")
        new NativeIoDriver(backend, backend.create(), isLinux)
end NativeIoDriver
