package kyo.ffi

import kyo.discard
import kyo.test.AllocationProbe

/** JVM-only [[Buffer]] tests. Cross-platform coverage lives in [[BufferSpec]]; this suite exercises behaviour that depends on Panama's
  * `Arena.ofConfined()`, specifically that touching a confined buffer from a thread other than its owning thread raises an exception,
  * plus the allocation bound on the non-generic primitive accessors (`getLong`/`setLong`, `getInt`/`setInt`, `getShort`/`setShort`,
  * `getFloat`/`setFloat`, `getDouble`/`setDouble`, `getByte`/`setByte`). Value-correctness for every accessor is covered cross-platform
  * by the shared `BufferTest`; this file's own reads assert the round-tripped value too, ahead of the timed measurement.
  */
class JvmBufferTest extends Test:

    private val warmupIters   = 20000
    private val measuredIters = 2000

    "primitive accessor allocation bound" - {

        // Values are deliberately outside the JVM's small-integer box cache (-128..127): a cached value
        // would pass this bound regardless of whether the accessor under test avoids boxing, since
        // `Long.valueOf` on a cached value never allocates either way. Each leaf first asserts the accessor's
        // OWN round-tripped value on a plain, unmeasured call; the measured closure below it stays
        // `discard`-wrapped, since asserting there would confound the very allocation the closure measures
        // (an assert is not "an assertion that belongs" inside the timed op, unlike the plain call above it).
        // The op's return value would otherwise box through AllocationProbe's generic `iterate[A]` on every
        // measured call, a confound unrelated to the accessor under test.

        "getLong/setLong on a Buffer[Long] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Long](4)
            try
                b.setLong(0, 1234567890123L)
                assert(b.getLong(0) == 1234567890123L)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setLong(0, 1234567890123L)
                    discard(b.getLong(0))
                }
            finally b.close()
            end try
        }

        "getInt/setInt on a Buffer[Int] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Int](4)
            try
                b.setInt(0, 123456789)
                assert(b.getInt(0) == 123456789)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setInt(0, 123456789)
                    discard(b.getInt(0))
                }
            finally b.close()
            end try
        }

        "getShort/setShort on a Buffer[Short] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Short](4)
            try
                b.setShort(0, 12345.toShort)
                assert(b.getShort(0) == 12345.toShort)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setShort(0, 12345.toShort)
                    discard(b.getShort(0))
                }
            finally b.close()
            end try
        }

        "getFloat/setFloat on a Buffer[Float] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Float](4)
            try
                b.setFloat(0, 3.14159f)
                assert(b.getFloat(0) == 3.14159f)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setFloat(0, 3.14159f)
                    discard(b.getFloat(0))
                }
            finally b.close()
            end try
        }

        "getDouble/setDouble on a Buffer[Double] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Double](4)
            try
                b.setDouble(0, 3.14159265358979)
                assert(b.getDouble(0) == 3.14159265358979)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setDouble(0, 3.14159265358979)
                    discard(b.getDouble(0))
                }
            finally b.close()
            end try
        }

        "getByte/setByte on a Buffer[Byte] allocate exactly 0 bytes per op" in {
            val b = Buffer.alloc[Byte](4)
            try
                b.setByte(0, 7.toByte)
                assert(b.getByte(0) == 7.toByte)
                AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                    b.setByte(0, 7.toByte)
                    discard(b.getByte(0))
                }
            finally b.close()
            end try
        }

        // Regression guard for the boxing this accessor set exists to avoid: the generic get/set dispatch
        // through UnsafeLayout[A] boxes every primitive element (type erasure), so an op built from THEM
        // instead of the non-generic accessors must fail this same bound.
        "the generic get/set dispatch allocates above the bound, proving the accessors above are load-bearing" in {
            val b = Buffer.alloc[Long](4)
            try
                val failure = intercept[kyo.test.AssertionFailed] {
                    AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                        b.set(0, 1234567890123L)
                        discard(b.get(0))
                    }
                }
                assert(failure.diagram.contains("per-op allocation"))
            finally b.close()
            end try
        }
    }

    "Buffer.confinedUse" - {
        "cross-thread access throws" in {
            val b =
                // allocate inside a confined arena on this thread via the internal factory
                val arena = java.lang.foreign.Arena.ofConfined()
                val seg   = arena.allocate(16)
                // cheat: produce a ConfinedBuffer via confinedUse by stashing the instance
                var cap: Buffer[Int] = null
                try
                    Buffer.confinedUse[Int, Unit](4) { buf =>
                        cap = buf
                        buf.set(0, 1)
                        // simulate cross-thread read inside a separate OS thread
                        @volatile var caught: Throwable = null
                        val t = new Thread(new Runnable:
                            def run(): Unit =
                                try
                                    discard(buf.get(0))
                                catch case e: Throwable => caught = e)
                        t.start()
                        t.join()
                        assert(caught != null)
                        assert((caught.isInstanceOf[RuntimeException] ||
                            caught.isInstanceOf[IllegalStateException]) == true)
                    }
                    cap
                finally arena.close()
                end try
            end b
            // After the confinedUse block closes, the buffer is closed too.
            interceptThrown[IllegalStateException](b.get(0))
        }
    }
end JvmBufferTest
