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
  * issues a single `@Ffi.blocking` [[SocketBindings.read]] on a dedicated fiber and completes the deposited promise with the bytes (an empty
  * [[Span]] on the 0-return EOF, a [[Closed]] on the -1 errno), honoring the one-read-per-handle [[IoDriver]] contract. A regular-file read
  * returns near-instantly, so the leaf fiber never busy-waits or starves the scheduler.
  *
  * A `read(2)` in flight cannot be withdrawn, so the read is owned like any other driver op. The deposited promise sits on the handle's
  * `pendingReadPromise`, where [[cancel]] and [[closeHandle]] fail it with [[Closed]] at once; the read then delivers to whichever promise
  * occupies that slot when it returns, so a read armed while a cancelled one is still blocked takes its bytes instead of issuing a second
  * `read(2)` into the same buffer. A result that returns with no promise deposited is carried to the next [[awaitRead]], since its bytes
  * are already consumed from the fd; only a close drops it. The read holds the handle's dispatch guard from arming until its bytes are copied out, so a close frees
  * `readBuffer` only after the `read(2)` has returned.
  *
  * Every other method delegates to the wrapped driver: [[write]] sends on `writeFd = 1` (a regular-file or pipe stdout completes
  * synchronously, so no separate blocking-writer path is needed).
  */
final private[net] class BlockingReaderDriver private[posix] (real: IoDriver[PosixHandle], sockets: SocketBindings)
    extends IoDriver[PosixHandle]:

    // Handles with a read(2) in flight: at most one per handle, since the read writes into the handle's single readBuffer.
    private val reading = java.util.concurrent.ConcurrentHashMap.newKeySet[PosixHandle]()

    // A read(2) result that returned after a cancel with no promise deposited. The bytes are already consumed from the fd, so they are kept
    // for the next awaitRead; only a close drops them.
    private val carried = new java.util.concurrent.ConcurrentHashMap[PosixHandle, Result[Closed, ReadOutcome < Abort[Closed]]]()

    def label: String = "BlockingReaderDriver"

    def handleLabel(handle: PosixHandle): String = real.handleLabel(handle)

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] = real.start()

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        handle.pendingReadPromise.set(Present(promise))
        startRead(handle)

    /** Serve the deposited promise from a carried result, or issue the handle's read, unless a read is already in flight, whose completion
      * then serves it.
      */
    private def startRead(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if reading.add(handle) then
            if handle.isClosing() then
                discard(carried.remove(handle))
                discard(reading.remove(handle))
                failPendingRead(handle, "closed")
            else
                Maybe(carried.remove(handle)) match
                    case Present(outcome) => deliver(handle, outcome)
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
                            failPendingRead(handle, "closed")
                end match
            end if
    end startRead

    private def finishRead(handle: PosixHandle, result: Result[Any, Ffi.Outcome[Long] < Any])(using AllowUnsafe, Frame): Unit =
        val outcome = toOutcome(handle, result)
        if handle.endDispatch() then deliver(handle, Result.fail(closed(handle, "closed")))
        else deliver(handle, outcome)
    end finishRead

    /** Hand `outcome` to the deposited promise, or carry it to the next [[awaitRead]] when none is deposited, then leave the in-flight set. */
    private def deliver(handle: PosixHandle, outcome: Result[Closed, ReadOutcome < Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        handle.pendingReadPromise.getAndSet(Absent) match
            case Present(promise) => promise.completeDiscard(outcome)
            case Absent           =>
                outcome match
                    case Result.Failure(_) => ()
                    case _                 =>
                        discard(carried.put(handle, outcome))
                        // Pairs with closeHandle's removal after it sets the close bit: whichever runs second drops the result.
                        if handle.isClosing() then discard(carried.remove(handle))
                end match
        end match
        // An awaitRead that deposits while the handle is still in the in-flight set returns without reading, so the re-check below issues it.
        discard(reading.remove(handle))
        if handle.pendingReadPromise.get().nonEmpty then startRead(handle)
    end deliver

    /** Map the `read(2)` result to the delivered outcome, copying the bytes out of `readBuffer` while the dispatch guard is still held. */
    private def toOutcome(handle: PosixHandle, result: Result[Any, Ffi.Outcome[Long] < Any])(using
        AllowUnsafe,
        Frame
    ): Result[Closed, ReadOutcome < Abort[Closed]] =
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

    private def failPendingRead(handle: PosixHandle, reason: String)(using AllowUnsafe, Frame): Unit =
        handle.pendingReadPromise.getAndSet(Absent).foreach(_.completeDiscard(Result.fail(closed(handle, reason))))

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
        failPendingRead(handle, "canceled")
        real.cancel(handle)

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        failPendingRead(handle, "closed")
        real.closeHandle(handle)
        discard(carried.remove(handle))
    end closeHandle

    def releaseFd(handle: PosixHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        real.releaseFd(handle, closeFd)

    def closeListener(handle: PosixHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        real.closeListener(handle, closeFd)

    def close()(using AllowUnsafe, Frame): Unit =
        real.close()

end BlockingReaderDriver

private[net] object BlockingReaderDriver:

    /** Wrap `real` with the regular-file blocking-read fallback, loading the real socket bindings for the fd-generic `read`. */
    def init(real: IoDriver[PosixHandle])(using AllowUnsafe): BlockingReaderDriver =
        new BlockingReaderDriver(real, Ffi.load[SocketBindings])

end BlockingReaderDriver
