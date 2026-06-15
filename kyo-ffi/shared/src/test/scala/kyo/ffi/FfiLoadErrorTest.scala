package kyo.ffi

/** `Ffi.load[T]` failure error must name the binding trait, not just the impl class. The user-facing message ought to mention both the
  * binding trait FQN they wrote and the impl class name the runtime tried to load, so they can locate the source file.
  *
  * No generated impl exists for [[FfiLoadErrorTest.MissingBindings]], so `Ffi.load` will raise. Cross-platform: runs on JVM, Scala Native,
  * and Scala.js, each platform's `FfiReflect.instantiate` produces its own message but all three must include the binding's FQN.
  */
class FfiLoadErrorTest extends Test:

    "Ffi.load failure message names both the binding trait and the impl class" in {
        val ex = intercept[Throwable](Ffi.load[FfiLoadErrorTest.MissingBindings])
        // The exception type differs slightly per platform (IllegalStateException everywhere; on JS in browsers
        // it'd be FfiLoadError.Unsupported but tests don't run in browsers). What matters: the binding trait FQN appears.
        val msg = ex.getMessage
        assert(msg != null)
        // The fix introduces an explicit `for binding ...` (or equivalent) phrase that names the trait's
        // class on its own, distinct from the impl class. Asserting the literal phrase guards against
        // the previous behaviour where only the impl class was mentioned (and `MissingBindings` was
        // matched as a substring of `MissingBindingsImpl`).
        assert(msg.contains("for binding"))
        assert(msg.contains("MissingBindings"))
        assert(msg.contains("MissingBindingsImpl"))
    }
end FfiLoadErrorTest

object FfiLoadErrorTest:
    /** Synthetic binding trait with no generated impl, used to provoke the not-found error path. */
    trait MissingBindings extends Ffi
end FfiLoadErrorTest
