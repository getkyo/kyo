package kyo.ffi.it

import kyo.ffi.Ffi

/** Abstract base with the 4 cross-platform varargs test cases.
  *
  * Concrete subclasses live in the per-platform source sets (JVM + JS). The abstract pattern keeps [[ItVarargsSpec]] on each platform from
  * silently drifting apart: any new common case added here is automatically inherited by both.
  *
  * Native does not extend this spec because Scala Native's `@extern` cannot express variadic function pointers; Native uses the fixed-arity
  * workaround instead (see [[ItSumFixedSpec]]).
  */
abstract class ItVarargsSharedTest extends ItTestBase:

    "kyoItSumVarargs" - {
        "empty varargs sums to zero when count = 0" in {
            val b = Ffi.load[ItVarargsBindings]
            assert(b.kyoItSumVarargs(0) == 0)
        }

        "three ints sum correctly" in {
            val b = Ffi.load[ItVarargsBindings]
            assert(b.kyoItSumVarargs(3, 1, 2, 3) == 6)
        }

        "five ints sum correctly" in {
            val b = Ffi.load[ItVarargsBindings]
            assert(b.kyoItSumVarargs(5, 10, 20, 30, 40, 50) == 150)
        }

        "single int" in {
            val b = Ffi.load[ItVarargsBindings]
            assert(b.kyoItSumVarargs(1, 42) == 42)
        }
    }
end ItVarargsSharedTest
