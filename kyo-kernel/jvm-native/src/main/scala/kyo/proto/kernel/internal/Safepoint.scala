package kyo.proto.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import scala.annotation.static
import scala.annotation.tailrec

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

    @static private def home(thread: Thread): Int =
        ((thread.threadId * LineStride) & (Slots - 1)).toInt

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
                            true
                        case _ =>
                            loop(i + 1, probes + 1)
                    end match
                end if
        thread.isAlive() && loop(home(thread), 0)
    end stop

    @static private def honored(slot: Slot, s: Stop): Boolean =
        (s.slice eq null) || (s.slice eq slices(slot))

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
