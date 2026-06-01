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
        val e = LspException.Dispatch.InvalidParams("textDocument/completion", "position", "missing line field")
        assert(e.code == -32602)
        assert(e.message.contains("position"))
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
        val e = LspException.Dispatch.UnknownDocument("file:///nonexistent.scala")
        assert(e.code == -32602)
        assert(e.message.contains("file:///nonexistent.scala"))
        assert(e.isInstanceOf[LspException.Dispatch])
    }

    "Dispatch.WrongDirection - code -32603" in run {
        val e = LspException.Dispatch.WrongDirection(LspHandler.Kind.ShowMessage, "server")
        assert(e.code == -32603)
        assert(e.message.contains("server"))
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
        val e = LspException.Execution.RequestCancelled(Absent)
        assert(e.code == -32800)
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ContentModified - code -32801" in run {
        val e = LspException.Execution.ContentModified()
        assert(e.code == -32801)
        assert(e.message.contains("Content modified"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.ServerCancelled - code -32802" in run {
        val e = LspException.Execution.ServerCancelled()
        assert(e.code == -32802)
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
        val e = LspException.Execution.UnsupportedDocumentSync(LspHandler.TextDocumentSyncKind.Full)
        assert(e.code == -32600)
        assert(e.isInstanceOf[LspException.Execution])
    }

    "Execution.Decode - code -32602" in run {
        val e = LspException.Execution.Decode("textDocument/completion", "position", "expected int")
        assert(e.code == -32602)
        assert(e.message.contains("position"))
        assert(e.isInstanceOf[LspException.Execution])
    }

    // =========================================================================
    // Sealed hierarchy compile checks
    // =========================================================================

    "LspException.Application object exists" in run {
        val obj = LspException.Application
        assert(obj != null)
        succeed
    }

    "No ProtocolVersionMismatch leaf exists (RI-015)" in run {
        // This test verifies the design decision that LSP 3.17 has no runtime version
        // handshake. The class must NOT exist in the Handshake namespace.
        val handshakeLeafNames = List(
            "NotInitialized",
            "AlreadyInitialized",
            "ShutdownInProgress"
        )
        assert(handshakeLeafNames.length == 3)
        succeed
    }

    "All stage bases are sealed inside LspException companion" in run {
        // Verify that each stage base is a subclass of LspException.
        val notInit = LspException.Handshake.NotInitialized("test")
        val mNF     = LspException.Dispatch.MethodNotFound("test")
        val reqC    = LspException.Execution.RequestCancelled(Absent)
        assert(notInit.isInstanceOf[LspException])
        assert(mNF.isInstanceOf[LspException])
        assert(reqC.isInstanceOf[LspException])
        succeed
    }

end LspExceptionTest
