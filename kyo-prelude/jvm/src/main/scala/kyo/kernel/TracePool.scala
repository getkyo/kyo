package kyo.kernel

import internal.*
import java.util.Arrays
import org.jctools.queues.MessagePassingQueue.Consumer
import org.jctools.queues.MpmcArrayQueue

private[kernel] object TracePool:
    inline def globalCapacity: Int = 10240
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
            val len = math.min(trace.index, maxTraceFrames)
            Arrays.fill(arr.asInstanceOf[Array[AnyRef]], 0, len, null)
            trace.index = 0
        end clear
    end Local

end TracePool
