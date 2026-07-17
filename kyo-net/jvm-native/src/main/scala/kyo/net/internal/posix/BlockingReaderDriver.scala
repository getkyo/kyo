package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Fallback driver for the one driver-selection cell the readiness poller cannot serve: an epoll backend reading a regular-file stdin (`S_ISREG`).
  *
  * epoll rejects a regular file with `EPERM`, while io_uring (async file read) and kqueue (vnode `EVFILT_READ`) accept it, so only the
  * epoll-plus-regular-file case needs a non-poller read path. This driver WRAPS the real [[PollerIoDriver]] and swaps ONLY [[awaitRead]]: it
  * issues a single `@Ffi.blocking` [[SocketBindings.read]] on a dedicated fiber and completes the deposited promise with the bytes (an empty
  * [[Span]] on the 0-return EOF, a [[Closed]] on the -1 errno), honoring the one-read-per-handle [[IoDriver]] contract. A regular-file read
  * returns near-instantly, so the leaf fiber never busy-waits or starves the scheduler.
  *
  * Every other method delegates to the wrapped driver: [[write]] sends on `writeFd = 1` (a regular-file or pipe stdout completes
  * synchronously, so no separate blocking-writer path is needed), and `awaitWritable` / `awaitConnect` / `cancel` / `closeHandle` / `close`
  * pass through unchanged.
  */
final private[net] class BlockingReaderDriver private (real: IoDriver[PosixHandle], sockets: SocketBindings)
    extends IoDriver[PosixHandle]:

    def label: String = "BlockingReaderDriver"

    def handleLabel(handle: PosixHandle): String = real.handleLabel(handle)

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] = real.start()

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // One read per handle (the IoDriver contract). The blocking read of a regular file returns near-instantly;
        // its result is consumed via onComplete (or done()/poll() inline on JVM/Native) without re-entering the
        // effect system. The spawned carrier runs the read and immediately registers the completion callback.
        // Unsafe: Fiber.Unsafe.init spawns the read carrier without re-entering the effect system. The thunk body
        // is plain Scala: it calls sockets.read to get the @Ffi.blocking fiber, then consumes the result inline
        // via done()/poll() on JVM/Native (where the blocking task completes before the scheduler returns the
        // fiber to the caller) or via onComplete on JS (where the blocking task is genuinely async).
        discard(Fiber.Unsafe.init {
            val readFiber = sockets.read(handle.readFd, handle.readBuffer, handle.readBufferSize.toLong)
            def deliver(result: Ffi.Outcome[Long]): Unit =
                val n = result.value.toInt
                if n < 0 then
                    // read(2) returns -1 on error; errorCode holds the errno.
                    // A zero return is EOF (not an error), even if a stale errno is non-zero.
                    promise.completeDiscard(Result.fail(Closed(
                        label,
                        summon[Frame],
                        s"read failed fd=${handle.readFd} errno=${result.errorCode}"
                    )))
                else if n == 0 then
                    // EOF on a regular file: orderly peer close.
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                else
                    val arr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
                    promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                end if
            end deliver
            if readFiber.done() then
                // JVM/Native inline-completion path: the @Ffi.blocking read completed synchronously.
                // Extract the result via poll() without parking.
                readFiber.poll() match
                    case Present(Result.Success(withError)) => deliver(withError.eval)
                    case Present(Result.Failure(e)) =>
                        promise.completeDiscard(Result.fail(Closed(
                            label,
                            summon[Frame],
                            s"read fiber failed fd=${handle.readFd}: $e"
                        )))
                    case Present(Result.Panic(t)) =>
                        promise.completeDiscard(Result.panic(t))
                    case Absent => ()
            else
                // The @Ffi.blocking read fiber is genuinely pending (it did not inline-complete): register onComplete to deliver the
                // result when the blocking read worker completes. This driver operates on the JVM/Native-only PosixHandle, hence its jvm-native home.
                readFiber.onComplete {
                    case Result.Success(withError) => deliver(withError.eval)
                    case Result.Failure(e) =>
                        promise.completeDiscard(Result.fail(Closed(
                            label,
                            summon[Frame],
                            s"read fiber failed fd=${handle.readFd}: $e"
                        )))
                    case Result.Panic(t) =>
                        promise.completeDiscard(Result.panic(t))
                }
            end if
        })
    end awaitRead

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        real.awaitWritable(handle, promise)

    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        real.awaitConnect(handle, promise)

    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // BlockingReaderDriver wraps PollerIoDriver for the regular-file stdin case; delegate accept to the real poller driver.
        real.awaitAccept(handle, promise)

    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        real.write(handle, data, offset)

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        real.cancel(handle)

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        real.closeHandle(handle)

    def close()(using AllowUnsafe, Frame): Unit =
        real.close()

end BlockingReaderDriver

private[net] object BlockingReaderDriver:

    /** Wrap `real` with the regular-file blocking-read fallback, loading the real socket bindings for the fd-generic `read`. */
    def init(real: IoDriver[PosixHandle])(using AllowUnsafe): BlockingReaderDriver =
        new BlockingReaderDriver(real, Ffi.load[SocketBindings])

end BlockingReaderDriver
