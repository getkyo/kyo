package kyo.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import kyo.ffi.internal.UpcallBridge

/** JVM integration test: simulates the end-to-end callback flow that generated FFI code performs.
  *
  * We build a Scala lambda, convert it to a C-callable function pointer via [[UpcallBridge]], and then call back into it through a Panama
  * `downcallHandle`, the same mechanism Panama uses when real native code invokes a function pointer. This validates the arity, primitive
  * marshalling, and arena-lifetime story without requiring an actual `.so`/`.dylib` to be present. End-to-end validation against real
  * shared libraries is covered in scripted integration tests.
  */
class CallbackIntegrationTest extends Test:

    private val linker = Linker.nativeLinker().nn

    "comparator-style callback: (Int, Int) => Int with transient arena" in {
        val arena = Arena.ofConfined().nn
        try
            val fd   = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT).nn
            val cmp  = (a: Int, b: Int) => a - b
            val stub = UpcallBridge.stub2(cmp, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            val cbMh = linker.downcallHandle(stub, fd).nn
            val out10 = cbMh.invokeWithArguments(java.lang.Integer.valueOf(10), java.lang.Integer.valueOf(4))
                .asInstanceOf[java.lang.Integer]
            assert(out10.intValue() == 6)
            val outNeg = cbMh.invokeWithArguments(java.lang.Integer.valueOf(2), java.lang.Integer.valueOf(9))
                .asInstanceOf[java.lang.Integer]
            assert(outNeg.intValue() == -7)
        finally arena.close()
        end try
    }

    "retained-style: a stub outlives the call into it when attached to a shared arena" in {
        // Model a guard: a shared Arena that is NOT closed by the call itself; the stub stays valid across multiple invocations.
        val guardArena = Arena.ofShared().nn
        try
            val fd          = FunctionDescriptor.ofVoid(JAVA_INT).nn
            var accumulator = 0
            val onEvent     = (x: Int) => accumulator += x
            val stub        = UpcallBridge.stub1(onEvent, fd, guardArena, "kyo.example.Spec", "testMethod", "transient")
            val cbMh        = linker.downcallHandle(stub, fd).nn
            val _           = cbMh.invokeWithArguments(java.lang.Integer.valueOf(1))
            val _           = cbMh.invokeWithArguments(java.lang.Integer.valueOf(2))
            val _           = cbMh.invokeWithArguments(java.lang.Integer.valueOf(3))
            assert(accumulator == 6)
        finally guardArena.close()
        end try
    }

    "MemorySegment-passing callback: receives an ADDRESS argument unboxed to MemorySegment" in {
        val arena = Arena.ofConfined().nn
        try
            val fd                       = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT).nn
            @volatile var seenValue: Int = -1
            val onBuf: (MemorySegment, Int) => Unit = (seg, len) =>
                // Scala sees the raw pointer as a MemorySegment; re-interpret to read the first `len` bytes.
                val sized = seg.reinterpret(len.toLong).nn
                seenValue = sized.get(JAVA_INT, 0L)
            val stub = UpcallBridge.stub2(onBuf, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            // Allocate a segment with a known value and call through the stub with its address.
            val data = arena.allocate(JAVA_INT).nn
            data.set(JAVA_INT, 0L, 12345)
            val cbMh = linker.downcallHandle(stub, fd).nn
            val _    = cbMh.invokeWithArguments(data, java.lang.Integer.valueOf(4))
            assert(seenValue == 12345)
        finally arena.close()
        end try
    }
end CallbackIntegrationTest
