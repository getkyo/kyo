package demo

import kyo.*

/** Validates every kyo-compiler demo against a real presentation compiler.
  *
  * The CI-exerciser half of the dual-purpose demos: it drives the SAME `flow` an editor author reads (no
  * re-implemented copy) against a live in-process pc through the demo object's `flow` and `validate`, and
  * asserts the demo's `validate` hook returns `Absent`. A `Present` verdict, or any op abort, fails the
  * leaf. Mirrors kyo-browser's `DemoValidationTest`.
  */
class DemoValidationTest extends kyo.test.Test[Any]:

    // A cold pc init dominates the run; the warm multi-op session is sub-second. Generous headroom so
    // the one-time init never races the timeout.
    override def timeout = 180.seconds

    "IdeSessionDemo: flow drives the real presentation compiler and validate returns Absent" in {
        Abort.run[CompilerException](IdeSessionDemo.withCompiler(c => IdeSessionDemo.flow(c).map(IdeSessionDemo.validate))).map {
            case Result.Success(verdict) =>
                assert(verdict == Absent, s"demo validate must return Absent against the real pc; got: $verdict")
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

end DemoValidationTest
