package kyo.ffi.bench

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kyo.ffi.internal.UpcallBridge
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Specialised-vs-generic upcall bridge cost for the `II_I` shape.
  *
  * Compares:
  *   - `genericStub2`, `UpcallBridge.stub2` (generic path). Uses `MethodHandle.asType` to bridge between the erased
  *     `FunctionN.apply(Object...)Object` signature and the descriptor's primitive MethodType. Boxing adapters are installed per argument.
  *   - `specialisedStubII_I`, `UpcallBridge.stubShape_II_I`. MethodHandle is built directly from a primitive-typed static invoker, no
  *     `asType` adapter chain on the hot path.
  *
  * Both variants are measured at the **stub construction + downcall invocation** granularity (the bench constructs the stub, reads its
  * address, closes the arena). This mirrors the shape of a transient-callback call site in generated code, and isolates the specialisation
  * benefit at the MethodHandle layer. Real end-to-end upcall invocation requires a C helper that actually calls back; see `example-sdl2`
  * for a scripted end-to-end.
  *
  * Expected: `specialisedStubII_I` runs in ≲ `genericStub2` time, the win is one to a few hundred nanoseconds per call depending on JIT
  * state. The bench does not assert; the delta is reported by JMH for inspection.
  */
class SpecializedCallbackBench extends BenchBase:

    private val cmpDesc: FunctionDescriptor =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)

    private val cmp: (Int, Int) => Int = (a, b) => a - b

    @Benchmark def genericStub2(bh: Blackhole): Unit =
        val arena = Arena.ofConfined().nn
        try
            val stub = UpcallBridge.stub2(cmp, cmpDesc, arena, "kyo.example.Bench", "genericStub2", "transient")
            bh.consume(stub.address())
        finally arena.close()
        end try
    end genericStub2

    @Benchmark def specialisedStubII_I(bh: Blackhole): Unit =
        val arena = Arena.ofConfined().nn
        try
            val stub = UpcallBridge.stubShape_II_I(cmp, cmpDesc, arena, "kyo.example.Bench", "specialisedStubII_I", "transient")
            bh.consume(stub.address())
        finally arena.close()
        end try
    end specialisedStubII_I

end SpecializedCallbackBench
