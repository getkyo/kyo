package kyo2.kernel

import kyo2.Test
import kyo2.kernel.Frame
import kyo2.kernel.Trace
import kyo2.kernel.TracePool

class TracePoolTest extends Test:

    "global" - {
        "borrow and release traces" in {
            val trace = TracePool.global.borrow()
            assert(trace != null)
            TracePool.global.release(trace)
            succeed
        }

        "return new trace when pool is empty" in {
            val traces = List.fill(TracePool.globalCapacity + 1)(TracePool.global.borrow())
            assert(traces.forall(_ != null))
            traces.foreach(TracePool.global.release)
            succeed
        }

        "reuse released traces" in {
            val trace1 = TracePool.global.borrow()
            TracePool.global.release(trace1)
            val trace2 = TracePool.global.borrow()
            assert(trace1 eq trace2)
        }
    }

    "Local" - {
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
    }

    "clear" - {
        "reset trace state" in {
            val trace = Trace.init
            val frame = Frame.derive
            trace.frames(0) = frame
            trace.index = 1

            TracePool.clear(trace)

            assert(trace.frames(0) == null)
            assert(trace.index == 0)
        }
    }
end TracePoolTest
