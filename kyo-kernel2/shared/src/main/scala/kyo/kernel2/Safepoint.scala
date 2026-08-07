package kyo.kernel2

import scala.annotation.static
import scala.annotation.tailrec

/** The kernel's runtime guard: per-thread depth accounting behind the stack-safety rescue.
  *
  * Every guarded transform execution brackets a depth increase and decrease on a reserved single-writer slot; crossing the limit reroutes
  * the execution through a rescue Defer so the trampoline unwinds the stack. Slots are claimed per thread and reclaimed from dead threads;
  * the shared overflow slot is pinned at the limit and never written. Statics are hosted on the companion class so hot callers reach the
  * counters via invokestatic with no module load.
  *
  * This is the successor of the current kernel's Safepoint: today it carries the depth guard; preemption polling lives in the drive and
  * consults the same budget machinery.
  */
final private[kyo] class Safepoint private ()

private[kyo] object Safepoint:

    inline def Limit = 512

    private inline def Slots        = 256
    private inline def Mask         = Slots - 1
    private inline def Probes       = 8
    private inline def Shift        = 3
    private inline def Transferring = -1L

    @static private val owners  = new java.util.concurrent.atomic.AtomicLongArray(Slots)
    @static private val threads = new java.util.concurrent.atomic.AtomicReferenceArray[Thread](Slots)

    // one cache line per cell; the last cell is pinned at Limit and never written
    @static private val cells =
        val a = new Array[Long]((Slots + 1) << Shift)
        a(Slots << Shift) = Limit
        a
    end cells

    // returns the previous depth; does not write at or past Limit, so the
    // shared overflow cell is never mutated
    @static def increase(slot: Int): Long =
        val depth = cells(slot)
        if depth < Limit then cells(slot) = depth + 1
        depth
    end increase

    @static def decrease(slot: Int): Unit =
        cells(slot) -= 1

    @static def slot(): Int =
        // getId, not threadId: threadId is absent from the Scala.js javalib and fails JS and
        // Wasm linking (it type-checks against the JDK, then breaks at link). getId is
        // deprecated-not-removed and returns the same identifier. This is shared code, so it
        // must link on every platform.
        val tid = Thread.currentThread().getId(): @scala.annotation.nowarn("cat=deprecation")
        val i   = tid.toInt & Mask
        if owners.get(i) == tid then i << Shift
        else slow(tid)
    end slot

    @static private def slow(tid: Long): Int =
        val self = Thread.currentThread()
        @tailrec def probe(i: Int, remaining: Int): Int =
            if remaining == 0 then Slots << Shift
            else
                val owner = owners.get(i)
                if owner == tid then i << Shift
                else if owner == 0L && owners.compareAndSet(i, 0L, Transferring) then claim(i, self, tid)
                else if owner > 0L && dead(i) && owners.compareAndSet(i, owner, Transferring) then claim(i, self, tid)
                else probe((i + 1) & Mask, remaining - 1)
                end if
        probe(tid.toInt & Mask, Probes)
    end slow

    @static private def dead(i: Int): Boolean =
        val t = threads.get(i)
        (t ne null) && !t.isAlive

    @static private def claim(i: Int, self: Thread, tid: Long): Int =
        threads.set(i, self)
        cells(i << Shift) = 0L
        owners.set(i, tid)
        i << Shift
    end claim

    @static private[kyo] def owned: Boolean =
        // getId, not threadId, so shared code links on Scala.js and Wasm; see `slot` above.
        val tid = Thread.currentThread().getId(): @scala.annotation.nowarn("cat=deprecation")
        @tailrec def scan(i: Int): Boolean =
            if i == Slots then false
            else if owners.get(i) == tid then true
            else scan(i + 1)
        scan(0)
    end owned

end Safepoint
