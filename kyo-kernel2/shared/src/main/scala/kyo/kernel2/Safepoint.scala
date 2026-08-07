package kyo.kernel2

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import scala.annotation.static
import scala.annotation.tailrec

/** The kernel's runtime guard: a per-thread token gating eager execution behind depth accounting and preemption polling.
  *
  * Each thread claims a Safepoint instance. Guarded transform executions obtain it with `get` and bracket each frame with `enter` and
  * `exit`: `enter` refuses when the depth budget is exhausted, and the refusing caller reroutes through a rescue Defer so the trampoline
  * unwinds the stack or surfaces the park.
  *
  * Preemption rides the same machinery with no cost on the entering path. A requester (`preempt`) publishes its condition (promise
  * state, preempt flag) first, then swaps the victim's slot to a wrapper instance whose depth is pinned at the limit. The victim's next
  * `get`, a volatile read it performs on every frame anyway, returns the wrapper, so every subsequent frame refuses and the park
  * cascades out to the drives; frames already entered keep exiting against the original instance, unaffected. Non-boundary drives
  * observe the wrapper without consuming it (`preempted`) and return their remainder. The boundary drive consumes it (`clearPreempt`),
  * swapping the original back before consulting the authoritative state; the swap is a volatile exchange, ordering the requester's
  * condition writes before that check. Delivery cannot be lost: both the request and its consumption are compare-and-swaps of the slot,
  * and the owner never writes its slot outside claiming.
  *
  * `preempted` and `clearPreempt` are meaningful on a freshly obtained Safepoint: a reference held from before the request still names
  * the original instance, which never reports pending. Drives comply naturally, calling `get` at every check site.
  *
  * Ownership is checked by thread reference identity, not id: the JVM may reuse a dead thread's id, and an id match could hand a new
  * thread a dead owner's instance while reclamation swaps it out underneath. A dead owner's slot is reclaimed by swapping in a fresh
  * instance, so no state of the previous owner, including a pending request, survives reclamation. The shared Overflow instance is
  * returned when no slot can be claimed: its depth is pinned at the limit, every frame refuses and trampolines, and preemption requests
  * to it are no-ops.
  *
  * This is the successor of the current kernel's Safepoint: `enter` and `exit` replace its stack-depth accounting, and the wrapper swap
  * replaces its interceptor's preemption role.
  */
final private[kyo] class Safepoint private (
    private[kernel2] val thread: Maybe[Thread],
    private val index: Int,
    private var depth: Long,
    private val original: Maybe[Safepoint]
):
    import Safepoint.*

    /** Enters a guarded frame: false when the depth budget is exhausted or this is the wrapper of a preempted thread. */
    def enter(): Boolean =
        val d = depth
        if d < Limit then
            depth = d + 1
            true
        else false
        end if
    end enter

    /** Exits a frame entered successfully. Never call after a refused `enter`. */
    def exit(): Unit =
        depth -= 1

    /** Requests preemption of this safepoint's thread. Publish the condition before calling. The request cannot be lost: it is a
      * compare-and-swap of the thread's slot, which the owner never writes outside claiming. No-op on the Overflow instance and on a
      * wrapper already carrying a request.
      */
    def preempt(): Unit =
        if thread.isDefined && original.isEmpty then
            val _ = slots.compareAndSet(index, this, new Safepoint(thread, index, Limit, Maybe(this)))
    end preempt

    /** Whether this safepoint carries a preemption request. Observation only; the cascade check for non-boundary drives. */
    def preempted: Boolean =
        original.isDefined

    /** Consumes a pending request, true when one was pending. Boundary drives call this before the authoritative check; the exchange
      * orders the requester's condition writes before that check.
      */
    def clearPreempt(): Boolean =
        original match
            case Present(o) =>
                val _ = slots.compareAndSet(index, this, o)
                true
            case Absent =>
                false
    end clearPreempt

    private[kernel2] def ownedBy(t: Thread): Boolean =
        thread.exists(_ eq t)

    private[kernel2] def alive: Boolean =
        thread.exists(_.isAlive)
end Safepoint

private[kyo] object Safepoint:

    private inline def Limit  = 512
    private inline def Slots  = 256
    private inline def Mask   = Slots - 1
    private inline def Probes = 8

    @static private val slots = new AtomicReferenceArray[Safepoint](Slots)

    /** The shared overflow safepoint: depth pinned at the limit so `enter` always refuses, exempt from preemption requests. */
    @static private[kyo] val Overflow: Safepoint = new Safepoint(Absent, -1, Limit, Absent)

    /** The current thread's safepoint, or the wrapper carrying its pending preemption request. */
    @static def get: Safepoint =
        val self = Thread.currentThread()
        // getId, not threadId: threadId is absent from the Scala.js javalib and fails JS and
        // Wasm linking (it type-checks against the JDK, then breaks at link). getId is
        // deprecated-not-removed and returns the same identifier. This is shared code, so it
        // must link on every platform.
        val tid = self.getId(): @scala.annotation.nowarn("cat=deprecation")
        val sp  = slots.get(tid.toInt & Mask)
        if (sp ne null) && sp.ownedBy(self) then sp
        else slow(self, tid)
    end get

    @static private def slow(self: Thread, tid: Long): Safepoint =
        @tailrec def probe(i: Int, remaining: Int): Safepoint =
            if remaining == 0 then Overflow
            else
                val sp = slots.get(i)
                if sp eq null then
                    val fresh = new Safepoint(Maybe(self), i, 0L, Absent)
                    if slots.compareAndSet(i, null, fresh) then fresh
                    else probe(i, remaining)
                else if sp.ownedBy(self) then sp
                else if !sp.alive then
                    // a dead owner's slot is reclaimed by swapping in a fresh instance, so no state
                    // of the previous owner, including a pending request, survives
                    val fresh = new Safepoint(Maybe(self), i, 0L, Absent)
                    if slots.compareAndSet(i, sp, fresh) then fresh
                    else probe(i, remaining)
                else probe((i + 1) & Mask, remaining - 1)
                end if
        probe(tid.toInt & Mask, Probes)
    end slow

    @static private[kyo] def owned: Boolean =
        val self = Thread.currentThread()
        @tailrec def scan(i: Int): Boolean =
            if i == Slots then false
            else
                val sp = slots.get(i)
                if (sp ne null) && sp.ownedBy(self) then true
                else scan(i + 1)
        scan(0)
    end owned

end Safepoint
