package kyo.proto

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.StaticFlag
import scala.annotation.static
import scala.annotation.tailrec

/** The strict-recursion budget. A thread owns a slot holding its remaining depth; every strict step enters and exits, and a step that
  * finds the budget drained is deferred instead of applied. Preemption is not here yet.
  */
class Safepoint

object Safepoint:

    opaque type Slot = Int

    opaque type State = Int

    private inline def LineStride = 8

    @static private val Slots      = slotCount()
    @static private val Overflowed = Slots
    @static private val depths =
        val a = new Array[State](Slots + 1)
        a(Slots) = State.init
        a
    end depths
    @static private val slots = new AtomicReferenceArray[Thread](Slots + 1)
    @static private val local = new ThreadLocal[Integer]

    private[proto] object period extends StaticFlag[Int](512, n => Right(Math.min(Math.max(1, n), 0x7fff)))

    private[proto] object slotCount extends StaticFlag[Int](
            65536,
            n =>
                if Integer.bitCount(n) == 1 then Right(n)
                else Left(new IllegalArgumentException(s"slotCount must be a power of two, got $n"))
        )

    object State:
        private inline def DepthGuard = 1 << 15

        private val Initial: State = DepthGuard | period()

        private[Safepoint] def init: State = Initial

        extension (self: State)
            private[Safepoint] inline def enterInto(slot: Int): Boolean =
                val s2 = self - 1
                if (s2 & DepthGuard) == DepthGuard then
                    depths(slot) = s2
                    true
                else enterDrained(slot)
                end if
            end enterInto

            private[Safepoint] inline def decrementDepth: State = self + 1
        end extension

        private[Safepoint] def drained: State = DepthGuard
    end State

    import State.*

    @static private def home(thread: Thread): Int =
        ((thread.threadId * LineStride) & (Slots - 1)).toInt

    @static def get(): Slot =
        val thread = Thread.currentThread()
        val h      = home(thread)
        if slots.get(h) eq thread then h
        else resolve(thread, h)
    end get

    @static private def resolve(thread: Thread, h: Int): Slot =
        @tailrec def claim(i: Int, probes: Int): Int =
            if probes == Slots then Overflowed
            else
                val idx   = i & (Slots - 1)
                val owner = slots.get(idx)
                val free  = (owner eq null) || !owner.isAlive
                if !free then claim(i + 1, probes + 1)
                else if slots.compareAndSet(idx, owner, thread) then
                    depths(idx) = State.init
                    idx
                else claim(i, probes)
                end if
        end claim

        val cached = local.get()
        if cached ne null then cached.intValue()
        else
            val slot = claim(h, 0)
            local.set(Integer.valueOf(slot))
            slot
        end if
    end resolve

    @static def enter(slot: Slot): Boolean =
        depths(slot).enterInto(slot)

    @static private def enterDrained(slot: Slot): Boolean =
        depths(slot) = State.drained
        false
    end enterDrained

    @static def exit(slot: Slot): Unit =
        depths(slot) = depths(slot).decrementDepth

    @static def save(slot: Slot): State =
        val d = depths(slot)
        depths(slot) = State.init
        d
    end save

    @static def restore(slot: Slot, saved: State): Unit =
        depths(slot) = saved

end Safepoint
