package kyo.internal

import java.io.IOException
import java.io.OutputStream
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Owns a write descriptor on libuv. Safe input feeds await each write callback, so a child that stops reading cannot grow their queue.
  * The java.io boundary retains Node's asynchronous write semantics; its caller controls its own outstanding writes.
  */
final private[kyo] class ProcessNodeOutputStream private (socket: ProcessNodeSocket, completion: Promise.Unsafe[Unit, Any])
    extends OutputStream:

    private var failure = Maybe.empty[IOException]
    private var closing = false
    private var closed  = false
    private var pending = 0

    private def completeClose()(using AllowUnsafe): Unit =
        if closed && pending == 0 then completion.completeDiscard(Result.succeed(()))

    private def listen()(using AllowUnsafe): Unit =
        discard(socket.on(
            "error",
            { (error: js.Any) =>
                failure = Present(new IOException("Process stdin write failed", js.JavaScriptException(error)))
            }
        ))
        discard(socket.on(
            "close",
            { (_: js.Any) =>
                closed = true
                completeClose()
            }
        ))
    end listen

    def writePending(bytes: Array[Byte], offset: Int, length: Int)(using AllowUnsafe): Fiber.Unsafe[Unit, Any] =
        if offset < 0 || length < 0 || offset > bytes.length - length then throw new IndexOutOfBoundsException
        failure.foreach(error => throw error)
        if closing || closed || socket.destroyed then throw new IOException("Process stdin closed")
        val buffer = new Uint8Array(length)
        var index  = 0
        while index < length do
            buffer(index) = (bytes(offset + index) & 0xff).toShort
            index += 1
        val result = Promise.Unsafe.initUninterruptible[Unit, Any]()
        pending += 1
        try
            discard(socket.write(
                buffer,
                { (error: js.Any) =>
                    if error == null || js.isUndefined(error) then result.completeDiscard(Result.succeed(()))
                    else result.completeDiscard(Result.panic(new IOException("Process stdin write failed", js.JavaScriptException(error))))
                    pending -= 1
                    completeClose()
                }
            ))
        catch
            case error: Throwable =>
                pending -= 1
                completeClose()
                throw error
        end try
        result
    end writePending

    def writeAsync(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Unit < Async =
        // Unsafe: buffer submission and promise allocation happen when the write effect runs.
        Sync.Unsafe.defer(writePending(bytes, offset, length).safe.get)

    override def write(value: Int): Unit = write(Array(value.toByte), 0, 1)

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
        // Unsafe: java.io is an imperative host boundary; overrides cannot accept contextual evidence.
        import AllowUnsafe.embrace.danger
        discard(writePending(bytes, offset, length))
    end write

    /** Ends stdin after already submitted writes have flushed. */
    override def close(): Unit =
        if !closing && !closed then
            closing = true
            discard(socket.end())

    def finish()(using Frame): Unit < Async = Sync.defer(close()).andThen(completion.safe.get)

    /** Cancels queued writes and joins their callbacks and the descriptor's actual close. */
    def closeAndWait()(using Frame): Unit < Async =
        Sync.defer {
            closing = true
            discard(socket.destroy())
        }.andThen(completion.safe.get)
end ProcessNodeOutputStream

private[kyo] object ProcessNodeOutputStream:
    def init(descriptor: Int)(using AllowUnsafe): ProcessNodeOutputStream =
        try
            val stream = new ProcessNodeOutputStream(
                new ProcessNodeSocket(js.Dynamic.literal(fd = descriptor, readable = false, writable = true)),
                Promise.Unsafe.initUninterruptible[Unit, Any]()
            )
            stream.listen()
            stream
        catch
            case failure: Throwable =>
                try ProcessNodeFs.closeSync(descriptor)
                catch case error: Throwable => failure.addSuppressed(error)
                throw failure
end ProcessNodeOutputStream
