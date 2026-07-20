package kyo.ai

import kyo.*
import kyo.ai.completion.Completion

class ConfigTest extends kyo.test.Test[Any]:

    "temperature clamps to [0,2]" in {
        val base = Config.Anthropic.default
        assert(base.temperature(5.0).temperature == Present(2.0))
        assert(base.temperature(-1.0).temperature == Present(0.0))
    }

    "maxTokens and seed builders set the field from an Int" in {
        val base = Config.Anthropic.default
        assert(base.maxTokens == Absent && base.seed == Absent)
        assert(base.maxTokens(256).maxTokens == Present(256))
        assert(base.seed(42).seed == Present(42))
    }

    "default field values are exactly the documented defaults" in {
        val cfg = Config.Anthropic.default
        assert(cfg.timeout == 2.minutes)
        assert(cfg.maxIterations == 5)
        assert(cfg.retrySchedule == Schedule.repeat(10))
        assert(cfg.meter eq Meter.Noop)
        assert(cfg.temperature.isEmpty)
    }

    "provider override flags use the documented names" in {
        assert(provider.name == "kyo.ai.provider")
        assert(provider.envName == "KYO_AI_PROVIDER")
    }

    "Provider.all carries exactly the providers in order, each with a valid Completion backend" in {
        val names = Config.Provider.all.map(_.name)
        assert(names == Chunk("Anthropic", "OpenAI", "DeepSeek", "Gemini", "Groq", "Baseten", "OpenRouter", "Claude Code", "Codex"))
        assert(Config.Provider.apiKeyProviders.map(_.name) == Chunk(
            "Anthropic",
            "OpenAI",
            "DeepSeek",
            "Gemini",
            "Groq",
            "Baseten",
            "OpenRouter"
        ))
        assert(Config.Anthropic.completion eq Completion.anthropic)
        assert(Config.OpenAI.completion eq Completion.openAI)
        assert(Config.ClaudeCode.completion eq Completion.claudeCode)
        assert(Config.Codex.completion eq Completion.codex)
    }

    "default auto-selects the keyed provider via kyo.System and fills its key" in {
        val customSystem = System(new TestUnsafeSystem(properties = Map("OPENAI_API_KEY" -> "key")))
        System.let(customSystem)(Config.default).map { cfg =>
            assert(cfg.provider.name == "OpenAI")
            assert(cfg.apiKey == Present("key"), s"default must fill the selected provider's key, got: ${cfg.apiKey}")
        }
    }

    "default falls back to Anthropic when no key is set" in {
        val customSystem = System(new TestUnsafeSystem())
        System.let(customSystem)(Config.default).map(cfg => assert(cfg.provider.name == "Anthropic"))
    }

    "default prefers command harness markers before API provider keys" in {
        val customSystem = System(new TestUnsafeSystem(properties = Map("CODEX" -> "1", "CLAUDE_CODE" -> "1")))
        System.let(customSystem)(Config.default).map(cfg => assert(cfg.provider.name == "Claude Code"))
    }

    "default selects Codex when its marker is present and Claude Code is absent" in {
        val customSystem = System(new TestUnsafeSystem(properties = Map("CODEX" -> "1", "ANTHROPIC_API_KEY" -> "key")))
        System.let(customSystem)(Config.default).map(cfg => assert(cfg.provider.name == "Codex"))
    }

    "init for command harness providers does not read API key or org settings" in {
        val customSystem = System(new TestUnsafeSystem(properties =
            Map(
                "CODEX"     -> "not-an-api-key",
                "CODEX_ORG" -> "not-an-org"
            )
        ))
        System.let(customSystem)(Config.init(Config.Codex, Config.Codex.default.modelName, Config.Codex.default.modelMaxTokens)).map {
            cfg =>
                assert(cfg.provider eq Config.Codex)
                assert(cfg.apiKey.isEmpty)
                assert(cfg.apiOrg.isEmpty)
        }
    }

    "named catalog entries are pure Config literals (key absent) with the right model and token cap" in {
        assert(Config.OpenAI.gpt_4o.modelName == "gpt-4o")
        assert(Config.OpenAI.gpt_4o.modelMaxTokens == 128000)
        assert(Config.OpenAI.gpt_4o.apiKey.isEmpty, "a catalog entry leaves the key absent (filled at use)")
        assert(Config.Anthropic.opus_4_8.modelName == "claude-opus-4-8")
        assert(Config.Groq.gpt_oss_120b.modelMaxTokens == 131072)
        // each provider's default points at one of its named catalog entries
        assert(Config.OpenAI.default.modelName == Config.OpenAI.gpt_5_4.modelName)
        assert(Config.Anthropic.default.modelName == "claude-opus-4-8")
    }

    "read prefers a system property over an environment variable" in {
        val sys = System(new TestUnsafeSystem(
            envVars = Map("K" -> "from-env"),
            properties = Map("K" -> "from-prop")
        ))
        System.let(sys)(Config.read("K")).map {
            case Present(v) => assert(v == "from-prop", s"a system property must win over an env var, got: $v")
            case Absent     => assert(false, "expected a value")
        }
    }

    "init reads the provider's key and org from the system" in {
        val sys = System(new TestUnsafeSystem(properties =
            Map(
                "OPENAI_API_KEY"     -> "k",
                "OPENAI_API_KEY_ORG" -> "o"
            )
        ))
        System.let(sys)(Config.init(Config.OpenAI, "gpt-4o", 128000)).map { cfg =>
            assert(cfg.modelName == "gpt-4o" && cfg.provider.name == "OpenAI")
            assert(cfg.apiKey == Present("k"), s"apiKey: ${cfg.apiKey}")
            assert(cfg.apiOrg == Present("o"), s"apiOrg: ${cfg.apiOrg}")
        }
    }

    "model resets apiUrl to the new provider's baseUrl" in {
        val cfg = Config.OpenAI.default.apiUrl("http://override").model(Config.Anthropic, "claude-x", 1000)
        assert(cfg.apiUrl == Config.Anthropic.baseUrl, s"apiUrl: ${cfg.apiUrl}")
        assert(cfg.modelName == "claude-x" && cfg.modelMaxTokens == 1000 && cfg.provider.name == "Anthropic")
    }

    "every provider default is a pure catalog entry with positive cap, absent key, matching provider" in {
        Config.Provider.all.foreach { p =>
            val d = p.default
            assert(d.modelMaxTokens > 0, s"${p.name} default token cap not positive: ${d.modelMaxTokens}")
            assert(d.apiKey.isEmpty, s"${p.name} default must leave the key absent (filled at use)")
            assert(d.provider eq p, s"${p.name} default provider mismatch: ${d.provider.name}")
        }
        succeed
    }

    "each provider default points at the documented catalog entry" in {
        assert(Config.Anthropic.default.modelName == "claude-opus-4-8")
        assert(Config.OpenAI.default.modelName == "gpt-5.4")
        assert(Config.DeepSeek.default.modelName == "deepseek-v4-flash")
        assert(Config.Gemini.default.modelName == "gemini-2.5-flash")
        assert(Config.Groq.default.modelName == "openai/gpt-oss-120b")
        assert(Config.Baseten.default.modelName == "deepseek-ai/DeepSeek-V4-Pro")
        assert(Config.OpenRouter.default.modelName == "x-ai/grok-4-fast:nitro")
        assert(Config.ClaudeCode.default.modelName == "sonnet")
        assert(Config.Codex.default.modelName == "")
    }

    "Config.embedder builder round-trips" in {
        val config = Config.OpenAI.default
        val e      = Config.Anthropic.default
        config.embedder(e).embedder match
            case Present(c) => assert(c eq e, s"expected the paired embedder config, got: $c")
            case Absent     => assert(false, "expected embedder to be Present after the builder call")
        assert(config.embedder == Absent, s"a default Config must leave embedder Absent, got: ${config.embedder}")
    }

    "every catalog Provider.small resolves to its named cheap literal" in {
        assert(Config.Anthropic.small.modelName == "claude-haiku-4-5-20251001" && (Config.Anthropic.small.provider eq Config.Anthropic))
        assert(Config.OpenAI.small.modelName == "gpt-5-nano" && (Config.OpenAI.small.provider eq Config.OpenAI))
        assert(Config.DeepSeek.small.modelName == "deepseek-v4-flash" && (Config.DeepSeek.small.provider eq Config.DeepSeek))
        assert(Config.Gemini.small.modelName == "gemini-2.5-flash-lite" && (Config.Gemini.small.provider eq Config.Gemini))
        assert(Config.Groq.small.modelName == "llama-3.1-8b-instant" && (Config.Groq.small.provider eq Config.Groq))
        assert(Config.Baseten.small.modelName == "openai/gpt-oss-120b" && (Config.Baseten.small.provider eq Config.Baseten))
        assert(
            Config.OpenRouter.small.modelName == "meta-llama/llama-3.1-8b-instruct:nitro" && (Config.OpenRouter.small.provider eq Config.OpenRouter)
        )
        assert(Config.ClaudeCode.small.modelName == "haiku" && (Config.ClaudeCode.small.provider eq Config.ClaudeCode))
        assert(Config.Codex.small.modelName == "gpt-5-mini" && (Config.Codex.small.provider eq Config.Codex))
    }

    "Provider.small is a distinct accessor from default where the catalog differs" in {
        assert(Config.Anthropic.small.modelName != Config.Anthropic.default.modelName)
        assert(Config.OpenAI.small.modelName != Config.OpenAI.default.modelName)
    }

    "compaction config is exactly five fields on kyo.ai.Config; init takes nothing (INV-CMP-58)" in {
        val cfg = Config.Anthropic.default
            .compactionBudget(1000)
            .compactionHighWatermark(0.7)
            .compactionLowWatermark(0.45)
            .compactionHardLimit(0.9)
        assert(cfg.compactionBudget == Present(1000))
        assert(cfg.compactionHighWatermark == 0.7)
        assert(cfg.compactionLowWatermark == 0.45)
        assert(cfg.compactionHardLimit == 0.9)
        val d = Config.Anthropic.default
        assert(d.compactionBudget == Absent, s"compactionBudget defaults Absent (Maybe[Int]), got: ${d.compactionBudget}")
        assert(d.compactionHighWatermark == 0.7 && d.compactionLowWatermark == 0.45 && d.compactionHardLimit == 0.9)
        assert(d.tokenizer eq Compactor.Tokenizer.default, "tokenizer defaults to Compactor.Tokenizer.default")
        val c: Compactor[Any] = Compactor.init
        assert(c eq Compactor.init, "Compactor.init takes no parameters and returns the shared default Compactor[Any]")
    }

    "effectiveCompactionBudget scales with modelMaxTokens; a copy-based model switch re-derives it; Present pins survive the switch (F7.1-r2)" in {
        val base = Config.Anthropic.default.model(Config.Anthropic, "m", 200000)
        assert(base.effectiveCompactionBudget == 48000, s"min(100000, 48000) = 48000, got: ${base.effectiveCompactionBudget}")
        val big = base.model(Config.Anthropic, "m2", 1000000)
        assert(
            big.effectiveCompactionBudget == 48000,
            s"re-derived from the ACTIVE modelMaxTokens: min(500000, 48000) = 48000, got: ${big.effectiveCompactionBudget}"
        )
        val small = base.model(Config.Anthropic, "m3", 60000)
        assert(
            small.effectiveCompactionBudget == 30000,
            s"a small-window model reads min(30000, 48000) = 30000, got: ${small.effectiveCompactionBudget}"
        )
        val pinned = base.compactionBudget(5000)
        assert(pinned.effectiveCompactionBudget == 5000, "a user-pinned budget is used verbatim before a switch")
        assert(
            pinned.model(Config.Anthropic, "m4", 1000000).effectiveCompactionBudget == 5000,
            "a user-pinned budget is never overridden by a model switch"
        )
    }

    "compaction builders clamp into [0,1]; compactionBudget floors at 1 (F6.2-r2)" in {
        val c = Config.Anthropic.default
        assert(c.compactionHighWatermark(1.3).compactionHighWatermark == 1.0)
        assert(c.compactionLowWatermark(-0.2).compactionLowWatermark == 0.0)
        assert(
            c.compactionHardLimit(2.0).compactionHardLimit == 1.0,
            "hardLimit clamps to 1.0 so it can never disable the forced-path guard"
        )
        assert(c.compactionBudget(-5).compactionBudget == Present(1), "compactionBudget floors at 1, never non-positive")
    }

    private class TestUnsafeSystem(
        envVars: Map[String, String] = Map.empty,
        properties: Map[String, String] = Map.empty
    ) extends System.Unsafe:
        def env(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(envVars.get(name))
        def property(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(properties.get(name))
        def lineSeparator()(using AllowUnsafe): String      = "\n"
        def userName()(using AllowUnsafe): String           = "test"
        def operatingSystem()(using AllowUnsafe): System.OS = System.OS.Unknown
        def architecture()(using AllowUnsafe): System.Arch  = System.Arch.Unknown
        def availableProcessors()(using AllowUnsafe): Int   = 1
    end TestUnsafeSystem

end ConfigTest
