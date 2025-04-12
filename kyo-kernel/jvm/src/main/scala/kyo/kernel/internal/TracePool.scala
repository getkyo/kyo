package kyo.kernel.internal

import java.util.Arrays
import kyo.discard
import kyo.kernel.internal.*
import org.jctools.queues.MessagePassingQueue.Consumer
import org.jctools.queues.MpmcArrayQueue

/** A concurrent two-level object pooling system for managing Trace objects used in execution tracking.
  *
  * TracePool uses a combination of thread-local and global pooling to minimize contention while efficiently reusing Trace objects. Each
  * thread maintains its own local pool, with overflow being handled by a shared global pool.
  *
  * The pool operates with two hierarchical levels: a thread-local pool which provides a fast path with no synchronization, acting as first
  * level for both allocation and recycling using a fixed size array with simple index management. Local pools are never shared between
  * threads - each thread has exclusive access to its own Local instance with no synchronization needed. The global pool handles overflow
  * from local pools and serves as a source for local pool replenishment, maintaining thread-safety through a non-blocking concurrent queue.
  *
  * To minimize contention on the global queue, traces are transferred in batches - when a local pool runs empty, it will attempt to drain
  * multiple traces from the global queue in a single operation rather than accessing it repeatedly.
  *
  * The pool is designed to work in conjunction with Safepoint's thread ownership model, ensuring traces are properly isolated between
  * threads while allowing efficient reuse of resources. All traces are automatically cleared before reuse to prevent information leaks
  * between different execution contexts.
  */
private[kernel] object TracePool:
    inline def globalCapacity: Int = 8192
    inline def localCapacity: Int  = 32

    private val global = new MpmcArrayQueue[Trace](globalCapacity)

    abstract class Local extends Serializable:
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
