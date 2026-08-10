package kyo.prototype

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.discard
import scala.annotation.static
import scala.annotation.tailrec

class Safepoint

object Safepoint:

    opaque type Slot = Int

    inline def Period = 512

    private inline def Slots = 1024

    @static val depths: Array[Long] = new Array[Long](Slots)

    @static val owners: AtomicReferenceArray[Thread] = new AtomicReferenceArray[Thread](Slots)

    @static def get(): Slot =
        val thread = Thread.currentThread()
        val idx    = java.lang.System.identityHashCode(thread) & (Slots - 1)
        if owners.get(idx) eq thread then idx
        else find(thread, idx)
    end get

    @static def find(thread: Thread, from: Int): Slot =
        @tailrec def loop(i: Int, probes: Int, compacted: Boolean): Int =
            if probes == Slots then
                if compacted then throw new IllegalStateException("Safepoint slots exhausted")
                else
                    compact()
                    loop(from, 0, true)
            else
                val idx   = i & (Slots - 1)
                val owner = owners.get(idx)
                if owner eq thread then idx
                else if (owner eq null) && owners.compareAndSet(idx, null, thread) then
                    depths(idx) = 0L
                    idx
                else loop(i + 1, probes + 1, compacted)
                end if
        end loop
        loop(from, 0, false)
    end find

    @static def compact(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < Slots then
                val owner = owners.get(i)
                if (owner ne null) && !owner.isAlive() then
                    discard(owners.compareAndSet(i, owner, null))
                loop(i + 1)
        loop(0)
    end compact

    @static def enter(slot: Slot): Boolean =
        val d = depths(slot)
        if d < Period then
            depths(slot) = d + 1
            true
        else false
        end if
    end enter

    @static def exit(slot: Slot): Unit =
        depths(slot) -= 1

    @static def save(slot: Slot): Long =
        val d = depths(slot)
        depths(slot) = 0L
        d
    end save

    @static def restore(slot: Slot, saved: Long): Unit =
        depths(slot) = saved

end Safepoint
