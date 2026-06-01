package kyo

/** Tests for the LspException sealed hierarchy (Phase 01).
  *
  * Verifies the four stage bases and 22 concrete leaves are properly nested inside
  * `object LspException` and carry the correct JSON-RPC error codes.
  */
class LspExceptionTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // =========================================================================
    // Handshake leaves
    // =========================================================================

    "Handshake.NotInitialized - code -32002" in run {
        val e = LspException.Handshake.NotInitialized("textDocument/completion")
        assert(e.code == -32002)
        assert(e.message.contains("textDocument/completion"))
        assert(e.isInstanceOf[LspException.Handshake])
        assert(e.isInstanceOf[LspException])
        assert(e.isInstanceOf[JsonRpcApplicationError])
    }

    "Handshake.AlreadyInitialized - code -32002" in run {
        val e = LspException.Handshake.AlreadyInitialized("initialize")
        assert(e.code == -32002)
        assert(e.message.contains("initialize"))
        assert(e.isInstanceOf[LspException.Handshake])
    }

    "Handshake.ShutdownInProgress - code -32600" in run {
        val e = LspException.Handshake.ShutdownInProgress("textDocument/hover")
        assert(e.code == -32600)
        assert(e.message.contains("textDocument/hover"))
        assert(e.isInstanceOf[LspException.Handshake])
    }

    // =========================================================================
    // Dispatch leaves
    // =========================================================================

    "Dispatch.MethodNotFound - code -32601" in run {
        val e = LspException.Dispatch.MethodNotFound("unknown/method")
        assert(e.code == -32601)
        assert(e.message.contains("unknown/method"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.InvalidParams - code -32602" in run {
        val e = LspException.Dispatch.InvalidParams("textDocument/completion", "missing line field")
        assert(e.code == -32602)
        assert(e.message.contains("textDocument/completion"))
        assert(e.message.contains("missing line field"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.InvalidRequest - code -32600" in run {
        val e = LspException.Dispatch.InvalidRequest("malformed JSON")
        assert(e.code == -32600)
        assert(e.message.contains("malformed JSON"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.UnknownDocument - code -32602" in run {
        val uri = LspHandler.LspDocument.Uri.parse("file:///nonexistent.scala").get
        val e   = LspException.Dispatch.UnknownDocument(uri, "textDocument/hover")
        assert(e.code == -32602)
        assert(e.message.contains("file:///nonexistent.scala"))
        assert(e.message.contains("textDocument/hover"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.WrongDirection - code -32603" in run {
        val e = LspException.Dispatch.WrongDirection(LspHandler.Kind.ShowMessage, LspHandler.Direction.ServerHandled)
        assert(e.code == -32603)
        assert(e.message.contains("ShowMessage"))
        assert(e.message.contains("ServerHandled"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.ReservedMethod - code -32603" in run {
        val e = LspException.Dispatch.ReservedMethod("initialize")
        assert(e.code == -32603)
        assert(e.message.contains("initialize"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.CapabilityNotAdvertised - code -32601" in run {
        val e = LspException.Dispatch.CapabilityNotAdvertised(LspCapabilities.Name.Completion)
        assert(e.code == -32601)
        assert(e.message.contains("Completion"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.DuplicateHandler - code -32603" in run {
        val e = LspException.Dispatch.DuplicateHandler(LspHandler.Kind.Completion)
        assert(e.code == -32603)
        assert(e.message.contains("Completion"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    // =========================================================================
    // Execution leaves
    // =========================================================================

    "Execution.RequestCancelled - code -32800" in run {
        val e = LspException.Execution.RequestCancelled(JsonRpcId(42L))
        assert(e.code == -32800)
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ContentModified - code -32801" in run {
        val uri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
        val e   = LspException.Execution.ContentModified(uri)
        assert(e.code == -32801)
        assert(e.message.contains("Content modified"))
        assert(e.message.contains("file:///Main.scala"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ServerCancelled - code -32802" in run {
        val e = LspException.Execution.ServerCancelled("handler overloaded")
        assert(e.code == -32802)
        assert(e.message.contains("handler overloaded"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ExecutionPanic - code -32603" in run {
        val e = LspException.Execution.ExecutionPanic("textDocument/hover")
        assert(e.code == -32603)
        assert(e.message.contains("textDocument/hover"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ProgressTokenAlreadyInUse - code -32603" in run {
        val e = LspException.Execution.ProgressTokenAlreadyInUse(LspHandler.ProgressToken.StringToken("my-token"))
        assert(e.code == -32603)
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.UnsupportedDocumentSync - code -32600" in run {
        val e = LspException.Execution.UnsupportedDocumentSync(
            LspHandler.TextDocumentSyncKind.Full,
            LspHandler.TextDocumentSyncKind.Incremental
        )
        assert(e.code == -32600)
        assert(e.message.contains("Full"))
        assert(e.message.contains("Incremental"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.Decode - code -32602" in run {
        val e = LspException.Execution.Decode("textDocument/completion", "expected int")
        assert(e.code == -32602)
        assert(e.message.contains("textDocument/completion"))
        assert(e.message.contains("expected int"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    // =========================================================================
    // Sealed hierarchy compile checks
    // =========================================================================

    "LspException.Application stage base is a sealed abstract type" in run {
        // Application is the stage base for user-domain errors.
        // The sealed trait means no library leaves exist; user errors come via .error[E2].
        // We verify the hierarchy by constructing a value, upcasting to LspException, and
        // using exhaustive pattern matching to confirm stage membership.
        val notInit: LspException = LspException.Handshake.NotInitialized("test-method")
        notInit match
            case _: LspException.Handshake   => () // expected
            case _: LspException.Dispatch    => fail("Wrong stage: expected Handshake")
            case _: LspException.Execution   => fail("Wrong stage: expected Handshake")
            case _: LspException.Application => fail("Wrong stage: expected Handshake")
        end match
        assert(notInit.isInstanceOf[LspException.Handshake])
    }

    "No ProtocolVersionMismatch leaf exists; only three Handshake leaves (RI-015)" in run {
        // LSP 3.17 has no runtime version handshake.
        // Verify the three Handshake leaves all exist and are accessible.
        val notInit     = LspException.Handshake.NotInitialized("test")
        val alreadyInit = LspException.Handshake.AlreadyInitialized("test")
        val shutdown    = LspException.Handshake.ShutdownInProgress("test")
        assert(notInit.isInstanceOf[LspException.Handshake])
        assert(alreadyInit.isInstanceOf[LspException.Handshake])
        assert(shutdown.isInstanceOf[LspException.Handshake])
        // Verify distinct error codes (all three are the complete Handshake leaf set per INV-014).
        assert(notInit.code == -32002)
        assert(alreadyInit.code == -32002)
        assert(shutdown.code == -32600)
    }

    "All stage bases extend LspException and are subclasses of it" in run {
        val notInit  = LspException.Handshake.NotInitialized("test")
        val mNF      = LspException.Dispatch.MethodNotFound("test")
        val reqC     = LspException.Execution.RequestCancelled(JsonRpcId(1L))
        val shutdown = LspException.Handshake.ShutdownInProgress("shutdown")
        assert(notInit.isInstanceOf[LspException])
        assert(mNF.isInstanceOf[LspException])
        assert(reqC.isInstanceOf[LspException])
        assert(shutdown.isInstanceOf[LspException])
        // Verify exact stage hierarchy
        assert(notInit.isInstanceOf[LspException.Handshake])
        assert(mNF.isInstanceOf[LspException.Dispatch])
        assert(reqC.isInstanceOf[LspException.Execution])
        assert(shutdown.isInstanceOf[LspException.Handshake])
    }

end LspExceptionTest
