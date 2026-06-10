package kyo.internal

import kyo.*

/** Verifies that LspServer.Unsafe method-name set equals the LspServer extension-method set.
  *
  * Every safe extension method has a corresponding Unsafe mirror. This test samples critical
  * method names via reflection to confirm the contract holds. The full mirror is enforced at
  * compile time by the abstract class definition.
  */
class UnsafeMirrorTest extends Test:

    private val unsafeMethods: Set[String] =
        classOf[LspServer.Unsafe].getDeclaredMethods.map(_.getName).toSet

    "Unsafe has showMessage" in {
        assert(unsafeMethods.contains("showMessage"))
    }

    "Unsafe has showMessageRequest" in {
        assert(unsafeMethods.contains("showMessageRequest"))
    }

    "Unsafe has showDocument" in {
        assert(unsafeMethods.contains("showDocument"))
    }

    "Unsafe has logMessage" in {
        assert(unsafeMethods.contains("logMessage"))
    }

    "Unsafe has createWorkDoneProgress" in {
        assert(unsafeMethods.contains("createWorkDoneProgress"))
    }

    "Unsafe has telemetry" in {
        assert(unsafeMethods.contains("telemetry"))
    }

    "Unsafe has applyEdit" in {
        assert(unsafeMethods.contains("applyEdit"))
    }

    "Unsafe has getConfiguration" in {
        assert(unsafeMethods.contains("getConfiguration"))
    }

    "Unsafe has getWorkspaceFolders" in {
        assert(unsafeMethods.contains("getWorkspaceFolders"))
    }

    "Unsafe has refreshSemanticTokens" in {
        assert(unsafeMethods.contains("refreshSemanticTokens"))
    }

    "Unsafe has refreshInlineValue" in {
        assert(unsafeMethods.contains("refreshInlineValue"))
    }

    "Unsafe has refreshInlayHint" in {
        assert(unsafeMethods.contains("refreshInlayHint"))
    }

    "Unsafe has refreshDiagnostic" in {
        assert(unsafeMethods.contains("refreshDiagnostic"))
    }

    "Unsafe has refreshCodeLens" in {
        assert(unsafeMethods.contains("refreshCodeLens"))
    }

    "Unsafe has registerCapability" in {
        assert(unsafeMethods.contains("registerCapability"))
    }

    "Unsafe has unregisterCapability" in {
        assert(unsafeMethods.contains("unregisterCapability"))
    }

    "Unsafe has publishDiagnostics" in {
        assert(unsafeMethods.contains("publishDiagnostics"))
    }

    "Unsafe has logTrace" in {
        assert(unsafeMethods.contains("logTrace"))
    }

    "Unsafe has workDoneProgress" in {
        assert(unsafeMethods.contains("workDoneProgress"))
    }

    "Unsafe has cancel" in {
        assert(unsafeMethods.contains("cancel"))
    }

    "Unsafe has specVersion" in {
        assert(unsafeMethods.contains("specVersion"))
    }

    "Unsafe has positionEncoding" in {
        assert(unsafeMethods.contains("positionEncoding"))
    }

    "Unsafe has clientCapabilities" in {
        assert(unsafeMethods.contains("clientCapabilities"))
    }

    "Unsafe has clientInfo" in {
        assert(unsafeMethods.contains("clientInfo"))
    }

    "Unsafe has workspaceFolders" in {
        assert(unsafeMethods.contains("workspaceFolders"))
    }

    "Unsafe has underlying" in {
        assert(unsafeMethods.contains("underlying"))
    }

    "Unsafe has awaitDrain" in {
        assert(unsafeMethods.contains("awaitDrain"))
    }

    "Unsafe has close" in {
        assert(unsafeMethods.contains("close"))
    }

    "Unsafe has safe (opaque bridge)" in {
        assert(unsafeMethods.contains("safe"))
    }

end UnsafeMirrorTest
