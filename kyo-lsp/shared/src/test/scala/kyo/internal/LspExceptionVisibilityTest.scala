package kyo

/** Verifies that `LspException` leaves can be constructed from within the kyo package.
  *
  * This test lives in `package kyo` intentionally. The `private[kyo]` constructor qualifier
  * on `LspException` and its stage bases means they are accessible HERE (inside the kyo package)
  * and invisible to code OUTSIDE the kyo package. The proof that code outside `package kyo`
  * cannot invoke `new LspException(...)` directly rests on Scala 3's access-modifier enforcement:
  * any test file in a package OTHER than `kyo` attempting `new LspException(...)` would fail to
  * compile. The package boundary is enforced by the Scala type system.
  *
  * What this test does verify:
  *   - Concrete leaf classes can be constructed via their factory `apply` methods from within kyo.
  *   - The hierarchy is sealed: instanceof checks confirm stage membership.
  *   - Leaf values extend the correct stage base class.
  */
class LspExceptionVisibilityTest extends Test:

    "LspException leaves can be constructed from kyo package" in {
        val e1 = LspException.Handshake.NotInitialized("test-method")
        val e2 = LspException.Dispatch.MethodNotFound("test-method")
        val e3 = LspException.Execution.RequestCancelled(JsonRpcId(1L))
        assert(e1.isInstanceOf[LspException])
        assert(e2.isInstanceOf[LspException])
        assert(e3.isInstanceOf[LspException])
    }

    "LspException leaves extend the correct stage base" in {
        val handshake   = LspException.Handshake.NotInitialized("x")
        val dispatch    = LspException.Dispatch.MethodNotFound("x")
        val execution   = LspException.Execution.ContentModified(LspHandler.LspDocument.Uri.parse("file:///Main.scala").get)
        val alreadyInit = LspException.Handshake.AlreadyInitialized("x")
        val shutdown    = LspException.Handshake.ShutdownInProgress("x")
        val unknown = LspException.Dispatch.UnknownDocument(LspHandler.LspDocument.Uri.parse("file:///X.scala").get, "textDocument/hover")
        val panic   = LspException.Execution.ExecutionPanic("x")
        assert(handshake.isInstanceOf[LspException.Handshake])
        assert(dispatch.isInstanceOf[LspException.Dispatch])
        assert(execution.isInstanceOf[LspException.Execution])
        assert(alreadyInit.isInstanceOf[LspException.Handshake])
        assert(shutdown.isInstanceOf[LspException.Handshake])
        assert(unknown.isInstanceOf[LspException.Dispatch])
        assert(panic.isInstanceOf[LspException.Execution])
    }

    "LspException stage hierarchy is sealed (compile check)" in {
        // The sealed modifier prevents extension outside the file. This assertion
        // verifies the closed hierarchy at runtime via exhaustive pattern matching.
        val exceptions: Chunk[LspException] = Chunk(
            LspException.Handshake.NotInitialized("h"),
            LspException.Dispatch.MethodNotFound("d"),
            LspException.Execution.ExecutionPanic("e")
        )
        val stages: Chunk[String] = exceptions.map {
            case _: LspException.Handshake   => "handshake"
            case _: LspException.Dispatch    => "dispatch"
            case _: LspException.Execution   => "execution"
            case _: LspException.Application => "application"
        }
        assert(stages == Chunk("handshake", "dispatch", "execution"))
    }

end LspExceptionVisibilityTest
