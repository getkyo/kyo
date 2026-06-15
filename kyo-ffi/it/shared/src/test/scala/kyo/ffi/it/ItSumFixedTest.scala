package kyo.ffi.it

import kyo.ffi.Ffi

/** Cross-platform coverage for the non-variadic `kyoItSumFixed3` binding.
  *
  * The binding is the workaround-pointer pattern the codegen directs users to when they hit Scala Native's variadic rejection: a fixed-
  * arity C wrapper that callers invoke in place of the underlying variadic function. Tests here run on JVM + Native + JS so all three
  * platforms exercise the non-variadic path; JVM and JS additionally run the variadic surface in their platform-specific specs
  * ([[kyo.ffi.it.ItVarargsSpec]] lives under `{jvm,js}/src/test`).
  */
class ItSumFixedTest extends ItTestBase:

    "kyoItSumFixed3" - {
        "1 + 2 + 3 = 6" in {
            val b = Ffi.load[ItSumFixedBindings]
            assert(b.kyoItSumFixed3(1, 2, 3) == 6)
        }

        "sums negative inputs correctly" in {
            val b = Ffi.load[ItSumFixedBindings]
            assert(b.kyoItSumFixed3(-10, 5, 7) == 2)
        }

        "zero inputs" in {
            val b = Ffi.load[ItSumFixedBindings]
            assert(b.kyoItSumFixed3(0, 0, 0) == 0)
        }
    }
end ItSumFixedTest
