package kyo

class LspConfigTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def decode[A: Schema](json: String): A = Json.decode[A](json).getOrThrow

    "SpecVersion constant" - {
        "equals 3.17" in {
            assert(LspConfig.SpecVersion == "3.17")
        }
        "no ProtocolVersionMismatch reference" in {
            // INV-036: the library uses SpecVersion "3.17"; no LspProtocolVersionMismatchException exists (RI-015)
            assert(LspConfig.SpecVersion == "3.17")
        }
    }

    "LspConfig.default" - {
        "enforceCapabilities is true" in {
            // INV-087
            assert(LspConfig.default.enforceCapabilities)
        }
        "positionEncodings contains UTF16" in {
            assert(LspConfig.default.positionEncodings.contains(LspHandler.PositionEncodingKind.UTF16))
        }
        "positionEncodings is non-empty" in {
            assert(LspConfig.default.positionEncodings.nonEmpty)
        }
        "documentSync is Incremental" in {
            assert(LspConfig.default.documentSync == LspHandler.TextDocumentSyncKind.Incremental)
        }
        "declaredServerCapabilities is Absent" in {
            assert(LspConfig.default.declaredServerCapabilities.isEmpty)
        }
        "serverInfo has name kyo-lsp" in {
            assert(LspConfig.default.serverInfo.name == "kyo-lsp")
        }
        "onTypeFormattingTriggers is empty" in {
            assert(LspConfig.default.onTypeFormattingTriggers.isEmpty)
        }
        "executeCommandCommands is empty" in {
            assert(LspConfig.default.executeCommandCommands.isEmpty)
        }
        "completionTriggerCharacters is empty" in {
            assert(LspConfig.default.completionTriggerCharacters.isEmpty)
        }
        "signatureHelpTriggerCharacters is empty" in {
            assert(LspConfig.default.signatureHelpTriggerCharacters.isEmpty)
        }
        "semanticTokensLegend is Absent" in {
            assert(LspConfig.default.semanticTokensLegend.isEmpty)
        }
    }

    "require" - {
        "passes for default config" in {
            // Should not throw; also verify the validated config matches default.
            LspConfig.require(LspConfig.default)
            assert(LspConfig.default.positionEncodings.nonEmpty)
        }
        "throws when positionEncodings is empty" in {
            val bad = LspConfig.default.withPositionEncodings(Chunk.empty)
            assertThrows[IllegalArgumentException] {
                LspConfig.require(bad)
            }
        }
        "error message mentions positionEncodings" in {
            val bad = LspConfig.default.withPositionEncodings(Chunk.empty)
            val ex = intercept[IllegalArgumentException] {
                LspConfig.require(bad)
            }
            assert(ex.getMessage.contains("positionEncodings"))
        }
        "passes with multiple encodings" in {
            val cfg = LspConfig.default.withPositionEncodings(
                Chunk(LspHandler.PositionEncodingKind.UTF16, LspHandler.PositionEncodingKind.UTF8)
            )
            LspConfig.require(cfg)
            assert(cfg.positionEncodings.size == 2)
        }
    }

    "withServerInfo" in {
        val info = LspInfo(name = "my-server", version = "1.0.0")
        val cfg  = LspConfig.default.withServerInfo(info)
        assert(cfg.serverInfo == info)
    }

    "withPositionEncodings" in {
        val encs = Chunk(LspHandler.PositionEncodingKind.UTF8)
        val cfg  = LspConfig.default.withPositionEncodings(encs)
        assert(cfg.positionEncodings == encs)
    }

    "withDocumentSync" in {
        val cfg = LspConfig.default.withDocumentSync(LspHandler.TextDocumentSyncKind.Full)
        assert(cfg.documentSync == LspHandler.TextDocumentSyncKind.Full)
    }

    "withDeclaredServerCapabilities" in {
        val caps = LspCapabilities.Server.empty
        val cfg  = LspConfig.default.withDeclaredServerCapabilities(caps)
        assert(cfg.declaredServerCapabilities == Present(caps))
    }

    "withEnforceCapabilities" in {
        val cfg = LspConfig.default.withEnforceCapabilities(false)
        assert(!cfg.enforceCapabilities)
    }

    "withOnTypeFormattingTriggers" in {
        val ts  = Chunk(".", ">")
        val cfg = LspConfig.default.withOnTypeFormattingTriggers(ts)
        assert(cfg.onTypeFormattingTriggers == ts)
    }

    "withSemanticTokensLegend" in {
        val legend = LspHandler.SemanticTokensLegend(
            tokenTypes = Chunk(LspHandler.SemanticTokenTypes("type")),
            tokenModifiers = Chunk(LspHandler.SemanticTokenModifiers("readonly"))
        )
        val cfg = LspConfig.default.withSemanticTokensLegend(legend)
        assert(cfg.semanticTokensLegend == Present(legend))
    }

    "withExecuteCommandCommands" in {
        val cmds = Chunk("editor.action.rename", "editor.action.quickFix")
        val cfg  = LspConfig.default.withExecuteCommandCommands(cmds)
        assert(cfg.executeCommandCommands == cmds)
    }

    "withCompletionTriggerCharacters" in {
        val cs  = Chunk(".", ":")
        val cfg = LspConfig.default.withCompletionTriggerCharacters(cs)
        assert(cfg.completionTriggerCharacters == cs)
    }

    "withSignatureHelpTriggerCharacters" in {
        val cs  = Chunk("(", ",")
        val cfg = LspConfig.default.withSignatureHelpTriggerCharacters(cs)
        assert(cfg.signatureHelpTriggerCharacters == cs)
    }

    "withJsonRpc" in {
        val jrc = JsonRpcHandler.Config.default
        val cfg = LspConfig.default.withJsonRpc(jrc)
        assert(cfg.jsonRpc == jrc)
    }

    "experimentalServerCapabilities" - {
        "sets _rawExperimental to Present" in {
            final case class Marker(enabled: Boolean) derives Schema, CanEqual
            val cfg = LspConfig.default.experimentalServerCapabilities(Marker(true))
            assert(cfg._rawExperimental.isDefined)
        }
        "encoded JSON contains field values" in {
            final case class Flags(fast: Boolean, verbose: Boolean) derives Schema, CanEqual
            val cfg  = LspConfig.default.experimentalServerCapabilities(Flags(fast = true, verbose = false))
            val json = cfg._rawExperimental.get
            assert(json.contains("fast"))
        }
        "round-trips a typed value via JSON" in {
            final case class MyExp(tag: String) derives Schema, CanEqual
            val exp     = MyExp("test-feature")
            val cfg     = LspConfig.default.experimentalServerCapabilities(exp)
            val rawJson = cfg._rawExperimental.get
            val decoded = decode[MyExp](rawJson)
            assert(decoded == exp)
        }
    }

    "derives CanEqual" - {
        "two identical configs are equal" in {
            assert(LspConfig.default == LspConfig.default)
        }
        "different configs are not equal" in {
            val a = LspConfig.default.withEnforceCapabilities(true)
            val b = LspConfig.default.withEnforceCapabilities(false)
            assert(a != b)
        }
    }

end LspConfigTest
