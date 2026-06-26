package kyo.net.internal

import kyo.*
import kyo.net.internal.transport.*
import kyo.net.internal.util.HandleId
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
final private[kyo] class JsHandle private[kyo] (val socket: js.Dynamic, val id: HandleId):
    // Pending read promise (at most one). Null when no read is pending.
    var pendingRead: Promise.Unsafe[ReadOutcome, Abort[Closed]] = uninitialized

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
        pendingRead = null.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]

end JsHandle

/** Factory for `JsHandle`. Registers permanent event listeners on the Node.js socket. */
private[kyo] object JsHandle:

    /** Create a `JsHandle` from a connected, paused Node.js socket and attach permanent `data`, `end`, `close`, and `error` listeners. */
    def init(socket: js.Dynamic, driver: IoDriver[JsHandle])(using AllowUnsafe, Frame): JsHandle =
        // JS has no file-descriptor concept; use 0 as the fd placeholder so HandleId.next produces a process-unique id.
        val handle = new JsHandle(socket, HandleId.next(0))

        // Permanent "data" listener
        discard(socket.on(
            "data",
            { (chunk: js.Dynamic) =>
                discard(socket.pause())
                val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                val arr        = copyFromNodeBuffer(nodeBuffer, nodeBuffer.length)

                val pending = handle.pendingRead
                if !isNull(pending) then
                    handle.clearPendingRead()
                    pending.nn.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                else
                    // No pending read: store as leftover
                    handle.setLeftover(arr, 0, arr.length)
                end if
            }: js.Function1[js.Dynamic, Unit]
        ))

        // EOF/close/error listeners
        val signalEof: js.Function0[Unit] = () =>
            val pending = handle.pendingRead
            if !isNull(pending) then
                handle.clearPendingRead()
                pending.nn.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
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

    private def copyFromNodeBuffer(src: js.typedarray.Uint8Array, len: Int): Array[Byte] =
        // Bulk copy via Int8Array view of the same ArrayBuffer (no byte-by-byte loop).
        // The view shares the underlying ArrayBuffer with the Node.js chunk; int8Array2ByteArray copies it in one native call.
        val i8 = new js.typedarray.Int8Array(src.buffer, src.byteOffset, len)
        js.typedarray.int8Array2ByteArray(i8)
    end copyFromNodeBuffer

end JsHandle
