package kyo.internal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** The supervisor is the sole owner of its command. This channel carries requests and acknowledged outcomes, never cached process IDs
  * used for signalling. Each request is sent at most once, bounding even a nonblocking transport's pending writes to three bytes.
  */
final private[kyo] class ProcessControl private (
    send: Int => Unit,
    closeTransport: () => Unit < Async,
    requests: AtomicInteger,
    exit: Promise.Unsafe[ExitCode, Any],
    release: Promise.Unsafe[Unit, Any],
    reader: Fiber.Unsafe[Unit, Any]
)(using Frame):

    private def request(value: Int)(using AllowUnsafe): Unit =
        val bit = 1 << value
        if (requests.getAndUpdate(_ | bit) & bit) == 0 then
            try send(value)
            catch
                case error: Throwable =>
                    exit.completeDiscard(Result.panic(error))
                    release.completeDiscard(Result.panic(error))
                    throw error
        end if
    end request

    def terminate()(using AllowUnsafe): Unit = request(1)

    def kill()(using AllowUnsafe): Unit = request(2)

    def waitFor: Fiber.Unsafe[ExitCode, Any] = exit

    /** Completes only on RELEASED(0), after the supervisor has cleaned its group and reaped the leader. */
    def releaseGroup(): Unit < Async =
        // Unsafe: the control request is sent only when the release effect runs.
        Sync.Unsafe.defer(request(3)).andThen(release.safe.get)

    /** Closing the transport also releases an abandoned acquisition. Join the reader before its buffers can be discarded. */
    def close(): Unit < Async =
        Abort.run[Throwable](closeTransport()).map { result =>
            Sync.Unsafe.defer(reader.uninterruptible().safe.getResult).map { _ => result.getOrThrow }
        }
end ProcessControl

private[kyo] object ProcessControl:
    def init(
        read: (Array[Byte], Int, Int) => Int < Async,
        send: Int => Unit,
        closeTransport: () => Unit < Async
    )(using Frame, AllowUnsafe): ProcessControl =
        val requests = new AtomicInteger(0)
        val exit     = Promise.Unsafe.initUninterruptible[ExitCode, Any]()
        val release  = Promise.Unsafe.initUninterruptible[Unit, Any]()
        val reader   = Fiber.Unsafe.init(receive(read, exit, release))
        reader.onComplete {
            case Result.Panic(error) =>
                exit.completeDiscard(Result.panic(error))
                release.completeDiscard(Result.panic(error))
            case _ => ()
        }
        new ProcessControl(send, closeTransport, requests, exit, release, reader)
    end init

    private def receive(
        read: (Array[Byte], Int, Int) => Int < Async,
        exit: Promise.Unsafe[ExitCode, Any],
        release: Promise.Unsafe[Unit, Any]
    )(using Frame): Unit < Async = Sync.Unsafe.defer {
        // Unsafe: protocol buffer mutation and promise completion run on the owned reader fiber.
        val bytes = new Array[Byte](8)
        val frame = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        Loop(0) { offset =>
            read(bytes, offset, bytes.length - offset).map { count =>
                if count < 0 then throw new IOException("Process supervisor closed before acknowledging release")
                else if count == 0 then throw new IOException("Process control read returned without data or EOF")
                else if offset + count < bytes.length then Loop.continue(offset + count)
                else
                    val kind  = frame.getInt(0)
                    val value = frame.getInt(4)
                    kind match
                        case 2 =>
                            exit.completeDiscard(Result.succeed(if value == 0 then ExitCode.Success else ExitCode.Failure(value)))
                            Loop.continue(0)
                        case 3 =>
                            exit.completeDiscard(Result.succeed(ExitCode.Signaled(value)))
                            Loop.continue(0)
                        case 5               => throw new IOException(s"Process supervisor failed: errno $value")
                        case 6 if value == 0 =>
                            if exit.poll().isEmpty then throw new IOException("Process supervisor released without an exit status")
                            release.completeDiscard(Result.succeed(()))
                            Loop.done
                        case 6 => throw new IOException(s"Process supervisor cleanup failed: errno $value")
                        case _ => throw new IOException(s"Unexpected process supervisor event: $kind")
                    end match
                end if
            }
        }
    }
    end receive
end ProcessControl
