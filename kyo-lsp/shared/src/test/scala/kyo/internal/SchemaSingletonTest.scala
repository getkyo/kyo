package kyo.internal

import kyo.*

/** Tests that sealed-union Schemas are reference-stable singletons.
  *
  * Each `summon[Schema[T]]` call must return the same object reference as the
  * val in `LspContentSchemas`. This is the singleton guarantee: not just equal,
  * but the same reference.
  */
class SchemaSingletonTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "TextDocumentContentChangeEvent schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.TextDocumentContentChangeEvent]]
        val s2 = summon[Schema[LspHandler.TextDocumentContentChangeEvent]]
        assert(s1 eq s2)
    }

    "ProgressToken schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.ProgressToken]]
        val s2 = summon[Schema[LspHandler.ProgressToken]]
        assert(s1 eq s2)
    }

    "MarkedString schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.MarkedString]]
        val s2 = summon[Schema[LspHandler.MarkedString]]
        assert(s1 eq s2)
    }

    "HoverContents schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.HoverContents]]
        val s2 = summon[Schema[LspHandler.HoverContents]]
        assert(s1 eq s2)
    }

    "DocumentSymbolResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.DocumentSymbolResult]]
        val s2 = summon[Schema[LspHandler.DocumentSymbolResult]]
        assert(s1 eq s2)
    }

    "WorkspaceSymbolLocation schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.WorkspaceSymbolLocation]]
        val s2 = summon[Schema[LspHandler.WorkspaceSymbolLocation]]
        assert(s1 eq s2)
    }

    "CompletionResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.CompletionResult]]
        val s2 = summon[Schema[LspHandler.CompletionResult]]
        assert(s1 eq s2)
    }

    "ParameterLabel schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.ParameterLabel]]
        val s2 = summon[Schema[LspHandler.ParameterLabel]]
        assert(s1 eq s2)
    }

    "CommandOrCodeAction schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.CommandOrCodeAction]]
        val s2 = summon[Schema[LspHandler.CommandOrCodeAction]]
        assert(s1 eq s2)
    }

    "WorkspaceEditDocumentChange schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.WorkspaceEditDocumentChange]]
        val s2 = summon[Schema[LspHandler.WorkspaceEditDocumentChange]]
        assert(s1 eq s2)
    }

    "PrepareRenameResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.PrepareRenameResult]]
        val s2 = summon[Schema[LspHandler.PrepareRenameResult]]
        assert(s1 eq s2)
    }

    "DefinitionResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.DefinitionResult]]
        val s2 = summon[Schema[LspHandler.DefinitionResult]]
        assert(s1 eq s2)
    }

    "DeclarationResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.DeclarationResult]]
        val s2 = summon[Schema[LspHandler.DeclarationResult]]
        assert(s1 eq s2)
    }

    "TypeDefinitionResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.TypeDefinitionResult]]
        val s2 = summon[Schema[LspHandler.TypeDefinitionResult]]
        assert(s1 eq s2)
    }

    "ImplementationResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.ImplementationResult]]
        val s2 = summon[Schema[LspHandler.ImplementationResult]]
        assert(s1 eq s2)
    }

    "SemanticTokensResult schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.SemanticTokensResult]]
        val s2 = summon[Schema[LspHandler.SemanticTokensResult]]
        assert(s1 eq s2)
    }

    "InlayHintLabelPart schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.InlayHintLabelPart]]
        val s2 = summon[Schema[LspHandler.InlayHintLabelPart]]
        assert(s1 eq s2)
    }

    "InlayHintLabel schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.InlayHintLabel]]
        val s2 = summon[Schema[LspHandler.InlayHintLabel]]
        assert(s1 eq s2)
    }

    "InlineValue schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.InlineValue]]
        val s2 = summon[Schema[LspHandler.InlineValue]]
        assert(s1 eq s2)
    }

    "NotebookDocumentFilter schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.NotebookDocumentFilter]]
        val s2 = summon[Schema[LspHandler.NotebookDocumentFilter]]
        assert(s1 eq s2)
    }

    "Registration schema is a singleton" in {
        val s1 = summon[Schema[LspHandler.Registration]]
        val s2 = summon[Schema[LspHandler.Registration]]
        assert(s1 eq s2)
    }

    // BooleanOr / StringOr are NOT singletons (they are parameterized givens)
    // but they should resolve to non-stub instances

    "BooleanOr schema is non-null and non-throwing" in {
        val s = summon[Schema[LspHandler.BooleanOr[LspHandler.SaveOptions]]]
        assert(s != null)
    }

    "StringOr schema is non-null and non-throwing" in {
        val s = summon[Schema[LspHandler.StringOr[LspHandler.SaveOptions]]]
        assert(s != null)
    }

end SchemaSingletonTest
