package kyo.ffi.bench

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Multi-threaded FFI call benchmark.
  *
  * Runs the same `strlen` downcall from 4 threads concurrently. Measures whether thread-state transitions or lock contention inflates
  * per-call cost under concurrency compared to the single-threaded [[CallOverheadBench]].
  *
  * The argument string is pre-allocated in the shared arena so the benchmark isolates call overhead from allocation.
  */
@Threads(4)
class MultiThreadedCallBench extends BenchBase:

    private val strlenDesc             = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    private val strlenAddr             = lookup.find("strlen").orElseThrow()
    private val strlenMH: MethodHandle = linker.downcallHandle(strlenAddr, strlenDesc)

    // Pre-allocated argument in the shared arena, safe for concurrent reads.
    private val argSeg: MemorySegment =
        val s     = "hello-mt"
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        val seg   = sharedArena.allocate(bytes.length.toLong + 1L, 1L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
        seg.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0.toByte)
        seg
    end argSeg

    @Benchmark def concurrentStrlen(bh: Blackhole): Unit =
        val n = strlenMH.invokeExact(argSeg).asInstanceOf[Long]
        bh.consume(n)

end MultiThreadedCallBench
