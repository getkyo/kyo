package kyo.kernel3.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import kyo.kernel.*
import scala.annotation.static
import scala.annotation.tailrec

class Safepoint

object Safepoint:

    opaque type Slot = Int

    opaque type State = Int

    private inline def LineStride = 8

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

    object State:
        private inline def DepthGuard = 1 << 15
        private inline def Armed      = 1 << 30

        private val Initial: State = DepthGuard | period()

        private[Safepoint] def init: State = Initial

        extension (self: State)
            private[Safepoint] inline def enterInto(slot: Int): Boolean =
                val s2 = self - 1
                if (s2 & DepthGuard) == DepthGuard then
                    depths(slot) = s2
                    true
                else enterPark(slot, self)
                end if
            end enterInto

            private[Safepoint] inline def decrementDepth: State = self + 1
            private[Safepoint] inline def drained: State        = (self & Armed) | DepthGuard
            private[Safepoint] inline def reset: State          = (self & Armed) | Initial
            private[Safepoint] inline def armed: State          = self | Armed
            private[Safepoint] inline def isArmed: Boolean      = (self & Armed) != 0
        end extension
    end State

    import State.*

    @static private def home(thread: Thread): Int =
        ((thread.threadId * LineStride) & (Slots - 1)).toInt

    @static def get(): Slot =
        val thread = Thread.currentThread()
        val h      = home(thread)
        if slots.get(h) eq thread then h
        else resolve(thread, h)
    end get

    @static private def resolve(thread: Thread, h: Int): Slot =
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
    end resolve

    @static def enter(slot: Slot): Boolean =
        depths(slot).enterInto(slot)

    @static private def enterPark(slot: Slot, s: State): Boolean =
        depths(slot) = s.drained
        false
    end enterPark

    @static def exit(slot: Slot): Unit =
        depths(slot) = depths(slot).decrementDepth

    @static def save(slot: Slot): State =
        val d = depths(slot)
        depths(slot) = State.init
        d
    end save

    @static def restore(slot: Slot, saved: State): Unit =
        depths(slot) = saved

    @static def reset(slot: Slot): Unit =
        depths(slot) = depths(slot).reset

    @static def arm(slot: Slot): Unit =
        depths(slot) = depths(slot).armed

    @static def stop(thread: Thread): Boolean =
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

    @static def consumeStopped(slot: Slot): Boolean =
        slots.get(slot) match
            case pending: Stop =>
                slots.set(slot, pending.thread)
                true
            case _ =>
                false

end Safepoint
