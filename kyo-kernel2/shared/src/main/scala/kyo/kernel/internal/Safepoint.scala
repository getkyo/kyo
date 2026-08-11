package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.discard
import kyo.kernel.*
import scala.annotation.static
import scala.annotation.tailrec

class Safepoint

// TODO do we have proper concurrency tests for this?
// TODO can this be private[kernel]? do a sweep of what's public in the internal package and if we can reduce visbility to the kernel
object Safepoint:

    opaque type Slot = Int

    inline def Period = 512

    private inline def Slots = 1024

    // a slot entry is the owning thread, or the thread wrapped in Stop when a
    // stop has been requested and not yet consumed; the wrapper rides the
    // existing volatile array so the running evaluation only pays a read on
    // its slow path
    final private class Stop(val thread: Thread)

    @static val depths: Array[Long] = new Array[Long](Slots)

    @static val owners: AtomicReferenceArray[AnyRef] = new AtomicReferenceArray[AnyRef](Slots)

    @static private def threadOf(entry: AnyRef): Thread =
        entry match
            case stop: Stop => stop.thread
            case thread     => thread.asInstanceOf[Thread]

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
                if (owner ne null) && (threadOf(owner) eq thread) then idx
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
                if (owner ne null) && !threadOf(owner).isAlive() then
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

    // requests that the thread's evaluation yield at its next safepoint. The
    // marker rides the volatile owners array and is read only on the budget's
    // slow path, so the running evaluation pays nothing on its hot path and
    // detection latency is bounded by one budget period
    @static def stop(thread: Thread): Boolean =
        val idx  = java.lang.System.identityHashCode(thread) & (Slots - 1)
        val slot = probe(thread, idx)
        @tailrec def attempt(): Boolean =
            owners.get(slot) match
                case stop: Stop =>
                    stop.thread eq thread
                case entry if entry eq thread =>
                    owners.compareAndSet(slot, entry, new Stop(thread)) || attempt()
                case _ =>
                    false
        (slot >= 0) && attempt()
    end stop

    @static private def probe(thread: Thread, from: Int): Int =
        @tailrec def loop(i: Int, probes: Int): Int =
            if probes == Slots then -1
            else
                val idx   = i & (Slots - 1)
                val owner = owners.get(idx)
                if (owner ne null) && (threadOf(owner) eq thread) then idx
                else loop(i + 1, probes + 1)
        loop(from, 0)
    end probe

    // consumes a pending stop request on the current thread's slot, restoring
    // the plain ownership entry; the request is one-shot
    @static def stopped(slot: Slot): Boolean =
        owners.get(slot) match
            case stop: Stop =>
                owners.set(slot, stop.thread)
                true
            case _ =>
                false
    end stopped

end Safepoint
