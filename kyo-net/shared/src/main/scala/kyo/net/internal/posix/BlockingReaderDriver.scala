package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConnectionIoException
import kyo.net.NetErrno
import kyo.net.NetException
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Fallback driver for the one driver-selection cell the readiness poller cannot serve: an epoll backend reading a regular-file stdin (`S_ISREG`).
  *
  * epoll rejects a regular file with `EPERM`, while io_uring (async file read) and kqueue (vnode `EVFILT_READ`) accept it, so only the
  * epoll-plus-regular-file case needs a non-poller read path. This driver WRAPS the real [[PollerIoDriver]] and swaps ONLY the read side: it
  * issues a single `@Ffi.blocking` [[SocketBindings.read]] on a dedicated fiber and completes the parked promise with the bytes (`PeerFin`
  * on the 0-return EOF, `Failed` on the -1 errno), honoring the one-read-per-handle [[IoDriver]] contract. A regular-file read returns
  * near-instantly, so the leaf fiber never busy-waits or starves the scheduler.
  *
  * A `read(2)` in flight cannot be withdrawn, so the read is owned like any other driver op. The promise is parked in the handle's
  * `life.read`, where `cancel` and `closeHandle` fail it with [[Closed]] at once; the read then delivers to whichever promise occupies the
  * slot when it returns, so a read armed while a cancelled one is still blocked takes its bytes instead of issuing a second `read(2)` into
  * the same buffer. A result that returns with no promise parked is queued with `life.carry`, since its bytes are already consumed from the
  * fd, and the next [[awaitRead]] takes it before reading again. The read holds the handle's guard from arming until its bytes are copied
  * out, so a close frees `readBuffer` only after the `read(2)` has returned.
  *
  * Every other method delegates to the wrapped driver: [[write]] sends on `writeFd = 1` (a regular-file or pipe stdout completes
  * synchronously, so no separate blocking-writer path is needed).
  */
final private[net] class BlockingReaderDriver private[posix] (real: IoDriver[PosixHandle], sockets: SocketBindings)
    extends IoDriver[PosixHandle]:

    // Handles with a read(2) in flight: at most one per handle, since the read writes into the handle's single readBuffer.
    private val reading = java.util.concurrent.ConcurrentHashMap.newKeySet[PosixHandle]()

    def label: String = "BlockingReaderDriver"

    def handleLabel(handle: PosixHandle): String = real.handleLabel(handle)

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] = real.start()

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.life.read.park(promise, closed(handle, "released")) then startRead(handle)

    /** Serve the parked promise from a carried result, or issue the handle's read, unless a read is already in flight, whose completion
      * then serves it.
      */
    private def startRead(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if reading.add(handle) then
            handle.life.takeCarried() match
                case Present(outcome) => deliver(handle, Result.succeed(outcome))
                case Absent           =>
                    if handle.beginDispatch() then
                        // Unsafe: Fiber.Unsafe.init spawns the read carrier without re-entering the effect system. On JVM and Native the
                        // @Ffi.blocking read parks this carrier and returns an already-completed fiber; on JS it resolves from a libuv worker.
                        discard(Fiber.Unsafe.init {
                            val readFiber = sockets.read(handle.readFd, handle.readBuffer, handle.readBufferSize.toLong)
                            if readFiber.done() then readFiber.poll().foreach(finishRead(handle, _))
                            else readFiber.onComplete(finishRead(handle, _))
                        })
                    else
                        discard(reading.remove(handle))
                        handle.life.read.fail(closed(handle, "closed"))
            end match
    end startRead

    private def finishRead(handle: PosixHandle, result: Result[Any, Ffi.Outcome[Long] < Any])(using AllowUnsafe, Frame): Unit =
        val outcome = toOutcome(handle, result)
        if handle.endDispatch() then deliver(handle, Result.fail(closed(handle, "closed")))
        else deliver(handle, outcome)
    end finishRead

    /** Hand `outcome` to the parked promise, or carry it to the next [[awaitRead]] when none is parked, then leave the in-flight set. */
    private def deliver(handle: PosixHandle, outcome: Result[Closed, ReadOutcome])(using AllowUnsafe, Frame): Unit =
        handle.life.read.take() match
            case Present(promise) =>
                outcome match
                    case Result.Success(read) => promise.completeDiscard(Result.succeed(read))
                    case Result.Failure(e)    => promise.completeDiscard(Result.fail(e))
                    case Result.Panic(t)      => promise.completeDiscard(Result.panic(t))
            case Absent =>
                outcome match
                    case Result.Success(read) => discard(handle.life.carry(read))
                    case _                    => ()
        end match
        // An awaitRead that parks while the handle is still in the in-flight set returns without reading, so the re-check below issues it.
        discard(reading.remove(handle))
        if handle.life.read.current.nonEmpty then startRead(handle)
    end deliver

    /** Map the `read(2)` result to the delivered outcome, copying the bytes out of `readBuffer` while the guard is still held. */
    private def toOutcome(handle: PosixHandle, result: Result[Any, Ffi.Outcome[Long] < Any])(using
        AllowUnsafe,
        Frame
    ): Result[Closed, ReadOutcome] =
        result match
            case Result.Success(pending) =>
                val read = pending.eval
                val n    = read.value.toInt
                if n < 0 then
                    // read(2) returns -1 on error; errorCode holds the errno. A zero return is EOF, even if a stale errno is non-zero.
                    Result.succeed(failed(handle, new NetErrno(read.errorCode)))
                else if n == 0 then Result.succeed(ReadOutcome.PeerFin)
                else Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Buffer.copyToArray[Byte](handle.readBuffer, 0, n))))
                end if
            case Result.Failure(e) => Result.succeed(failed(handle, e.toString))
            case Result.Panic(t)   => Result.panic(t)

    private def failed(handle: PosixHandle, errno: NetErrno): ReadOutcome =
        ReadOutcome.Failed(
            NetConnectionIoException(s"connection ${handleLabel(handle)}", NetConnectionIoException.Operation.Receive, errno)(using
                handle.createdAt
            )
        )

    private def failed(handle: PosixHandle, detail: String): ReadOutcome =
        ReadOutcome.Failed(
            NetConnectionIoException(s"connection ${handleLabel(handle)}", NetConnectionIoException.Operation.Receive, detail)(using
                handle.createdAt
            )
        )

    private def closed(handle: PosixHandle, reason: String)(using Frame): Closed =
        Closed(handleLabel(handle), handle.createdAt, reason)

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        real.awaitWritable(handle, promise)

    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        real.awaitConnect(handle, promise)

    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // BlockingReaderDriver wraps PollerIoDriver for the regular-file stdin case; delegate accept to the real poller driver.
        real.awaitAccept(handle, promise)

    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        real.write(handle, data, offset)

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        real.cancel(handle)

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        real.closeHandle(handle)

    def releaseFd(handle: PosixHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        real.releaseFd(handle, closeFd)

    def closeListener(handle: PosixHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        real.closeListener(handle, closeFd)

    def runOwned(handle: PosixHandle)(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        real.runOwned(handle)(op)

    def close()(using AllowUnsafe, Frame): Unit =
        real.close()

end BlockingReaderDriver

private[net] object BlockingReaderDriver:

    /** Wrap `real` with the regular-file blocking-read fallback, loading the real socket bindings for the fd-generic `read`. */
    def init(real: IoDriver[PosixHandle])(using AllowUnsafe): BlockingReaderDriver =
        new BlockingReaderDriver(real, Ffi.load[SocketBindings])

end BlockingReaderDriver
