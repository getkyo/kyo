package kyo.kernel.internal

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
    @static private val depths     = new Array[State](Slots)
    @static private val slots      = new AtomicReferenceArray[Thread | Stop](Slots)
    @static private val local      = new ThreadLocal[Integer]

    private[kyo] object period extends StaticFlag[Int](512, n => Right(Math.min(Math.max(1, n), 0x7fff)))

    private[kyo] object preemptionInterval extends StaticFlag[Int](
            1024,
            n =>
                if Integer.bitCount(n) == 1 && n <= 16384 then Right(n)
                else Left(new IllegalArgumentException(s"preemptionInterval must be a power of two up to 16384, got $n"))
        )

    private[kyo] object slotCount extends StaticFlag[Int](
            65536,
            n =>
                if Integer.bitCount(n) == 1 then Right(n)
                else Left(new IllegalArgumentException(s"slotCount must be a power of two, got $n"))
        )

    object State:
        private inline def DepthGuard = 1 << 15
        private inline def StepsGuard = 1 << 31
        private inline def Guards     = StepsGuard | DepthGuard

        private val Initial: State    = StepsGuard | (preemptionInterval() << 16) | DepthGuard | period()
        private val DepthLimit: State = (Initial & ~0xffff) | DepthGuard

        private[Safepoint] def init: State = Initial

        extension (self: State)
            private[Safepoint] inline def incrementDepth: State  = self - ((1 << 16) | 1)
            private[Safepoint] inline def decrementDepth: State  = self + 1
            private[Safepoint] inline def withinLimits: Boolean  = (self & Guards) == Guards
            private[Safepoint] inline def depthExceeded: Boolean = (self & DepthGuard) == 0
            private[Safepoint] inline def atDepthLimit: State    = DepthLimit
            private[Safepoint] inline def restartInterval: State = (Initial & ~0xffff) | (self & 0xffff)
            private[Safepoint] inline def reset: State           = Initial
        end extension
    end State

    import State.*

    @static private def home(thread: Thread): Int =
        ((thread.threadId() * LineStride) & (Slots - 1)).toInt

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
                            case owner: Thread => !owner.isAlive()
                            case pending: Stop => !pending.thread.isAlive()
                    }
                if !free then claim(i + 1, probes + 1)
                else if slots.compareAndSet(idx, entry, thread) then
                    depths(idx) = depths(idx).reset
                    idx
                else claim(i, probes)
                end if
        end claim

        val cached = local.get()
        if cached ne null then cached.intValue()
        else
            val slot = claim(h, 0)
            local.set(Integer.valueOf(slot))
            slot
        end if
    end resolve

    @static def enter(slot: Slot): Boolean =
        if slot != Overflowed then
            val s  = depths(slot)
            val s2 = s.incrementDepth
            if s2.withinLimits then
                depths(slot) = s2
                true
            else enterSlow(slot, s, s2)
            end if
        else true

    @static private def enterSlow(slot: Slot, s: State, s2: State): Boolean =
        if s2.depthExceeded then
            depths(slot) = s2.atDepthLimit
            false
        else if !slots.get(slot).isInstanceOf[Stop] then
            depths(slot) = s2.restartInterval
            true
        else
            depths(slot) = s.restartInterval
            false
    end enterSlow

    @static def exit(slot: Slot): Unit =
        if slot != Overflowed then depths(slot) = depths(slot).decrementDepth

    @static def save(slot: Slot): State =
        if slot != Overflowed then
            val d = depths(slot)
            depths(slot) = d.reset
            d
        else State.init

    @static def restore(slot: Slot, saved: State): Unit =
        if slot != Overflowed then depths(slot) = saved

    @static def reset(slot: Slot): Unit =
        if slot != Overflowed then depths(slot) = depths(slot).reset

    @static def stop(thread: Thread): Boolean =
        @tailrec def loop(i: Int, probes: Int): Boolean =
            if probes == Slots then false
            else
                val idx = i & (Slots - 1)
                slots.get(idx) match
                    case owner: Thread if owner eq thread =>
                        slots.compareAndSet(idx, owner, new Stop(thread)) || loop(i, probes)
                    case pending: Stop if pending.thread eq thread =>
                        true
                    case _ =>
                        loop(i + 1, probes + 1)
                end match
        thread.isAlive() && loop(home(thread), 0)
    end stop

    @static def consumeStopped(slot: Slot): Boolean =
        if slot != Overflowed then
            slots.get(slot) match
                case pending: Stop =>
                    slots.set(slot, pending.thread)
                    true
                case _ =>
                    false
        else false

end Safepoint
