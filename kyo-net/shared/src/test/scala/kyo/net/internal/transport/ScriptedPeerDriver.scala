package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** An [[IoDriver]] for one connection whose peer the test plays, so the test decides when bytes leave and the pump's read is held until
  * the test delivers its outcome. A leaf of the shared connection layer spells out a close's interleaving with it: what is parked when
  * `close()` runs, which teardown source arrives next, and when the tail drains.
  *
  * Writes follow one of two models. With no `tailBound`, every write puts one byte straight into the kernel and parks the pump on
  * writability, the plaintext socket model. With a `tailBound`, a write is owned whole by the driver's tail, as the TLS and io_uring
  * drivers own theirs: `Done` while the tail has room, `TailPartial` once it holds `tailBound` bytes, and the bytes are owed until
  * [[peerReads]] hands them to the kernel.
  *
  * `cancel` fails a held writable and a held read `Closed`, as the real drivers do. Its counters are atomic because the grace timer fires
  * on the clock's carrier.
  */
final class ScriptedPeerDriver(tailBound: Maybe[Int] = Absent) extends IoDriver[Unit]:
    import AllowUnsafe.embrace.danger

    val written      = AtomicInt.Unsafe.init(0)
    val releaseCalls = AtomicInt.Unsafe.init(0)

    private val handleLife = HandleLife.init()

    /** What the handle owes the kernel and what the kernel took. */
    val ledger: OutboundLedger = handleLife.ledger

    @volatile private var parked: Maybe[Promise.Unsafe[Unit, Abort[Closed | NetException]]] = Absent
    @volatile private var read: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]]           = Absent

    /** The peer reads: the parked writable completes and the pump writes its next byte. */
    def peerReads()(using AllowUnsafe): Unit = signalWritable()

    /** The kernel takes `bytes` of the tail, which wakes a pump parked on it. */
    def peerReads(bytes: Int)(using AllowUnsafe): Unit =
        ledger.send(math.min(bytes.toLong, ledger.owed).toInt)
        signalWritable()

    /** The parked writable completes with no byte leaving, as a writable that finds the tail still full does. */
    def signalWritable()(using AllowUnsafe): Unit =
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
        tailBound match
            case Absent =>
                ledger.direct(1)
                if offset + 1 < data.size then WriteResult.Partial(data, offset + 1) else WriteResult.Done
            case Present(bound) =>
                val taken = math.max(0L, math.min(bound - ledger.owed, (data.size - offset).toLong)).toInt
                if taken > 0 then
                    ledger.accept(taken)
                    ledger.encode(taken, taken)
                if offset + taken == data.size then WriteResult.Done else WriteResult.TailPartial(data, offset + taken)
        end match
    end write

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

    def life(handle: Unit): HandleLife = handleLife

    def release(handle: Unit, mode: ReleaseMode, released: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Unit =
        discard(releaseCalls.incrementAndGet())
        released.completeUnitDiscard()
    end release

    def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()

    def runOwned(handle: Unit)(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        try cancel(handle)
        finally releaseFd(handle, closeFd)

    def close()(using AllowUnsafe, Frame): Unit = ()

    def label: String = "ScriptedPeerDriver"

    def handleLabel(handle: Unit): String = "scripted-peer"

end ScriptedPeerDriver
