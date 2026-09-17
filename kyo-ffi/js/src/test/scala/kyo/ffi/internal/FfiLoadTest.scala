package kyo.ffi.internal

import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.Test

/** `Ffi.load` on Scala.js constructs the impl the call site names, so the impl needs no registration for reflective instantiation.
  *
  * The fixtures here are hand-written siblings named the way the generator names an impl, and carry no annotation: a load that went
  * through a reflective lookup would not find them.
  */
class FfiLoadTest extends Test:

    // Every leaf calls Ffi.load, which a page answers with FfiLoadError.Unsupported before it reaches an impl. That gate is the subject of
    // BrowserDetectionTest's own browser group; here it would only mask what these leaves are about.
    override protected def hostFilters = kyo.Chunk(kyo.test.HostFilter.NotBrowser)

    // The load cache and the construction count are process-global; the leaves run one at a time.
    override def config = super.config.sequential

    "Ffi.load" - {
        "constructs the sibling impl with no reflective registration" in {
            Ffi.unload[FfiLoadTest.Plain]
            val loaded = Ffi.load[FfiLoadTest.Plain]
            assert(loaded.isInstanceOf[FfiLoadTest.PlainImpl])
            assert(loaded.answer == 42)
        }

        "constructs the impl once and hands back the same instance after that" in {
            Ffi.unload[FfiLoadTest.Counted]
            val before = FfiLoadTest.constructed
            val first  = Ffi.load[FfiLoadTest.Counted]
            val second = Ffi.load[FfiLoadTest.Counted]
            assert(first eq second)
            assert(FfiLoadTest.constructed == before + 1)
        }

        "constructs it again after unload" in {
            Ffi.unload[FfiLoadTest.Counted]
            val first = Ffi.load[FfiLoadTest.Counted]
            Ffi.unload[FfiLoadTest.Counted]
            val second = Ffi.load[FfiLoadTest.Counted]
            assert(!(first eq second))
        }

        "raises ImplNotFound naming the trait and the impl when no sibling impl exists" in {
            val ex = intercept[FfiLoadError.ImplNotFound](Ffi.load[FfiLoadTest.Unimplemented])
            assert(ex.traitFqcn == "kyo.ffi.internal.FfiLoadTest$Unimplemented")
            assert(ex.getMessage.contains("kyo.ffi.internal.FfiLoadTest$UnimplementedImpl"))
        }

        "does not take a sibling that is not an impl of the trait" in {
            discard(intercept[FfiLoadError.ImplNotFound](Ffi.load[FfiLoadTest.Mismatched]))
            succeed
        }
    }
end FfiLoadTest

object FfiLoadTest:

    var constructed: Int = 0

    trait Plain extends Ffi:
        def answer: Int

    final class PlainImpl extends Plain:
        def answer: Int = 42

    trait Counted extends Ffi

    final class CountedImpl extends Counted:
        constructed += 1

    trait Unimplemented extends Ffi

    trait Mismatched extends Ffi

    // Named like an impl of Mismatched, but it does not extend it.
    final class MismatchedImpl
end FfiLoadTest
