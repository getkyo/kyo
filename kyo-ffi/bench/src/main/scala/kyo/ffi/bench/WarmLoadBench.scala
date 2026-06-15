package kyo.ffi.bench

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.Ffi
import org.openjdk.jmh.annotations.*

/** Reflection-cost benchmark for `Ffi.load[T]`.
  *
  * Quantifies the cold-vs-warm cost of the reflection path. The first `Ffi.load[T]` call for a given trait triggers
  * `Class.forName(implName)` + `getDeclaredConstructor().newInstance()`; subsequent calls are a `ConcurrentHashMap` hit. `Ffi.warmLoad[T]`
  * provides an explicit pre-JIT warm-up knob so applications can pay the cold cost outside a latency-sensitive path.
  *
  *   - `coldLoad`, evicts the cache via `Ffi.unload` before each measurement, so every iteration exercises the full reflection chain.
  *   - `warmLoad`, leaves the cache populated, so every iteration is a pure cache hit. `Ffi.warmLoad` is invoked once in `@Setup` to
  *     guarantee the cache entry exists without relying on incidental test state.
  *
  * The delta quantifies the actual reflection cost amortised by `warmLoad`. On a 2024-class x86 host `coldLoad` runs in the low-μs range
  * (single-digit μs) while `warmLoad` is a few ns, the reflective lookup dominates by roughly three orders of magnitude.
  */
class WarmLoadBench extends BenchBase:

    @Setup(Level.Trial)
    def warmupCache(): Unit =
        // Ensure `Ffi.warmLoad` is wired correctly AND seed the `warmLoad` bench's cache entry so its
        // tight-loop measurements aren't polluted by a cold lookup on the first invocation.
        Ffi.warmLoad[WarmLoadBench.WarmBinding]
    end warmupCache

    @Benchmark def coldLoad(): AnyRef =
        // Evict the cache so this iteration exercises the reflection path in full.
        Ffi.unload[WarmLoadBench.WarmBinding]
        Ffi.load[WarmLoadBench.WarmBinding]
    end coldLoad

    @Benchmark def warmLoadHit(): AnyRef =
        Ffi.load[WarmLoadBench.WarmBinding]

end WarmLoadBench

object WarmLoadBench:

    /** Fixture trait for the load-cost bench. Uses the same pattern as `kyo.ffi.FfiUnloadJvmSpec`, a nested trait with a matching `*Impl`
      * in this object so `Class.forName` resolves it under the mangled `kyo.ffi.bench.WarmLoadBench$WarmBindingImpl` name.
      */
    trait WarmBinding extends Ffi

    class WarmBindingImpl extends WarmBinding
end WarmLoadBench
