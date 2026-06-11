package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.discard
import kyo.ffi.Ffi

/** Variadic (`Any*`) function IT coverage, JVM.
  *
  * Extends [[ItVarargsSharedTest]] (4 common cases) and adds the JVM-only `FfiUnsupported` error path test that exercises the
  * `VariadicMarshaller`'s rejection of an unsupported runtime class.
  */
class ItVarargsTest extends ItVarargsSharedTest:

    import AllowUnsafe.embrace.danger

    "unsupported runtime class surfaces a clear FfiUnsupported" in {
        val b = Ffi.load[ItVarargsBindings]
        val ex = intercept[kyo.ffi.FfiUnsupported] {
            discard(b.kyoItSumVarargs(1, List(1, 2, 3)))
        }
        assert(ex.getMessage.contains("kyo.ffi.it.ItVarargsBindings.kyoItSumVarargs"))
        assert(ex.getMessage.contains("scala.collection.immutable"))
    }
end ItVarargsTest
