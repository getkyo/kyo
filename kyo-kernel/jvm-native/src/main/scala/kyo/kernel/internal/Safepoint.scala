package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import scala.annotation.static
import scala.annotation.tailrec

/** The budget that decides when a fused run has to stop building JVM stack and defer instead, and the channel a scheduler stops a fiber
  * through.
  *
  * Fusion runs transformations on the caller's stack, so an unbounded fused run would overflow it. Each fused step takes a unit of budget;
  * when it runs out the combinator builds a node instead and the evaluator picks it up, costing heap rather than a frame.
  * The same check covers preemption: a scheduler arms a thread's
  * slot, and the next poll that sees it parks the computation and answers the remainder as a value, so a run becomes a slice without the
  * computation knowing.
  *
  * #### Layout
  *
  * State is per thread but not a `ThreadLocal` read: a thread takes a slot index once and the state lives in a plain array, so a poll is an
  * array read and a compare rather than a map lookup. A thread's index is spread by `LineStride` so adjacent thread ids do not share a cache
  * line, and a thread that finds no free slot after probing falls back to a shared overflow slot, which stays correct but contends. One int
  * carries the whole state: the remaining depth in the low bits, a guard bit that keeps the counter from going negative into the arming bit,
  * and the arming bit itself, so a poll tests one field and the common answer is a decrement.
  *
  * #### Three arrays, and which threads touch them
  *
  * The state is split by who writes it, which keeps the hot path off atomics:
  *
  *   - `depths`, the budget and the arming bit. A plain array, since only the slot's owner reads or writes it; every poll is here, so it has
  *     to stay cheap.
  *   - `slots`, the ownership entry, holding the owning `Thread` or a `Stop` aimed at it. An `AtomicReferenceArray`, the one place another
  *     thread writes.
  *   - `slices`, the token of what the owner is currently running. A plain array, owner-only, read by a stopper only through the entry it
  *     already holds.
  *
  * A thread claims a slot once by compare-and-set, starting from the index its id hashes to and probing on. A slot is free when empty or its
  * owner is no longer alive, so slots recycle without anything releasing them. The claimed index is cached in a `ThreadLocal`, and the fast
  * path checks the array entry directly.
  *
  * #### How a stop is signalled
  *
  * There is no separate flag. [[stop]] replaces the slot's `Thread` entry with a `Stop` carrying that same thread, by compare-and-set, and
  * the owner learns of it on its next poll, since the entry it would look at anyway is now a different shape; the stop is consumed by putting
  * the plain thread back. A `Stop` may name a slice, and `honored` is what makes that safe: a stop with no slice is unconditional, one naming
  * a slice counts only while that slice is still what the slot is running, so a stop aimed at work that has already finished is ignored
  * rather than landing on whatever ran next, and `endSlice` clears one aimed at the slice just ended.
  *
  * Nothing here blocks or interrupts, so a computation that never polls is never preempted.
  *
  * @see
  *   [[Safepoint.period]] For the budget, which defaults per platform and is overridable
  */
private[kyo] class Safepoint

object Safepoint:

    opaque type Slot >: Int = Int

    opaque type State = Int

    private inline def LineStride = 8

    private inline def DepthGuard = 1 << 15
    private inline def Armed      = 1 << 30

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

    @static private val slices = new Array[AnyRef](Slots + 1)

    private[kyo] object period extends StaticFlag[Int](maxStackDepth, n => Right(Math.min(Math.max(1, n), 0x7fff)))

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

    @static private def home(thread: Thread): Int = ((thread.threadId * LineStride) & (Slots - 1)).toInt

    @static def get(): Slot =
        val thread = Thread.currentThread()
        val h      = home(thread)

        if slots.getPlain(h) eq thread then h
        else resolve(thread, h)
    end get

    @static private def resolve(thread: Thread, h: Int): Slot =

        slots.get(h) match
            case s: Stop if s.thread eq thread =>

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
                        val free  = (entry eq null) || {
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

        if Debugger.enabled && {
                val d = Debugger.get
                (d ne Debugger.Noop) && !(s.isArmed && stopped(slot)) && d.enter()
            }
        then

            true
        else
            depths(slot) = s.drained
            false
        end if
    end enterPark

    @static private[kyo] def drain(slot: Slot): Unit =
        depths(slot) = depths(slot).drained

    @static def exit(slot: Slot): Unit =

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

    inline def deadline(inline d: Long): Unit = ()

    @static private[kyo] def stop(thread: Thread): Boolean = stop(thread, null)

    @static private[kyo] def stop(thread: Thread, slice: AnyRef): Boolean =
        @tailrec def loop(i: Int, probes: Int): Boolean =
            if probes == Slots then false
            else
                val idx   = i & (Slots - 1)
                val entry = slots.get(idx)

                if entry eq null then false
                else
                    entry match
                        case owner: Thread if owner eq thread =>
                            slots.compareAndSet(idx, owner, new Stop(thread, slice)) || loop(i, probes)
                        case pending: Stop if pending.thread eq thread =>
                            // A pending stop answers this request when it is honored wherever this one would be: a
                            // wildcard is, and so is one naming the same slice. Two stops naming different slices
                            // merge into a wildcard, honored wherever either would have been: one of them is a
                            // late delivery to work that has ended and the other may be the running slice's own,
                            // and nobody but the owner can tell which, so neither is dropped. The cost is at most
                            // one spurious park, at the next evaluation's entry, where a wildcard is consumed.
                            (pending.slice eq null) || (pending.slice eq slice) ||
                            slots.compareAndSet(idx, pending, new Stop(thread, null)) || loop(i, probes)
                        case _ =>
                            loop(i + 1, probes + 1)
                    end match
                end if
        thread.isAlive() && loop(home(thread), 0)
    end stop

    @static private def honored(slot: Slot, s: Stop): Boolean = (s.slice eq null) || (s.slice eq slices(slot))

    @static private[kyo] def beginSlice(slot: Slot, slice: AnyRef): AnyRef =
        val prev = slices(slot)
        slices(slot) = slice
        prev
    end beginSlice

    @static private[kyo] def endSlice(slot: Slot, prev: AnyRef): Unit =
        slots.get(slot) match
            case s: Stop if s.slice eq slices(slot) => slots.set(slot, s.thread)
            case _                                  => ()
        slices(slot) = prev
    end endSlice

    @static private[kyo] def stopped(slot: Slot): Boolean =
        slots.get(slot) match
            case s: Stop => honored(slot, s)
            case _       => false

    @static private[kyo] def consumeStopped(slot: Slot): Boolean =
        slots.get(slot) match
            case pending: Stop =>

                slots.set(slot, pending.thread)
                honored(slot, pending)
            case _ =>
                false

end Safepoint
