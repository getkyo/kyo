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
import scala.compiletime.uninitialized

/** End-to-end upcall round-trip benchmark via POSIX `qsort`.
  *
  * This benchmark measures a full downcall-to-upcall round trip: Scala calls C's `qsort`, which in turn calls back into a Scala comparator
  * for every element comparison. Unlike [[CallbackBench]] (which only measures stub construction), this exercises the actual C -> Scala
  * upcall invocation path on every comparison.
  *
  * The comparator reads two `int*` arguments, dereferences each pointer to obtain the integer values, and returns their difference (`a -
  * b`) for ascending sort. The array is reverse-sorted at the start of each invocation so every call performs O(n log n) comparisons.
  *
  * Parameterised by array size (16 and 256 elements) to show how upcall overhead scales with callback frequency.
  */
@State(Scope.Benchmark)
class UpcallRoundTripBench extends BenchBase:

    @Param(Array("16", "256"))
    var size: Int = uninitialized

    // qsort(void *base, size_t nmemb, size_t size, int (*compar)(const void *, const void *))
    private val qsortDesc = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS
    )
    private val qsortAddr             = lookup.find("qsort").orElseThrow()
    private val qsortMH: MethodHandle = linker.downcallHandle(qsortAddr, qsortDesc)

    // Comparator descriptor: int (*)(const void *, const void *)
    private val cmpDesc: FunctionDescriptor =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

    private var templateArr: Array[Int] = uninitialized
    private var stubArena: Arena        = uninitialized
    private var cmpStub: MemorySegment  = uninitialized

    /** Static comparator method invoked by the upcall stub. Reads two `int*` pointers and returns their difference for ascending sort. */
    @SuppressWarnings(Array("unused"))
    def compareInts(a: MemorySegment, b: MemorySegment): Int =
        val va = a.reinterpret(4).get(ValueLayout.JAVA_INT, 0L)
        val vb = b.reinterpret(4).get(ValueLayout.JAVA_INT, 0L)
        Integer.compare(va, vb)
    end compareInts

    @Setup(Level.Trial)
    def setup(): Unit =
        templateArr = Array.tabulate(size)(i => size - i) // reverse sorted
        stubArena = Arena.ofShared()
        // Build a MethodHandle pointing at this.compareInts(MemorySegment, MemorySegment): Int
        val mhLookup = MethodHandles.lookup()
        val cmpMH = mhLookup.findVirtual(
            classOf[UpcallRoundTripBench],
            "compareInts",
            MethodType.methodType(classOf[Int], classOf[MemorySegment], classOf[MemorySegment])
        ).bindTo(this)
        cmpStub = linker.upcallStub(cmpMH, cmpDesc, stubArena)
    end setup

    @TearDown(Level.Trial)
    def teardown(): Unit =
        if stubArena != null then stubArena.close()

    @Benchmark def qsortRoundTrip(bh: Blackhole): Unit =
        val arena = Arena.ofConfined()
        try
            val byteSize = templateArr.length.toLong * 4L
            val seg      = arena.allocate(byteSize, 4L)
            MemorySegment.copy(templateArr, 0, seg, ValueLayout.JAVA_INT, 0, templateArr.length)
            qsortMH.invokeExact(seg, templateArr.length.toLong, 4L, cmpStub)
            bh.consume(seg.get(ValueLayout.JAVA_INT, 0L))
        finally arena.close()
        end try
    end qsortRoundTrip

end UpcallRoundTripBench
