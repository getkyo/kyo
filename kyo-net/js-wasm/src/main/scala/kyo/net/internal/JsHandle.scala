package kyo.net.internal

import kyo.*
import kyo.net.internal.transport.*
import kyo.net.internal.util.HandleId
import scala.scalajs.js

/** Connection handle for `JsIoDriver`, wrapping a paused Node.js socket.
  *
  * Mutable state (`pendingRead`, leftover fields) is safe because JavaScript is single-threaded and the driver never accesses a handle from
  * concurrent callbacks. `pendingRead` holds at most one outstanding read promise; once satisfied it is cleared to avoid double-delivery.
  *
  * Leftover bytes are stored when a `"data"` chunk arrives before `awaitRead` has been called (or when a chunk contains more data than the
  * pending read can consume). They are delivered on the next `awaitRead` call without resuming the socket.
  */
final private[kyo] class JsHandle private[kyo] (val socket: js.Dynamic, val id: HandleId):
    // Pending read promise (at most one). Absent when no read is pending.
    var pendingRead: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent

    // Leftover bytes from an oversized chunk
    private var leftoverState: Maybe[JsHandle.Leftover] = Absent

    def hasLeftover: Boolean = leftoverState.isDefined

    def leftover: Maybe[JsHandle.Leftover] = leftoverState

    def setLeftover(buf: Array[Byte], off: Int, len: Int): Unit =
        leftoverState = Present(JsHandle.Leftover(buf, off, len))

    def clearLeftover(): Unit =
        leftoverState = Absent

    def clearPendingRead(): Unit =
        pendingRead = Absent

end JsHandle

/** Factory for `JsHandle`. Registers permanent event listeners on the Node.js socket. */
private[kyo] object JsHandle:

    /** An oversized chunk's undelivered tail: `buf` sliced `[off, off + len)`. */
    private[kyo] case class Leftover(buf: Array[Byte], off: Int, len: Int)

    /** Create a `JsHandle` from a connected, paused Node.js socket and attach permanent `data`, `end`, `close`, and `error` listeners. */
    def init(socket: js.Dynamic, driver: IoDriver[JsHandle])(using AllowUnsafe, Frame): JsHandle =
        // JS has no file-descriptor concept; use 0 as the fd placeholder so HandleId.next produces a process-unique id.
        val handle = new JsHandle(socket, HandleId.next(0))

        // Permanent "data" listener
        discard(socket.on(
            "data",
            { (chunk: js.Dynamic) =>
                discard(socket.pause())
                // Safe: a Node socket with no encoding set always emits its "data" chunks as Buffers, which are Uint8Arrays.
                val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                val arr        = copyFromNodeBuffer(nodeBuffer, nodeBuffer.length)

                handle.pendingRead match
                    case Present(pending) =>
                        handle.clearPendingRead()
                        pending.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                    case Absent =>
                        // No pending read: store as leftover
                        handle.setLeftover(arr, 0, arr.length)
                end match
            }: js.Function1[js.Dynamic, Unit]
        ))

        // EOF/close/error listeners
        val signalEof: js.Function0[Unit] = () =>
            handle.pendingRead match
                case Present(pending) =>
                    handle.clearPendingRead()
                    pending.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                case Absent => ()

        discard(socket.on("end", signalEof))
        discard(socket.on("close", signalEof))
        discard(socket.on(
            "error",
            { (_: js.Dynamic) =>
                handle.pendingRead match
                    case Present(pending) =>
                        handle.clearPendingRead()
                        pending.completeDiscard(Result.fail(Closed(driver.label, summon[Frame], "socket error")))
                    case Absent => ()
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
