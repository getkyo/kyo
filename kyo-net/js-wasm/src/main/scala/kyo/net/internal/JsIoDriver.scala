package kyo.net.internal

import kyo.*
import kyo.net.NetDriverUnsupportedException
import kyo.net.NetException
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js

/** JS I/O driver backed by Node.js socket events.
  *
  * Unlike the NIO and Native drivers, there is no poll loop: Node.js's own event loop dispatches I/O events. Read readiness arrives as a
  * `"data"` event, and write backpressure is signalled by the `"drain"` event. The `start()` method returns a sentinel `IOPromise` that
  * completes only when `close()` is called, satisfying the `IoDriver` contract without spinning a background fiber.
  *
  * Data flow: each Node.js socket is paused after creation; `awaitRead` calls `socket.resume()` to request the next chunk. When a chunk
  * arrives that is larger than what the caller expects, the excess bytes are stored as `leftover` in the `JsHandle` and delivered on the
  * next `awaitRead` without touching the socket.
  *
  * Note: there is no per-handle read buffer because Node.js delivers data through its own `Buffer` objects; only a shallow copy (Uint8Array
  * to Array[Byte]) is needed.
  */
final private[kyo] class JsIoDriver private (
    private val shutdownPromise: IOPromise[Any, Unit],
    private val closedFlag: AtomicBoolean.Unsafe
) extends IoDriver[JsHandle]:

    // Diagnostics state. Node's event loop has no poll cycle to count, so progress is the number of reads and writable waits armed: a pump
    // arms its next one only after the previous completed, so the count stands still exactly when no I/O completes. Every access runs on
    // the single event-loop thread.
    private var armedOps: Long                                                 = 0L
    private var pendingWritables: Int                                          = 0
    private val readArmed: scala.collection.mutable.Set[JsHandle]              = scala.collection.mutable.Set.empty
    private var diagRegistration: kyo.internal.Diagnostics.Registration | Null = null

    def label: String = "JsIoDriver"

    def handleLabel(handle: JsHandle): String = s"socket#${handle.id}"

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Monitor for unexpected completion
        shutdownPromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    if closedFlag.get() then
                        Log.live.unsafe.info(s"$label sentinel completed after close()")
                case Result.Failure(e) =>
                    Log.live.unsafe.error(s"$label sentinel failed: $e")
                case Result.Panic(t) =>
                    Log.live.unsafe.error(s"$label sentinel crashed", t)
        }

        // Same naming as PollerIoDriver's registration, including the marker the stranded-op gate exempts for a process-lifetime transport.
        val diagName =
            "JsIoDriver@" + java.lang.System.identityHashCode(this) +
                (if ProcessSharedTransport.isBuilding then " processSharedTransport" else "")
        diagRegistration = kyo.internal.Diagnostics.register(diagName)(
            dump = () =>
                val reads = new StringBuilder
                pendingReadHandles().foreach(h => discard(reads.append(handleLabel(h)).append(' ')))
                s"closed=${closedFlag.get()} armedOps=$armedOps pendingWritables=$pendingWritables pendingReads=[$reads]"
            ,
            probe = () =>
                kyo.internal.Diagnostics.Probe(
                    closed = closedFlag.get(),
                    cycles = armedOps,
                    pending = pendingWritables > 0 || pendingReadHandles().nonEmpty
                )
        )

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Any, Unit], even though both erase to the same runtime object; the alias is transparent only inside
        // kyo.Fiber's own defining scope, so exposing shutdownPromise as the locked IoDriver.start return needs this erased-boundary cast.
        // Safe: the promise completes only when close() settles it with Unit above.
        shutdownPromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    def awaitRead(handle: JsHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        armedOps += 1
        // Node's net.Socket#destroyed / #readableEnded are documented boolean properties; js.Dynamic erases them to untyped JS values, so recovering
        // the typed Boolean needs these narrowing casts. Safe per Node's documented property types.
        if handle.hasLeftover then
            // (i) Deliver any staged/leftover chunk FIRST, in order, even if the socket has since ended/been destroyed: a peer-close-watch-induced
            // 'end' (and Node's allowHalfOpen=false auto-destroy) must not drop bytes the probe already staged. deliverLeftover needs pendingRead set.
            handle.pendingRead = Present(promise)
            deliverLeftover(handle)
        else if handle.socket.readableEnded.asInstanceOf[Boolean] then
            // (ii) The readable side ended (peer FIN, buffer fully consumed). Surface EOF. MANDATORY after a watch-induced 'end' fired with no pending
            // read (signalEof dropped it): a resume() on an already-ended stream would park forever, since no further 'data'/'end' will come.
            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        else if handle.socket.destroyed.asInstanceOf[Boolean] then
            // (iii) Socket destroyed (RST, or a completed close) with nothing staged: fail Closed.
            promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "socket destroyed")))
        else
            // (iv) Request the next chunk: the permanent 'data' listener delivers it (or 'end'/'error' the EOF/failure).
            handle.pendingRead = Present(promise)
            readArmed += handle
            discard(handle.socket.resume())
        end if
    end awaitRead

    /** Node gives no non-consuming FIN signal on a PAUSED socket: `pause()` calls `readStop`, so while backpressured libuv does not read the
      * kernel socket and a FIN/RST never reaches Node. The watch therefore resumes the socket: the permanent 'data' listener stages each chunk and
      * resumes again while the watch is registered and the staging is under its cap, and 'end', 'close' or 'error' completes the watch. Everything
      * runs on the one event-loop carrier. Reading stops at the cap or when the 'data' listener is gone (detached for a STARTTLS upgrade).
      */
    override def awaitPeerClose(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        val s = handle.socket
        handle.peerCloseWatch = Present(promise)
        // Narrowing casts: Node documents destroyed / readableEnded as booleans, and js.Dynamic erases them.
        if s.destroyed.asInstanceOf[Boolean] || s.readableEnded.asInstanceOf[Boolean] then handle.completePeerCloseWatch()
        else handle.resumeForPeerClose()
    end awaitPeerClose

    override def cancelPeerCloseWatch(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.peerCloseWatch.exists(_.equals(promise)) then handle.peerCloseWatch = Absent

    /** STARTTLS handoff: the plaintext ReadPump pulled `bytes` off the socket but detachForUpgrade already closed the inbound
      * channel, so these are the peer's first TLS flight (the ClientHello a server pulled a moment before detaching).
      * The [[kyo.net.internal.transport.IoDriver]] default drops them, which strands the handshake at its deadline.
      * Guarded on `upgrading` so an ordinary teardown close still discards. Single event-loop
      * carrier, so the plain enqueue is safe; the read routes to EITHER the channel (offer succeeds) OR here (offer fails Closed), never both, so
      * no bytes are fed twice.
      */
    override def onInboundClosedDuringRead(handle: JsHandle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        if handle.upgrading then
            val arr = bytes.toArrayUnsafe
            handle.enqueueLeftover(arr, 0, arr.length)
    end onInboundClosedDuringRead

    def awaitConnect(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // JS connect is handled via Node.js 'connect' event callback, not via the driver
        promise.completeDiscard(Result.succeed(()))

    def awaitAccept(handle: JsHandle, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // JS does not run the PosixTransport accept loop; fail fast so a caller that accidentally uses this path gets an immediate error.
        promise.completeDiscard(Result.Panic(NetDriverUnsupportedException(label, "awaitAccept")))

    def awaitWritable(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // Node's net.Socket#destroyed is a documented boolean property; js.Dynamic erases that to an untyped JS value, so recovering the typed
        // Boolean needs this narrowing cast. Safe per Node's documented property type; it cannot dissolve without a typed facade for Node's
        // net.Socket.
        if handle.socket.destroyed.asInstanceOf[Boolean] then
            promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "socket destroyed")))
        else
            // Register one-shot listeners for drain/close/error
            var drainFn: js.Function0[Unit]             = null
            var closeFn: js.Function0[Unit]             = null
            var errorFn: js.Function1[js.Dynamic, Unit] = null

            def removeAll(): Unit =
                pendingWritables -= 1
                discard(handle.socket.removeListener("drain", drainFn))
                discard(handle.socket.removeListener("close", closeFn))
                discard(handle.socket.removeListener("error", errorFn))
            end removeAll

            // The `drain` event is the success signal (the socket is writable again); `close`/`error` mean the socket went away before it became
            // writable, so the awaiting write must fail with a typed Closed, never spuriously succeed. A single `complete()` shared by all three
            // listeners would report Done for a write parked over a dying socket and then write into a destroyed socket, so success and failure
            // have separate completions here. This matches every other driver, where a close/error on a pending op fails the promise with Closed.
            def completeSuccess(): Unit =
                removeAll()
                promise.completeDiscard(Result.succeed(()))

            def completeFailure(reason: String): Unit =
                removeAll()
                promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, reason)))

            drainFn = (() => completeSuccess()): js.Function0[Unit]
            closeFn = (() => completeFailure("socket closed before writable")): js.Function0[Unit]
            errorFn = ((_: js.Dynamic) => completeFailure("socket error before writable")): js.Function1[js.Dynamic, Unit]

            armedOps += 1
            pendingWritables += 1
            discard(handle.socket.once("drain", drainFn))
            discard(handle.socket.once("close", closeFn))
            discard(handle.socket.once("error", errorFn))
        end if
    end awaitWritable

    def write(handle: JsHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        // Two Node narrowing casts below: net.Socket#destroyed (a documented boolean property) and net.Socket#write (a documented boolean
        // return, where false signals backpressure). js.Dynamic erases both to untyped JS values, so recovering the typed Boolean needs these
        // narrowing casts. Safe per Node's documented types; they cannot dissolve without a typed facade for Node's net.Socket.
        if data.isEmpty || offset >= data.size then WriteResult.Done
        else if handle.socket.destroyed.asInstanceOf[Boolean] then WriteResult.Error
        else
            // Node.js socket.write accepts the data slice starting at offset. On backpressure (flushed == false), the data was already
            // handed to Node.js, so we must NOT re-send it on the next pump call. Returning Partial(data, data.size) causes the pump to
            // retry with offset == data.size, which computes len == 0 and returns Done -- effectively "wait for drain, then proceed".
            val nodeBuf = toNodeBuffer(data, offset)
            val flushed = handle.socket.write(nodeBuf).asInstanceOf[Boolean]
            if flushed then WriteResult.Done
            else WriteResult.Partial(data, data.size) // accepted, not flushed: sentinel offset prevents double-send on retry
        end if
    end write

    def cancel(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
        discard(handle.socket.pause())
        val closed = Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "canceled")
        handle.pendingRead match
            case Present(pending) =>
                pending.completeDiscard(Result.fail(closed))
                handle.clearPendingRead()
            case Absent => ()
        end match
    end cancel

    /** A graceful close ends the writable side and destroys the socket once Node reports it finished, instead of destroying at once.
      *
      * `destroy()` discards what the writable side still holds. A `net.Socket` write reaches the kernel on the calling turn when it can, so
      * a destroy right after it rarely loses anything; a `tls.TLSSocket` write is encrypted at once but handed to the underlying socket only
      * when no earlier write is in flight there, and a write's completion lands on a later event-loop turn even on loopback, so the second
      * of two back-to-back writes still sits in Node's TLS output when a same-turn destroy discards it. The write pump cannot see the loss:
      * `socket.write` accepted the bytes. `end()` flushes that output and then sends the FIN; `finish` is Node's signal that every accepted
      * write reached the underlying socket, and the destroy follows it.
      *
      * The wait is bounded by the handle's `closeFlushGrace`, the window a closing connection grants a peer that has stopped reading: a
      * peer that never drains never lets the output finish, and the socket is destroyed regardless when the window ends. `Duration.Infinity`
      * (stdio, whose shim owns no socket) arms no timer. An abort keeps the immediate destroy: the upgrade abandon, the handshake deadline
      * and a peer reset destroy the socket themselves before this runs, and a socket Node already destroyed is left alone.
      */
    def closeHandle(handle: JsHandle)(using allow: AllowUnsafe, frame: Frame): Unit =
        // Node's net.Socket#destroyed and #writableFinished are documented boolean properties; js.Dynamic erases them to untyped JS values,
        // so recovering the typed Boolean needs these narrowing casts. Safe per Node's documented property types; they cannot dissolve
        // without a typed facade for Node's net.Socket.
        readArmed -= handle
        val socket = handle.socket
        if !socket.destroyed.asInstanceOf[Boolean] then
            def destroyNow(): Unit =
                if !socket.destroyed.asInstanceOf[Boolean] then discard(socket.destroy())
            val settle: js.Function0[Unit] =
                if handle.closeFlushGrace.isFinite then
                    val timer = handle.clock.unsafe.sleep(handle.closeFlushGrace)
                    timer.onComplete(_ => destroyNow())
                    () =>
                        destroyNow()
                        // Interrupting completes the timer and runs the callback above, which finds the socket destroyed and does nothing.
                        timer.interruptDiscard(Result.Panic(Interrupted(frame, "socket settled before the close grace")))
                else () => destroyNow()
            // `close` covers a socket that finished before this ran or that Node destroys on an error after the end.
            discard(socket.once("finish", settle))
            discard(socket.once("close", settle))
            discard(socket.end())
            if socket.writableFinished.asInstanceOf[Boolean] then settle()
        end if
    end closeHandle

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            val reg = diagRegistration
            if reg ne null then reg.close()
            shutdownPromise.completeDiscard(Result.unit)
        end if
    end close

    /** The handles whose armed read is still outstanding, dropping the ones whose read has since completed or been cancelled. */
    private def pendingReadHandles(): List[JsHandle] =
        readArmed.filterInPlace(_.pendingRead.isDefined)
        readArmed.toList
    end pendingReadHandles

    private def deliverLeftover(handle: JsHandle)(using AllowUnsafe): Unit =
        // Deliver exactly ONE queued chunk (the oldest), the FIFO head; a later awaitRead delivers the next. Called only with a pending read set.
        handle.dequeueLeftover() match
            case Present(JsHandle.Leftover(buf, off, len)) =>
                val arr =
                    if off == 0 && len == buf.length then
                        // Whole-array leftover: transfer ownership directly without copying.
                        buf
                    else
                        java.util.Arrays.copyOfRange(buf, off, off + len)
                handle.pendingRead match
                    case Present(pending) =>
                        handle.clearPendingRead()
                        pending.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                    case Absent => ()
                end match
            case Absent => ()
        end match
    end deliverLeftover

    private def toNodeBuffer(span: Span[Byte], offset: Int): js.Dynamic =
        val arr = span.toArrayUnsafe
        val i8  = js.typedarray.byteArray2Int8Array(arr)
        // byteArray2Int8Array allocates a fresh ArrayBuffer (i8.byteOffset is always 0); adjust the view start by offset.
        val u8 = new js.typedarray.Uint8Array(i8.buffer, offset, i8.length - offset)
        js.Dynamic.global.Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength)
    end toNodeBuffer

end JsIoDriver

/** Factory for `JsIoDriver`. Each instance gets its own shutdown `IOPromise`. */
private[kyo] object JsIoDriver:

    /** Cap on staged probe bytes per handle; past it the probe stops resuming (see NioIoDriver.GraceProbeStagingCap for the trade). 1 MiB. */
    private[net] val PeerProbeBufferCap: Int = 1 << 20

    def init()(using AllowUnsafe): JsIoDriver =
        new JsIoDriver(new IOPromise[Any, Unit], AtomicBoolean.Unsafe.init(false))
end JsIoDriver
