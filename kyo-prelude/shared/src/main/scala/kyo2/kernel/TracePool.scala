package kyo2.kernel

import internal.*
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

private[kernel] object TracePool:
    inline def globalCapacity: Int = 1024
    inline def localCapacity: Int  = 32

    object global extends AtomicInteger(globalCapacity - 1):
        private val pool = new Array[Trace](globalCapacity)

        def borrow(): Trace =
            @tailrec def borrowLoop(): Trace =
                val currentIndex = get()
                if currentIndex < 0 then Trace.init
                else if compareAndSet(currentIndex, currentIndex - 1) then
                    val frame = pool(currentIndex)
                    pool(currentIndex) = null
                    if frame eq null then Trace.init else frame
                else
                    borrowLoop()
                end if
            end borrowLoop
            borrowLoop()
        end borrow

        def release(trace: Trace): Unit =
            clear(trace)
            @tailrec def releaseLoop(): Unit =
                val currentIndex = get()
                if currentIndex < globalCapacity - 1 then
                    if compareAndSet(currentIndex, currentIndex + 1) then
                        pool(currentIndex + 1) = trace
                    else releaseLoop()
                end if
            end releaseLoop
            releaseLoop()
        end release

        private[TracePool] def replenish(localPool: Array[Trace]): Unit =
            val currentIndex   = get()
            val availableCount = math.min(currentIndex + 1, localPool.length)

            @tailrec def replenishPool(i: Int): Unit =
                if i < localPool.length then
                    if i < availableCount && compareAndSet(currentIndex, currentIndex - availableCount) then
                        val frame = pool(currentIndex - i)
                        localPool(i) = if frame eq null then Trace.init else frame
                        pool(currentIndex - i) = null
                        replenishPool(i + 1)
                    else
                        localPool(i) = Trace.init
                        replenishPool(i + 1)
            replenishPool(0)
        end replenish
    end global

    abstract class Local:
        final private val pool = new Array[Trace](localCapacity)
        final private var size = 0

        final def borrow(): Trace =
            if size == 0 then
                global.replenish(pool)
                size = localCapacity
            size -= 1
            val buffer = pool(size)
            pool(size) = null
            buffer
        end borrow

        final def release(trace: Trace): Unit =
            clear(trace)
            if size < localCapacity then
                pool(size) = trace
                size += 1
            else
                global.release(trace)
            end if
        end release
    end Local

    final private[kernel] def clear(trace: Trace) =
        val arr = trace.frames
        var i   = 0
        while i < maxTraceFrames && arr(i) != null do
            arr(i) = null.asInstanceOf[Frame]
            i += 1
        end while
        trace.index = 0
    end clear

end TracePool
