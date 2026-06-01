package kyo

/** Tests that Lsp.DocumentRegistry does not expose a public encoding accessor (INV-035).
  *
  * The position encoding lives at session level via `Lsp.positionEncoding`, not per-document.
  * This test verifies the API shape is correct.
  */
class LspPositionEncodingScopeTest extends Test:

    "Lsp.DocumentRegistry has no public encoding method" in run {
        // Verify DocumentRegistry trait methods via runtime reflection.
        val methods = classOf[Lsp.DocumentRegistry].getMethods.map(_.getName).toSet
        // Should have get, version, listOpen, listOpenUris, isOpen but NOT encoding.
        assert(!methods.contains("encoding"))
        // Verify it has the expected public methods.
        assert(methods.contains("get"))
        assert(methods.contains("version"))
        assert(methods.contains("listOpen"))
        assert(methods.contains("listOpenUris"))
        assert(methods.contains("isOpen"))
        succeed
    }

    "PositionEncodingKind constants are accessible" in run {
        import LspHandler.PositionEncodingKind
        assert(PositionEncodingKind.UTF8.asString == "utf-8")
        assert(PositionEncodingKind.UTF16.asString == "utf-16")
        assert(PositionEncodingKind.UTF32.asString == "utf-32")
        succeed
    }

end LspPositionEncodingScopeTest
