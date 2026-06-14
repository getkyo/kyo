package kyo.ffi.bench

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kyo.ffi.Buffer
import org.openjdk.jmh.annotations.*

/** Measures per-call FFI overhead on a trivial callee. Three variants:
  *
  *   1. `panamaDirect`, hand-rolled `MethodHandle.invokeExact`. This is the lower bound: what a user would write without kyo-ffi.
  *   2. `kyoFfiStyle`, what a kyo-ffi-generated `strlen` binding looks like: `Buffer[Byte]` on the Scala side, `MemorySegment`
  *      marshalling, same underlying `invokeExact`. The delta vs `panamaDirect` is the cost the generator adds.
  *   3. `criticalPanama`, same hand-rolled call but requested with the `Linker.Option.critical(true)` option on JDK 22+. When available,
  *      this skips the thread-state transition. When unavailable (JDK 21), this falls back to the default option set, the bench still runs
  *      but its delta vs #1 is zero.
  *
  * Callee is POSIX `strlen` on a small constant string. Real C work is ~10 cycles; the bench isolates call-site overhead (arena alloc,
  * thread transition, argument marshalling).
  */
class CallOverheadBench extends BenchBase:

    private val strlenDesc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    private val strlenAddr = lookup.find("strlen").orElseThrow()

    // Default-options handle, equivalent to what kyo-ffi emits for a
    // non-blocking method with no critical-options support.
    private val strlenMH: MethodHandle = linker.downcallHandle(strlenAddr, strlenDesc)

    // Critical-options handle, best-effort. Falls back silently on older JDKs.
    private val strlenCriticalMH: MethodHandle =
        try
            val critical = classOf[Linker.Option].getMethod("critical", classOf[Boolean]).invoke(null, java.lang.Boolean.TRUE)
            linker.downcallHandle(strlenAddr, strlenDesc, critical.asInstanceOf[Linker.Option])
        catch case _: Throwable => strlenMH

    // Pre-allocated argument, excluded from steady-state allocation cost.
    // (JDK 21 does not expose Arena.allocateUtf8; use manual bytes + NUL.)
    private val argSeg: MemorySegment =
        val s     = "hello-kyo-ffi"
        val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val seg   = sharedArena.allocate(bytes.length.toLong + 1L, 1L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
        seg.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0.toByte)
        seg
    end argSeg
    private val argBuf: Buffer[Byte] = Buffer.fromUtf8("hello-kyo-ffi")

    @Benchmark def panamaDirect(): Long =
        strlenMH.invokeExact(argSeg).asInstanceOf[Long]

    @Benchmark def kyoFfiStyle(): Long =
        // Mirror the code shape the JvmEmitter generates for
        //   def strlen(s: Buffer[Byte]): Long
        // 1) unwrap the Buffer's raw segment, 2) invokeExact, 3) return.
        val seg = argBuf.raw.asInstanceOf[MemorySegment]
        strlenMH.invokeExact(seg).asInstanceOf[Long]
    end kyoFfiStyle

    @Benchmark def criticalPanama(): Long =
        strlenCriticalMH.invokeExact(argSeg).asInstanceOf[Long]

end CallOverheadBench
