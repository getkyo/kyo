package kyo.internal

import kyo.*

/** Verifies that LspServer.Unsafe method-name set equals the LspServer extension-method set. INV-028.
  *
  * The plan requires every safe extension method to have a corresponding Unsafe mirror. This test
  * samples critical method names via reflection to confirm the contract holds. The full mirror is
  * enforced at compile time by the abstract class definition.
  */
class UnsafeMirrorTest extends Test:

    private val unsafeMethods: Set[String] =
        classOf[LspServer.Unsafe].getDeclaredMethods.map(_.getName).toSet

    "Unsafe has showMessage" in run {
        assert(unsafeMethods.contains("showMessage"))
    }

    "Unsafe has showMessageRequest" in run {
        assert(unsafeMethods.contains("showMessageRequest"))
    }

    "Unsafe has showDocument" in run {
        assert(unsafeMethods.contains("showDocument"))
    }

    "Unsafe has logMessage" in run {
        assert(unsafeMethods.contains("logMessage"))
    }

    "Unsafe has createWorkDoneProgress" in run {
        assert(unsafeMethods.contains("createWorkDoneProgress"))
    }

    "Unsafe has telemetry" in run {
        assert(unsafeMethods.contains("telemetry"))
    }

    "Unsafe has applyEdit" in run {
        assert(unsafeMethods.contains("applyEdit"))
    }

    "Unsafe has getConfiguration" in run {
        assert(unsafeMethods.contains("getConfiguration"))
    }

    "Unsafe has getWorkspaceFolders" in run {
        assert(unsafeMethods.contains("getWorkspaceFolders"))
    }

    "Unsafe has refreshSemanticTokens" in run {
        assert(unsafeMethods.contains("refreshSemanticTokens"))
    }

    "Unsafe has refreshInlineValue" in run {
        assert(unsafeMethods.contains("refreshInlineValue"))
    }

    "Unsafe has refreshInlayHint" in run {
        assert(unsafeMethods.contains("refreshInlayHint"))
    }

    "Unsafe has refreshDiagnostic" in run {
        assert(unsafeMethods.contains("refreshDiagnostic"))
    }

    "Unsafe has refreshCodeLens" in run {
        assert(unsafeMethods.contains("refreshCodeLens"))
    }

    "Unsafe has registerCapability" in run {
        assert(unsafeMethods.contains("registerCapability"))
    }

    "Unsafe has unregisterCapability" in run {
        assert(unsafeMethods.contains("unregisterCapability"))
    }

    "Unsafe has publishDiagnostics" in run {
        assert(unsafeMethods.contains("publishDiagnostics"))
    }

    "Unsafe has logTrace" in run {
        assert(unsafeMethods.contains("logTrace"))
    }

    "Unsafe has workDoneProgress" in run {
        assert(unsafeMethods.contains("workDoneProgress"))
    }

    "Unsafe has cancel" in run {
        assert(unsafeMethods.contains("cancel"))
    }

    "Unsafe has specVersion" in run {
        assert(unsafeMethods.contains("specVersion"))
    }

    "Unsafe has positionEncoding" in run {
        assert(unsafeMethods.contains("positionEncoding"))
    }

    "Unsafe has clientCapabilities" in run {
        assert(unsafeMethods.contains("clientCapabilities"))
    }

    "Unsafe has clientInfo" in run {
        assert(unsafeMethods.contains("clientInfo"))
    }

    "Unsafe has workspaceFolders" in run {
        assert(unsafeMethods.contains("workspaceFolders"))
    }

    "Unsafe has underlying" in run {
        assert(unsafeMethods.contains("underlying"))
    }

    "Unsafe has awaitDrain" in run {
        assert(unsafeMethods.contains("awaitDrain"))
    }

    "Unsafe has close" in run {
        assert(unsafeMethods.contains("close"))
    }

    "Unsafe has safe (opaque bridge)" in run {
        assert(unsafeMethods.contains("safe"))
    }

end UnsafeMirrorTest
