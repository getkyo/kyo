package kyo.ffi.bench

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import org.openjdk.jmh.annotations.*

/** Validates the "kyo-ffi overhead" claim end-to-end: a hand-written Panama downcall vs. the exact code shape the `JvmEmitter` produces for
  * the same binding.
  *
  * Both variants call POSIX `strlen` on the same pre-allocated NUL-terminated off-heap buffer. The only difference is the surface:
  *
  *   - `handPanama`, direct `MethodHandle.invokeExact(MemorySegment)` with the handle cached as a private val on this class. This is the
  *     lower bound: what a user would write if they bypassed kyo-ffi and used Panama directly.
  *   - `generatedShape`, routes through a concrete `StrlenBindingImpl` that implements a user-facing `StrlenBinding extends Ffi` trait
  *     with a `strlen(Buffer[Byte]): Long` method. The impl class mirrors the `JvmEmitter`'s code shape: per-binding cached `MethodHandle`
  *     in a companion, trait-dispatched method that unwraps the `Buffer[Byte]`'s raw `MemorySegment` and invokes the handle. This is the
  *     worked cost that a kyo-ffi user pays, including virtual dispatch through the `Ffi` trait.
  *
  * `CallOverheadBench` covers a similar comparison inline (MethodHandle-to-MethodHandle); this bench adds the generated **trait-dispatch**
  * overhead. The delta vs `handPanama` quantifies what kyo-ffi adds on top of raw Panama for the simplest possible binding.
  *
  * Expectation: the delta should be on the order of a single virtual call (a few ns); if it grows to tens of ns the emitter is adding
  * unexpected indirection and the generator output is worth a second look.
  */
class PanamaVsKyoFfiBench extends BenchBase:

    import PanamaVsKyoFfiBench.*

    private val strlenDesc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    private val strlenAddr = lookup.find("strlen").orElseThrow()

    // Cached, like the emitter would cache a per-binding handle in the companion.
    private val strlenMH: MethodHandle = linker.downcallHandle(strlenAddr, strlenDesc)

    // Shared input buffer, allocation cost is excluded from the steady-state measurement.
    private val argBuf: Buffer[Byte]  = Buffer.fromUtf8("hello-kyo-ffi-F14")
    private val argSeg: MemorySegment = argBuf.raw.asInstanceOf[MemorySegment]

    // Concrete "generator-shape" impl of the binding trait. Constructed once so the benchmark measures
    // per-call dispatch, not per-call instantiation.
    private val binding: StrlenBinding = new StrlenBindingImpl(strlenMH)

    @Benchmark def handPanama(): Long =
        strlenMH.invokeExact(argSeg).asInstanceOf[Long]

    @Benchmark def generatedShape(): Long =
        binding.strlen(argBuf)

end PanamaVsKyoFfiBench

object PanamaVsKyoFfiBench:

    /** User-visible binding trait. Mirrors what a kyo-ffi user would declare (minus `@Ffi.method` + library ID, which the emitter consumes
      * but aren't needed here since we wire the MethodHandle directly).
      */
    trait StrlenBinding extends Ffi:
        def strlen(s: Buffer[Byte]): Long

    /** Concrete impl that matches the shape the `JvmEmitter` emits:
      *   - Cached MethodHandle held by the impl instance (the generator puts it in the companion, equivalent visibility from the call
      *     site).
      *   - Method body unwraps the `Buffer[Byte]` to its raw `MemorySegment` and forwards to `invokeExact`.
      *
      * Kept intentionally small so the bench numbers reflect the dispatch + unwrap cost, not bookkeeping.
      */
    final class StrlenBindingImpl(mh: MethodHandle) extends StrlenBinding:
        def strlen(s: Buffer[Byte]): Long =
            val seg = s.raw.asInstanceOf[MemorySegment]
            mh.invokeExact(seg).asInstanceOf[Long]
    end StrlenBindingImpl

end PanamaVsKyoFfiBench
