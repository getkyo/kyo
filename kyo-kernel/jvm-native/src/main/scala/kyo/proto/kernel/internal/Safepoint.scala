package kyo.proto.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import scala.annotation.static
import scala.annotation.tailrec

// TODO this should be private[kernel]
private[kyo] class Safepoint

// What an inline body names has to be public, at both levels. A private[kyo] top-level object makes dotty
// emit an accessor whose receiver is the package itself, which the backend loads as
// `getstatic kyo/proto/kernel/internal.MODULE$` and no such class exists. A private[kyo] member gets a well formed
// accessor instead, but the accessor is a second call the expansion pays for: narrowing `lift` and the depth
// guard this way took the value lift from 5 bytes to 20. So the members the expansions reach stay public and
// the rest narrows. Slot and State are opaque, so public here hands out no operations.
object Safepoint:

    /** The lower bound lets a caller declare the variable holding a slot with a literal, and fill it only on the
      * path that resolves one. There is no upper bound, so a `Slot` still cannot be used as an `Int` out here.
      */
    opaque type Slot >: Int = Int

    opaque type State = Int

    private inline def LineStride = 8

    // the two state bits live here rather than on `State` so `enter` can read them without naming `State`.
    // Naming it there makes the expansion load the module and reach the depth array through an accessor,
    // which is bytecode `enter` pays for at every settled map step
    private inline def DepthGuard = 1 << 15
    private inline def Armed      = 1 << 30

    /** The pending-stop sentinel: `thread` is who the slot belongs to, `slice` is the slice the
      * stop is addressed to, `null` for a wildcard. See `stop` for how the two are honored.
      */
    final private class Stop(val thread: Thread, val slice: AnyRef)

    @static private val Slots      = slotCount()
    @static private val Overflowed = Slots

    @static private val depths =
        val a = new Array[State](Slots + 1)
        a(Slots) = State.init
        a
    end depths
    @static private val slots = new AtomicReferenceArray[Thread | Stop](Slots + 1)
    @static private val local = new ThreadLocal[Integer]

    // the slice each claimed slot is currently running, written and read only by the slot's own
    // thread (`beginSlice`, `endSlice`, and the honor checks), so the lane is plain. A stopper
    // never reads it: an addressed stop carries its slice, and the owner compares on observation
    @static private val slices = new Array[AnyRef](Slots + 1)

    private[kyo] object period extends StaticFlag[Int](512, n => Right(Math.min(Math.max(1, n), 0x7fff)))

    private[kyo] object slotCount extends StaticFlag[Int](
            65536,
            n =>
                if Integer.bitCount(n) == 1 then Right(n)
                else Left(new IllegalArgumentException(s"slotCount must be a power of two, got $n"))
        )

    private[kyo] object State:

        private val Initial: State = DepthGuard | period()

        private[Safepoint] def init: State = Initial

        extension (self: State)
            private[Safepoint] inline def drained: State   = (self & Armed) | DepthGuard
            private[Safepoint] inline def reset: State     = (self & Armed) | Initial
            private[Safepoint] inline def armed: State     = self | Armed
            private[Safepoint] inline def isArmed: Boolean = (self & Armed) != 0
        end extension
    end State

    import State.*

    @static private def home(thread: Thread): Int =
        ((thread.threadId * LineStride) & (Slots - 1)).toInt

    @static def get(): Slot =
        val thread = Thread.currentThread()
        val h      = home(thread)
        // plain on purpose, and this probe runs at every settled step. Only this thread installs its
        // own reference (the claim CAS), and a plain read cannot see anything older than its own
        // write, so a hit proves ownership without an acquire. What it can miss is a stopper's
        // Thread-to-Stop exchange, which only delays the slow path below by cache propagation
        if slots.getPlain(h) eq thread then h
        else resolve(thread, h)
    end get

    @static private def resolve(thread: Thread, h: Int): Slot =
        // slow path, so the read is volatile again: confirm what is actually in the slot before acting
        slots.get(h) match
            case s: Stop if s.thread eq thread =>
                // a stop pending on the home slot, handled where it is observed: drain so an armed
                // slice defers at once and its poll parks it. Observation only: consumption stays
                // with the partial eval's poll, so a nested plain eval cannot eat an enclosing
                // slice's preemption, and an unarmed thread keeps the stop for its next slice. One
                // addressed to a slice this thread no longer runs raced the slice boundary; it is
                // dropped here instead, so it cannot touch the slice that is running
                if honored(h, s) then
                    if depths(h).isArmed then depths(h) = depths(h).drained
                else slots.set(h, s.thread)
                h
            case _ =>
                @tailrec def claim(i: Int, probes: Int): Int =
                    if probes == Slots then Overflowed
                    else
                        val idx   = i & (Slots - 1)
                        val entry = slots.get(idx)
                        val free =
                            (entry eq null) || {
                                entry match
                                    case owner: Thread => !owner.isAlive
                                    case pending: Stop => !pending.thread.isAlive
                            }
                        if !free then claim(i + 1, probes + 1)
                        else if slots.compareAndSet(idx, entry, thread) then
                            depths(idx) = State.init
                            idx
                        else claim(i, probes)
                        end if
                end claim

                val cached = local.get()
                if cached ne null then
                    val slot = cached.intValue()
                    if depths(slot).isArmed && stopped(slot) then
                        depths(slot) = depths(slot).drained
                    slot
                else
                    val slot = claim(h, 0)
                    local.set(Integer.valueOf(slot))
                    slot
                end if
        end match
    end resolve

    // written out rather than delegating to an extension on `State`: this runs at every settled map step, and
    // its bytecode size decides whether a caller can inline it. `DepthGuard` is a single bit, so testing it
    // against zero is the same test in fewer instructions than comparing it back to the mask.
    // The debugger costs this method nothing: a session drains its eval's slot, so every strict
    // application lands in `enterPark`, and the frameless consult lives entirely on that cold path.
    // Two earlier shapes were rejected by the board: consulting the hook here grew the body from 30 to
    // 44 bytes, and even a frame parameter alone kept the strict-fusion rows up to 4x over baseline
    // through the operand stamped at every call site
    @static def enter(slot: Slot): Boolean =
        val s  = depths(slot)
        val s2 = s - 1
        if (s2 & DepthGuard) != 0 then
            depths(slot) = s2
            true
        else enterPark(slot, s)
        end if
    end enter

    @static private def enterPark(slot: Slot, s: State): Boolean =
        // preemption outranks the session: an armed slice with a stop pending refuses whatever the
        // gate would say, so the eval parks instead of running the program to completion unobserved.
        // The whole consult folds away with the debugger disabled: the park arm is all that remains
        if Debugger.enabled && {
                val d = Debugger.get
                (d ne Debugger.Noop) && !(s.isArmed && stopped(slot)) && d.enter()
            }
        then
            // an allowed application proceeds without touching the drained state, so the next one
            // lands here again: per-application consult is the session's contract. The consult is
            // frameless, since carrying a frame here costs every call site an operand; a session
            // that wants frames routes the application and reads them at onDefer. The depth guard
            // does not bound strict recursion while a session allows applications; a debugger that
            // runs the program is expected to pay the program's shape
            true
        else
            depths(slot) = s.drained
            false
        end if
    end enterPark

    /** Exhausts the slot's budget so every strict application on this thread lands in `enterPark`,
      * where the debugger's gate lives. Only the slot's own thread may call it: the eval drains its
      * own slot at entry when a session is installed, which also survives `save` installing a fresh
      * budget, since the drain runs after it.
      */
    @static private[kyo] def drain(slot: Slot): Unit =
        depths(slot) = depths(slot).drained

    @static def exit(slot: Slot): Unit =
        // written out for the reason `enter` is: naming the extension makes the expansion load `State`'s module
        // and hold the array and index on the operand stack across it, which reads as too deep to inline
        val s = depths(slot)
        depths(slot) = s + 1
    end exit

    @static def save(slot: Slot): State =
        val d = depths(slot)
        depths(slot) = State.init
        d
    end save

    @static def restore(slot: Slot, saved: State): Unit =
        depths(slot) = saved

    @static private[kyo] def reset(slot: Slot): Unit =
        depths(slot) = depths(slot).reset

    @static private[kyo] def arm(slot: Slot): Unit =
        depths(slot) = depths(slot).armed

    /** The scheduler's slice deadline. Preemption here comes from `stop`, delivered by a live
      * thread, so the deadline has no carrier to check it and the call inlines to nothing. The
      * js-wasm variant stores it; there it is the only preemption source, observed through
      * `stopped` at the budget drains of the eval the slice arms.
      */
    inline def deadline(inline d: Long): Unit = ()

    /** Requests a preemption stop for the evaluation running on `thread`, addressed to nobody.
      *
      * A wildcard is honored by whatever slice observes it, so it is only race-free when the
      * requester knows which slice that is: a computation stopping its own thread, or a test
      * driving a thread it controls. The scheduler addresses its stops instead.
      */
    @static private[kyo] def stop(thread: Thread): Boolean = stop(thread, null)

    /** Requests a preemption stop for the evaluation running on `thread`, addressed to `slice`.
      *
      * Delivery is thread-addressed and can race the slice boundary: between the requester reading
      * who runs where and the sentinel landing, the slice can end and the thread move on to
      * another task. The addressee closes that race on the observation side: the slot's own thread
      * honors an addressed stop only while `slice` is what `beginSlice` recorded, and drops one
      * that arrives late, so a stale delivery cannot short-circuit the slice that is running.
      *
      * A request finding another sentinel already pending reports delivered without validating the
      * addressee; if the pending one turns out stale and is dropped, this request is lost with it.
      * A lost request is the requester's to re-issue, which the scheduler's stall checks do by
      * re-firing on every probe.
      */
    @static private[kyo] def stop(thread: Thread, slice: AnyRef): Boolean =
        @tailrec def loop(i: Int, probes: Int): Boolean =
            if probes == Slots then false
            else
                val idx   = i & (Slots - 1)
                val entry = slots.get(idx)
                // an unclaimed cell ends the probe: a claim walks forward from
                // this same home and stops at the first free cell, and no site
                // writes a cell back to null once claimed, so a thread holding
                // one would have been found before here
                if entry eq null then false
                else
                    entry match
                        case owner: Thread if owner eq thread =>
                            slots.compareAndSet(idx, owner, new Stop(thread, slice)) || loop(i, probes)
                        case pending: Stop if pending.thread eq thread =>
                            true
                        case _ =>
                            loop(i + 1, probes + 1)
                    end match
                end if
        thread.isAlive() && loop(home(thread), 0)
    end stop

    // whether a pending stop is for the slice this slot is running: a wildcard is honored
    // anywhere, an addressed stop only while its slice holds the slot. Owner-side only, so the
    // `slices` read is the reader's own write
    @static private def honored(slot: Slot, s: Stop): Boolean =
        (s.slice eq null) || (s.slice eq slices(slot))

    /** Records `slice` as what this slot is running, handing back what it replaces for
      * `endSlice`. Only the slot's own thread calls it, at the scheduler's slice entry; the value
      * is what an addressed stop must name to be honored while the slice runs. Slices nest, a
      * task can run inside another task's slice, which is what the returned value carries.
      */
    @static private[kyo] def beginSlice(slot: Slot, slice: AnyRef): AnyRef =
        val prev = slices(slot)
        slices(slot) = slice
        prev
    end beginSlice

    /** The counterpart of `beginSlice` at the slice's end: restores `prev` and drops a pending
      * stop addressed to the departing slice, which the boundary it just crossed has satisfied.
      */
    @static private[kyo] def endSlice(slot: Slot, prev: AnyRef): Unit =
        slots.get(slot) match
            case s: Stop if s.slice eq slices(slot) => slots.set(slot, s.thread)
            case _                                  => ()
        slices(slot) = prev
    end endSlice

    /** Whether a stop for the running slice is pending on the slot, without taking it: the poll's
      * read. The sentinel stays in the slot, so every decision point that asks sees the same
      * answer until the slice boundary consumes it. A stop addressed to a slice that no longer
      * holds the slot answers false: it raced the boundary, and the next consume drops it.
      */
    @static private[kyo] def stopped(slot: Slot): Boolean =
        slots.get(slot) match
            case s: Stop => honored(slot, s)
            case _       => false

    @static private[kyo] def consumeStopped(slot: Slot): Boolean =
        slots.get(slot) match
            case pending: Stop =>
                // taken either way: an honored stop is consumed, a stale addressed one is dropped,
                // and both leave the thread back in its slot for the next request
                slots.set(slot, pending.thread)
                honored(slot, pending)
            case _ =>
                false

end Safepoint
