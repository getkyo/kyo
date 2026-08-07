package kyo.kernel2

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import scala.annotation.static
import scala.annotation.tailrec

/** The kernel's runtime guard: per-thread depth accounting and preemption polling behind the stack-safety rescue.
  *
  * Each claimed slot spans one cache line holding two words. The depth word brackets every guarded transform execution with a
  * single-writer increase and decrease; crossing the limit reroutes the execution through a rescue Defer so the trampoline unwinds the
  * stack. The preempt word next to it is a 0-or-Limit flag written only by requesters (volatile store) and the consuming boundary drive
  * (volatile exchange). `increase` folds it into the depth it returns, so a pending request reads as depth-at-limit: the caller's
  * existing limit compare doubles as the preemption poll with no added branch, and the fold also suppresses the depth store, keeping the
  * rescue path write-free.
  *
  * Requesters publish their condition (promise state, preempt flag) before storing the flag, and boundary drives consume with
  * `clearPreempt` before consulting that state; the volatile exchange orders the requester's writes before the check, so a consumed
  * request's condition is always visible to the decision that follows. The flag cannot be lost: only `clearPreempt` writes 0, and the
  * depth word is never written by another thread.
  *
  * Slots are claimed per thread and reclaimed from dead threads, wiping both words; the shared overflow slot is pinned at the limit,
  * never written, and exempt from preemption requests since it already rescues every frame. Statics are hosted on the companion class so
  * hot callers reach the counters via invokestatic with no module load.
  *
  * This is the successor of the current kernel's Safepoint: the depth words replace its stack-depth accounting and the preempt word
  * replaces its interceptor's preemption role.
  */
final private[kyo] class Safepoint private ()

private[kyo] object Safepoint:

    inline def Limit = 512

    private inline def Slots        = 256
    private inline def Mask         = Slots - 1
    private inline def Probes       = 8
    private inline def Shift        = 3
    private inline def Transferring = -1L
    private inline def Flag         = Limit.toLong

    @static private val owners  = new AtomicLongArray(Slots)
    @static private val threads = new AtomicReferenceArray[Thread](Slots)

    @static private[kyo] val OverflowSlot: Int = Slots << Shift

    // one cache line per claimed slot: the depth word at the slot index (single-writer plain), the
    // preempt word right after it (atomics only). The overflow depth word is pinned at Limit and
    // never written.
    @static private val cells =
        val a = new AtomicLongArray((Slots + 1) << Shift)
        a.setPlain(Slots << Shift, Limit)
        a
    end cells

    // returns the previous depth with the preempt word folded in: a pending request reads as
    // >= Limit, routing the caller onto the rescue trampoline and suppressing the depth store
    @static def increase(slot: Int): Long =
        val depth = cells.getPlain(slot) | cells.getOpaque(slot + 1)
        if depth < Limit then cells.setPlain(slot, depth + 1)
        depth
    end increase

    @static def decrease(slot: Int): Unit =
        cells.setPlain(slot, cells.getPlain(slot) - 1)

    // requesters publish their condition before calling; the store cannot be lost since only
    // clearPreempt writes 0. No-op on the overflow slot, which already rescues every frame.
    @static def preempt(slot: Int): Unit =
        if slot != OverflowSlot then cells.set(slot + 1, Flag)

    // observe without consuming: the cascade check for non-boundary drives
    @static def preempted(slot: Int): Boolean =
        cells.getOpaque(slot + 1) != 0L

    // boundary drives, consume-then-check: the exchange orders the requester's condition writes
    // before the authoritative check that follows. Guarded so an idle stride costs one resident load.
    @static def clearPreempt(slot: Int): Boolean =
        preempted(slot) && cells.getAndSet(slot + 1, 0L) != 0L

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
            if remaining == 0 then OverflowSlot
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
        cells.setPlain(i << Shift, 0L)
        // wipe a stale request left by the slot's previous owner; volatile so the wipe is ordered
        // before the owner publication below
        cells.set((i << Shift) + 1, 0L)
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
