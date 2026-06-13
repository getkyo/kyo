package kyo.ffi

import kyo.discard

/** JVM-only [[Buffer]] tests. Cross-platform coverage lives in [[BufferSpec]]; this suite exercises behaviour that depends on Panama's
  * `Arena.ofConfined()`, specifically that touching a confined buffer from a thread other than its owning thread raises an exception.
  */
class JvmBufferTest extends Test:

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
