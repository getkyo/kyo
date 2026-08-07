package kyo.kernel2

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import scala.annotation.static
import scala.annotation.tailrec

/** The kernel's runtime guard: a per-thread token gating eager execution behind depth accounting and preemption polling.
  *
  * A Safepoint identifies the current thread's claimed slot. Guarded transform executions bracket each frame with `enter` and `exit`:
  * `enter` refuses when the depth budget is exhausted or a preemption request is pending, and the refusing caller reroutes through a
  * rescue Defer so the trampoline unwinds the stack or surfaces the park. A single check serves both concerns: the preempt word is
  * folded into the depth read, so a pending request reads as depth-at-limit with no added branch.
  *
  * The preemption protocol has three roles, one method each. Requesters (`preempt`) publish their condition (promise state, preempt
  * flag) first, then store the request; the store cannot be lost since only the consumer writes it back to zero. Non-boundary drives
  * observe without consuming (`preempted`) and cascade the park outward. Boundary drives consume with `clearPreempt` before consulting
  * the authoritative state; the volatile exchange orders the requester's writes before that check, so a consumed request's condition is
  * always visible to the decision that follows.
  *
  * This is the successor of the current kernel's Safepoint: `enter` and `exit` replace its stack-depth accounting, and the preempt word
  * replaces its interceptor's preemption role.
  */
private[kyo] opaque type Safepoint = Int

private[kyo] object Safepoint:

    /** The current thread's safepoint. */
    inline def get: Safepoint = SafepointState.slot()

    /** The shared overflow safepoint: depth pinned at the limit so `enter` always refuses, exempt from preemption requests. */
    private[kyo] inline def Overflow: Safepoint = SafepointState.OverflowSlot

    private[kyo] def owned: Boolean = SafepointState.owned

    extension (self: Safepoint)
        /** Enters a guarded frame: false when the depth budget is exhausted or a preemption request is pending. */
        inline def enter(): Boolean = SafepointState.enter(self)

        /** Exits a frame entered successfully. Never call after a refused `enter`. */
        inline def exit(): Unit = SafepointState.exit(self)

        /** Requests preemption of this safepoint's thread. Publish the condition before calling. */
        inline def preempt(): Unit = SafepointState.preempt(self)

        /** Whether a preemption request is pending. Observation only; the cascade check for non-boundary drives. */
        inline def preempted: Boolean = SafepointState.preempted(self)

        /** Consumes a pending request, true when one was pending. Boundary drives call this before the authoritative check. */
        inline def clearPreempt(): Boolean = SafepointState.clearPreempt(self)
    end extension

end Safepoint

final private[kyo] class SafepointState private ()

private[kyo] object SafepointState:

    private inline def Limit        = 512
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
    // never written. Statics are hosted on the companion class so hot callers reach the counters
    // via invokestatic with no module load.
    @static private val cells =
        val a = new AtomicLongArray((Slots + 1) << Shift)
        a.setPlain(Slots << Shift, Limit)
        a
    end cells

    // the preempt word folds into the depth read: a pending request reads as >= Limit, refusing
    // the frame with the same compare that guards the depth budget and skipping the store, so the
    // refusal path stays write-free
    @static def enter(slot: Int): Boolean =
        val depth = cells.getPlain(slot) | cells.getOpaque(slot + 1)
        if depth < Limit then
            cells.setPlain(slot, depth + 1)
            true
        else false
        end if
    end enter

    @static def exit(slot: Int): Unit =
        cells.setPlain(slot, cells.getPlain(slot) - 1)

    // the store cannot be lost since only clearPreempt writes 0. No-op on the overflow slot, which
    // already refuses every frame.
    @static def preempt(slot: Int): Unit =
        if slot != OverflowSlot then cells.set(slot + 1, Flag)

    @static def preempted(slot: Int): Boolean =
        cells.getOpaque(slot + 1) != 0L

    // the exchange orders the requester's condition writes before the authoritative check that
    // follows; guarded so an idle check costs one resident load
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

end SafepointState
