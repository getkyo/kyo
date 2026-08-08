package kyo.kernel2.internal

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import scala.annotation.tailrec

/** The kernel's runtime guard: a per-thread token gating eager execution behind depth accounting and preemption polling.
  *
  * A thread's safepoint is a two-state machine held in its home, the one synchronization location for that thread: its claimed slot in
  * the shared array, or its own cell when the array is full. `Active` performs the real accounting: guarded transform executions obtain
  * the current state with `get` and bracket each frame with `enter` and `exit`, and `enter` refuses when the depth budget is exhausted,
  * rerouting the caller through a rescue Defer so the trampoline unwinds the stack. `Preempted` is a pending request: it refuses every
  * frame, so the park cascades out to the drives, and carries the `Active` to restore.
  *
  * Preemption is a state transition, and the poll costs nothing on the entering path. A requester publishes its condition (promise
  * state, scheduler flag) first, then compare-and-swaps the victim's home from its `Active` to a `Preempted` carrying it. The victim's
  * next `get`, a volatile read it performs on every frame anyway, returns the pending state, so every subsequent frame refuses; frames
  * already entered keep exiting against the `Active` they captured. The drives poll at the top of their loop: a Preemptible drive
  * returns the remainder and consumes the request once at exit, a Cascade drive returns without consuming so the request reaches the
  * enclosing slice, and a Masked drive absorbs the request at each poll and re-issues it at exit. Delivery cannot be lost: both
  * transitions are compare-and-swaps of the home, which the owner never writes outside claiming.
  *
  * A thread that cannot claim a slot is detached: it receives an `Active` backed by its own cell, cached in a ThreadLocal for the life
  * of the thread, keeping progress, the depth guard, and preemption delivery; only detached threads pay the ThreadLocal and cell reads.
  * Ownership is checked by thread reference identity, not id: the JVM may reuse a dead thread's id. Reclamation installs a fresh
  * `Active`, so no state of a previous owner, including a pending request, survives.
  */
sealed abstract private[kyo] class Safepoint(private[kernel2] val thread: Thread):

    /** Enters a guarded frame: false when the depth budget is exhausted or a preemption request is pending. */
    def enter(): Boolean

    /** Exits a frame entered successfully. Never call after a refused `enter`. */
    def exit(): Unit

    /** Requests preemption of this safepoint's thread. Publish the condition before calling. No-op when a request is already pending. */
    def preempt(): Unit

    /** Opens a fresh depth budget for a drive, returning the caller's depth to restore with [[closeDrive]].
      *
      * A drive is a trampoline: its real stack restarts at the drive's own frame, so frames the caller has already committed must not
      * count against it.
      */
    private[kyo] def openDrive(): Long

    /** Restores the depth saved by [[openDrive]]. */
    private[kyo] def closeDrive(saved: Long): Unit

    /** Disarms the slice deadline. */
    private[kyo] def endSlice(): Unit

    private[kernel2] def ownedBy(t: Thread): Boolean =
        thread eq t

    private[kernel2] def alive: Boolean =
        thread.isAlive
end Safepoint

private[kyo] object Safepoint:

    /** Frames between two trampoline bounces: the eager depth budget, the chain segment length, and therefore the preemption poll
      * cadence. One constant, never overridable through any API.
      */
    private[kyo] inline def Period = 512

    private inline def Slots  = 256
    private inline def Mask   = Slots - 1
    private inline def Probes = 8

    /** Where a thread's safepoint state lives: its claimed array slot, or its own cell when detached. Consulted on the cold paths only
      * (request, consume, detached get); the hot `get` fast path reads the array directly.
      */
    sealed private trait Current:
        def current: Safepoint
        def swap(expect: Safepoint, update: Safepoint): Boolean

    final private class Slot(index: Int) extends Current:
        def current: Safepoint                                  = slots.get(index)
        def swap(expect: Safepoint, update: Safepoint): Boolean = slots.compareAndSet(index, expect, update)

    final private class Cell extends AtomicReference[Safepoint] with Current:
        def current: Safepoint                                  = this.get()
        def swap(expect: Safepoint, update: Safepoint): Boolean = compareAndSet(expect, update)

    final private[kernel2] class Active private[Safepoint] (thread: Thread, private[Safepoint] val home: Current)
        extends Safepoint(thread):

        // plain single-writer fields owned by the thread: the state swap never touches
        // them, so a request cannot corrupt depth accounting or the deadline
        private var depth    = 0L
        private var deadline = Long.MaxValue
        private var masked   = false

        def enter(): Boolean =
            val d = depth
            if d < Period then
                depth = d + 1
                true
            else false
            end if
        end enter

        def exit(): Unit =
            depth -= 1

        def preempt(): Unit =
            val _ = home.swap(this, new Preempted(this, thread))

        private[kyo] def openDrive(): Long =
            val d = depth
            depth = 0L
            d
        end openDrive

        private[kyo] def closeDrive(saved: Long): Unit =
            depth = saved

        private[Safepoint] def arm(deadlineMillis: Long): Unit =
            deadline = deadlineMillis

        private[kyo] def endSlice(): Unit =
            deadline = Long.MaxValue

        private[Safepoint] def expired: Boolean =
            deadline != Long.MaxValue && java.lang.System.currentTimeMillis() >= deadline

        private[Safepoint] def markMasked(): Unit =
            masked = true

        private[Safepoint] def takeMasked(): Boolean =
            val m = masked
            masked = false
            m
        end takeMasked
    end Active

    /** A pending preemption request: refuses every frame so the park cascades to the drives; `restore` is the state to put back. */
    final private[kernel2] class Preempted private[Safepoint] (
        private[Safepoint] val restore: Active,
        thread: Thread
    ) extends Safepoint(thread):
        def enter(): Boolean = false
        def exit(): Unit     = ()
        def preempt(): Unit  = ()

        // a drive entered while a request is pending must still reset the depth budget
        // on the Active a later consume restores, or a Masked drive would inherit the
        // caller's exhausted budget and crawl one frame per bounce
        private[kyo] def openDrive(): Long             = restore.openDrive()
        private[kyo] def closeDrive(saved: Long): Unit = restore.closeDrive(saved)
        private[kyo] def endSlice(): Unit              = restore.endSlice()
    end Preempted

    private val slots = new AtomicReferenceArray[Safepoint](Slots)

    private val detached = new ThreadLocal[Cell]

    /** The current thread's safepoint state. */
    def get: Safepoint =
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

    /** Whether a request is pending for this thread, converting an expired slice deadline into an ordinary request first.
      * Observation only; consumption is [[clearPreempt]].
      */
    def pollPreempt(): Boolean =
        get match
            case _: Preempted => true
            case a: Active =>
                if a.expired then
                    a.endSlice()
                    a.preempt()
                    true
                else false

    /** Consumes the pending request, true when one was pending. The exchange orders the requester's condition writes before the
      * caller's subsequent authoritative check.
      */
    def clearPreempt(): Boolean =
        get match
            case p: Preempted =>
                val _ = p.restore.home.swap(p, p.restore)
                true
            case _ =>
                false

    /** Masked-region absorb: consumes a pending request and records it for re-issue at the region's exit, so the region keeps
      * making progress while the enclosing slice still sees the request.
      */
    def maskPreempt(): Unit =
        get match
            case p: Preempted =>
                if p.restore.home.swap(p, p.restore) then p.restore.markMasked()
            case _ =>
                ()

    /** Masked-region exit: re-issues an absorbed request. A request that landed fresh during the region supersedes the recorded one. */
    def unmaskPreempt(): Unit =
        get match
            case a: Active =>
                if a.takeMasked() then a.preempt()
            case p: Preempted =>
                val _ = p.restore.takeMasked()

    /** Arms the slice deadline and returns the instance the slice owner publishes for requesters. */
    def beginSlice(deadlineMillis: Long): Safepoint =
        val sp = get
        sp match
            case a: Active    => a.arm(deadlineMillis)
            case p: Preempted => p.restore.arm(deadlineMillis)
        sp
    end beginSlice

    private def slow(self: Thread, tid: Long): Safepoint =
        val d = detached.get()
        if d ne null then d.current
        else
            @tailrec def probe(i: Int, remaining: Int): Safepoint =
                if remaining == 0 then detach(self)
                else
                    val sp = slots.get(i)
                    if sp eq null then
                        val fresh = new Active(self, new Slot(i))
                        if slots.compareAndSet(i, null, fresh) then fresh
                        else probe(i, remaining)
                    else if sp.ownedBy(self) then sp
                    else if !sp.alive then
                        // a dead owner's slot is reclaimed by installing a fresh Active, so no state
                        // of the previous owner, including a pending request, survives
                        val fresh = new Active(self, new Slot(i))
                        if slots.compareAndSet(i, sp, fresh) then fresh
                        else probe(i, remaining)
                    else probe((i + 1) & Mask, remaining - 1)
                    end if
            probe(tid.toInt & Mask, Probes)
        end if
    end slow

    /** The full-array fallback: a detached safepoint backed by the thread's own cell, keeping progress, the depth guard, and
      * preemption delivery. A detached thread never migrates back to a slot: probing per frame for its lifetime costs more than the
      * detached path, and a migration would split depth accounting across two instances.
      */
    private def detach(self: Thread): Safepoint =
        val cell   = new Cell
        val active = new Active(self, cell)
        cell.set(active)
        detached.set(cell)
        active
    end detach

    private[kyo] def owned: Boolean =
        val self = Thread.currentThread()
        @tailrec def scan(i: Int): Boolean =
            if i == Slots then false
            else
                val sp = slots.get(i)
                if (sp ne null) && sp.ownedBy(self) then true
                else scan(i + 1)
        (detached.get() ne null) || scan(0)
    end owned

end Safepoint
