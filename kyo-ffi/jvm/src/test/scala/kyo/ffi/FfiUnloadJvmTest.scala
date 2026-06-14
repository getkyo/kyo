package kyo.ffi

/** Cache-eviction tests for [[Ffi.unload]].
  *
  * The shared `Ffi.load[T]` inline method caches `{T}Impl` instances in a process-wide `ConcurrentHashMap` keyed by the trait class. This
  * spec verifies that [[Ffi.unload]] removes the cached instance so that the next [[Ffi.load]] re-instantiates the impl via reflection.
  *
  * The test lives in the JVM source set because it relies on JVM-style `Class.forName` reflection, the shared test set would need
  * platform-specific `@EnableReflectiveInstantiation` annotations on the impl class that don't apply on JVM. The `unload` method itself is
  * cross-platform; the semantics it enforces are identical on Native and JS.
  */
class FfiUnloadJvmTest extends Test:

    // The leaves share one process-global state set: the `Ffi` load cache keyed by the binding trait.
    // Each leaf loads, unloads, and reloads the same binding, so under the default parallel leaf
    // execution a sibling leaf's unload evicts the entry between another leaf's two loads. Run the
    // leaves sequentially.
    override def config = super.config.sequential

    "Ffi.unload" - {
        "evicts cached impl so the next load produces a new instance" in {
            val first = Ffi.load[FfiUnloadJvmTest.TestBinding]
            val same  = Ffi.load[FfiUnloadJvmTest.TestBinding]
            assert((first eq same) == true)

            Ffi.unload[FfiUnloadJvmTest.TestBinding]

            val second = Ffi.load[FfiUnloadJvmTest.TestBinding]
            assert((first eq second) == false)
        }

        "is idempotent, calling on an already-evicted key does nothing" in {
            Ffi.unload[FfiUnloadJvmTest.TestBinding]
            Ffi.unload[FfiUnloadJvmTest.TestBinding]

            val fresh = Ffi.load[FfiUnloadJvmTest.TestBinding]
            assert(fresh != null)
        }

        "does not affect other cached bindings" in {
            val a1 = Ffi.load[FfiUnloadJvmTest.TestBinding]
            val b1 = Ffi.load[FfiUnloadJvmTest.OtherBinding]

            Ffi.unload[FfiUnloadJvmTest.TestBinding]

            val b2 = Ffi.load[FfiUnloadJvmTest.OtherBinding]
            assert((b1 eq b2) == true)

            val a2 = Ffi.load[FfiUnloadJvmTest.TestBinding]
            assert((a1 eq a2) == false)
        }
    }
end FfiUnloadJvmTest

object FfiUnloadJvmTest:
    /** Fixture trait, the corresponding `TestBindingImpl` is the nested class below. `Ffi.load` resolves it via
      * `Class.forName(s"$name$$TestBindingImpl")`.
      */
    trait TestBinding extends Ffi

    /** Fixture impl, nullary constructor, loaded reflectively. */
    class TestBindingImpl extends TestBinding

    trait OtherBinding     extends Ffi
    class OtherBindingImpl extends OtherBinding
end FfiUnloadJvmTest
