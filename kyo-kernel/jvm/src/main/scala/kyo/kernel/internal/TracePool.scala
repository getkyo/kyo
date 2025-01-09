package kyo.kernel.internal

import java.util.Arrays
import kyo.discard
import kyo.kernel.internal.*
import org.jctools.queues.MessagePassingQueue.Consumer
import org.jctools.queues.MpmcArrayQueue

private[kernel] object TracePool:
    inline def globalCapacity: Int = 8192
    inline def localCapacity: Int  = 32

    private val global = new MpmcArrayQueue[Trace](globalCapacity)

    abstract class Local:
        final private val pool = new Array[Trace](localCapacity)
        final private var size = 0

        final def borrow(): Trace =
            if size == 0 then
                discard(global.drain(add, localCapacity))
            if size == 0 then
                Trace.init
            else
                val idx = this.size - 1
                size = idx
                val buffer = pool(idx)
                pool(idx) = null
                buffer
            end if
        end borrow

        final private val add: Consumer[Trace] =
            (trace: Trace) =>
                val idx = size
                if (trace ne null) && idx < localCapacity then
                    pool(idx) = trace
                    size = idx + 1

        final def release(trace: Trace): Unit =
            clear(trace)
            if size < localCapacity then
                val idx = size
                size = idx + 1
                pool(idx) = trace
            else
                discard(global.offer(trace))
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
