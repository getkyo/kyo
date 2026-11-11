package kyo.internal

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.*
import scala.annotation.tailrec

/** Owns a cancellable descriptor. Closing prevents new calls; the last native caller frees the handle.
  * This keeps Java stream close nonblocking even when a read or write is parked in the kernel.
  */
final private[kyo] class ProcessPipe private (handle: Ffi.Handle[ProcessPipeHandle], bindings: ProcessPipeBindings)(using Frame):

    // The sign bit closes admission. The remaining bits count calls that can still touch native memory, including close itself.
    private val calls      = new AtomicInteger(0)
    private val completion =
        import AllowUnsafe.embrace.danger
        Promise.Unsafe.initUninterruptible[Unit, Any]()

    @tailrec
    private def tryRetain()(using AllowUnsafe): Boolean =
        val count = calls.get()
        if count < 0 then false
        else if calls.compareAndSet(count, count + 1) then true
        else tryRetain()
    end tryRetain

    private def release()(using AllowUnsafe): Unit =
        if calls.decrementAndGet() == Int.MinValue then
            val result = Result { checked(ProcessPipe.result(bindings.release(handle)), "release"): Unit }
            completion.completeDiscard(result.map(Kyo.lift))
            result.getOrThrow

    private def use[A](body: => A)(using AllowUnsafe): A =
        if !tryRetain() then throw new IOException("Stream closed")
        try body
        finally release()
    end use

    private def checked(value: Int, operation: String): Int =
        if value < 0 then throw new IOException(FfiErrno(-value, "kyo_process_pipe", operation))
        value

    @tailrec
    private def cancelCall(drain: Boolean)(using AllowUnsafe): Unit =
        val result = if drain then bindings.cancel(handle) else bindings.stop(handle)
        if result == (if Platform.isMac then -35 else -11) then
            // A reader holds the native mutex only around a nonblocking syscall. Its kernel wait never holds the mutex.
            Thread.onSpinWait()
            cancelCall(drain)
        else checked(result, if drain then "cancel" else "stop"): Unit
        end if
    end cancelCall

    /** Preserve the output prefix already buffered when the process scope ends. */
    def cancelRead()(using AllowUnsafe): Unit =
        if tryRetain() then
            try cancelCall(true)
            finally release()

    @tailrec
    private def beginClose()(using AllowUnsafe): Boolean =
        val count = calls.get()
        if count < 0 then false
        else if calls.compareAndSet(count, (count | Int.MinValue) + 1) then true
        else beginClose()
    end beginClose

    def close()(using AllowUnsafe): Unit =
        if beginClose() then
            try cancelCall(false)
            finally release()

    /** Closes admission and waits until every retained native call has returned and the handle has been freed. */
    def closeAndWait(): Unit < Async =
        Sync.Unsafe.defer(Result(close())).map { result =>
            completion.safe.getResult.map { released =>
                result.getOrThrow
                released.getOrThrow
            }
        }

    private def checkRange(bytes: Array[Byte], offset: Int, length: Int): Unit =
        if offset < 0 || length < 0 || offset > bytes.length - length then throw new IndexOutOfBoundsException()

    def read(bytes: Array[Byte], offset: Int, length: Int)(using AllowUnsafe): Int =
        checkRange(bytes, offset, length)
        use {
            if length == 0 then 0
            else
                val buffer = Buffer.alloc[Byte](length)
                try
                    val count = checked(ProcessPipe.result(bindings.read(handle, buffer, length)), "read")
                    buffer.copyToArray(bytes, offset, 0, count)
                    if count == 0 then -1 else count
                finally buffer.close()
                end try
        }
    end read

    def write(bytes: Array[Byte], offset: Int, length: Int)(using AllowUnsafe): Unit =
        checkRange(bytes, offset, length)
        use {
            if length > 0 then
                val buffer = Buffer.alloc[Byte](length)
                try
                    buffer.copyFromArray(bytes, offset, 0, length)
                    var written = 0
                    while written < length do
                        val count = checked(ProcessPipe.result(bindings.write(handle, buffer, written, length - written)), "write")
                        if count == 0 then throw new IOException("Process pipe write made no progress")
                        written += count
                    end while
                finally buffer.close()
                end try
        }
    end write

    def inputStream: InputStream = new InputStream:
        override def read(): Int =
            val bytes = new Array[Byte](1)
            if read(bytes, 0, 1) < 0 then -1 else bytes(0) & 0xff

        override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
            // Unsafe: java.io.InputStream is the external runtime boundary; its caller owns this stream.
            import AllowUnsafe.embrace.danger
            ProcessPipe.this.read(bytes, offset, length)
        end read

        override def close(): Unit =
            // Unsafe: closes admission and wakes native calls; the last retained call releases their handle.
            import AllowUnsafe.embrace.danger
            ProcessPipe.this.close()
        end close

    def outputStream: OutputStream = new OutputStream:
        override def write(value: Int): Unit = write(Array(value.toByte), 0, 1)

        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
            // Unsafe: java.io.OutputStream is the external runtime boundary; its caller owns this stream.
            import AllowUnsafe.embrace.danger
            ProcessPipe.this.write(bytes, offset, length)
        end write

        override def close(): Unit =
            // Unsafe: close wakes blocked writes without closing a descriptor while a native call uses it.
            import AllowUnsafe.embrace.danger
            ProcessPipe.this.close()
        end close
end ProcessPipe

private[kyo] object ProcessPipe:
    def acquire(launch: ProcessLaunch, slot: Int)(using Frame): ProcessPipe < (Sync & Scope) =
        // Unsafe: these JVM/Native downcalls complete synchronously inside the scope's acquisition step.
        Scope.acquireRelease(Sync.Unsafe.defer {
            val bindings = Ffi.load[ProcessPipeBindings]
            val error    = Buffer.alloc[Int](1)
            try
                result(launch.bindings.take_pipe(launch.handle, slot, error)) match
                    case Present(handle) => init(handle, bindings)
                    case Absent          => throw new IOException(s"Process pipe transfer failed: errno ${error.get(0)}")
            finally error.close()
            end try
        })(_.closeAndWait())

    def init(handle: Ffi.Handle[ProcessPipeHandle], bindings: ProcessPipeBindings)(using Frame, AllowUnsafe): ProcessPipe =
        try
            new ProcessPipe(handle, bindings)
        catch
            case failure: Throwable =>
                result(bindings.release(handle)): Unit
                throw failure

    // JVM/Native BlockingBridge.run executes the downcall synchronously and returns a completed promise. This file is absent on Node,
    // whose async foreign calls must instead be joined with Async before freeing argument buffers.
    private def result[A](call: Fiber.Unsafe[A, Any])(using Frame, AllowUnsafe): A = call.poll().get.getOrThrow.eval
end ProcessPipe
