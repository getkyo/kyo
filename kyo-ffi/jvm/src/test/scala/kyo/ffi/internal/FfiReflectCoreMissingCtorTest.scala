package kyo.ffi.internal

import kyo.ffi.Test

/** Pins the diagnostic produced by [[FfiReflect.instantiate]] when the candidate impl class only exposes a non-nullary constructor. A
  * stable, class-name-bearing error message is the contract between `Ffi.load` and users debugging missing generator runs.
  */
class FfiReflectCoreMissingCtorTest extends Test:

    "instantiate a class with no nullary constructor produces a diagnostic naming the class and the missing-nullary-ctor cause" in {
        val cls = classOf[FfiReflectCoreMissingCtorTest.NoNullaryCtor]
        val ex = intercept[IllegalStateException] {
            FfiReflect.instantiate(cls.getName, cls.getName)
        }
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.contains(cls.getName))
        assert(msg.contains("nullary constructor"))
    }
end FfiReflectCoreMissingCtorTest

object FfiReflectCoreMissingCtorTest:
    class NoNullaryCtor(val s: String)
end FfiReflectCoreMissingCtorTest
