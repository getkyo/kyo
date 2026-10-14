package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** An [[IoDriver]] for one connection whose peer the test plays: every write takes one byte and parks the pump on writability, so the test
  * decides when the peer reads, and the pump's read is held until the test delivers its outcome. A leaf of the shared connection layer
  * spells out a close's interleaving with it: what is parked when `close()` runs, which teardown source arrives next, and when the tail
  * drains.
  *
  * `cancel` fails a held writable and a held read `Closed`, as the real drivers do. Its counters are atomic because the grace timer fires
  * on the clock's carrier.
  */
final class ScriptedPeerDriver extends IoDriver[Unit]:
    import AllowUnsafe.embrace.danger

    val written          = AtomicInt.Unsafe.init(0)
    val closeHandleCalls = AtomicInt.Unsafe.init(0)

    @volatile private var parked: Maybe[Promise.Unsafe[Unit, Abort[Closed | NetException]]] = Absent
    @volatile private var read: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]]           = Absent

    /** The peer reads: the parked writable completes and the pump writes its next byte. */
    def peerReads()(using AllowUnsafe): Unit =
        parked.foreach { p =>
            parked = Absent
            p.completeDiscard(Result.succeed(()))
        }

    /** The peer's next read event: completes the read the pump has armed. */
    def deliver(outcome: ReadOutcome)(using AllowUnsafe): Unit =
        read.foreach { p =>
            read = Absent
            p.completeDiscard(Result.succeed(outcome))
        }

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]

    def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        read = Present(promise)

    def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        parked = Present(promise)

    def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()

    def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()

    def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        discard(written.incrementAndGet())
        if offset + 1 < data.size then WriteResult.Partial(data, offset + 1) else WriteResult.Done

    def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit =
        val closed = Closed("scripted peer", summon[Frame], "canceled")
        parked.foreach { p =>
            parked = Absent
            p.completeDiscard(Result.fail(closed))
        }
        read.foreach { p =>
            read = Absent
            p.completeDiscard(Result.fail(closed))
        }
    end cancel

    def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCalls.incrementAndGet())

    def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()

    def runOwned(handle: Unit)(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        try cancel(handle)
        finally releaseFd(handle, closeFd)

    def close()(using AllowUnsafe, Frame): Unit = ()

    def label: String = "ScriptedPeerDriver"

    def handleLabel(handle: Unit): String = "scripted-peer"

end ScriptedPeerDriver
