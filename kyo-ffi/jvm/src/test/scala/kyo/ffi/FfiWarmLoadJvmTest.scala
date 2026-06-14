package kyo.ffi

import kyo.discard

/** `Ffi.warmLoad[T]` pre-JIT reflection warm-up.
  *
  * `Ffi.load[T]` performs reflective class lookup on its first call per trait, then caches the resulting impl instance. Applications that
  * want to amortise the first-call cost outside a latency-sensitive path can invoke `warmLoad[T]` during startup.
  *
  * The contract verified here:
  *   - `warmLoad` returns `Unit` (no allocation leakage of the impl instance).
  *   - After `warmLoad`, a subsequent `load[T]` returns the same cached instance (no second reflection round-trip).
  *   - `warmLoad` is idempotent, repeated calls resolve to the same cached instance.
  *   - `unload[T]` evicts the warm entry so a later `warmLoad` triggers reflection again.
  */
class FfiWarmLoadJvmTest extends Test:

    // The leaves share one process-global state set: the `Ffi` load cache keyed by `WarmBinding`
    // and the static `LookupCounter`. Each leaf unloads, reloads, and asserts on counter deltas or
    // instance identity, so under the default parallel leaf execution a sibling leaf's unload or
    // re-instantiation corrupts another leaf's observation. Run the leaves sequentially.
    override def config = super.config.sequential

    "Ffi.warmLoad" - {
        "seeds the cache so a subsequent load returns the same instance" in {
            Ffi.unload[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val a = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            val b = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            assert((a eq b) == true)
        }

        "repeated calls resolve to the same cached instance" in {
            Ffi.unload[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val first = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val later = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            assert((first eq later) == true)
        }

        "is a no-op for already-loaded bindings (no second reflection call)" in {
            Ffi.unload[FfiWarmLoadJvmTest.WarmBinding]
            val seeded = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            // Count the class-lookup events by temporarily overriding the test's reflection counter.
            val before = FfiWarmLoadJvmTest.LookupCounter.get
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val after = FfiWarmLoadJvmTest.LookupCounter.get
            // If warmLoad is a cache hit path, no additional constructor invocation should occur. We
            // assert via impl-identity since the impl's static-init increments `LookupCounter`.
            assert(after == before)
            val post = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            assert((seeded eq post) == true)
        }

        "after unload, warmLoad re-triggers impl instantiation" in {
            Ffi.unload[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val a = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.unload[FfiWarmLoadJvmTest.WarmBinding]
            Ffi.warmLoad[FfiWarmLoadJvmTest.WarmBinding]
            val b = Ffi.load[FfiWarmLoadJvmTest.WarmBinding]
            assert((a eq b) == false)
        }
    }

end FfiWarmLoadJvmTest

object FfiWarmLoadJvmTest:
    /** Atomic counter incremented by `WarmBindingImpl`'s constructor. Used by the "no-op" test above to observe that `warmLoad` on an
      * already-cached binding does not re-instantiate.
      */
    val LookupCounter: java.util.concurrent.atomic.AtomicInteger =
        new java.util.concurrent.atomic.AtomicInteger(0)

    trait WarmBinding extends Ffi

    /** Fixture impl, constructor increments `LookupCounter` so tests can observe instantiation. */
    class WarmBindingImpl extends WarmBinding:
        discard(LookupCounter.incrementAndGet())
    end WarmBindingImpl
end FfiWarmLoadJvmTest
