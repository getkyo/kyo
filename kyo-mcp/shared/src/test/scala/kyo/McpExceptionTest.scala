package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

/** Tests for the McpException sealed hierarchy.
  *
  * Each leaf test:
  *   1. Constructs the leaf with representative typed field values.
  *   2. Asserts the `code` matches the spec-assigned or vendor-range constant.
  *   3. Asserts `message` contains the key diagnostic string.
  *   4. Asserts the leaf's operation-trait membership: the per-operation sealed traits that ARE its
  *      public `Abort` rows. A leaf that the engine raises onto a public row mixes in every operation
  *      whose row can surface it; engine-internal and handler-only leaves mix in none.
  *   5. Roundtrips the leaf through the inherited `Schema[JsonRpcError]` codec and asserts the
  *      decoded value's `code` (and, for vendor-range codes, `message`) are preserved. Per the
  *      wire-encoding asymmetry rule, the decoded type is a `JsonRpcError` leaf, so the test does NOT
  *      assert decoded == original.
  */
class McpExceptionTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // -------------------------------------------------------------------------
    // Helpers: roundtrip a McpException leaf through Schema[JsonRpcError].
    //
    // codeRoundtrip: checks only code preservation. Use for leaves with standard
    // JSON-RPC codes (-32601, -32602, -32603) where fromWire rebuilds the message
    // from a placeholder; the original message is NOT preserved for those codes.
    //
    // messageRoundtrip: checks both code and message fragment. Use for leaves in
    // the implementation-defined range (-32000 to -32099) where fromWire decodes
    // as JsonRpcImplementationError and passes the original message through.
    // -------------------------------------------------------------------------
    private def codeRoundtrip(err: McpException, expectedCode: Int)(using kyo.test.AssertScope, Frame): Boolean =
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        decoded.code == expectedCode
    end codeRoundtrip

    private def messageRoundtrip(err: McpException, expectedCode: Int, messageFragment: String)(using
        kyo.test.AssertScope,
        Frame
    ): Boolean =
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        decoded.code == expectedCode && decoded.message.contains(messageFragment)
    end messageRoundtrip

    // =========================================================================
    // Connection / configuration leaves
    // =========================================================================

    "McpConnectionClosedException mixes into every operation-trait" in {
        val e = McpConnectionClosedException()
        assert(e.code == -32603)
        assert(e.message.contains("Connection closed"))
        assert(e.isInstanceOf[McpException])
        // The transport-closed leaf is a member of every public operation row.
        assert(e.isInstanceOf[McpCallToolFailure])
        assert(e.isInstanceOf[McpCallToolRawFailure])
        assert(e.isInstanceOf[McpReadResourceFailure])
        assert(e.isInstanceOf[McpReadResourceRawFailure])
        assert(e.isInstanceOf[McpGetPromptFailure])
        assert(e.isInstanceOf[McpGetPromptRawFailure])
        assert(e.isInstanceOf[McpGetPromptCheckedFailure])
        assert(e.isInstanceOf[McpListFailure])
        assert(e.isInstanceOf[McpCompleteFailure])
        assert(e.isInstanceOf[McpClientRequestFailure])
        assert(e.isInstanceOf[McpRequestSamplingFailure])
        assert(e.isInstanceOf[McpRequestElicitationFailure])
        assert(e.isInstanceOf[McpRequestElicitationAsFailure])
        assert(e.isInstanceOf[McpRequestRootsFailure])
        assert(e.isInstanceOf[McpInitFailure])
        assert(codeRoundtrip(e, -32603))
    }

    "McpConfigurationError is a configuration panic payload with no operation trait" in {
        val e = McpConfigurationError("supportedProtocolVersions", "must be non-empty")
        assert(e.code == -32603)
        assert(e.message.contains("supportedProtocolVersions"))
        assert(e.message.contains("must be non-empty"))
        assert(e.isInstanceOf[McpException])
        // Config validation panics (it is a construction-time programmer error), so the leaf carries
        // no operation trait and never appears on a tracked Abort row.
        assert(!e.isInstanceOf[McpInitFailure])
        assert(codeRoundtrip(e, -32603))
    }

    // =========================================================================
    // Handshake-failure leaves
    // =========================================================================

    "McpHandshakeNotInitializedException is an init failure" in {
        val e = McpHandshakeNotInitializedException("tools/call")
        assert(e.code == -32002)
        assert(e.message.contains("initialize request"))
        assert(e.message.contains("tools/call"))
        assert(e.isInstanceOf[McpInitFailure])
        assert(e.isInstanceOf[McpException])
        assert(e.isInstanceOf[JsonRpcError])
        assert(messageRoundtrip(e, -32002, "initialize request"))
    }

    "McpHandshakeAlreadyInitializedException is engine-internal (no operation-trait)" in {
        val e = McpHandshakeAlreadyInitializedException("initialize")
        assert(e.code == -32002)
        assert(e.message.contains("already completed"))
        assert(e.message.contains("initialize"))
        assert(e.isInstanceOf[McpException])
        // produced server-side during dispatch; never on a client-facing operation row
        assert(!e.isInstanceOf[McpInitFailure])
        assert(messageRoundtrip(e, -32002, "already completed"))
    }

    "McpProtocolVersionMismatchException" in {
        val clientVer  = McpConfig.ProtocolVersion.fromWire("2024-01-01")
        val supportedV = McpConfig.ProtocolVersion.fromWire("2025-06-18")
        val e          = McpProtocolVersionMismatchException(clientVer, Chunk(supportedV))
        assert(e.code == -32602)
        assert(e.message.contains("2024-01-01"))
        assert(e.message.contains("2025-06-18"))
        assert(e.message.contains("mismatch"))
        assert(e.isInstanceOf[McpException])
        // Verify sorted output: single supported version should appear as-is
        assert(e.message.contains("Server supports: 2025-06-18"))
        // Code -32602 is a standard JSON-RPC code; fromWire rebuilds the message from a placeholder.
        assert(codeRoundtrip(e, -32602))
    }

    "McpProtocolVersionMismatchException sorted supported list" in {
        val clientVer = McpConfig.ProtocolVersion.fromWire("2024-01-01")
        val v1        = McpConfig.ProtocolVersion.fromWire("2025-06-18")
        val v2        = McpConfig.ProtocolVersion.fromWire("2025-03-26")
        val e         = McpProtocolVersionMismatchException(clientVer, Chunk(v1, v2))
        val msg       = e.message
        val idx1      = msg.indexOf("2025-03-26")
        val idx2      = msg.indexOf("2025-06-18")
        assert(idx1 >= 0)
        assert(idx2 >= 0)
        assert(idx1 < idx2)
    }

    // =========================================================================
    // Dispatch-failure leaves
    // =========================================================================

    "McpUnknownToolException is engine-internal (no operation-trait)" in {
        val e = McpUnknownToolException("add", Chunk("subtract", "multiply"))
        assert(e.code == -32602)
        assert(e.message.contains("add"))
        assert(e.message.contains("subtract"))
        assert(e.message.contains("multiply"))
        assert(e.isInstanceOf[McpException])
        // raised at the server dispatch boundary; the client sees a remote wire error, not this leaf
        assert(!e.isInstanceOf[McpCallToolFailure])
        assert(codeRoundtrip(e, -32602))
    }

    "McpUnknownToolException empty registered list" in {
        val e = McpUnknownToolException("missing", Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpUnknownResourceException is engine-internal" in {
        val uri    = McpResourceUri.parse("file:///missing").get
        val regUri = McpResourceUri.parse("file:///known").get
        val e      = McpUnknownResourceException(uri, Chunk(regUri))
        assert(e.code == -32002)
        assert(e.message.contains("file:///missing"))
        assert(e.message.contains("file:///known"))
        assert(e.isInstanceOf[McpException])
        assert(!e.isInstanceOf[McpReadResourceFailure])
        assert(messageRoundtrip(e, -32002, "file:///missing"))
    }

    "McpUnknownResourceException empty registered list" in {
        val uri = McpResourceUri.parse("file:///missing").get
        val e   = McpUnknownResourceException(uri, Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpUnknownPromptException is engine-internal" in {
        val e = McpUnknownPromptException("summarize", Chunk("translate", "format"))
        assert(e.code == -32602)
        assert(e.message.contains("summarize"))
        assert(e.message.contains("translate"))
        assert(e.isInstanceOf[McpException])
        assert(!e.isInstanceOf[McpGetPromptFailure])
        assert(codeRoundtrip(e, -32602))
    }

    "McpUnknownPromptException empty registered list" in {
        val e = McpUnknownPromptException("missing", Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpCapabilityNotAdvertisedException mixes into the guarded + reverse + init operations" in {
        val e = McpCapabilityNotAdvertisedException(
            "tools/call",
            McpCapabilities.Name.Tools,
            McpCapabilityNotAdvertisedException.Peer.Server
        )
        assert(e.code == -32601)
        assert(e.message.contains("tools/call"))
        assert(e.message.contains("tools"))
        assert(e.message.contains("server"))
        assert(e.isInstanceOf[McpException])
        // a representative spread of the 12 operation-traits it belongs to
        assert(e.isInstanceOf[McpCallToolFailure])
        assert(e.isInstanceOf[McpListFailure])
        assert(e.isInstanceOf[McpRequestSamplingFailure])
        assert(e.isInstanceOf[McpRequestRootsFailure])
        assert(e.isInstanceOf[McpInitFailure])
        // but not the reverse calls that cannot produce a missing-capability failure
        assert(!e.isInstanceOf[McpRequestElicitationFailure])
        assert(codeRoundtrip(e, -32601))
    }

    "McpCapabilityNotAdvertisedException - client peer" in {
        val e = McpCapabilityNotAdvertisedException(
            "sampling/createMessage",
            McpCapabilities.Name.Sampling,
            McpCapabilityNotAdvertisedException.Peer.Client
        )
        assert(e.code == -32601)
        assert(e.message.contains("client"))
        assert(e.message.contains("sampling"))
    }

    "McpCapabilityNotAdvertisedException.Peer.describe is lowercase" in {
        assert(McpCapabilityNotAdvertisedException.Peer.Server.describe == "server")
        assert(McpCapabilityNotAdvertisedException.Peer.Client.describe == "client")
    }

    "McpInvalidArgumentException mixes into getPromptChecked + requestRoots" in {
        val e = McpInvalidArgumentException("tools/call", "name", "must not be empty")
        assert(e.code == -32602)
        assert(e.message.contains("tools/call"))
        assert(e.message.contains("name"))
        assert(e.message.contains("must not be empty"))
        assert(e.isInstanceOf[McpGetPromptCheckedFailure])
        assert(e.isInstanceOf[McpRequestRootsFailure])
        assert(codeRoundtrip(e, -32602))
    }

    // =========================================================================
    // Execution-failure leaves
    // =========================================================================

    "McpToolStructuredMissingException mixes into the typed read operations" in {
        val e = McpToolStructuredMissingException("add")
        assert(e.code == -32603)
        assert(e.message.contains("add"))
        assert(e.message.contains("structured content"))
        assert(e.isInstanceOf[McpCallToolFailure])
        assert(e.isInstanceOf[McpReadResourceFailure])
        assert(e.isInstanceOf[McpGetPromptFailure])
        assert(e.isInstanceOf[McpGetPromptCheckedFailure])
        // but never on a raw lane (which does not decode)
        assert(!e.isInstanceOf[McpCallToolRawFailure])
        assert(codeRoundtrip(e, -32603))
    }

    "McpToolStructuredDecodeException mixes into the typed reads + requestElicitationAs" in {
        val e = McpToolStructuredDecodeException("add", "expected Int got String")
        assert(e.tool == "add")
        assert(e.reason == "expected Int got String")
        assert(e.code == -32603)
        assert(e.message.contains("add"))
        assert(e.message.contains("expected Int got String"))
        assert(e.isInstanceOf[McpCallToolFailure])
        assert(e.isInstanceOf[McpReadResourceFailure])
        assert(e.isInstanceOf[McpGetPromptFailure])
        assert(e.isInstanceOf[McpGetPromptCheckedFailure])
        assert(e.isInstanceOf[McpRequestElicitationAsFailure])
        assert(e.isInstanceOf[McpException])
        assert(codeRoundtrip(e, -32603))
    }

    "McpDecodeException carries field + code, no operation-trait (narrow helper row)" in {
        val e = McpDecodeException("_meta", "bad shape")
        assert(e.field == "_meta")
        assert(e.reason == "bad shape")
        assert(e.code == -32603)
        assert(e.message.contains("_meta"))
        assert(e.message.contains("bad shape"))
        assert(e.isInstanceOf[McpException])
        // it carries its own Abort[McpDecodeException] row on extras/metaAs, not an operation-trait
        assert(!e.isInstanceOf[McpCallToolFailure])
        assert(codeRoundtrip(e, -32603))
    }

    "McpSamplingRejectedException is a requestSampling failure" in {
        val e = McpSamplingRejectedException("model unavailable")
        assert(e.code == -32603)
        assert(e.message.contains("model unavailable"))
        assert(e.isInstanceOf[McpRequestSamplingFailure])
        assert(!e.isInstanceOf[McpRequestElicitationFailure])
        assert(codeRoundtrip(e, -32603))
    }

    "McpElicitationDeclinedException mixes into both elicitation operations" in {
        val e = McpElicitationDeclinedException("user cancelled")
        assert(e.code == -32603)
        assert(e.message.contains("user cancelled"))
        assert(e.isInstanceOf[McpRequestElicitationFailure])
        assert(e.isInstanceOf[McpRequestElicitationAsFailure])
        assert(codeRoundtrip(e, -32603))
    }

    // =========================================================================
    // Application-error leaves (handler-raisable; no engine operation-trait)
    // =========================================================================

    "McpToolExecutionException is handler-only (no operation-trait)" in {
        val e = McpToolExecutionException("add", "div-by-zero")
        assert(e.code == -32000)
        assert(e.message.contains("add"))
        assert(e.message.contains("div-by-zero"))
        assert(e.isInstanceOf[McpException])
        assert(!e.isInstanceOf[McpCallToolFailure])
        // Wire roundtrip: code -32000 is in the implementation-defined range so fromWire decodes it
        // as JsonRpcImplementationError (wire-encoding asymmetry).
        val encoded = Structure.encode[JsonRpcError](e)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        assert(decoded.code == -32000)
        assert(decoded.message.contains("div-by-zero"))
    }

    "McpToolExecutionException with Throwable cause" in {
        val cause = new RuntimeException("/ by zero")
        val e     = McpToolExecutionException("add", "division by zero", cause = Present(cause))
        assert(e.code == -32000)
        assert(e.message.contains("division by zero"))
        assert(messageRoundtrip(e, -32000, "division by zero"))
    }

    "McpToolExecutionException with absent cause" in {
        val e = McpToolExecutionException("add", "overflow", cause = Absent)
        assert(e.code == -32000)
        assert(messageRoundtrip(e, -32000, "overflow"))
    }

    "McpResourceReadException is handler-only" in {
        val uri = McpResourceUri.parse("file:///data.txt").get
        val e   = McpResourceReadException(uri, "permission denied")
        assert(e.code == -32001)
        assert(e.message.contains("file:///data.txt"))
        assert(e.message.contains("permission denied"))
        assert(e.isInstanceOf[McpException])
        assert(!e.isInstanceOf[McpReadResourceFailure])
        assert(messageRoundtrip(e, -32001, "permission denied"))
    }

    "McpResourceReadException with Throwable cause" in {
        val uri   = McpResourceUri.parse("file:///data.txt").get
        val cause = new RuntimeException("file not found")
        val e     = McpResourceReadException(uri, "read failed", cause = Present(cause))
        assert(e.code == -32001)
        assert(e.message.contains("read failed"))
        assert(messageRoundtrip(e, -32001, "read failed"))
    }

    "McpPromptRenderException is handler-only" in {
        val e = McpPromptRenderException("summarize", "template syntax error")
        assert(e.code == -32003)
        assert(e.message.contains("summarize"))
        assert(e.message.contains("template syntax error"))
        assert(e.isInstanceOf[McpException])
        assert(!e.isInstanceOf[McpGetPromptFailure])
        assert(messageRoundtrip(e, -32003, "template syntax error"))
    }

    "McpPromptRenderException with Throwable cause" in {
        val cause = new RuntimeException("parse error")
        val e     = McpPromptRenderException("format", "render failed", cause = Present(cause))
        assert(e.code == -32003)
        assert(messageRoundtrip(e, -32003, "render failed"))
    }

    "McpRemoteApplicationException mixes into every client forward operation" in {
        val data = Present(Structure.encode(1))
        val e    = McpRemoteApplicationException(-32050, "remote boom", data)
        assert(e.remoteCode == -32050)
        assert(e.remoteMessage == "remote boom")
        assert(e.remoteData == data)
        assert(e.underlyingCause == Absent)
        assert(e.isInstanceOf[McpCallToolFailure])
        assert(e.isInstanceOf[McpCallToolRawFailure])
        assert(e.isInstanceOf[McpListFailure])
        assert(e.isInstanceOf[McpCompleteFailure])
        assert(e.isInstanceOf[McpClientRequestFailure])
        // but never on a reverse-direction row (the server never returns a remote app error to a reverse call)
        assert(!e.isInstanceOf[McpRequestSamplingFailure])
        assert(messageRoundtrip(e, -32050, "remote boom"))
    }

    // =========================================================================
    // Operation-trait compile evidence + base wiring
    // =========================================================================

    "operation-trait is a valid Abort row element: McpCallToolFailure" in {
        val e: McpCallToolFailure   = McpConnectionClosedException()
        val asError: McpException   = e
        val asJsonRpc: JsonRpcError = e
        assert(asError.code == -32603)
        assert(asJsonRpc.code == -32603)
    }

    "operation-trait is a valid Abort row element: McpRequestSamplingFailure" in {
        val e: McpRequestSamplingFailure     = McpSamplingRejectedException("x")
        val asError: McpException            = e
        val asApp: JsonRpcApplicationFailure = e
        assert(asError.code == -32603)
        assert(asApp.code == -32603)
    }

    "McpResourceUri fields are typed McpResourceUri not String" in {
        val uri1                     = McpResourceUri.parse("file:///a").get
        val uri2                     = McpResourceUri.parse("file:///b").get
        val e                        = McpUnknownResourceException(uri1, Chunk(uri2))
        val _: McpResourceUri        = e.uri
        val _: Chunk[McpResourceUri] = e.registered
        assert(e.uri.asString == "file:///a")
        assert(e.registered.head.asString == "file:///b")
    }

    "no leaf carries a free-form detail: String field" in {
        // The message is constructed from typed fields, never a free-form detail param.
        val e1 = McpHandshakeNotInitializedException("tools/call")
        val e2 = McpUnknownToolException("add", Chunk.empty)
        val e3 = McpToolStructuredMissingException("add")
        val e4 = McpToolExecutionException("add", "error")
        assert(!e1.message.startsWith("detail"))
        assert(!e2.message.startsWith("detail"))
        assert(!e3.message.startsWith("detail"))
        assert(!e4.message.startsWith("detail"))
    }

    // =========================================================================
    // Cause unification
    // =========================================================================

    "leaf cause exposed via underlyingCause" in {
        val cause = new RuntimeException("x")
        val e     = McpToolExecutionException("t", "boom", Present(cause))
        val uc    = e.underlyingCause
        assert(uc.isDefined)
        assert(uc.get.getMessage == "x")
    }

    "absent cause is Absent" in {
        val uri = McpResourceUri.parse("file:///r.txt").get
        val e   = McpResourceReadException(uri, "r")
        assert(e.underlyingCause == Absent)
    }

    "cause unified order across leaves" in {
        val handshake           = McpHandshakeNotInitializedException("m")
        val ex                  = new RuntimeException("err")
        val prompt              = McpPromptRenderException("p", "r", Present(ex))
        val _: Maybe[Throwable] = handshake.underlyingCause
        val _: Maybe[Throwable] = prompt.underlyingCause
        assert(handshake.underlyingCause == Absent)
        assert(prompt.underlyingCause == Present(ex))
    }

end McpExceptionTest
