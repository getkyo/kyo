package kyo.net.internal

import kyo.*
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
    private val shutdownPromise: IOPromise[Any, Unit]
) extends IoDriver[JsHandle]:

    // Unsafe: created at driver construction with no ambient AllowUnsafe; the danger bridge builds it here and every get/compareAndSet runs
    // under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

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

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Any, Unit], even though both erase to the same runtime object; the alias is transparent only inside
        // kyo.Fiber's own defining scope, so exposing shutdownPromise as the locked IoDriver.start return needs this erased-boundary cast.
        // Safe: the promise completes only when close() settles it with Unit above.
        shutdownPromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    def awaitRead(handle: JsHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Node's net.Socket#destroyed / #readableEnded are documented boolean properties; js.Dynamic erases them to untyped JS values, so recovering
        // the typed Boolean needs these narrowing casts. Safe per Node's documented property types.
        if handle.hasLeftover then
            // (i) Deliver any staged/leftover chunk FIRST, in order, even if the socket has since ended/been destroyed: a peer-close-probe-induced
            // 'end' (and Node's allowHalfOpen=false auto-destroy) must not drop bytes the probe already staged. deliverLeftover needs pendingRead set.
            handle.pendingRead = Present(promise)
            deliverLeftover(handle)
        else if handle.socket.readableEnded.asInstanceOf[Boolean] then
            // (ii) The readable side ended (peer FIN, buffer fully consumed). Surface EOF. MANDATORY after a probe-induced 'end' fired with no pending
            // read (signalEof dropped it): a resume() on an already-ended stream would park forever, since no further 'data'/'end' will come.
            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        else if handle.socket.destroyed.asInstanceOf[Boolean] then
            // (iii) Socket destroyed (RST, or a completed close) with nothing staged: fail Closed.
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"socket destroyed")))
        else
            // (iv) Request the next chunk: the permanent 'data' listener delivers it (or 'end'/'error' the EOF/failure).
            handle.pendingRead = Present(promise)
            discard(handle.socket.resume())
        end if
    end awaitRead

    /** Whether the peer has closed, for the ReadPump's grace poll (poll-on-expiry). Node gives no non-consuming FIN signal on a PAUSED socket:
      * `pause()` calls `readStop`, so while backpressured libuv does not read the kernel socket and a FIN/RST never reaches Node (`readableEnded`/
      * `destroyed` stay false forever). Consuming inline is fine: the kick runs on the same single event-loop carrier as every socket callback
      * (unlike NIO). `false` here can mean "not observed yet". Skipped once staged bytes hit the cap or when the 'data' listener is gone (detached for a STARTTLS upgrade).
      */
    override def isPeerClosed(handle: JsHandle)(using AllowUnsafe, Frame): Boolean =
        val s = handle.socket
        if s.destroyed.asInstanceOf[Boolean] || s.readableEnded.asInstanceOf[Boolean] then true
        else if handle.stagedBytes >= JsIoDriver.PeerProbeBufferCap then false
        else if s.listenerCount("data").asInstanceOf[Int] == 0 then false
        else
            // Resume for one chunk: the permanent 'data' listener re-pauses and stashes it, or 'end'/'error' latches the close.
            discard(s.resume())
            false
        end if
    end isPeerClosed

    def awaitConnect(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // JS connect is handled via Node.js 'connect' event callback, not via the driver
        promise.completeDiscard(Result.succeed(()))

    def awaitAccept(handle: JsHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // JS does not run the PosixTransport accept loop; fail fast so a caller that accidentally uses this path gets an immediate error.
        promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"awaitAccept not supported on JsIoDriver")))

    def awaitWritable(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Node's net.Socket#destroyed is a documented boolean property; js.Dynamic erases that to an untyped JS value, so recovering the typed
        // Boolean needs this narrowing cast. Safe per Node's documented property type; it cannot dissolve without a typed facade for Node's
        // net.Socket.
        if handle.socket.destroyed.asInstanceOf[Boolean] then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"socket destroyed")))
        else
            // Register one-shot listeners for drain/close/error
            var drainFn: js.Function0[Unit]             = null
            var closeFn: js.Function0[Unit]             = null
            var errorFn: js.Function1[js.Dynamic, Unit] = null

            def removeAll(): Unit =
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
                promise.completeDiscard(Result.fail(Closed(label, summon[Frame], reason)))

            drainFn = (() => completeSuccess()): js.Function0[Unit]
            closeFn = (() => completeFailure("socket closed before writable")): js.Function0[Unit]
            errorFn = ((_: js.Dynamic) => completeFailure("socket error before writable")): js.Function1[js.Dynamic, Unit]

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
        val closed = Closed(label, summon[Frame], s"${handleLabel(handle)} canceled")
        handle.pendingRead match
            case Present(pending) =>
                pending.completeDiscard(Result.fail(closed))
                handle.clearPendingRead()
            case Absent => ()
        end match
    end cancel

    def closeHandle(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
        // Node's net.Socket#destroyed is a documented boolean property; js.Dynamic erases that to an untyped JS value, so recovering the typed
        // Boolean needs this narrowing cast. Safe per Node's documented property type; it cannot dissolve without a typed facade for Node's
        // net.Socket.
        if !handle.socket.destroyed.asInstanceOf[Boolean] then
            discard(handle.socket.destroy())
        end if
    end closeHandle

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            shutdownPromise.completeDiscard(Result.unit)
        end if
    end close

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
        new JsIoDriver(new IOPromise[Any, Unit])
end JsIoDriver
