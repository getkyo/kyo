package kyo2.kernel

import kyo2.Test
import kyo2.kernel.Frame
import kyo2.kernel.Trace
import kyo2.kernel.TracePool

class TracePoolTest extends Test:

    class TestLocal extends TracePool.Local

    "borrow and release traces" in {
        val local = new TestLocal
        val trace = local.borrow()
        assert(trace != null)
        local.release(trace)
        succeed
    }

    "reuse released traces" in {
        val local  = new TestLocal
        val trace1 = local.borrow()
        local.release(trace1)
        val trace2 = local.borrow()
        assert(trace1 eq trace2)
    }

    "replenish from global pool when local pool is empty" in {
        val local  = new TestLocal
        val traces = List.fill(TracePool.localCapacity + 1)(local.borrow())
        assert(traces.forall(_ != null))
        traces.foreach(local.release)
        succeed
    }

    "clear traces when returning to the pool" in {
        val local = new TestLocal

        for _ <- 0 until TracePool.localCapacity * 1000 do
            val trace = local.borrow()
            trace.frames(0) = Frame.derive
            trace.frames(1) = Frame.derive
            trace.index = 2
            local.release(trace)
        end for

        for _ <- 0 until TracePool.localCapacity * 1000 do
            val recycledTrace = local.borrow()
            assert(recycledTrace.frames.forall(_ == null))
            assert(recycledTrace.index == 0)
        end for
        succeed
    }

end TracePoolTest
