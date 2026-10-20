package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** The bytes a handle owes the kernel: what `IoDriver.write` accepted and what the driver produced, less what the kernel took.
  *
  * `IoDriver.write` returning `Done` means the driver owns every byte, not that the kernel has them, so the release predicate reads [[owed]]
  * beside the write pump's state. Three counters instead of one because a TLS record carries overhead: `queued` is plaintext accepted and not
  * yet encoded, `produced` is wire bytes put on the tail (encrypted records and driver-generated ones such as a KeyUpdate reply or
  * close_notify), `sent` is wire bytes the kernel accepted. `owed` mixes units only between [[accept]] and [[encode]], which the owner has
  * drained by the time it decides a close.
  *
  * `sent` is monotonic, so a close-flush window measures progress by comparing it across windows: a tail that rises and falls back inside a
  * window is progress, and a pump that moves no byte is a stall.
  *
  * [[accept]] runs on the writing carrier; [[encode]], [[generate]], [[send]], [[direct]] and [[onDrained]] run on the handle's owner. The
  * [[onDrained]] waiter fires inside the [[encode]] or [[send]] that brings [[owed]] to zero, on the owner, so it must re-enter through
  * `IoDriver.runOwned` rather than act inline: on NIO the owner holds the handle's gate at that point.
  *
  * The first write-side failure is sticky: [[fail]] records it once, and every later write reports it. It also fires the [[onDrained]]
  * waiter, from whichever carrier saw the failure: what is owed then never drains, and a close waiting on it must learn of the failure
  * instead, since a pump that already tore down is not woken by it.
  */
final private[kyo] class OutboundLedger private (
    queued: AtomicLong.Unsafe,
    produced: AtomicLong.Unsafe,
    sent: AtomicLong.Unsafe,
    firstFailure: AtomicRef.Unsafe[Maybe[NetException]],
    drainedWaiter: AtomicRef.Unsafe[Maybe[() => Unit]]
):

    /** `plain` bytes accepted by a write that returns `Done` or `Partial`, not yet encoded onto the tail. */
    def accept(plain: Int)(using AllowUnsafe): Unit = discard(queued.addAndGet(plain))

    /** `plain` accepted bytes left the queue as `wire` bytes on the tail. */
    def encode(plain: Int, wire: Int)(using AllowUnsafe): Unit =
        discard(produced.addAndGet(wire))
        discard(queued.addAndGet(-plain))
        fireIfDrained()
    end encode

    /** A record the driver produced on its own (a KeyUpdate reply, a renegotiation flight, close_notify). */
    def generate(wire: Int)(using AllowUnsafe): Unit = discard(produced.addAndGet(wire))

    /** The kernel accepted `wire` bytes of the tail. */
    def send(wire: Int)(using AllowUnsafe): Unit =
        discard(sent.addAndGet(wire))
        fireIfDrained()
    end send

    /** `n` plaintext bytes a synchronous write put straight into the kernel: accepted, encoded and sent at once. */
    def direct(n: Int)(using AllowUnsafe): Unit =
        discard(produced.addAndGet(n))
        discard(sent.addAndGet(n))
    end direct

    /** Records the first write-side failure. `true` for the call that recorded it. */
    def fail(cause: NetException)(using AllowUnsafe): Boolean =
        val recorded = firstFailure.compareAndSet(Absent, Present(cause))
        if recorded then fireIfDrained()
        recorded
    end fail

    /** Bytes accepted or produced and not yet taken by the kernel. */
    def owed(using AllowUnsafe): Long = queued.get() + produced.get() - sent.get()

    /** Runs `f` now when nothing is owed or a failure is recorded, otherwise once, inside the next [[encode]] or [[send]] that brings
      * [[owed]] to zero or the [[fail]] that records the first failure. A later registration replaces an earlier one that has not fired.
      */
    def onDrained(f: () => Unit)(using AllowUnsafe): Unit =
        drainedWaiter.set(Present(f))
        fireIfDrained()
    end onDrained

    /** Wire bytes the kernel accepted since the handle opened. */
    def sentTotal(using AllowUnsafe): Long = sent.get()

    /** The first write-side failure, if any. */
    def failure(using AllowUnsafe): Maybe[NetException] = firstFailure.get()

    // A registration sets the waiter before it reads the counters and the failure, and a fail records the failure before it reads the
    // waiter, so of a racing pair at least one sees the other; getAndSet runs the waiter once.
    private def fireIfDrained()(using AllowUnsafe): Unit =
        if (owed == 0 || firstFailure.get().nonEmpty) && drainedWaiter.get().nonEmpty then
            drainedWaiter.getAndSet(Absent).foreach(_())

end OutboundLedger

private[kyo] object OutboundLedger:

    def init()(using AllowUnsafe): OutboundLedger =
        new OutboundLedger(
            AtomicLong.Unsafe.init(0),
            AtomicLong.Unsafe.init(0),
            AtomicLong.Unsafe.init(0),
            AtomicRef.Unsafe.init(Absent),
            AtomicRef.Unsafe.init(Absent)
        )
end OutboundLedger
