package kyo.internal

import java.io.InputStream
import java.io.IOException
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("node:net", "Socket")
private[kyo] class ProcessNodeSocket(options: js.Object) extends js.Object:
    def on(event: String, callback: js.Function1[js.Any, Unit]): ProcessNodeSocket = js.native
    def pause(): ProcessNodeSocket                                                 = js.native
    def resume(): ProcessNodeSocket                                                = js.native
    def destroy(): ProcessNodeSocket                                               = js.native
    def destroyed: Boolean                                                         = js.native
    def write(bytes: Uint8Array): Boolean                                          = js.native
    def write(bytes: Uint8Array, callback: js.Function1[js.Any, Unit]): Boolean    = js.native
    def end(): ProcessNodeSocket                                                   = js.native
end ProcessNodeSocket

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object ProcessNodeFs extends js.Object:
    def openSync(path: String, flags: String): Int                                                   = js.native
    def readSync(descriptor: Int, buffer: Uint8Array, offset: Int, length: Int, position: Null): Int = js.native
    def writeSync(descriptor: Int, buffer: Uint8Array): Int                                          = js.native
    def closeSync(descriptor: Int): Unit                                                             = js.native
end ProcessNodeFs

/** Owns one nonblocking pipe descriptor on Node's event loop. Reads use one reusable buffer; returning false from onread pauses libuv
  * until that buffer has been consumed. No idle read occupies a foreign-call worker.
  */
sealed abstract private[kyo] class ProcessNodeInputStream private (
    descriptor: Int,
    bindings: ProcessPipeBindings,
    buffer: Uint8Array,
    completion: Promise.Unsafe[Unit, Any],
    initialReadiness: Promise.Unsafe[Unit, Any]
) extends InputStream:

    protected val socket: ProcessNodeSocket
    private var position  = 0
    private var limit     = 0
    private var ended     = false
    private var closed    = false
    private var cancelled = false
    private var remaining = 0
    private var failure   = Maybe.empty[IOException]
    private var readiness = initialReadiness

    protected def receive(count: Int)(using AllowUnsafe): Boolean =
        position = 0
        limit = count
        readiness.completeDiscard(Result.succeed(()))
        false
    end receive

    private def listen()(using AllowUnsafe): Unit =
        discard(socket.on(
            "end",
            { (_: js.Any) =>
                ended = true
                readiness.completeDiscard(Result.succeed(()))
            }
        ))
        discard(socket.on(
            "close",
            { (_: js.Any) =>
                ended = true
                readiness.completeDiscard(Result.succeed(()))
                completion.completeDiscard(Result.succeed(()))
            }
        ))
        discard(socket.on(
            "error",
            { (error: js.Any) =>
                failure = Present(new IOException("Process pipe read failed", js.JavaScriptException(error)))
                ended = true
                readiness.completeDiscard(Result.succeed(()))
            }
        ))
    end listen

    /** Stops reads at the current buffered prefix without closing the reader or signalling a pipe holder. */
    def cancelRead()(using AllowUnsafe): Unit =
        if !closed && !cancelled then
            discard(socket.pause())
            cancelled = true
            readiness.completeDiscard(Result.succeed(()))
            // A destroyed socket may already have released its descriptor. Never inspect its cached numeric value then.
            if !socket.destroyed then
                val count = bindings.available_descriptor(descriptor)
                if count < 0 then
                    failure = Present(new IOException(s"Process pipe snapshot failed: errno ${-count}"))
                else remaining = count
            end if
        end if
    end cancelRead

    private def fill()(using AllowUnsafe): Unit =
        if position == limit && !closed then
            position = 0
            limit = 0
            if cancelled then
                if remaining > 0 && !socket.destroyed then
                    // pause() and this read run on the same event loop. The descriptor is nonblocking and no other reader owns it;
                    // read only the finite prefix observed at cancellation, even if a writer continues producing afterwards.
                    try
                        limit = ProcessNodeFs.readSync(descriptor, buffer, 0, math.min(remaining, buffer.length), null)
                        remaining -= limit
                        if limit == 0 then remaining = 0
                    catch
                        case error: js.JavaScriptException =>
                            remaining = 0
                            failure = Present(new IOException("Process pipe drain failed", error))
                    end try
                end if
            else if !ended then
                if readiness.poll().isDefined then readiness = Promise.Unsafe.initUninterruptible[Unit, Any]()
                discard(socket.resume())
            end if
        end if
    end fill

    private def emptyRead: Int =
        failure.foreach(error => throw error)
        if closed || ended || cancelled then -1 else -2

    override def read(): Int =
        // Unsafe: java.io invokes this imperative adapter directly.
        import AllowUnsafe.embrace.danger
        fill()
        if position < limit then
            val value = buffer(position).toInt
            position += 1
            value
        else emptyRead
        end if
    end read

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
        // Unsafe: java.io invokes this imperative adapter directly.
        import AllowUnsafe.embrace.danger
        if offset < 0 || length < 0 || offset > bytes.length - length then throw new IndexOutOfBoundsException
        if length == 0 then 0
        else
            fill()
            if position < limit then
                val count = math.min(length, limit - position)
                var index = 0
                while index < count do
                    bytes(offset + index) = buffer(position + index).toByte
                    index += 1
                position += count
                count
            else
                val result = emptyRead
                if result == -2 then 0 else result
            end if
        end if
    end read

    override def available(): Int = limit - position + remaining

    /** Suspends on libuv readiness instead of polling an idle descriptor or occupying a foreign worker. */
    def readAsync(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Int < Async =
        Loop(()) { _ =>
            Sync.defer {
                val count = read(bytes, offset, length)
                if count != 0 || length == 0 then Loop.done(count)
                else readiness.safe.get.andThen(Loop.continue(()))
            }
        }

    def stream(using Frame): Stream[Byte, Async & Scope] = ProcessStreams.fromReader(readAsync, () => closeAndWait())

    /** Control requests are bounded by ProcessControl to three one-byte writes over the lifetime of this socket. */
    def sendRequest(value: Int)(using AllowUnsafe): Unit =
        if closed || socket.destroyed then throw new IOException("Process control socket closed")
        val bytes = new Uint8Array(1)
        bytes(0) = value.toShort
        discard(socket.write(bytes))
    end sendRequest

    override def close(): Unit =
        // Unsafe: java.io close wakes effectful readers from an imperative host call.
        import AllowUnsafe.embrace.danger
        if !closed then
            closed = true
            position = 0
            limit = 0
            remaining = 0
            readiness.completeDiscard(Result.succeed(()))
            discard(socket.destroy())
        end if
    end close

    /** Waits for libuv to finish releasing the descriptor, including a close already requested through java.io. */
    def closeAndWait()(using Frame): Unit < Async = Sync.defer(close()).andThen(completion.safe.get)
end ProcessNodeInputStream

private[kyo] object ProcessNodeInputStream:
    /** Takes ownership of a descriptor transferred from native acquisition, including constructor failures. */
    def init(descriptor: Int, bindings: ProcessPipeBindings)(using AllowUnsafe): ProcessNodeInputStream =
        init(descriptor, bindings, false)

    def initControl(descriptor: Int, bindings: ProcessPipeBindings)(using AllowUnsafe): ProcessNodeInputStream =
        init(descriptor, bindings, true)

    private def init(descriptor: Int, bindings: ProcessPipeBindings, writable: Boolean)(using AllowUnsafe): ProcessNodeInputStream =
        try
            val buffer = new Uint8Array(8192)
            val stream = new ProcessNodeInputStream(
                descriptor,
                bindings,
                buffer,
                Promise.Unsafe.initUninterruptible[Unit, Any](),
                Promise.Unsafe.initUninterruptible[Unit, Any]()
            ):
                protected val socket = new ProcessNodeSocket(js.Dynamic.literal(
                    fd = descriptor,
                    readable = true,
                    writable = writable,
                    onread = js.Dynamic.literal(
                        buffer = buffer,
                        callback = { (count: Int, _: Uint8Array) => receive(count) }: js.Function2[Int, Uint8Array, Boolean]
                    )
                ))
            stream.listen()
            stream
        catch
            case failure: Throwable =>
                // The fixed Unix Socket options have no validation after fd adoption. An open failure leaves the fd with its caller.
                try ProcessNodeFs.closeSync(descriptor)
                catch case error: Throwable => failure.addSuppressed(error)
                throw failure
end ProcessNodeInputStream
