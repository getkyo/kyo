package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** An [[IoDriver]] for one connection whose write outcomes, writable readiness and handle close are decided by the test, so a [[WritePump]]
  * leaf picks every transition itself and no kernel buffer size or poll timing reaches the outcome.
  *
  * Its default is the plaintext posix contract (PollerIoDriver's raw send path), since a fake that drifts from a real driver proves nothing
  * about the pump:
  *
  *   - `write` of an empty span is `Done`; so is a write at `offset >= data.size` when no step is scripted.
  *   - `write` after `closeHandle` is `Error`.
  *   - Otherwise the next [[ScriptedIoDriver.Step]] decides. `Accept(n)` takes up to `n` bytes from `offset`: `Done` when that reaches the end
  *     of the span, else `Partial(data, offset + n)` on the same span reference, so `Accept(0)` is the EAGAIN `Partial(data, offset)`.
  *     `TailFull` is `TailPartial(data, offset)` and `Fail` is `Error`. Once the script is exhausted every write is taken in full.
  *   - Accepted bytes are appended to [[wire]] in order: they are what the kernel took.
  *   - `awaitWritable` after `closeHandle` fails the promise `Closed` at once. Otherwise the promise is held until the test settles it with
  *     [[signalWritable]] or [[failWritable]], standing in for the poll carrier's later completion. The posix drivers keep one writable
  *     promise per handle, so a second wait while one is held would strand the first; it is counted in [[overlappingWritableWaits]].
  *   - `closeHandle` and `cancel` fail a held writable `Closed` synchronously, as the posix `deregisterFds` does.
  *
  * The other drivers diverge, and the fake reaches the divergences the pump must survive:
  *
  *   - `AcceptPark(n)` returns `Partial` even when the bytes reach the end of the span, and a scripted step also decides a write at
  *     `offset == data.size`: JsIoDriver reports a write Node buffered as `Partial(data, data.size)`, and NioIoDriver's TLS path has no early
  *     `Done` at the span's end.
  *   - [[completeNextWaitInline]] completes the next writable wait with `Success` inside `awaitWritable`, as IoUringDriver does for a wait it
  *     can answer at once (including on a closed handle).
  *   - [[failWritable]] fails a held wait later rather than at once, as JsIoDriver does after `end()`.
  *
  * Not modelled: the poller's TLS path answers a write on a closed handle with `Done`, leaving the pump Idle until its channel closes.
  *
  * Every callback runs inline on the caller, so a leaf is exactly the interleaving it spells out.
  */
final class ScriptedIoDriver(steps: ScriptedIoDriver.Step*) extends IoDriver[Unit]:
    import ScriptedIoDriver.Step

    private var script: List[Step]                                              = steps.toList
    private var held: Maybe[Promise.Unsafe[Unit, Abort[Closed | NetException]]] = Absent
    private var handleClosed: Boolean                                           = false
    private var inlineNextWait: Boolean                                         = false
    private var beforeNextWrite: Maybe[() => Unit]                              = Absent
    private var afterNextWrite: Maybe[() => Unit]                               = Absent
    private var writeLog: Chunk[(Span[Byte], Int, WriteResult)]                 = Chunk.empty
    private var accepted: Chunk[Byte]                                           = Chunk.empty
    private var writableWaitCount: Int                                          = 0
    private var overlapping: Int                                                = 0
    private var loop: Maybe[Promise.Unsafe[Unit, Any]]                          = Absent

    /** Every write call in order: the span reference, the offset it was presented at, and the result returned. */
    def writes: Chunk[(Span[Byte], Int, WriteResult)] = writeLog

    /** Every byte a write accepted, in order. */
    def wire: Chunk[Byte] = accepted

    def writableWaits: Int = writableWaitCount

    def overlappingWritableWaits: Int = overlapping

    def holdsWritable: Boolean = held.nonEmpty

    /** Runs `action` at the start of the next write, before its outcome is decided, so a leaf can land an event between a resume and its
      * write.
      */
    def onNextWrite(action: () => Unit): Unit = beforeNextWrite = Present(action)

    /** Runs `action` once the next write's outcome is decided, before it returns, so a leaf can land an event between a write and the park
      * that follows it.
      */
    def afterNextWrite(action: () => Unit): Unit = afterNextWrite = Present(action)

    def completeNextWaitInline(): Unit = inlineNextWait = true

    def signalWritable()(using AllowUnsafe): Unit = settle(Result.succeed(()))

    def failWritable(error: Closed | NetException)(using AllowUnsafe): Unit = settle(Result.fail(error))

    private def settle(result: Result[Closed | NetException, Unit < Abort[Closed | NetException]])(using AllowUnsafe): Unit =
        val promise = held.getOrElse(throw new IllegalStateException("no writable wait is held"))
        held = Absent
        promise.completeDiscard(result)
    end settle

    private def closedError(using Frame): Closed = Closed(label, Frame.internal, "handle closed")

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        val promise = Promise.Unsafe.init[Unit, Any]()
        loop = Present(promise)
        promise
    end start

    def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        val before = beforeNextWrite
        beforeNextWrite = Absent
        before.foreach(_())
        val result =
            if data.isEmpty || (offset >= data.size && script.isEmpty) then WriteResult.Done
            else if handleClosed then WriteResult.Error
            else
                val step = script match
                    case next :: rest =>
                        script = rest
                        next
                    case Nil => Step.Accept(data.size - offset)
                step match
                    case Step.Accept(n) =>
                        val end = take(data, offset, n)
                        if end == data.size then WriteResult.Done else WriteResult.Partial(data, end)
                    case Step.AcceptPark(n) => WriteResult.Partial(data, take(data, offset, n))
                    case Step.TailFull      => WriteResult.TailPartial(data, offset)
                    case Step.Fail          => WriteResult.Error
                end match
        writeLog = writeLog.append((data, offset, result))
        val after = afterNextWrite
        afterNextWrite = Absent
        after.foreach(_())
        result
    end write

    private def take(data: Span[Byte], offset: Int, n: Int): Int =
        val end = math.min(data.size, offset + n)
        if end > offset then accepted = accepted.concat(Chunk.from(data.slice(offset, end).toArray))
        end
    end take

    def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        writableWaitCount += 1
        if inlineNextWait then
            inlineNextWait = false
            promise.completeDiscard(Result.succeed(()))
        else if handleClosed then promise.completeDiscard(Result.fail(closedError))
        else
            if held.nonEmpty then overlapping += 1
            held = Present(promise)
        end if
    end awaitWritable

    def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit =
        val pending = held
        held = Absent
        pending.foreach(_.completeDiscard(Result.fail(closedError)))
    end cancel

    def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit =
        handleClosed = true
        cancel(handle)

    def close()(using AllowUnsafe, Frame): Unit =
        closeHandle(())
        loop.foreach(_.completeDiscard(Result.succeed(())))

    def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        throw new UnsupportedOperationException("ScriptedIoDriver drives writes only")

    def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        throw new UnsupportedOperationException("ScriptedIoDriver drives writes only")

    def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        throw new UnsupportedOperationException("ScriptedIoDriver drives writes only")

    def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()

    def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        try cancel(handle)
        finally releaseFd(handle, closeFd)

    def label: String = "ScriptedIoDriver"

    def handleLabel(handle: Unit): String = "scripted"

end ScriptedIoDriver

object ScriptedIoDriver:

    /** One scripted write outcome, consumed by the next write of a non-empty span on an open handle. */
    enum Step derives CanEqual:
        case Accept(bytes: Int)
        case AcceptPark(bytes: Int)
        case TailFull
        case Fail
    end Step

end ScriptedIoDriver
