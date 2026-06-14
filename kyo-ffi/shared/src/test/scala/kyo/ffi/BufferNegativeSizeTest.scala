package kyo.ffi

/** D11: Tests for [[Buffer.alloc]] with a negative `size` argument.
  *
  * `Buffer.alloc[Int](-1)` propagates directly to the platform-specific allocator with no validation in the shared code path
  * ([[kyo.ffi.internal.BufferCore]] only validates index access, not the size itself). The underlying allocator throws on all platforms,
  * but the concrete exception type is implementation-defined:
  *
  *   - **JVM**: `java.lang.foreign.Arena.allocate(-4L)` raises [[java.lang.IllegalArgumentException]] with a message indicating the
  *     negative byte size.
  *   - **Scala Native**: `stdlib.malloc` is called with `(-4L).toCSize` which wraps to a huge unsigned value on 64-bit; malloc returns
  *     null, and [[kyo.ffi.internal.BufferFactory.alloc]] converts that to [[java.lang.OutOfMemoryError]].
  *   - **Scala.js**: `new Uint8Array(-4)` raises a JS `RangeError` (wrapped in a [[java.lang.RuntimeException]] by the Scala.js runtime).
  *
  * The shared test therefore only asserts that some [[java.lang.Throwable]] is raised, each platform-specific test suite may add a
  * narrower assertion for the concrete type.
  *
  * Coverage gaps that require platform-specific mocking (D1, D2):
  *   - D1 (malloc failure on Native): the `OutOfMemoryError` path in the Native [[kyo.ffi.internal.BufferFactory]] (when `stdlib.malloc`
  *     returns null for a *valid* size) cannot be exercised without intercepting the C stdlib. A mock allocator or LD_PRELOAD shim would be
  *     needed, not available in the current test infrastructure.
  *   - D2 (pool exhaustion on JS / koffi): the koffi callback-pool exhaustion path cannot be triggered without koffi-specific mocking or an
  *     injected koffi stub. Not available in the current test infrastructure.
  */
class BufferNegativeSizeTest extends Test:

    "Buffer.alloc[Int](-1)" - {
        "throws an exception, negative size is not supported by the underlying allocator" in {
            // All three platform allocators (JVM Arena, Native malloc, JS Uint8Array) raise when
            // given a negative element count. The exact exception type is platform-specific (see
            // class-level scaladoc), so we assert the common supertype Throwable here.
            // A negative size has no valid interpretation, the allocator must not return a buffer.
            intercept[Throwable] {
                Buffer.alloc[Int](-1)
            }
            succeed
        }

        "Buffer.use with negative size throws before entering the body" in {
            // Buffer.use calls Buffer.alloc first; the body (which returns succeed) must never run.
            var bodyEntered = false
            intercept[Throwable] {
                Buffer.use[Int, Unit](-1) { _ =>
                    bodyEntered = true
                }
            }
            assert(bodyEntered == false)
        }
    }
end BufferNegativeSizeTest
