package kyo

/** Opaque identity tests verifying runtime-level identity for all opaque String types (INV-012).
  *
  * Note: Scala 3 opaque type `=:=` proofs are only available inside the defining companion scope.
  * External code verifies identity by testing that values are accepted and round-trip as strings.
  */
class OpaqueIdentityTest extends Test:

    "LspDocument.Uri round-trips as String" in run {
        val uri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
        assert(uri.asString == "file:///Main.scala")
    }

    "CodeActionKind round-trips as String" in run {
        val k = LspHandler.CodeActionKind.QuickFix
        assert(k.asString == "quickfix")
        val custom = LspHandler.CodeActionKind("custom.action")
        assert(custom.asString == "custom.action")
    }

    "FoldingRangeKind round-trips as String" in run {
        val k = LspHandler.FoldingRangeKind.Comment
        assert(k.asString == "comment")
        val custom = LspHandler.FoldingRangeKind("custom.kind")
        assert(custom.asString == "custom.kind")
    }

    "SemanticTokenTypes round-trips as String" in run {
        val t = LspHandler.SemanticTokenTypes.Class
        assert(t.asString == "class")
        val custom = LspHandler.SemanticTokenTypes("custom")
        assert(custom.asString == "custom")
    }

    "SemanticTokenModifiers round-trips as String" in run {
        val m = LspHandler.SemanticTokenModifiers.Readonly
        assert(m.asString == "readonly")
        val custom = LspHandler.SemanticTokenModifiers("custom")
        assert(custom.asString == "custom")
    }

    "PositionEncodingKind round-trips as String" in run {
        val k = LspHandler.PositionEncodingKind.UTF16
        assert(k.asString == "utf-16")
    }

    "TraceValue round-trips as String" in run {
        val v = LspHandler.TraceValue.Off
        assert(v.asString == "off")
    }

end OpaqueIdentityTest
