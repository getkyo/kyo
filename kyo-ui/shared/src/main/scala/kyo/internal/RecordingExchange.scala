package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** A transport that paints nothing and remembers everything it was told, oldest first.
  *
  * The engine's tests read what reached the page from here. A test waits on [[awaitOp]], which a promise releases, or on an observer parked
  * on its signal; neither reads a clock.
  *
  * @param beforePaint
  *   runs before a paint is recorded, and may suspend it there, as a slow transport would
  */
private[kyo] class RecordingExchange(
    beforePaint: (Seq[String], UI) => Unit < Async = (_, _) => ()
) extends UIExchange:
    import RecordingExchange.*

    // Unsafe: a sink runs on the writer's stack, where no effect context exists, and the log has to take what it reports.
    private val log = AtomicRef.Unsafe.init(Chunk.empty[Op])(using AllowUnsafe.embrace.danger)
    // Unsafe: as for the log. A waiter is released from the same stack that records the op it waits for.
    private val waiting =
        AtomicRef.Unsafe.init(Chunk.empty[(Op => Boolean, Promise.Unsafe[Unit, Any])])(using AllowUnsafe.embrace.danger)

    private def record(op: Op)(using AllowUnsafe): Unit =
        discard(log.getAndUpdate(_.append(op)))
        releaseMet(op)

    /** A waiter that registers after this looked finds the op in the log by itself. */
    @tailrec private def releaseMet(op: Op)(using AllowUnsafe): Unit =
        val all         = waiting.get()
        val (met, rest) = all.partition(_._1(op))
        if met.nonEmpty then
            if waiting.compareAndSet(all, rest) then met.foreach(_._2.completeUnitDiscard())
            else releaseMet(op)
    end releaseMet

    private def recording(op: Op)(using Frame): Unit < Sync = Sync.Unsafe.defer(record(op))

    def onChange(
        region: ReactiveRegion,
        path: Seq[String],
        contentContext: ReactiveRegion.RegionIdentity,
        parentContext: ReactiveRegion.ParentContext,
        previous: Maybe[UI],
        ui: UI
    )(using Frame): Unit < Async =
        beforePaint(path, ui).andThen(recording(Op.Paint(path, previous, ui)))

    /** Everything recorded so far, oldest first. */
    def ops(using Frame): Chunk[Op] < Sync = Sync.Unsafe.defer(log.get())

    /** [[ops]] where a test reads counts in a pure position, right after the write that delivers on the writer's stack. */
    def recorded: Chunk[Op] =
        // Unsafe: a read of the log, for a test that compares it right away; nothing here suspends or writes.
        log.get()(using AllowUnsafe.embrace.danger)

    def paints(using Frame): Chunk[Op.Paint] < Sync = ops.map(_.collect { case op: Op.Paint => op })

    /** Waits until an op that `pred` accepts has been recorded, whether already or later. */
    def awaitOp(pred: Op => Boolean)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val promise = Promise.Unsafe.init[Unit, Any]()
            discard(waiting.getAndUpdate(_.append((pred, promise))))
            if log.get().exists(pred) then promise.completeUnitDiscard()
            promise.safe
        }.map(_.get)
end RecordingExchange

private[kyo] object RecordingExchange:

    enum Op derives CanEqual:
        case Paint(path: Seq[String], previous: Maybe[UI], ui: UI)
    end Op
end RecordingExchange
