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
        val cfg = Config.OpenAI.default.apiUrl("http://override").model(Config.Anthropic, "claude-x", 200000)
        assert(cfg.apiUrl == Config.Anthropic.baseUrl, s"apiUrl: ${cfg.apiUrl}")
        assert(cfg.modelName == "claude-x" && cfg.modelMaxTokens == 200000 && cfg.provider.name == "Anthropic")
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

    "INV-050 a valid axis constructs and the ordering holds" in {
        // default compaction on a 200000-token window with maxTokens 10000 (§6):
        //   effectiveHigh = min(0.5*200000, 128000) = 100000
        //   effectiveLow  = 0.6*100000 = 60000 ; prepareLine = 0.8*100000 = 80000
        //   hardLimitTokens = 0.9*(200000-10000) = 171000
        val cfg = Config.OpenAI.default.model(Config.OpenAI, "m", 200000).maxTokens(10000)
        assert(cfg.effectiveHigh == 100000, s"effectiveHigh: ${cfg.effectiveHigh}")
        assert(cfg.effectiveLow == 60000, s"effectiveLow: ${cfg.effectiveLow}")
        assert(cfg.prepareLine == 80000, s"prepareLine: ${cfg.prepareLine}")
        assert(cfg.hardLimitTokens == 171000, s"hardLimitTokens: ${cfg.hardLimitTokens}")
        assert(
            cfg.effectiveLow < cfg.prepareLine && cfg.prepareLine <= cfg.effectiveHigh && cfg.effectiveHigh < cfg.hardLimitTokens,
            "the full occupancy axis holds exactly (the middle equality only at prepareWatermark 1.0)"
        )
        val c: Compactor[Any] = Compactor.init
        assert(c eq Compactor.init, "Compactor.init takes no parameters and returns the shared default Compactor[Any]")
        assert(cfg.tokenizer == Absent, "tokenizer defaults to Absent (the provider's offline tiktoken default)")
    }

    "INV-050-reorder a reordering override is rejected at construction, naming the inequality" in {
        // Pushing highWatermark to 1.0 with no ceiling makes effectiveHigh (200000) cross the hard-limit
        // line (0.9*(200000-4096)=176313), so validatedAxis fails at construction, never at a boundary.
        val base = Config.OpenAI.default.model(Config.OpenAI, "m", 200000)
        val thrown =
            try
                base.compaction(_.noContextCeiling.highWatermark(1.0))
                None
            catch case e: IllegalArgumentException => Some(e.getMessage)
        assert(thrown.isDefined, "an axis-crossing override throws IllegalArgumentException at construction")
        assert(
            thrown.exists(m => m.contains("effectiveHigh") && m.contains("must be <")),
            s"the message NAMES the violated inequality, got: $thrown"
        )
    }

    "INV-051 per-field clamps and the Absent-is-off knobs" in {
        val d = Config.Compaction.default
        assert(d.highWatermark(1.5).highWatermark == 1.0, "highWatermark clamps to 1.0")
        assert(d.hardLimit(0.0).hardLimit > 0.0, "hardLimit clamps to > 0 so it can never disable the forced-path guard")
        val prep = d.prepareWatermark(0.1)
        assert(
            prep.prepareWatermark > d.lowWatermark && prep.prepareWatermark <= 1.0,
            s"prepareWatermark clamps into (lowWatermark, 1.0], got: ${prep.prepareWatermark}"
        )
        val drift = d.driftThreshold(2.0)
        assert(
            drift.driftThreshold match
                case Present(v) => v > 0.0 && v < 1.0
                case Absent     => false
            ,
            s"driftThreshold Present clamps into (0.0, 1.0), got: ${drift.driftThreshold}"
        )
        assert(d.noContextCeiling.contextCeiling == Absent, "noContextCeiling gives a pure-fraction trigger")
        assert(d.noDriftThreshold.driftThreshold == Absent, "noDriftThreshold recovers size-only triggering exactly")
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
