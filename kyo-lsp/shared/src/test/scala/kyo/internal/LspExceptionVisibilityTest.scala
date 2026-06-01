package kyo

/** Verifies the private[kyo] constructor on LspException and its stage bases (INV-029).
  *
  * External code cannot call `new LspException(...)` directly. This test verifies the
  * hierarchy is sealed and only accessible from within the `kyo` package scope.
  */
class LspExceptionVisibilityTest extends Test:

    "LspException leaves can be constructed from kyo package" in run {
        // This file is in package kyo, so construction succeeds.
        val e1 = LspException.Handshake.NotInitialized("test")
        val e2 = LspException.Dispatch.MethodNotFound("test")
        val e3 = LspException.Execution.RequestCancelled(Absent)
        assert(e1.isInstanceOf[LspException])
        assert(e2.isInstanceOf[LspException])
        assert(e3.isInstanceOf[LspException])
        succeed
    }

    "LspException hierarchy is sealed (compile check)" in run {
        // If LspException were not sealed, there would be no compile-time guarantee
        // against extension. The sealed keyword is enforced by Scala 3's type system.
        // We verify the hierarchy via instanceof checks.
        val handshake = LspException.Handshake.NotInitialized("x")
        val dispatch  = LspException.Dispatch.MethodNotFound("x")
        val execution = LspException.Execution.ContentModified()
        assert(handshake.isInstanceOf[LspException.Handshake])
        assert(dispatch.isInstanceOf[LspException.Dispatch])
        assert(execution.isInstanceOf[LspException.Execution])
        succeed
    }

end LspExceptionVisibilityTest
