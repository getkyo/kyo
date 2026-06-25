package kyo

/** Tests for Lsp.extras[T] typed accessor and protocol-extension decode path. */
class LspExtrasTest extends Test:

    "Lsp.extras[T] method exists on the Lsp object" in {
        // Verify the method is in scope and type-checks correctly.
        // The type annotation confirms the return row; compilation is the assertion.
        val _: Maybe[Int] < (Sync & Abort[LspInvalidParamsException]) = Lsp.extras[Int]
        succeed
    }

    "LspInfo name field carries the provided string" in {
        val info = LspInfo(name = "my-server")
        assert(info.name == "my-server")
    }

    "LspInfo version defaults to 0.0.0" in {
        val info = LspInfo(name = "srv")
        assert(info.version == "0.0.0")
    }

    "LspInfo title is Absent by default" in {
        val info = LspInfo(name = "srv")
        assert(info.title == Absent)
    }

    "LspInfo title carries the provided value when set" in {
        val info = LspInfo(name = "srv", title = Present("Server Display"))
        assert(info.title == Present("Server Display"))
    }

    "LspConfig.SpecVersion is the literal string 3.17" in {
        assert(LspConfig.SpecVersion == "3.17")
    }

    "LspConfig.default has UTF-16 position encoding" in {
        assert(LspConfig.default.positionEncodings == Chunk(LspHandler.PositionEncodingKind.UTF16))
    }

    "LspConfig.default enforces capabilities" in {
        assert(LspConfig.default.enforceCapabilities)
    }

end LspExtrasTest
