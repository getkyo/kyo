package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.discard
import kyo.kernel.*
import scala.annotation.static
import scala.annotation.tailrec

class Safepoint

// public: referenced from public inline bodies (map, suspendWith), so any
// private qualifier would make the compiler emit inline accessors that
// materialize the package prefix as a runtime value, failing with
// NoClassDefFoundError: kyo/kernel/internal, reproduced on Eval. The
// internal package carries the visibility intent
object Safepoint:

    opaque type Slot = Int

    inline def Period = 512

    // 8192 slots: exhaustion needs that many live threads evaluating at the
    // same time, an order of magnitude past large worker pools, and compact
    // reclaims the slots of dead threads. The arrays cost 128KB
    private inline def Slots = 8192

    // initial placement only: threads land one cache line apart, so with a
    // sparse population the budget counters, written by enter and exit on
    // the hottest path, never false-share a line. Under crowding the linear
    // probe packs the indices in between, trading locality for capacity
    // instead of failing earlier
    private inline def LineStride = 8

    // the overflow slot, claimed by every thread past capacity: its budget
    // counter is shared and racy, so stack-safety budgeting degrades to best
    // effort there, and its owners entry stays null, so stop requests cannot
    // target overflow threads. Both beat failing the evaluation
    private inline def Overflow = Slots

    // a slot entry is the owning thread, or the thread wrapped in Stop when a
    // stop has been requested and not yet consumed; the wrapper rides the
    // existing volatile array so the running evaluation only pays a read on
    // its slow path
    final private class Stop(val thread: Thread)

    @static val depths: Array[Long] = new Array[Long](Slots + 1)

    @static val owners: AtomicReferenceArray[AnyRef] = new AtomicReferenceArray[AnyRef](Slots + 1)

    @static private val overflowReported = new java.util.concurrent.atomic.AtomicBoolean

    @static private def threadOf(entry: AnyRef): Thread =
        entry match
            case stop: Stop     => stop.thread
            case thread: Thread => thread

    // the fast path does not see through Stop: a wrapped entry pays the find
    // probe only while a stop request is pending, which the next safepoint
    // consumes, and a type test here would tax every evaluation entry for
    // that transient state
    @static def get(): Slot =
        val thread = Thread.currentThread()
        val idx    = ((thread.threadId() * LineStride) & (Slots - 1)).toInt
        if owners.get(idx) eq thread then idx
        else find(thread, idx)
    end get

    @static def find(thread: Thread, from: Int): Slot =
        @tailrec def loop(i: Int, probes: Int, compactions: Int): Int =
            if probes == Slots then
                // a full scan found no slot: every entry is owned by another
                // live thread, which takes more live evaluating threads than
                // slots. Compaction retries reclaim recently died owners and
                // past them evaluation degrades to the overflow slot
                if compactions == 3 then
                    if overflowReported.compareAndSet(false, true) then
                        java.lang.System.err.println(
                            s"kyo: Safepoint slots exhausted, more than $Slots live threads are evaluating concurrently; " +
                                "stack-safety budgeting and preemption degrade to best effort for the threads past capacity"
                        )
                    end if
                    Overflow
                else
                    compact()
                    loop(from, 0, compactions + 1)
            else
                val idx   = i & (Slots - 1)
                val owner = owners.get(idx)
                if (owner ne null) && (threadOf(owner) eq thread) then idx
                else if (owner eq null) && owners.compareAndSet(idx, null, thread) then
                    depths(idx) = 0L
                    idx
                else loop(i + 1, probes + 1, compactions)
                end if
        end loop
        loop(from, 0, 0)
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

    // no ownership sanity check: unlike the old kernel's implicit Safepoint
    // values, which user code could capture inside computations and resume
    // on another thread, a Slot is obtained at each evaluation entry from
    // the current thread and only ever lives on that evaluation's stack, so
    // there is no path for it to leak across threads
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
        val idx  = ((thread.threadId() * LineStride) & (Slots - 1)).toInt
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
