package kyo.proto.kernel.internal

import kyo.StaticFlag

private[kyo] class Safepoint

private object periodBounds extends (Int => Either[Throwable, Int]):
    def apply(n: Int): Either[Throwable, Int] = Right(Math.min(Math.max(1, n), 0x7fff))

object Safepoint:

    opaque type Slot >: Int = Int

    opaque type State = Int

    private inline def DepthGuard = 1 << 15
    private inline def Armed      = 1 << 30

    private[kyo] object period extends StaticFlag[Int](maxStackDepth, periodBounds)

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

        if Debugger.enabled && {
                val d = Debugger.get
                (d ne Debugger.Noop) && !(s.isArmed && stopped(slot)) && d.enter()
            }
        then
            true
        else
            depth = s.drained
            false
        end if
    end enterPark

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

    def deadline(d: Long): Unit =
        armedDeadline = d

    private[kyo] def stop(thread: Thread): Boolean = false

    private[kyo] def stop(thread: Thread, slice: AnyRef): Boolean = false

    private[kyo] def beginSlice(slot: Slot, slice: AnyRef): AnyRef = null

    private[kyo] def endSlice(slot: Slot, prev: AnyRef): Unit = ()

    private def expired(): Boolean =
        armedDeadline != Long.MaxValue && java.lang.System.currentTimeMillis() >= armedDeadline

    private[kyo] def stopped(slot: Slot): Boolean =
        expired()

    private[kyo] def consumeStopped(slot: Slot): Boolean =
        if expired() then
            armedDeadline = Long.MaxValue
            true
        else false

end Safepoint
