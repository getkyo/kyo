package kyo.ffi.bench

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized

/** Compares two strategies for passing an on-heap `Array[Byte]` across an FFI boundary:
  *
  *   - `pinDirect`, `MemorySegment.ofArray(arr)` produces a segment that aliases the Java array's heap storage. With
  *     `Linker.Option.critical(true)` available (JDK 22+), the downcall pins the array in-place. This is the zero-copy path the kyo-ffi
  *     emitter uses when `@Ffi.blocking` is absent.
  *   - `copyToScratch`, allocate an off-heap segment, memcpy the Array contents into it, pass the off-heap segment. This matches what
  *     kyo-ffi emits for `@Ffi.blocking` methods: the thread may yield, so pinning is unsafe.
  *
  * The callee is POSIX `strlen`, a read-only C function that walks the buffer until a NUL byte. The array is pre-populated with a
  * NUL-terminated string so `strlen` returns a known length.
  *
  * Expectation: the "pin" path is dominated by the downcall + thread-state transition cost; the "copy" path adds per-byte memcpy. Crossover
  * size depends on JDK + CPU.
  */
@State(Scope.Benchmark)
class ArrayPassBench extends BenchBase:

    @Param(Array("16", "256", "4096"))
    var size: Int = uninitialized

    private val strlenDesc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    private val strlenAddr = lookup.find("strlen").orElseThrow()

    private val strlenMH: MethodHandle = linker.downcallHandle(strlenAddr, strlenDesc)

    private var arr: Array[Byte] = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        // Fill with non-NUL; last byte is NUL terminator so strlen returns size-1.
        arr = Array.fill(size)('a'.toByte)
        arr(size - 1) = 0.toByte
    end setup

    /** Zero-copy pin: MemorySegment.ofArray aliases the heap array. */
    @Benchmark def pinDirect(bh: Blackhole): Unit =
        val seg: MemorySegment = MemorySegment.ofArray(arr)
        val n                  = strlenMH.invokeExact(seg).asInstanceOf[Long]
        bh.consume(n)
    end pinDirect

    /** Copy into a freshly-allocated off-heap segment. */
    @Benchmark def copyToScratch(bh: Blackhole): Unit =
        val scratch = Arena.ofConfined()
        try
            val seg = scratch.allocate(arr.length.toLong, 1L)
            MemorySegment.copy(arr, 0, seg, ValueLayout.JAVA_BYTE, 0L, arr.length)
            val n = strlenMH.invokeExact(seg).asInstanceOf[Long]
            bh.consume(n)
        finally scratch.close()
        end try
    end copyToScratch

end ArrayPassBench
