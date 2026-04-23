package kyo.internal

import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js

/** JS I/O driver backed by Node.js socket events.
  *
  * Unlike the NIO and Native drivers, there is no poll loop — Node.js's own event loop dispatches I/O events. Read readiness arrives as a
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

    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

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

        shutdownPromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    def awaitRead(handle: JsHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.socket.destroyed.asInstanceOf[Boolean] then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"socket destroyed")))
        else
            handle.pendingRead = promise
            // If there's leftover data from a previous oversized chunk, deliver it immediately
            if handle.hasLeftover then
                deliverLeftover(handle)
            else
                discard(handle.socket.resume())
            end if
        end if
    end awaitRead

    def awaitConnect(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // JS connect is handled via Node.js 'connect' event callback, not via the driver
        promise.completeDiscard(Result.succeed(()))

    def awaitWritable(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.socket.destroyed.asInstanceOf[Boolean] then
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"socket destroyed")))
        else
            // Register one-shot listeners for drain/close/error
            var drainFn: js.Function0[Unit]             = null
            var closeFn: js.Function0[Unit]             = null
            var errorFn: js.Function1[js.Dynamic, Unit] = null

            def complete(): Unit =
                discard(handle.socket.removeListener("drain", drainFn))
                discard(handle.socket.removeListener("close", closeFn))
                discard(handle.socket.removeListener("error", errorFn))
                promise.completeDiscard(Result.succeed(()))
            end complete

            drainFn = (() => complete()): js.Function0[Unit]
            closeFn = (() => complete()): js.Function0[Unit]
            errorFn = ((_: js.Dynamic) => complete()): js.Function1[js.Dynamic, Unit]

            discard(handle.socket.once("drain", drainFn))
            discard(handle.socket.once("close", closeFn))
            discard(handle.socket.once("error", errorFn))
        end if
    end awaitWritable

    def write(handle: JsHandle, data: Span[Byte])(using AllowUnsafe): WriteResult =
        if data.isEmpty then WriteResult.Done
        else if handle.socket.destroyed.asInstanceOf[Boolean] then WriteResult.Error
        else
            val nodeBuf = toNodeBuffer(data)
            val flushed = handle.socket.write(nodeBuf).asInstanceOf[Boolean]
            if flushed then WriteResult.Done
            else WriteResult.Partial(Span.empty[Byte]) // All data accepted but not flushed — await drain
        end if
    end write

    def cancel(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
        discard(handle.socket.pause())
        val closed  = Closed(label, summon[Frame], s"${handleLabel(handle)} canceled")
        val pending = handle.pendingRead
        if !isNull(pending) then
            pending.nn.completeDiscard(Result.fail(closed))
            handle.clearPendingRead()
        end if
    end cancel

    def closeHandle(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
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
        val (leftoverBuf, leftoverOff, leftoverLen) = handle.leftover
        val bytes = Span.fromUnsafe(java.util.Arrays.copyOfRange(leftoverBuf, leftoverOff, leftoverOff + leftoverLen))
        handle.clearLeftover()
        val pending = handle.pendingRead
        if !isNull(pending) then
            handle.clearPendingRead()
            pending.nn.completeDiscard(Result.succeed(bytes))
        end if
    end deliverLeftover

    private def toNodeBuffer(span: Span[Byte]): js.Dynamic =
        val arr = span.toArrayUnsafe
        val i8  = js.typedarray.byteArray2Int8Array(arr)
        val u8  = new js.typedarray.Uint8Array(i8.buffer, i8.byteOffset, i8.length)
        js.Dynamic.global.Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength)
    end toNodeBuffer

end JsIoDriver

/** Factory for `JsIoDriver`. Each instance gets its own shutdown `IOPromise`. */
private[kyo] object JsIoDriver:
    def init()(using AllowUnsafe): JsIoDriver =
        new JsIoDriver(new IOPromise[Any, Unit])
end JsIoDriver
