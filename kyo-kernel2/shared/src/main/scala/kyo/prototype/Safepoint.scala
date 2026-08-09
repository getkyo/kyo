package kyo.prototype

final class Safepoint:
    private var depth = 0

    def enter(): Boolean =
        if depth < Safepoint.Period then
            depth += 1
            true
        else false

    def exit(): Unit =
        depth -= 1

    private[prototype] def save(): Int =
        val d = depth
        depth = 0
        d
    end save

    private[prototype] def restore(saved: Int): Unit =
        depth = saved
end Safepoint

object Safepoint:

    inline def Period = 512

    private val local = new ThreadLocal[Safepoint]:
        override def initialValue = new Safepoint

    def get: Safepoint = local.get
end Safepoint
