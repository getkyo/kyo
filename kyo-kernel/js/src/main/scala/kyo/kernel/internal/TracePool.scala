package kyo.kernel.internal

import kyo.Frame

private[kernel] object TracePool:
    inline def globalCapacity: Int = 0
    inline def localCapacity: Int  = 1024

    abstract class Local:
        final private val pool = new Array[Trace](localCapacity)
        final private var size = 0

        final def borrow(): Trace =
            if size == 0 then
                return Trace.init
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
