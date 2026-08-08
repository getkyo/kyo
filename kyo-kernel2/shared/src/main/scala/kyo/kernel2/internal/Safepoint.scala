package kyo.kernel2.internal

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import scala.annotation.static
import scala.annotation.tailrec

/** The kernel's runtime guard: a per-thread token gating eager execution behind depth accounting and preemption polling.
  *
  * A thread's safepoint is a two-state machine held in its claimed slot. `Active` performs the real accounting: guarded transform
  * executions obtain the current state with `get` and bracket each frame with `enter` and `exit`, and `enter` refuses when the depth
  * budget is exhausted, rerouting the caller through a rescue Defer so the trampoline unwinds the stack. `Parked` refuses every frame:
  * it is installed by a preemption request and carries the `Active` to restore, and the shared Overflow token is the degenerate
  * permanently parked state (no slot could be claimed, nothing to restore).
  *
  * Preemption is a state transition, and the poll costs nothing on the entering path. A requester (`preempt`) publishes its condition
  * (promise state, preempt flag) first, then compare-and-swaps the victim's slot from its `Active` to a `Parked` carrying it. The
  * victim's next `get`, a volatile read it performs on every frame anyway, returns the parked state, so every subsequent frame refuses
  * and the park cascades out to the drives; frames already entered keep exiting against the `Active` they captured, unaffected.
  * Non-boundary drives observe without consuming (`preempted`) and return their remainder. The boundary drive consumes
  * (`clearPreempt`), swapping the `Active` back before consulting the authoritative state; the exchange orders the requester's
  * condition writes before that check. Delivery cannot be lost: both transitions are compare-and-swaps of the slot, which the owner
  * never writes outside claiming.
  *
  * `preempted` and `clearPreempt` live on the companion and read the current thread's slot, so a stashed token cannot be asked a
  * question only the slot can answer. Ownership is checked by thread reference identity, not id: the JVM may reuse a dead thread's id,
  * and an id match could hand a new thread a dead owner's token while reclamation swaps it out underneath. Reclamation installs a fresh
  * `Active`, so no state of the previous owner, including a pending request, survives.
  *
  * This is the successor of the current kernel's Safepoint: `enter` and `exit` replace its stack-depth accounting, and the parked
  * transition replaces its interceptor's preemption role.
  */
sealed abstract private[kyo] class Safepoint(private[kernel2] val thread: Maybe[Thread]):

    /** Enters a guarded frame: false when the depth budget is exhausted or this safepoint is parked. */
    def enter(): Boolean

    /** Exits a frame entered successfully. Never call after a refused `enter`. */
    def exit(): Unit

    /** Requests preemption of this safepoint's thread. Publish the condition before calling. The request cannot be lost: it is a
      * compare-and-swap of the thread's slot, which the owner never writes outside claiming. No-op on a parked safepoint.
      */
    def preempt(): Unit

    /** Opens a fresh depth budget for a drive, returning the caller's depth to restore with [[closeDrive]].
      *
      * A drive is a trampoline: its real stack restarts at the drive's own frame, so frames the caller has already committed must not
      * count against it. Without the reset, a drive nested inside eager recursion that exhausted the budget can never enter a frame, and
      * its rescue regenerates the same deferred step forever.
      */
    private[kyo] def openDrive(): Long

    /** Restores the depth saved by [[openDrive]]. */
    private[kyo] def closeDrive(saved: Long): Unit

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

    final private[kernel2] class Active private[Safepoint] (thread: Maybe[Thread], private[Safepoint] val index: Int)
        extends Safepoint(thread):

        private var depth = 0L

        def enter(): Boolean =
            val d = depth
            if d < Limit then
                depth = d + 1
                true
            else false
            end if
        end enter

        def exit(): Unit =
            depth -= 1

        def preempt(): Unit =
            val _ = slots.compareAndSet(index, this, new Parked(Present(this), thread))

        private[kyo] def openDrive(): Long =
            val d = depth
            depth = 0L
            d
        end openDrive

        private[kyo] def closeDrive(saved: Long): Unit =
            depth = saved
    end Active

    // TODO isn't a bette rname for this Preempt? it's odd to think a safepoint would be parked
    final private[kernel2] class Parked private[Safepoint] (
        private[Safepoint] val resume: Maybe[Active],
        thread: Maybe[Thread]
    ) extends Safepoint(thread):
        def enter(): Boolean = false
        def exit(): Unit     = ()
        def preempt(): Unit  = ()

        private[kyo] def openDrive(): Long             = 0L
        private[kyo] def closeDrive(saved: Long): Unit = ()
    end Parked

    @static private val slots = new AtomicReferenceArray[Safepoint](Slots) // TODO let's use Maybe[Safepoint] if no perf overhead

    /** The shared overflow safepoint: permanently parked, every frame refuses and trampolines, preemption requests are no-ops. */
    // TODO this seems quite drastic? is it better to simply fail? It's better to let a task run without preemption/interruption/stack safety than make it never make progress
    @static private[kyo] val Overflow: Safepoint = new Parked(Absent, Absent)

    /** The current thread's safepoint state. */
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

    /** Whether the current thread has a pending preemption request. Observation only; the cascade check for non-boundary drives. */
    @static def preempted: Boolean =
        get match
            case parked: Parked => parked.resume.isDefined
            case _              => false

    /** Consumes the current thread's pending request, true when one was pending. Boundary drives call this before the authoritative
      * check; the exchange orders the requester's condition writes before that check.
      */
    // TODO do we have cases where preempted clearing wouldn't be enough?
    @static def clearPreempt(): Boolean =
        get match
            case parked: Parked =>
                parked.resume match
                    case Present(active) =>
                        val _ = slots.compareAndSet(active.index, parked, active)
                        true
                    case Absent =>
                        false
            case _ =>
                false

    @static private def slow(self: Thread, tid: Long): Safepoint =
        @tailrec def probe(i: Int, remaining: Int): Safepoint =
            if remaining == 0 then Overflow
            else
                val sp = slots.get(i)
                if sp eq null then
                    val fresh = new Active(Maybe(self), i)
                    if slots.compareAndSet(i, null, fresh) then fresh
                    else probe(i, remaining)
                else if sp.ownedBy(self) then sp
                else if !sp.alive then
                    // a dead owner's slot is reclaimed by installing a fresh Active, so no state
                    // of the previous owner, including a pending request, survives
                    val fresh = new Active(Maybe(self), i)
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
