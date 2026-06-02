package kyo

/** Tests for LspInfo Schema round-trip and default values. */
class LspInfoTest extends Test:

    "LspInfo default version" in run {
        val info = LspInfo(name = "test-server")
        assert(info.version == "0.0.0")
    }

    "LspInfo with all fields" in run {
        val info = LspInfo(name = "test", version = "1.0.0", title = Present("Test Server"))
        assert(info.name == "test")
        assert(info.version == "1.0.0")
        assert(info.title == Present("Test Server"))
    }

    "LspInfo with absent title" in run {
        val info = LspInfo(name = "test")
        assert(info.title == Absent)
    }

    "LspInfo Schema round-trip" in run {
        val info    = LspInfo(name = "kyo-lsp", version = "0.1.0", title = Present("Kyo LSP"))
        val encoded = Json.encode[LspInfo](info)
        val decoded = Json.decode[LspInfo](encoded)
        assert(decoded == Result.Success(info))
    }

    "LspInfo Schema round-trip with absent title" in run {
        val info    = LspInfo(name = "minimal")
        val encoded = Json.encode[LspInfo](info)
        val decoded = Json.decode[LspInfo](encoded)
        assert(decoded == Result.Success(info))
    }

end LspInfoTest
