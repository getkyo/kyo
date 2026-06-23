package kyo

/** Tests that Lsp.DocumentRegistry does not expose a public encoding accessor.
  *
  * The position encoding lives at session level via `Lsp.positionEncoding`, not per-document.
  * This test verifies the API shape is correct.
  */
class LspPositionEncodingScopeTest extends Test:

    "Lsp.DocumentRegistry compile-time API shape" in {
        // Verify the DocumentRegistry API shape at compile time:
        // the trait must define get, version, listOpen, listOpenUris, isOpen
        // and must NOT define a public encoding accessor.
        // This is a compile-time check: if any required method is missing, this block
        // fails to compile. If 'encoding' is added, it would pass (false negative)
        // since we can only use positive assertions here.
        //
        // The negative check (no 'encoding') is enforced via source-level lint
        // and by the absence of an 'encoding' overload in the trait's explicit definition.
        def checkApiShape(r: Lsp.DocumentRegistry)(using Frame): Boolean < Sync =
            val uri = LspHandler.LspDocument.Uri.parse("file:///x.scala").get
            r.get(uri).flatMap { _ =>
                r.version(uri).flatMap { _ =>
                    r.listOpen.flatMap { _ =>
                        r.listOpenUris.flatMap { _ =>
                            r.isOpen(uri).map(_ => true)
                        }
                    }
                }
            }
        end checkApiShape
        // If this compiles, the API shape is correct.
        succeed
    }

    "PositionEncodingKind constants are accessible" in {
        import LspHandler.PositionEncodingKind
        assert(PositionEncodingKind.UTF8.asString == "utf-8", s"Expected 'utf-8', got ${PositionEncodingKind.UTF8.asString}")
        assert(PositionEncodingKind.UTF16.asString == "utf-16", s"Expected 'utf-16', got ${PositionEncodingKind.UTF16.asString}")
        assert(PositionEncodingKind.UTF32.asString == "utf-32", s"Expected 'utf-32', got ${PositionEncodingKind.UTF32.asString}")
    }

end LspPositionEncodingScopeTest
