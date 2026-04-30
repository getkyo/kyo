package kyo.internal

import kyo.*
import kyo.internal.transport.*
import scala.compiletime.uninitialized
import scala.scalajs.js

/** Connection handle for `JsIoDriver`, wrapping a paused Node.js socket.
  *
  * Mutable state (`pendingRead`, leftover fields) is safe because JavaScript is single-threaded and the driver never accesses a handle from
  * concurrent callbacks. `pendingRead` holds at most one outstanding read promise; once satisfied it is nulled out to avoid
  * double-delivery.
  *
  * Leftover bytes are stored when a `"data"` chunk arrives before `awaitRead` has been called (or when a chunk contains more data than the
  * pending read can consume). They are delivered on the next `awaitRead` call without resuming the socket.
  */
final private[kyo] class JsHandle private[kyo] (val socket: js.Dynamic, val id: Int):
    // Pending read promise (at most one). Null when no read is pending.
    var pendingRead: Promise.Unsafe[Span[Byte], Abort[Closed]] = uninitialized

    // Leftover bytes from an oversized chunk
    private var leftoverBuf: Array[Byte] = uninitialized
    private var leftoverOff: Int         = 0
    private var leftoverLen: Int         = 0

    def hasLeftover: Boolean = !isNull(leftoverBuf)

    def leftover: (Array[Byte], Int, Int) =
        (leftoverBuf, leftoverOff, leftoverLen)

    def setLeftover(buf: Array[Byte], off: Int, len: Int): Unit =
        leftoverBuf = buf
        leftoverOff = off
        leftoverLen = len
    end setLeftover

    def clearLeftover(): Unit =
        leftoverBuf = null.asInstanceOf[Array[Byte]]
        leftoverOff = 0
        leftoverLen = 0
    end clearLeftover

    def clearPendingRead(): Unit =
        pendingRead = null.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]]

end JsHandle

/** Factory for `JsHandle`. Registers permanent event listeners on the Node.js socket. */
private[kyo] object JsHandle:
    private val idCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    /** Create a `JsHandle` from a connected, paused Node.js socket and attach permanent `data`, `end`, `close`, and `error` listeners. */
    def init(socket: js.Dynamic, driver: IoDriver[JsHandle])(using AllowUnsafe, Frame): JsHandle =
        val handle = new JsHandle(socket, idCounter.getAndIncrement())

        // Permanent "data" listener
        discard(socket.on(
            "data",
            { (chunk: js.Dynamic) =>
                discard(socket.pause())
                val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                val arr        = new Array[Byte](nodeBuffer.length)
                copyFromNodeBuffer(nodeBuffer, arr, nodeBuffer.length)
                val bytes = Span.fromUnsafe(arr)

                val pending = handle.pendingRead
                if !isNull(pending) then
                    handle.clearPendingRead()
                    pending.nn.completeDiscard(Result.succeed(bytes))
                else
                    // No pending read — store as leftover
                    handle.setLeftover(arr, 0, arr.length)
                end if
            }: js.Function1[js.Dynamic, Unit]
        ))

        // EOF/close/error listeners
        val signalEof: js.Function0[Unit] = () =>
            val pending = handle.pendingRead
            if !isNull(pending) then
                handle.clearPendingRead()
                pending.nn.completeDiscard(Result.succeed(Span.empty[Byte]))
            end if

        discard(socket.on("end", signalEof))
        discard(socket.on("close", signalEof))
        discard(socket.on(
            "error",
            { (_: js.Dynamic) =>
                val pending = handle.pendingRead
                if !isNull(pending) then
                    handle.clearPendingRead()
                    pending.nn.completeDiscard(Result.fail(Closed(driver.label, summon[Frame], "socket error")))
                end if
            }: js.Function1[js.Dynamic, Unit]
        ))

        handle
    end init

    @scala.annotation.tailrec
    private def copyFromNodeBuffer(src: js.typedarray.Uint8Array, dst: Array[Byte], remaining: Int, i: Int = 0): Unit =
        if i < remaining then
            dst(i) = src(i).toByte
            copyFromNodeBuffer(src, dst, remaining, i + 1)
        end if
    end copyFromNodeBuffer

end JsHandle
