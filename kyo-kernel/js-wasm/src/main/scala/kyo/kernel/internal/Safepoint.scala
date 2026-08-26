package kyo.kernel.internal

import kyo.StaticFlag

// TODO this should be private[kernel]
private[kyo] class Safepoint

/** The single-threaded Safepoint: one cell where the JVM has a slot table.
  *
  * JS and wasm run every evaluation on the one thread, so there is no claiming, no liveness
  * probing, and no cross-thread stop. What remains is the depth-guard budget, the armed bit, and
  * the scheduler's preemption mechanism: the slice deadline, armed once at slice entry through
  * `arm(slot, deadline)` and read-only for the slice's extent. `stopped` answers whether it has
  * passed, playing the role the stop sentinel plays on the JVM, and `consumeStopped` takes it at
  * the slice boundary the way the sentinel is taken there.
  *
  * A deadline that never fired cannot leak into an eval the scheduler does not own: a bare eval's
  * polls are compiled out (`armed = false`), an expired deadline is consumed by the slice's own
  * boundary, and the next slice's arm overwrites whatever stands. The clock is read only when a
  * finite deadline is armed, so evaluations outside a slice never pay for it.
  *
  * The member surface mirrors the jvm-native variant exactly: shared inline expansions (the value
  * lift, `deferInline`, the eval loop) name these members from downstream modules, so visibility
  * levels match even where a narrower one would suffice on a single thread.
  */
object Safepoint:

    /** The lower bound lets a caller declare the variable holding a slot with a literal, and fill it only on the
      * path that resolves one. There is no upper bound, so a `Slot` still cannot be used as an `Int` out here.
      */
    opaque type Slot >: Int = Int

    opaque type State = Int

    private inline def DepthGuard = 1 << 15
    private inline def Armed      = 1 << 30

    private[kyo] object period extends StaticFlag[Int](512, n => Right(Math.min(Math.max(1, n), 0x7fff)))

    private[kyo] object State:

        private val Initial: State = DepthGuard | period()

        private[Safepoint] def init: State = Initial

        extension (self: State)
            private[Safepoint] inline def drained: State   = (self & Armed) | DepthGuard
            private[Safepoint] inline def reset: State     = (self & Armed) | Initial
            private[Safepoint] inline def armed: State     = self | Armed
            private[Safepoint] inline def isArmed: Boolean = (self & Armed) != 0
        end extension
    end State

    import State.*

    private var depth: State        = State.init
    private var armedDeadline: Long = Long.MaxValue

    def get(): Slot =
        // an armed cell whose deadline has passed starts drained, so a fresh eval reaches its poll
        // at the first application: the counterpart of the jvm path that drains on a pending stop
        if depth.isArmed && expired() then depth = depth.drained
        0
    end get

    def enter(slot: Slot): Boolean =
        val s  = depth
        val s2 = s - 1
        if (s2 & DepthGuard) != 0 then
            depth = s2
            true
        else enterPark(slot, s)
        end if
    end enter

    private def enterPark(slot: Slot, s: State): Boolean =
        val d = Debugger.get
        // preemption outranks the session: an armed slice with a stop pending refuses whatever the
        // gate would say, so the eval parks instead of running the program to completion unobserved
        if (d ne Debugger.Noop) && !(s.isArmed && stopped(slot)) && d.enter() then
            true
        else
            depth = s.drained
            false
        end if
    end enterPark

    /** Exhausts the budget so every strict application lands in `enterPark`, where the debugger's
      * gate lives.
      */
    private[kyo] def drain(slot: Slot): Unit =
        depth = depth.drained

    def exit(slot: Slot): Unit =
        depth = depth + 1

    def save(slot: Slot): State =
        val d = depth
        depth = State.init
        d
    end save

    def restore(slot: Slot, saved: State): Unit =
        depth = saved

    private[kyo] def reset(slot: Slot): Unit =
        depth = depth.reset

    private[kyo] def arm(slot: Slot): Unit =
        depth = depth.armed

    /** The scheduler's slice deadline: the only preemption source on a single thread, since no
      * live thread exists to deliver a stop. Written once at slice entry, read-only for the
      * slice's extent (the budget drains compare against it through `stopped`), and consumed at
      * the slice boundary. The arming itself stays the eval's job, as on the JVM.
      */
    def deadline(d: Long): Unit =
        armedDeadline = d

    /** No live thread can be stopped from outside on a single-threaded runtime. */
    private[kyo] def stop(thread: Thread): Boolean = false

    private def expired(): Boolean =
        armedDeadline != Long.MaxValue && java.lang.System.currentTimeMillis() >= armedDeadline

    /** Whether the slice deadline has passed, without taking it: the poll's read, answering what
      * `stopped` answers on the JVM for a pending stop.
      */
    private[kyo] def stopped(slot: Slot): Boolean =
        expired()

    private[kyo] def consumeStopped(slot: Slot): Boolean =
        if expired() then
            armedDeadline = Long.MaxValue
            true
        else false

end Safepoint
