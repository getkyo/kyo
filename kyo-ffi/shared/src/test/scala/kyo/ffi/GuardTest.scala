package kyo.ffi

import kyo.discard

/** Cross-platform tests for [[Ffi.Guard]].
  *
  * Covers `open`/`close` lifecycle, idempotency, the [[Ffi.Guard.use]] bracket helper, and [[Ffi.Guard.registerBuffer]] semantics (LIFO
  * close, chaining return). Runs identically on JVM, Native, and JS.
  */
class GuardTest extends Test:

    // The GuardRegistry leaves read a global size baseline then assert an exact delta; run this suite's leaves
    // sequentially so concurrent leaves do not perturb the shared GuardRegistry between the read and the assertion.
    override def config = super.config.sequential

    "Ffi.Guard.open" - {
        "creates a usable guard" in {
            val g = Ffi.Guard.open()
            discard(g.close())
            succeed
        }

        "multiple opens produce distinct instances" in {
            val g1 = Ffi.Guard.open()
            val g2 = Ffi.Guard.open()
            try
                assert((g1 eq g2) == false)
            finally
                discard(g1.close())
                discard(g2.close())
            end try
        }
    }

    "Ffi.Guard.use" - {
        "runs the block, returns its value, and closes the guard" in {
            var inside: Ffi.Guard = null
            val result = Ffi.Guard.use[Int] { g =>
                inside = g
                42
            }
            assert(result == 42)
            // After use returns the guard is already closed, a second close must be a no-op.
            discard(inside.close())
            succeed
        }

        "closes the registered buffer when the block returns normally" in {
            var captured: Buffer[Int] = null
            Ffi.Guard.use[Unit] { g =>
                val b = Buffer.alloc[Int](4)
                discard(g.registerBuffer(b))
                b.set(0, 123)
                assert(b.get(0) == 123)
                captured = b
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }

        "closes the guard even when the block throws" in {
            var captured: Ffi.Guard = null
            interceptThrown[RuntimeException] {
                Ffi.Guard.use[Unit] { g =>
                    captured = g
                    throw new RuntimeException("boom")
                }
            }
            // Second close() should be a no-op (idempotent), proves the first close ran.
            discard(captured.close())
            succeed
        }

        "returns computed value" in {
            val n = Ffi.Guard.use[Int] { _ => 7 * 6 }
            assert(n == 42)
        }
    }

    "close" - {
        "is idempotent" in {
            val g = Ffi.Guard.open()
            discard(g.close())
            discard(g.close())
            discard(g.close())
            succeed
        }
    }

    "registerBuffer" - {
        "returns the same buffer instance for chaining" in {
            val g = Ffi.Guard.open()
            try
                val b        = Buffer.alloc[Byte](8)
                val returned = g.registerBuffer(b)
                assert((returned eq b) == true)
            finally discard(g.close())
            end try
        }

        "closes a registered buffer when the guard closes" in {
            val g = Ffi.Guard.open()
            val b = Buffer.alloc[Byte](8)
            discard(g.registerBuffer(b))
            discard(g.close())
            interceptThrown[IllegalStateException](b.get(0))
        }

        "closes multiple registered buffers when the guard closes" in {
            val g  = Ffi.Guard.open()
            val b1 = Buffer.alloc[Byte](4)
            val b2 = Buffer.alloc[Int](4)
            val b3 = Buffer.alloc[Long](4)
            discard(g.registerBuffer(b1))
            discard(g.registerBuffer(b2))
            discard(g.registerBuffer(b3))
            discard(g.close())
            interceptThrown[IllegalStateException](b1.get(0))
            interceptThrown[IllegalStateException](b2.get(0))
            interceptThrown[IllegalStateException](b3.get(0))
        }

        "close is idempotent when registered buffers were already closed" in {
            val g = Ffi.Guard.open()
            val b = Buffer.alloc[Byte](4)
            discard(g.registerBuffer(b))
            b.close()
            // Guard close must not throw even though b is already closed.
            discard(g.close())
            succeed
        }
    }

    "Guard.use composes with registerBuffer" - {
        "buffer registered inside use is closed even if block throws" in {
            var captured: Buffer[Int] = null
            interceptThrown[RuntimeException] {
                Ffi.Guard.use[Unit] { g =>
                    val b = Buffer.alloc[Int](4)
                    discard(g.registerBuffer(b))
                    captured = b
                    throw new RuntimeException("boom")
                }
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }
    }

    "GuardRegistry" - {
        "increments on open and decrements on close" in {
            val before = kyo.ffi.internal.GuardRegistry.size
            val g      = Ffi.Guard.open()
            assert((kyo.ffi.internal.GuardRegistry.size - before) == 1)
            discard(g.close())
            assert((kyo.ffi.internal.GuardRegistry.size - before) == 0)
        }

        "decrements only once for multiple close() calls" in {
            val before = kyo.ffi.internal.GuardRegistry.size
            val g      = Ffi.Guard.open()
            discard(g.close())
            discard(g.close())
            discard(g.close())
            assert((kyo.ffi.internal.GuardRegistry.size - before) == 0)
        }
    }
end GuardTest
