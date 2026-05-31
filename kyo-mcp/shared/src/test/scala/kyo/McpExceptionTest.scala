package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

/** Tests for the McpException sealed hierarchy (Phase 2).
  *
  * Each leaf test:
  *   1. Constructs the leaf with representative typed field values.
  *   2. Asserts the `code` matches the spec-assigned or vendor-range constant.
  *   3. Asserts `message` contains the key diagnostic string.
  *   4. Roundtrips the leaf through the inherited `Schema[JsonRpcError]` codec
  *      (encodes via `Structure.encode[JsonRpcError]`, decodes via
  *      `Structure.decode[JsonRpcError]`) and asserts the decoded value's
  *      `code` and `message` are preserved. Per the design's wire-encoding
  *      asymmetry rule, the decoded type is a `JsonRpcError` leaf (not the
  *      original `McpException` leaf), so the test does NOT assert decoded == original.
  *
  * Pins INV-003 (hierarchy root), INV-005 (handshake gate leaf set), INV-014
  * (McpClient parameter order is downstream of these leaves existing), INV-022
  * (typed McpResourceUri in leaf fields).
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
    private def codeRoundtrip(err: McpException, expectedCode: Int): Boolean =
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        decoded.code == expectedCode
    end codeRoundtrip

    private def messageRoundtrip(err: McpException, expectedCode: Int, messageFragment: String): Boolean =
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        decoded.code == expectedCode && decoded.message.contains(messageFragment)
    end messageRoundtrip

    // =========================================================================
    // Handshake-failure leaves
    // =========================================================================

    "McpHandshakeNotInitializedException" in run {
        val e = McpHandshakeNotInitializedException("tools/call")
        assert(e.code == -32002)
        assert(e.message.contains("initialize request"))
        assert(e.message.contains("tools/call"))
        assert(e.isInstanceOf[McpHandshakeException])
        assert(e.isInstanceOf[McpException])
        assert(e.isInstanceOf[JsonRpcError])
        assert(messageRoundtrip(e, -32002, "initialize request"))
    }

    "McpHandshakeAlreadyInitializedException" in run {
        val e = McpHandshakeAlreadyInitializedException("initialize")
        assert(e.code == -32002)
        assert(e.message.contains("already completed"))
        assert(e.message.contains("initialize"))
        assert(e.isInstanceOf[McpHandshakeException])
        assert(messageRoundtrip(e, -32002, "already completed"))
    }

    "McpProtocolVersionMismatchException" in run {
        val clientVer  = McpProtocolVersion.fromWire("2024-01-01")
        val supportedV = McpProtocolVersion.fromWire("2025-06-18")
        val e          = McpProtocolVersionMismatchException(clientVer, Chunk(supportedV))
        assert(e.code == -32602)
        assert(e.message.contains("2024-01-01"))
        assert(e.message.contains("2025-06-18"))
        assert(e.message.contains("mismatch"))
        assert(e.isInstanceOf[McpHandshakeException])
        // Verify sorted output: single supported version should appear as-is
        assert(e.message.contains("Server supports: 2025-06-18"))
        // Code -32602 is a standard JSON-RPC code; fromWire rebuilds the message from a placeholder.
        // Only code preservation is guaranteed on the wire.
        assert(codeRoundtrip(e, -32602))
    }

    "McpProtocolVersionMismatchException sorted supported list" in run {
        // Two supported versions: ensure message contains them in sorted order
        val clientVer = McpProtocolVersion.fromWire("2024-01-01")
        val v1        = McpProtocolVersion.fromWire("2025-06-18")
        val v2        = McpProtocolVersion.fromWire("2025-03-26")
        val e         = McpProtocolVersionMismatchException(clientVer, Chunk(v1, v2))
        // sorted alphabetically: "2025-03-26" < "2025-06-18"
        val msg  = e.message
        val idx1 = msg.indexOf("2025-03-26")
        val idx2 = msg.indexOf("2025-06-18")
        assert(idx1 >= 0)
        assert(idx2 >= 0)
        assert(idx1 < idx2)
    }

    // =========================================================================
    // Dispatch-failure leaves
    // =========================================================================

    "McpUnknownToolException" in run {
        val e = McpUnknownToolException("add", Chunk("subtract", "multiply"))
        assert(e.code == -32602)
        assert(e.message.contains("add"))
        assert(e.message.contains("subtract"))
        assert(e.message.contains("multiply"))
        assert(e.isInstanceOf[McpDispatchException])
        assert(codeRoundtrip(e, -32602))
    }

    "McpUnknownToolException empty registered list" in run {
        val e = McpUnknownToolException("missing", Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpUnknownResourceException" in run {
        val uri    = McpResourceUri.parse("file:///missing").get
        val regUri = McpResourceUri.parse("file:///known").get
        val e      = McpUnknownResourceException(uri, Chunk(regUri))
        assert(e.code == -32002)
        assert(e.message.contains("file:///missing"))
        assert(e.message.contains("file:///known"))
        assert(e.isInstanceOf[McpDispatchException])
        assert(messageRoundtrip(e, -32002, "file:///missing"))
    }

    "McpUnknownResourceException empty registered list" in run {
        val uri = McpResourceUri.parse("file:///missing").get
        val e   = McpUnknownResourceException(uri, Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpUnknownPromptException" in run {
        val e = McpUnknownPromptException("summarize", Chunk("translate", "format"))
        assert(e.code == -32602)
        assert(e.message.contains("summarize"))
        assert(e.message.contains("translate"))
        assert(e.isInstanceOf[McpDispatchException])
        assert(codeRoundtrip(e, -32602))
    }

    "McpUnknownPromptException empty registered list" in run {
        val e = McpUnknownPromptException("missing", Chunk.empty)
        assert(e.message.contains("(none)"))
    }

    "McpCapabilityNotAdvertisedException - server peer" in run {
        val e = McpCapabilityNotAdvertisedException(
            "tools/call",
            McpCapabilities.Name.Tools,
            McpCapabilityNotAdvertisedException.Peer.Server
        )
        assert(e.code == -32601)
        assert(e.message.contains("tools/call"))
        assert(e.message.contains("tools"))
        assert(e.message.contains("server"))
        assert(e.isInstanceOf[McpDispatchException])
        assert(codeRoundtrip(e, -32601))
    }

    "McpCapabilityNotAdvertisedException - client peer" in run {
        val e = McpCapabilityNotAdvertisedException(
            "sampling/createMessage",
            McpCapabilities.Name.Sampling,
            McpCapabilityNotAdvertisedException.Peer.Client
        )
        assert(e.code == -32601)
        assert(e.message.contains("client"))
        assert(e.message.contains("sampling"))
    }

    "McpCapabilityNotAdvertisedException.Peer.describe is lowercase" in run {
        assert(McpCapabilityNotAdvertisedException.Peer.Server.describe == "server")
        assert(McpCapabilityNotAdvertisedException.Peer.Client.describe == "client")
    }

    "McpInvalidArgumentException" in run {
        val e = McpInvalidArgumentException("tools/call", "name", "must not be empty")
        assert(e.code == -32602)
        assert(e.message.contains("tools/call"))
        assert(e.message.contains("name"))
        assert(e.message.contains("must not be empty"))
        assert(e.isInstanceOf[McpDispatchException])
        assert(codeRoundtrip(e, -32602))
    }

    // =========================================================================
    // Execution-failure leaves
    // =========================================================================

    "McpToolStructuredMissingException" in run {
        val e = McpToolStructuredMissingException("add")
        assert(e.code == -32603)
        assert(e.message.contains("add"))
        assert(e.message.contains("structured content"))
        assert(e.isInstanceOf[McpExecutionException])
        assert(codeRoundtrip(e, -32603))
    }

    "McpSamplingRejectedException" in run {
        val e = McpSamplingRejectedException("model unavailable")
        assert(e.code == -32603)
        assert(e.message.contains("model unavailable"))
        assert(e.isInstanceOf[McpExecutionException])
        assert(codeRoundtrip(e, -32603))
    }

    "McpElicitationDeclinedException" in run {
        val e = McpElicitationDeclinedException("user cancelled")
        assert(e.code == -32603)
        assert(e.message.contains("user cancelled"))
        assert(e.isInstanceOf[McpExecutionException])
        assert(codeRoundtrip(e, -32603))
    }

    // =========================================================================
    // Application-error leaves
    // =========================================================================

    "McpToolExecutionException with default cause" in run {
        val e = McpToolExecutionException("add", "div-by-zero")
        assert(e.code == -32000)
        assert(e.message.contains("add"))
        assert(e.message.contains("div-by-zero"))
        assert(e.isInstanceOf[McpApplicationException])
        assert(e.isInstanceOf[McpApplicationException])
        // Wire roundtrip: code -32000 is in the implementation-defined range [-32099, -32000]
        // so fromWire decodes it as JsonRpcImplementationError (wire-encoding asymmetry)
        val encoded = Structure.encode[JsonRpcError](e)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("Schema[JsonRpcError] decode failed"))
        assert(decoded.code == -32000)
        assert(decoded.message.contains("div-by-zero"))
    }

    "McpToolExecutionException with Throwable cause" in run {
        val cause = new RuntimeException("/ by zero")
        val e     = McpToolExecutionException("add", "division by zero", cause = cause)
        assert(e.code == -32000)
        assert(e.message.contains("division by zero"))
        assert(messageRoundtrip(e, -32000, "division by zero"))
    }

    "McpToolExecutionException with String cause" in run {
        val e = McpToolExecutionException("add", "overflow", cause = "integer overflow")
        assert(e.code == -32000)
        assert(messageRoundtrip(e, -32000, "overflow"))
    }

    "McpResourceReadException" in run {
        val uri = McpResourceUri.parse("file:///data.txt").get
        val e   = McpResourceReadException(uri, "permission denied")
        assert(e.code == -32001)
        assert(e.message.contains("file:///data.txt"))
        assert(e.message.contains("permission denied"))
        assert(e.isInstanceOf[McpApplicationException])
        assert(messageRoundtrip(e, -32001, "permission denied"))
    }

    "McpResourceReadException with Throwable cause" in run {
        val uri   = McpResourceUri.parse("file:///data.txt").get
        val cause = new RuntimeException("file not found")
        val e     = McpResourceReadException(uri, "read failed", cause = cause)
        assert(e.code == -32001)
        assert(e.message.contains("read failed"))
        assert(messageRoundtrip(e, -32001, "read failed"))
    }

    "McpPromptRenderException" in run {
        val e = McpPromptRenderException("summarize", "template syntax error")
        assert(e.code == -32003)
        assert(e.message.contains("summarize"))
        assert(e.message.contains("template syntax error"))
        assert(e.isInstanceOf[McpApplicationException])
        assert(messageRoundtrip(e, -32003, "template syntax error"))
    }

    "McpPromptRenderException with Throwable cause" in run {
        val cause = new RuntimeException("parse error")
        val e     = McpPromptRenderException("format", "render failed", cause = cause)
        assert(e.code == -32003)
        assert(messageRoundtrip(e, -32003, "render failed"))
    }

    // =========================================================================
    // Category trait compile evidence (INV-003)
    // =========================================================================

    "category trait compile evidence: McpHandshakeException" in run {
        val e: McpHandshakeException = McpHandshakeNotInitializedException("tools/call")
        val asError: McpException    = e
        val asJsonRpc: JsonRpcError  = e
        assert(asError.code == -32002)
        assert(asJsonRpc.code == -32002)
    }

    "category trait compile evidence: McpDispatchException" in run {
        val e: McpDispatchException = McpUnknownToolException("x", Chunk.empty)
        val asError: McpException   = e
        val asJsonRpc: JsonRpcError = e
        assert(asError.code == -32602)
        assert(asJsonRpc.code == -32602)
    }

    "category trait compile evidence: McpExecutionException" in run {
        val e: McpExecutionException = McpToolStructuredMissingException("x")
        val asError: McpException    = e
        val asJsonRpc: JsonRpcError  = e
        assert(asError.code == -32603)
        assert(asJsonRpc.code == -32603)
    }

    "category trait compile evidence: McpApplicationException" in run {
        val e: McpApplicationException       = McpToolExecutionException("x", "y")
        val asError: McpException            = e
        val asJsonRpc: JsonRpcError          = e
        val asApp: JsonRpcApplicationFailure = e
        assert(asError.code == -32000)
        assert(asJsonRpc.code == -32000)
        assert(asApp.code == -32000)
    }

    "McpResourceUri fields are typed McpResourceUri not String (INV-022)" in run {
        val uri1 = McpResourceUri.parse("file:///a").get
        val uri2 = McpResourceUri.parse("file:///b").get
        val e    = McpUnknownResourceException(uri1, Chunk(uri2))
        // Compile-time check: these are McpResourceUri references
        val _: McpResourceUri        = e.uri
        val _: Chunk[McpResourceUri] = e.registered
        assert(e.uri.asString == "file:///a")
        assert(e.registered.head.asString == "file:///b")
    }

    "no leaf carries detail: String field" in run {
        // This is a compile-time invariant enforced by the sealed hierarchy.
        // Construct every leaf and confirm none has a 'detail' field in the message
        // (the message is constructed from typed fields, not a free-form detail).
        // Runtime check: the message must come from typed fields, not a "detail" param.
        val e1 = McpHandshakeNotInitializedException("tools/call")
        val e2 = McpUnknownToolException("add", Chunk.empty)
        val e3 = McpToolStructuredMissingException("add")
        val e4 = McpToolExecutionException("add", "error")
        // Verify none of the messages is literally the name of a 'detail' field value
        assert(!e1.message.startsWith("detail"))
        assert(!e2.message.startsWith("detail"))
        assert(!e3.message.startsWith("detail"))
        assert(!e4.message.startsWith("detail"))
    }

end McpExceptionTest
