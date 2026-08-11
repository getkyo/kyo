package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import kyo.kernel.*
import scala.annotation.static
import scala.annotation.tailrec

class Safepoint

object Safepoint:

    opaque type Slot = Int

    private inline def LineStride   = 8
    private inline def DepthMask    = 0xffffL
    private inline def StepAndDepth = (1L << 16) | 1L

    final private class Stop(val thread: Thread)

    @static private val Period     = period().toLong
    @static private val Slots      = slotCount()
    @static private val Overflowed = Slots
    @static private val StepMask   = (preemptionInterval().toLong - 1L) << 16
    @static private val depths     = new Array[Long](Slots)
    @static private val slots      = new AtomicReferenceArray[Thread | Stop](Slots)
    @static private val local      = new ThreadLocal[Integer]

    private[kyo] object period extends StaticFlag[Int](512, n => Right(Math.min(Math.max(1, n), 0xffff)))

    private[kyo] object slotCount extends StaticFlag[Int](
            65536,
            n =>
                if Integer.bitCount(n) == 1 then Right(n)
                else Left(new IllegalArgumentException(s"slotCount must be a power of two, got $n"))
        )

    private[kyo] object preemptionInterval extends StaticFlag[Int](
            1024,
            n =>
                if Integer.bitCount(n) == 1 then Right(n)
                else Left(new IllegalArgumentException(s"preemptionInterval must be a power of two, got $n"))
        )

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
                    depths(idx) = 0L
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
            val s = depths(slot)
            if (s & DepthMask) < Period then
                val s2 = s + StepAndDepth
                if ((s2 & StepMask) != 0) || !stopPending(slot) then
                    depths(slot) = s2
                    true
                else
                    depths(slot) = s & DepthMask
                    false
                end if
            else
                depths(slot) = s & DepthMask
                false
            end if
        else true

    @static private def stopPending(slot: Slot): Boolean =
        slots.get(slot).isInstanceOf[Stop]

    @static def exit(slot: Slot): Unit =
        if slot != Overflowed then depths(slot) -= 1

    @static def save(slot: Slot): Long =
        if slot != Overflowed then
            val d = depths(slot)
            depths(slot) = 0L
            d
        else 0L

    @static def restore(slot: Slot, saved: Long): Unit =
        if slot != Overflowed then depths(slot) = saved

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
