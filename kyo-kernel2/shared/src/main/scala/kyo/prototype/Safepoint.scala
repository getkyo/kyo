package kyo.prototype

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.discard
import scala.annotation.tailrec

opaque type Safepoint = Int

object Safepoint:

    inline def Period = 512

    private inline def Slots = 1024

    private[prototype] val depths = new Array[Long](Slots)
    private[prototype] val owners = new AtomicReferenceArray[Thread](Slots)

    def get: Safepoint =
        val thread = Thread.currentThread()
        val idx    = java.lang.System.identityHashCode(thread) & (Slots - 1)
        if owners.get(idx) eq thread then idx
        else find(thread, idx)
    end get

    private def find(thread: Thread, from: Int): Int =
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

    private def compact(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < Slots then
                val owner = owners.get(i)
                if (owner ne null) && !owner.isAlive() then
                    discard(owners.compareAndSet(i, owner, null))
                loop(i + 1)
        loop(0)
    end compact

    extension (self: Safepoint)

        inline def enter(): Boolean =
            val d = depths(self)
            if d < Period then
                depths(self) = d + 1
                true
            else false
            end if
        end enter

        inline def exit(): Unit =
            depths(self) -= 1

        private[prototype] inline def save(): Long =
            val d = depths(self)
            depths(self) = 0L
            d
        end save

        private[prototype] inline def restore(saved: Long): Unit =
            depths(self) = saved

    end extension

end Safepoint
