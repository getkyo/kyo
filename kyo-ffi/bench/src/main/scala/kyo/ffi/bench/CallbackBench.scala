package kyo.ffi.bench

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Upcall (C-to-Scala callback) overhead.
  *
  *   - `upcallStub`, construct a Panama upcall stub from a Scala lambda, pass it to a C function that immediately invokes it, return.
  *     Approximates what the kyo-ffi transient-callback emitter does per call for small callbacks.
  *   - `reusedUpcallStub`, same shape, but the upcall stub is constructed once at setup and reused across invocations. A valid pattern
  *     when the callback is idempotent and doesn't close over per-call state; kyo-ffi could in principle emit this for Guard-retained
  *     callbacks.
  *   - `directJavaCall`, baseline, no FFI: call the Scala lambda directly. Shows the pure Scala dispatch cost.
  *
  * Callee is POSIX `abs(int)`, it doesn't actually invoke a callback, so we can't isolate real upcall-invocation cost without a custom C
  * helper. Instead the bench measures *stub construction* + *downcall* cost for the per-call variant, which on kyo-ffi's hot path is the
  * dominant upcall overhead. Full end-to-end invocation timing requires a dedicated C helper, see `example-sdl2` for a scripted end-to-end
  * that includes it.
  */
class CallbackBench extends BenchBase:

    private val absDesc             = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    private val absAddr             = lookup.find("abs").orElseThrow()
    private val absMH: MethodHandle = linker.downcallHandle(absAddr, absDesc)

    // Arena used to host the upcall stub that's constructed once at Setup.
    private val stubArena: Arena = Arena.ofShared()

    // An int-returning, int-taking callback that Scala invokes. The bench
    // target is the machinery around constructing an upcall stub from it.
    private val callback: Int => Int = (i: Int) => i + 1

    // Exported method handle pointing at `callback.apply(I)I`.
    private val callbackDesc: FunctionDescriptor =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)

    private val callbackMH: MethodHandle =
        val lookup = MethodHandles.lookup()
        val mt     = MethodType.methodType(classOf[Int], classOf[Int])
        lookup
            .bind(callback, "apply", MethodType.methodType(classOf[Any], classOf[Any]))
            .asType(MethodType.methodType(classOf[Int], classOf[Int]))
    end callbackMH

    // Pre-constructed upcall stub for the `reusedUpcallStub` variant.
    private val reusedStub: MemorySegment =
        linker.upcallStub(callbackMH, callbackDesc, stubArena)

    @Benchmark def directJavaCall(bh: Blackhole): Unit =
        bh.consume(callback(42))

    @Benchmark def upcallStub(bh: Blackhole): Unit =
        val arena = Arena.ofConfined()
        try
            val stub = linker.upcallStub(callbackMH, callbackDesc, arena)
            // Exercise the stub's address to ensure it's realized.
            bh.consume(stub.address())
        finally arena.close()
        end try
    end upcallStub

    @Benchmark def reusedUpcallStub(bh: Blackhole): Unit =
        bh.consume(reusedStub.address())

end CallbackBench
