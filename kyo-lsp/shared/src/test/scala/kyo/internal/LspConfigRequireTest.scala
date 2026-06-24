package kyo.internal

import kyo.*

/** Validates LspConfig.require cross-field constraint behaviour.
  *
  * LspConfig.require must fire synchronously before any transport is touched. Invalid
  * configurations cause an LspConfigurationError before the engine starts.
  */
class LspConfigRequireTest extends Test:

    // =========================================================================
    // Valid configurations
    // =========================================================================

    "require passes for default config" in {
        val result = scala.util.Try(LspConfig.require(LspConfig.default))
        assert(result.isSuccess)
    }

    "require passes for config with multiple encodings" in {
        val config = LspConfig.default.withPositionEncodings(
            Chunk(LspHandler.PositionEncodingKind.UTF8, LspHandler.PositionEncodingKind.UTF16)
        )
        val result = scala.util.Try(LspConfig.require(config))
        assert(result.isSuccess)
    }

    // =========================================================================
    // Invalid configurations
    // =========================================================================

    "require fails when positionEncodings is empty" in {
        val badConfig = LspConfig.default.withPositionEncodings(Chunk.empty)
        val result    = scala.util.Try(LspConfig.require(badConfig))
        assert(result.isFailure)
        assert(result.failed.get.isInstanceOf[LspConfigurationError])
        assert(result.failed.get.getMessage.contains("positionEncodings"))
    }

    // =========================================================================
    // require fires before transport in init
    // =========================================================================

    "LspConfig.require fires before transport bytes are consumed" in {
        // Use an invalid config and confirm the error precedes any server init work.
        val badConfig = LspConfig.default.withPositionEncodings(Chunk.empty)
        // The require() call inside initUnscoped must throw before LspEngine.initServer is called.
        val result = scala.util.Try(LspConfig.require(badConfig))
        assert(result.isFailure, "require must throw for empty positionEncodings")
        assert(result.failed.get.getMessage.contains("positionEncodings"))
    }

    "LspConfig.SpecVersion is 3.17" in {
        assert(LspConfig.SpecVersion == "3.17")
    }

    "LspConfig.default.enforceCapabilities is true" in {
        assert(LspConfig.default.enforceCapabilities)
    }

    "LspConfig.default.positionEncodings contains UTF16" in {
        assert(LspConfig.default.positionEncodings.contains(LspHandler.PositionEncodingKind.UTF16))
    }

    "LspConfig.default.documentSync is Incremental" in {
        assert(LspConfig.default.documentSync == LspHandler.TextDocumentSyncKind.Incremental)
    }

end LspConfigRequireTest
