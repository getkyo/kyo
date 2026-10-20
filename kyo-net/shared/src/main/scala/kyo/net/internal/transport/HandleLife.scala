package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException
import scala.annotation.tailrec

/** The one place a handle's parked promises and its release claim live, shared by every driver so the cancel contract cannot be implemented
  * as a subset.
  *
  * The contract, asserted per driver by `IoDriverCancelContractTest`:
  *   - [[cancel]] fails every promise parked through any slot, and every waiter registered with [[onCancel]], with `Closed`. It takes no
  *     claim, so a park after a bare cancel is live: the STARTTLS handshake parks on the same handle after `detachForUpgrade` cancels it.
  *   - [[claimRelease]] is the one release claim. A park after it fails at once ([[ParkedSlot.park]]), and the release that took it fails
  *     everything parked before it by calling [[cancel]].
  *   - [[armable]] is the gate every driver arm, send and recv checks on the carrier that applies it, so nothing is armed against a handle
  *     whose descriptor the release may already have closed.
  *
  * The pump's writable wait and the driver's own tail-flush re-arm are separate slots because they coexist by construction: the tail is
  * non-empty only while a flush hit `EAGAIN`, which is exactly when the pump may park on tail backpressure.
  *
  * [[carry]] queues what a read produced with no read armed (the blocking reader's cancelled `read(2)`, a failure io_uring decodes off any
  * read's own path), so the next read takes it in order before touching the descriptor.
  *
  * [[ledger]] is what the handle owes the kernel. A STARTTLS upgrade keeps the handle, so the upgraded connection reads the same one.
  */
final private[kyo] class HandleLife private (
    val guard: HandleGuard,
    val ledger: OutboundLedger,
    releaseClaim: AtomicBoolean.Unsafe,
    readCell: AtomicRef.Unsafe[ParkedSlot.State[ReadOutcome, Closed]],
    writableCell: AtomicRef.Unsafe[ParkedSlot.State[Unit, Closed | NetException]],
    flushCell: AtomicRef.Unsafe[ParkedSlot.State[Unit, Closed | NetException]],
    connectCell: AtomicRef.Unsafe[ParkedSlot.State[Unit, Closed | NetException]],
    acceptCell: AtomicRef.Unsafe[ParkedSlot.State[Int, Closed | NetException]],
    carried: AtomicRef.Unsafe[HandleLife.Carried],
    extra: AtomicRef.Unsafe[Chunk[Closed => Unit]]
):

    val read: ParkedSlot[ReadOutcome, Closed]             = new ParkedSlot(readCell, this)
    val writable: ParkedSlot[Unit, Closed | NetException] = new ParkedSlot(writableCell, this)
    val flush: ParkedSlot[Unit, Closed | NetException]    = new ParkedSlot(flushCell, this)
    val connect: ParkedSlot[Unit, Closed | NetException]  = new ParkedSlot(connectCell, this)
    val accept: ParkedSlot[Int, Closed | NetException]    = new ParkedSlot(acceptCell, this)

    /** Whether the release claim is still untaken. */
    def armable(using AllowUnsafe): Boolean = !releaseClaim.get()

    /** Takes the release claim. `true` for the single caller that owns the release. */
    def claimRelease()(using AllowUnsafe): Boolean = releaseClaim.compareAndSet(false, true)

    /** Fails every parked promise and every [[onCancel]] waiter with `closed`. Idempotent, and takes no claim. */
    def cancel(closed: Closed)(using AllowUnsafe, Frame): Unit =
        read.fail(closed)
        writable.fail(closed)
        flush.fail(closed)
        connect.fail(closed)
        accept.fail(closed)
        extra.getAndSet(Chunk.empty).foreach(_(closed))
    end cancel

    /** Registers a driver-specific waiter the next [[cancel]] fails. Registered after the release claim, it is failed at once. */
    def onCancel(waiter: Closed => Unit)(using AllowUnsafe, Frame): Unit =
        @tailrec def add(): Unit =
            val current = extra.get()
            if !extra.compareAndSet(current, current.append(waiter)) then add()
        add()
        if !armable then
            extra.getAndSet(Chunk.empty).foreach(_(Closed("handle", Frame.internal, "released")))
    end onCancel

    /** Queues an outcome read with no read armed. Bytes coalesce with bytes already queued; one terminal outcome may follow them. Returns
      * `false` for bytes or a second terminal after a terminal, and for `WouldBlock`, none of which is queued.
      */
    def carry(outcome: ReadOutcome)(using AllowUnsafe): Boolean =
        @tailrec def loop(): Boolean =
            val current = carried.get()
            if current.terminal.nonEmpty then false
            else
                val next = outcome match
                    case ReadOutcome.WouldBlock  => current
                    case ReadOutcome.Bytes(span) =>
                        current.copy(bytes = if current.bytes.isEmpty then span else Span.concat(current.bytes, span))
                    case terminal => current.copy(terminal = Present(terminal))
                if next eq current then false
                else if carried.compareAndSet(current, next) then true
                else loop()
            end if
        end loop
        loop()
    end carry

    /** Takes the head of the carried queue: the queued bytes if any, else the terminal outcome. */
    def takeCarried()(using AllowUnsafe): Maybe[ReadOutcome] =
        @tailrec def loop(): Maybe[ReadOutcome] =
            val current = carried.get()
            if !current.bytes.isEmpty then
                if carried.compareAndSet(current, current.copy(bytes = Span.empty[Byte])) then Present(ReadOutcome.Bytes(current.bytes))
                else loop()
            else if current.terminal.nonEmpty then
                if carried.compareAndSet(current, HandleLife.Carried.empty) then current.terminal else loop()
            else Absent
            end if
        end loop
        loop()
    end takeCarried

end HandleLife

private[kyo] object HandleLife:

    /** The carried queue: coalesced bytes, then at most one terminal outcome. */
    final case class Carried(bytes: Span[Byte], terminal: Maybe[ReadOutcome])

    object Carried:
        val empty: Carried = Carried(Span.empty[Byte], Absent)

    def init()(using AllowUnsafe): HandleLife =
        new HandleLife(
            HandleGuard.init(),
            OutboundLedger.init(),
            AtomicBoolean.Unsafe.init(false),
            AtomicRef.Unsafe.init(ParkedSlot.emptyState),
            AtomicRef.Unsafe.init(ParkedSlot.emptyState),
            AtomicRef.Unsafe.init(ParkedSlot.emptyState),
            AtomicRef.Unsafe.init(ParkedSlot.emptyState),
            AtomicRef.Unsafe.init(ParkedSlot.emptyState),
            AtomicRef.Unsafe.init(Carried.empty),
            AtomicRef.Unsafe.init(Chunk.empty)
        )
end HandleLife
