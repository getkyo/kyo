package kyo

/** Tests for the flat LspException hierarchy.
  *
  * Verifies the two per-operation traits ([[LspRequestFailure]], [[LspInitFailure]]), the
  * trait membership of each concrete leaf, and the JSON-RPC error codes. The hierarchy is flat
  * (no stage subcategories); leaves mix in exactly the operation-traits they can occur on.
  */
class LspExceptionTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // =========================================================================
    // Connection / remote leaves (the only client-observable leaves on a request row)
    // =========================================================================

    "LspConnectionClosedException - code -32603, mixes both operation-traits" in {
        val e = LspConnectionClosedException()
        assert(e.code == -32603)
        assert(e.message == "Connection closed.")
        assert(e.isInstanceOf[LspException])
        assert(e.isInstanceOf[JsonRpcApplicationError])
        assert(e.isInstanceOf[LspRequestFailure])
        assert(e.isInstanceOf[LspInitFailure])
    }

    "LspRemoteException - passes wire code/message through, mixes both operation-traits" in {
        val e = LspRemoteException(-32000, "remote boom")
        assert(e.code == -32000)
        assert(e.message == "remote boom")
        assert(e.isInstanceOf[LspRequestFailure])
        assert(e.isInstanceOf[LspInitFailure])
    }

    "LspConfigurationError - code -32603, mixes no operation-trait" in {
        val e = LspConfigurationError("positionEncodings", "must be non-empty")
        assert(e.code == -32603)
        assert(e.message.contains("positionEncodings"))
        assert(e.message.contains("must be non-empty"))
        assert(e.isInstanceOf[LspException])
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
        assert(!(e: LspException).isInstanceOf[LspInitFailure])
    }

    // =========================================================================
    // Engine-internal gate leaves (reach the client as LspRemoteException; no trait)
    // =========================================================================

    "LspNotInitializedException - code -32002, no operation-trait" in {
        val e = LspNotInitializedException("textDocument/completion")
        assert(e.code == -32002)
        assert(e.message.contains("textDocument/completion"))
        assert(e.isInstanceOf[LspException])
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
        assert(!(e: LspException).isInstanceOf[LspInitFailure])
    }

    "LspShutdownInProgressException - code -32600, no operation-trait" in {
        val e = LspShutdownInProgressException("textDocument/hover")
        assert(e.code == -32600)
        assert(e.message.contains("textDocument/hover"))
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
    }

    "LspCapabilityNotAdvertisedException - code -32601, no operation-trait" in {
        val e = LspCapabilityNotAdvertisedException(LspCapabilities.Name.Completion)
        assert(e.code == -32601)
        assert(e.message.contains("Completion"))
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
    }

    // =========================================================================
    // Construction-time programmer-error leaves (thrown inside init; no trait)
    // =========================================================================

    "LspWrongDirectionException - code -32603" in {
        val e = LspWrongDirectionException(LspHandler.Kind.ShowMessage, LspHandler.Direction.ServerHandled)
        assert(e.code == -32603)
        assert(e.message.contains("ShowMessage"))
        assert(e.message.contains("ServerHandled"))
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
    }

    "LspReservedMethodException - code -32603" in {
        val e = LspReservedMethodException("initialize")
        assert(e.code == -32603)
        assert(e.message.contains("initialize"))
    }

    "LspDuplicateHandlerException - code -32603" in {
        val e = LspDuplicateHandlerException(LspHandler.Kind.Completion)
        assert(e.code == -32603)
        assert(e.message.contains("Completion"))
    }

    // =========================================================================
    // Handler-body decode leaves (own narrow rows; no operation-trait)
    // =========================================================================

    "LspDecodeException - code -32602, no operation-trait" in {
        val e = LspDecodeException("textDocument/completion", "expected int")
        assert(e.code == -32602)
        assert(e.message.contains("textDocument/completion"))
        assert(e.message.contains("expected int"))
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
    }

    "LspInvalidParamsException - code -32602, no operation-trait" in {
        val e = LspInvalidParamsException("textDocument/completion", "missing line field")
        assert(e.code == -32602)
        assert(e.message.contains("textDocument/completion"))
        assert(e.message.contains("missing line field"))
        assert(!(e: LspException).isInstanceOf[LspRequestFailure])
    }

    // =========================================================================
    // Operation-trait exhaustiveness
    // =========================================================================

    "LspRequestFailure narrows to exactly the closed/remote leaves" in {
        def classify(e: LspRequestFailure): String = e match
            case _: LspConnectionClosedException => "closed"
            case _: LspRemoteException           => "remote"
        assert(classify(LspConnectionClosedException()) == "closed")
        assert(classify(LspRemoteException(-32000, "x")) == "remote")
    }

    "LspInitFailure narrows to exactly the closed/remote leaves" in {
        def classify(e: LspInitFailure): String = e match
            case _: LspConnectionClosedException => "closed"
            case _: LspRemoteException           => "remote"
        assert(classify(LspConnectionClosedException()) == "closed")
        assert(classify(LspRemoteException(-32002, "y")) == "remote")
    }

end LspExceptionTest
