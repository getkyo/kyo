package kyo.net.internal

import kyo.*
import kyo.net.NetConnectionIoException
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
final private[kyo] class JsHandle private[kyo] (val socket: js.Dynamic, val id: HandleId, val createdAt: Frame):
    // Pending read promise (at most one). Absent when no read is pending.
    var pendingRead: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent

    // Peer-close grace window the ReadPump applies when backpressured (see kyo.net.NetConfig.peerCloseGrace); on the handle so an in-place STARTTLS
    // upgrade (same socket) inherits it without re-threading. Duration.Infinity (no reclaim) for handles created without a config (stdio).
    var peerCloseGrace: Duration = Duration.Infinity

    // Never reset:
    // the upgraded connection wraps a fresh JsHandle over the TLSSocket, so this handle is discarded whether the upgrade succeeds or fails.
    var upgrading: Boolean = false

    // The permanent "data" listener registered in JsHandle.init, kept so a STARTTLS upgrade can remove exactly it via
    // socket.removeListener("data", _). That is the only removal that clears Node's internal kDataListening flag; a blanket
    // removeAllListeners() leaves the flag stale, and Node's updateReadableListening then re-resumes the raw socket on the next
    // tick, flowing the unshifted handshake flight to no listener and discarding it before the TLSSocket can read it.
    var dataListener: js.Function1[js.Dynamic, Unit] = null

    // FIFO of undelivered chunks, delivered one per awaitRead. Two producers: an oversized "data" chunk's tail, and the peer-close grace probe's
    // resume() draining kernel bytes into the "data" listener while the pump is parked (JsIoDriver.isPeerClosed). A single slot would let the probe's
    // chunk clobber the tail (byte loss), so a queue; the single-threaded event loop makes a plain mutable queue safe. stagedBytesTotal bounds it (JsIoDriver.PeerProbeBufferCap).
    private val leftoverQueue: scala.collection.mutable.Queue[JsHandle.Leftover] = scala.collection.mutable.Queue.empty
    private var stagedBytesTotal: Int                                            = 0

    def hasLeftover: Boolean = leftoverQueue.nonEmpty

    /** Total undelivered staged bytes across the queue, the peer-close probe's cap check. */
    def stagedBytes: Int = stagedBytesTotal

    def enqueueLeftover(buf: Array[Byte], off: Int, len: Int): Unit =
        leftoverQueue.enqueue(JsHandle.Leftover(buf, off, len))
        stagedBytesTotal += len

    def dequeueLeftover(): Maybe[JsHandle.Leftover] =
        if leftoverQueue.isEmpty then Absent
        else
            val head = leftoverQueue.dequeue()
            stagedBytesTotal -= head.len
            Present(head)

    def clearPendingRead(): Unit =
        pendingRead = Absent

end JsHandle

/** Factory for `JsHandle`. Registers permanent event listeners on the Node.js socket. */
private[kyo] object JsHandle:

    /** An oversized chunk's undelivered tail: `buf` sliced `[off, off + len)`. */
    private[kyo] case class Leftover(buf: Array[Byte], off: Int, len: Int)

    /** Create a `JsHandle` from a connected, paused Node.js socket and attach permanent `data`, `end`, `close`, and `error` listeners. */
    def init(socket: js.Dynamic, driver: IoDriver[JsHandle], createdAt: Frame)(using AllowUnsafe): JsHandle =
        // JS has no file-descriptor concept; use 0 as the fd placeholder so HandleId.next produces a process-unique id.
        val handle = new JsHandle(socket, HandleId.next(0), createdAt)

        val dataFn: js.Function1[js.Dynamic, Unit] = (chunk: js.Dynamic) =>
            discard(socket.pause())
            // Safe: a Node socket with no encoding set always emits its "data" chunks as Buffers, which are Uint8Arrays.
            val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
            val arr        = copyFromNodeBuffer(nodeBuffer, nodeBuffer.length)

            handle.pendingRead match
                case Present(pending) =>
                    handle.clearPendingRead()
                    pending.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                case Absent =>
                    handle.enqueueLeftover(arr, 0, arr.length)
            end match
        handle.dataListener = dataFn
        discard(socket.on("data", dataFn))

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
            { (err: js.Dynamic) =>
                handle.pendingRead match
                    case Present(pending) =>
                        handle.clearPendingRead()
                        // A Node "error" is a hard receive error on a live read: surface it as a typed receive failure on the read outcome (not a
                        // closure), carrying the socket's own creation frame and the error's message as the cause.
                        val cause = if js.typeOf(err.message) == "string" then err.message.toString else ""
                        pending.completeDiscard(Result.succeed(ReadOutcome.Failed(
                            NetConnectionIoException(
                                s"connection socket#${handle.id}",
                                NetConnectionIoException.Operation.Receive,
                                cause
                            )(using handle.createdAt)
                        )))
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
