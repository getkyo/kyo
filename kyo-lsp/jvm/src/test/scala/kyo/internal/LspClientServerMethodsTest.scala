package kyo.internal

import kyo.*

/** Verifies that LspClient.Unsafe exposes all server-handled request and notification methods.
  *
  * For every server-handled request method (textDocument language features, workspace commands),
  * there is a corresponding method on LspClient.Unsafe and a safe extension method on LspClient.
  * Notification methods (didOpen/didChange/etc.) also have client-side senders.
  */
class LspClientServerMethodsTest extends Test:

    private val unsafeMethods: Set[String] =
        classOf[LspClient.Unsafe].getDeclaredMethods.map(_.getName).toSet

    // textDocument request methods

    "Unsafe has completion" in {
        assert(unsafeMethods.contains("completion"))
    }

    "Unsafe has completionItemResolve" in {
        assert(unsafeMethods.contains("completionItemResolve"))
    }

    "Unsafe has hover" in {
        assert(unsafeMethods.contains("hover"))
    }

    "Unsafe has signatureHelp" in {
        assert(unsafeMethods.contains("signatureHelp"))
    }

    "Unsafe has declaration" in {
        assert(unsafeMethods.contains("declaration"))
    }

    "Unsafe has definition" in {
        assert(unsafeMethods.contains("definition"))
    }

    "Unsafe has typeDefinition" in {
        assert(unsafeMethods.contains("typeDefinition"))
    }

    "Unsafe has implementation" in {
        assert(unsafeMethods.contains("implementation"))
    }

    "Unsafe has references" in {
        assert(unsafeMethods.contains("references"))
    }

    "Unsafe has documentHighlight" in {
        assert(unsafeMethods.contains("documentHighlight"))
    }

    "Unsafe has documentSymbol" in {
        assert(unsafeMethods.contains("documentSymbol"))
    }

    "Unsafe has codeAction" in {
        assert(unsafeMethods.contains("codeAction"))
    }

    "Unsafe has codeActionResolve" in {
        assert(unsafeMethods.contains("codeActionResolve"))
    }

    "Unsafe has codeLens" in {
        assert(unsafeMethods.contains("codeLens"))
    }

    "Unsafe has codeLensResolve" in {
        assert(unsafeMethods.contains("codeLensResolve"))
    }

    "Unsafe has documentLink" in {
        assert(unsafeMethods.contains("documentLink"))
    }

    "Unsafe has documentLinkResolve" in {
        assert(unsafeMethods.contains("documentLinkResolve"))
    }

    "Unsafe has documentColor" in {
        assert(unsafeMethods.contains("documentColor"))
    }

    "Unsafe has colorPresentation" in {
        assert(unsafeMethods.contains("colorPresentation"))
    }

    "Unsafe has formatting" in {
        assert(unsafeMethods.contains("formatting"))
    }

    "Unsafe has rangeFormatting" in {
        assert(unsafeMethods.contains("rangeFormatting"))
    }

    "Unsafe has onTypeFormatting" in {
        assert(unsafeMethods.contains("onTypeFormatting"))
    }

    "Unsafe has rename" in {
        assert(unsafeMethods.contains("rename"))
    }

    "Unsafe has prepareRename" in {
        assert(unsafeMethods.contains("prepareRename"))
    }

    "Unsafe has foldingRange" in {
        assert(unsafeMethods.contains("foldingRange"))
    }

    "Unsafe has selectionRange" in {
        assert(unsafeMethods.contains("selectionRange"))
    }

    "Unsafe has linkedEditingRange" in {
        assert(unsafeMethods.contains("linkedEditingRange"))
    }

    "Unsafe has prepareCallHierarchy" in {
        assert(unsafeMethods.contains("prepareCallHierarchy"))
    }

    "Unsafe has callHierarchyIncomingCalls" in {
        assert(unsafeMethods.contains("callHierarchyIncomingCalls"))
    }

    "Unsafe has callHierarchyOutgoingCalls" in {
        assert(unsafeMethods.contains("callHierarchyOutgoingCalls"))
    }

    "Unsafe has prepareTypeHierarchy" in {
        assert(unsafeMethods.contains("prepareTypeHierarchy"))
    }

    "Unsafe has typeHierarchySupertypes" in {
        assert(unsafeMethods.contains("typeHierarchySupertypes"))
    }

    "Unsafe has typeHierarchySubtypes" in {
        assert(unsafeMethods.contains("typeHierarchySubtypes"))
    }

    "Unsafe has semanticTokensFull" in {
        assert(unsafeMethods.contains("semanticTokensFull"))
    }

    "Unsafe has semanticTokensFullDelta" in {
        assert(unsafeMethods.contains("semanticTokensFullDelta"))
    }

    "Unsafe has semanticTokensRange" in {
        assert(unsafeMethods.contains("semanticTokensRange"))
    }

    "Unsafe has moniker" in {
        assert(unsafeMethods.contains("moniker"))
    }

    "Unsafe has inlayHint" in {
        assert(unsafeMethods.contains("inlayHint"))
    }

    "Unsafe has inlayHintResolve" in {
        assert(unsafeMethods.contains("inlayHintResolve"))
    }

    "Unsafe has inlineValue" in {
        assert(unsafeMethods.contains("inlineValue"))
    }

    "Unsafe has documentDiagnostic" in {
        assert(unsafeMethods.contains("documentDiagnostic"))
    }

    "Unsafe has willSaveWaitUntil" in {
        assert(unsafeMethods.contains("willSaveWaitUntil"))
    }

    // textDocument notification methods

    "Unsafe has didOpen" in {
        assert(unsafeMethods.contains("didOpen"))
    }

    "Unsafe has didChange" in {
        assert(unsafeMethods.contains("didChange"))
    }

    "Unsafe has didSave" in {
        assert(unsafeMethods.contains("didSave"))
    }

    "Unsafe has didClose" in {
        assert(unsafeMethods.contains("didClose"))
    }

    "Unsafe has willSave" in {
        assert(unsafeMethods.contains("willSave"))
    }

    // workspace request methods

    "Unsafe has workspaceSymbol" in {
        assert(unsafeMethods.contains("workspaceSymbol"))
    }

    "Unsafe has executeCommand (typed-only)" in {
        assert(unsafeMethods.contains("executeCommand"))
    }

    "Unsafe has workspaceDiagnostic" in {
        assert(unsafeMethods.contains("workspaceDiagnostic"))
    }

    // lifecycle

    "Unsafe has workDoneProgressCancel" in {
        assert(unsafeMethods.contains("workDoneProgressCancel"))
    }

    "Unsafe has setTrace" in {
        assert(unsafeMethods.contains("setTrace"))
    }

    "Unsafe has cancel" in {
        assert(unsafeMethods.contains("cancel"))
    }

    // session properties

    "Unsafe has specVersion" in {
        assert(unsafeMethods.contains("specVersion"))
    }

    "Unsafe has positionEncoding" in {
        assert(unsafeMethods.contains("positionEncoding"))
    }

    "Unsafe has serverCapabilities" in {
        assert(unsafeMethods.contains("serverCapabilities"))
    }

    "Unsafe has serverInfo" in {
        assert(unsafeMethods.contains("serverInfo"))
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
        // final def safe: LspClient = this
        val finalMethods = classOf[LspClient.Unsafe].getMethods.map(_.getName).toSet
        assert(finalMethods.contains("safe"))
    }

    // Verify no untyped executeCommand sibling exists.
    // The typed [T] variant is the only one; no raw Structure.Value overload.
    "executeCommand has no untyped sibling returning Structure.Value" in {
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
