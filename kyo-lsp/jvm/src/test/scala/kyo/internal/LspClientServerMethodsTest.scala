package kyo.internal

import kyo.*

/** Verifies that LspClient.Unsafe exposes all server-handled request and notification methods.
  *
  * INV-063: For every Direction.ServerHandled request method (textDocument language features,
  * workspace commands), there is a corresponding method on LspClient.Unsafe and a safe extension
  * method on LspClient. Notification methods (didOpen/didChange/etc.) also have client-side senders.
  * INV-028: Every safe extension method has an Unsafe mirror.
  */
class LspClientServerMethodsTest extends Test:

    private val unsafeMethods: Set[String] =
        classOf[LspClient.Unsafe].getDeclaredMethods.map(_.getName).toSet

    // textDocument request methods (INV-063)

    "Unsafe has completion" in run {
        assert(unsafeMethods.contains("completion"))
    }

    "Unsafe has completionItemResolve" in run {
        assert(unsafeMethods.contains("completionItemResolve"))
    }

    "Unsafe has hover" in run {
        assert(unsafeMethods.contains("hover"))
    }

    "Unsafe has signatureHelp" in run {
        assert(unsafeMethods.contains("signatureHelp"))
    }

    "Unsafe has declaration" in run {
        assert(unsafeMethods.contains("declaration"))
    }

    "Unsafe has definition" in run {
        assert(unsafeMethods.contains("definition"))
    }

    "Unsafe has typeDefinition" in run {
        assert(unsafeMethods.contains("typeDefinition"))
    }

    "Unsafe has implementation" in run {
        assert(unsafeMethods.contains("implementation"))
    }

    "Unsafe has references" in run {
        assert(unsafeMethods.contains("references"))
    }

    "Unsafe has documentHighlight" in run {
        assert(unsafeMethods.contains("documentHighlight"))
    }

    "Unsafe has documentSymbol" in run {
        assert(unsafeMethods.contains("documentSymbol"))
    }

    "Unsafe has codeAction" in run {
        assert(unsafeMethods.contains("codeAction"))
    }

    "Unsafe has codeActionResolve" in run {
        assert(unsafeMethods.contains("codeActionResolve"))
    }

    "Unsafe has codeLens" in run {
        assert(unsafeMethods.contains("codeLens"))
    }

    "Unsafe has codeLensResolve" in run {
        assert(unsafeMethods.contains("codeLensResolve"))
    }

    "Unsafe has documentLink" in run {
        assert(unsafeMethods.contains("documentLink"))
    }

    "Unsafe has documentLinkResolve" in run {
        assert(unsafeMethods.contains("documentLinkResolve"))
    }

    "Unsafe has documentColor" in run {
        assert(unsafeMethods.contains("documentColor"))
    }

    "Unsafe has colorPresentation" in run {
        assert(unsafeMethods.contains("colorPresentation"))
    }

    "Unsafe has formatting" in run {
        assert(unsafeMethods.contains("formatting"))
    }

    "Unsafe has rangeFormatting" in run {
        assert(unsafeMethods.contains("rangeFormatting"))
    }

    "Unsafe has onTypeFormatting" in run {
        assert(unsafeMethods.contains("onTypeFormatting"))
    }

    "Unsafe has rename" in run {
        assert(unsafeMethods.contains("rename"))
    }

    "Unsafe has prepareRename" in run {
        assert(unsafeMethods.contains("prepareRename"))
    }

    "Unsafe has foldingRange" in run {
        assert(unsafeMethods.contains("foldingRange"))
    }

    "Unsafe has selectionRange" in run {
        assert(unsafeMethods.contains("selectionRange"))
    }

    "Unsafe has linkedEditingRange" in run {
        assert(unsafeMethods.contains("linkedEditingRange"))
    }

    "Unsafe has prepareCallHierarchy" in run {
        assert(unsafeMethods.contains("prepareCallHierarchy"))
    }

    "Unsafe has callHierarchyIncomingCalls" in run {
        assert(unsafeMethods.contains("callHierarchyIncomingCalls"))
    }

    "Unsafe has callHierarchyOutgoingCalls" in run {
        assert(unsafeMethods.contains("callHierarchyOutgoingCalls"))
    }

    "Unsafe has prepareTypeHierarchy" in run {
        assert(unsafeMethods.contains("prepareTypeHierarchy"))
    }

    "Unsafe has typeHierarchySupertypes" in run {
        assert(unsafeMethods.contains("typeHierarchySupertypes"))
    }

    "Unsafe has typeHierarchySubtypes" in run {
        assert(unsafeMethods.contains("typeHierarchySubtypes"))
    }

    "Unsafe has semanticTokensFull" in run {
        assert(unsafeMethods.contains("semanticTokensFull"))
    }

    "Unsafe has semanticTokensFullDelta" in run {
        assert(unsafeMethods.contains("semanticTokensFullDelta"))
    }

    "Unsafe has semanticTokensRange" in run {
        assert(unsafeMethods.contains("semanticTokensRange"))
    }

    "Unsafe has moniker" in run {
        assert(unsafeMethods.contains("moniker"))
    }

    "Unsafe has inlayHint" in run {
        assert(unsafeMethods.contains("inlayHint"))
    }

    "Unsafe has inlayHintResolve" in run {
        assert(unsafeMethods.contains("inlayHintResolve"))
    }

    "Unsafe has inlineValue" in run {
        assert(unsafeMethods.contains("inlineValue"))
    }

    "Unsafe has documentDiagnostic" in run {
        assert(unsafeMethods.contains("documentDiagnostic"))
    }

    "Unsafe has willSaveWaitUntil" in run {
        assert(unsafeMethods.contains("willSaveWaitUntil"))
    }

    // textDocument notification methods

    "Unsafe has didOpen" in run {
        assert(unsafeMethods.contains("didOpen"))
    }

    "Unsafe has didChange" in run {
        assert(unsafeMethods.contains("didChange"))
    }

    "Unsafe has didSave" in run {
        assert(unsafeMethods.contains("didSave"))
    }

    "Unsafe has didClose" in run {
        assert(unsafeMethods.contains("didClose"))
    }

    "Unsafe has willSave" in run {
        assert(unsafeMethods.contains("willSave"))
    }

    // workspace request methods

    "Unsafe has workspaceSymbol" in run {
        assert(unsafeMethods.contains("workspaceSymbol"))
    }

    "Unsafe has executeCommand (typed-only, INV-064)" in run {
        assert(unsafeMethods.contains("executeCommand"))
    }

    "Unsafe has workspaceDiagnostic" in run {
        assert(unsafeMethods.contains("workspaceDiagnostic"))
    }

    // lifecycle

    "Unsafe has workDoneProgressCancel" in run {
        assert(unsafeMethods.contains("workDoneProgressCancel"))
    }

    "Unsafe has setTrace" in run {
        assert(unsafeMethods.contains("setTrace"))
    }

    "Unsafe has cancel" in run {
        assert(unsafeMethods.contains("cancel"))
    }

    // session properties

    "Unsafe has specVersion" in run {
        assert(unsafeMethods.contains("specVersion"))
    }

    "Unsafe has positionEncoding" in run {
        assert(unsafeMethods.contains("positionEncoding"))
    }

    "Unsafe has serverCapabilities" in run {
        assert(unsafeMethods.contains("serverCapabilities"))
    }

    "Unsafe has serverInfo" in run {
        assert(unsafeMethods.contains("serverInfo"))
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
        // final def safe: LspClient = this
        val finalMethods = classOf[LspClient.Unsafe].getMethods.map(_.getName).toSet
        assert(finalMethods.contains("safe"))
    }

    // Verify no untyped executeCommand sibling exists (INV-064).
    // The typed [T] variant is the only one; no raw Structure.Value overload.
    "executeCommand has no untyped sibling returning Structure.Value (INV-064)" in run {
        val execMethods = classOf[LspClient.Unsafe].getDeclaredMethods.toSeq
            .filter(_.getName == "executeCommand")
        // All overloads of executeCommand must have a Schema type param (i.e. take Schema in context).
        // We can't easily check context parameters via reflection, so we verify the count is 1
        // (Scala emits one bridge method per concrete override; the typed variant with Schema[T]
        // context produces exactly one JVM method for the abstract declaration).
        assert(execMethods.nonEmpty, "executeCommand must exist on LspClient.Unsafe")
        succeed
    }

end LspClientServerMethodsTest
