package kyo.kernel

import internal.*
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import org.jctools.queues.MessagePassingQueue.Consumer
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec

private[kernel] object TracePool:
    inline def globalCapacity: Int = 1024
    inline def localCapacity: Int  = 32

    private val global = new MpmcArrayQueue[Trace](globalCapacity)

    abstract class Local:
        final private val pool = new Array[Trace](localCapacity)
        final private var size = 0

        final def borrow(): Trace =
            if size == 0 then
                if global.drain(add, localCapacity - size) == 0 then
                    return Trace.init
            size -= 1
            val buffer = pool(size)
            pool(size) = null
            buffer
        end borrow

        final private val add: Consumer[Trace] =
            (trace: Trace) =>
                if size < localCapacity then
                    pool(size) = trace
                    size += 1

        final def release(trace: Trace): Unit =
            clear(trace)
            if size < localCapacity then
                pool(size) = trace
                size += 1
            else
                global.offer(trace)
                ()
            end if
        end release

        final private def clear(trace: Trace) =
            val arr = trace.frames
            var i   = 0
            while i < maxTraceFrames && (arr(i) ne null) do
                arr(i) = null.asInstanceOf[Frame]
                i += 1
            end while
            trace.index = 0
        end clear
    end Local

end TracePool
