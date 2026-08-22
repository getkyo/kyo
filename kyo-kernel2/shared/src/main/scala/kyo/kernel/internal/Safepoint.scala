package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.Frame
import kyo.StaticFlag
import scala.annotation.static
import scala.annotation.tailrec

// TODO this should be private[kernel]
private[kyo] class Safepoint

// What an inline body names has to be public, at both levels. A private[kyo] top-level object makes dotty
// emit an accessor whose receiver is the package itself, which the backend loads as
// `getstatic kyo/kernel/internal.MODULE$` and no such class exists. A private[kyo] member gets a well formed
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

    final private class Stop(val thread: Thread)

    @static private val Slots      = slotCount()
    @static private val Overflowed = Slots

    @static private val depths =
        val a = new Array[State](Slots + 1)
        a(Slots) = State.init
        a
    end depths
    @static private val slots = new AtomicReferenceArray[Thread | Stop](Slots + 1)
    @static private val local = new ThreadLocal[Integer]

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
                // slice's preemption, and an unarmed thread keeps the stop for its next slice
                if depths(h).isArmed then depths(h) = depths(h).drained
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
                    if depths(slot).isArmed && slots.get(slot).isInstanceOf[Stop] then
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
        val d = Debugger.get
        if (d ne Debugger.Noop) && d.enterStrict() then
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

    @static private[kyo] def stop(thread: Thread): Boolean =
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
                            slots.compareAndSet(idx, owner, new Stop(thread)) || loop(i, probes)
                        case pending: Stop if pending.thread eq thread =>
                            true
                        case _ =>
                            loop(i + 1, probes + 1)
                    end match
                end if
        thread.isAlive() && loop(home(thread), 0)
    end stop

    /** Whether a stop is pending on the slot, without taking it: the poll's read. The sentinel
      * stays in the slot, so every decision point that asks sees the same answer until the slice
      * boundary consumes it.
      */
    @static private[kyo] def stopped(slot: Slot): Boolean =
        slots.get(slot).isInstanceOf[Stop]

    @static private[kyo] def consumeStopped(slot: Slot): Boolean =
        slots.get(slot) match
            case pending: Stop =>
                slots.set(slot, pending.thread)
                true
            case _ =>
                false

end Safepoint
