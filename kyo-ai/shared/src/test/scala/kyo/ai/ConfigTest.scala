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
        // Walked rather than compared by expression: a schedule composed onto another that already caps
        // its own attempts silently keeps the inner cap, so only stepping it reveals what callers get.
        val steps =
            def walk(s: Schedule, now: Instant, seen: Chunk[Duration]): Chunk[Duration] =
                s.next(now) match
                    case Present((delay, rest)) if seen.size < 50 => walk(rest, now + delay, seen.append(delay))
                    case _                                        => seen
            walk(cfg.retrySchedule, Instant.Epoch, Chunk.empty)
        end steps
        assert(steps.size == 10, s"the default schedule allows 10 retries, got ${steps.size}: $steps")
        assert(
            steps.head < steps.last,
            s"the default schedule backs off between attempts, got a flat schedule: $steps"
        )
        assert(cfg.meter eq Meter.Noop)
        assert(cfg.temperature.isEmpty)
        assert(cfg.reasoningEnabled, "reasoning is ON by default")
        // An untouched config STATES no amount. That is what lets an inexpressible statement warn:
        // a default that stated 12000 would be indistinguishable from a caller who asked for 12000,
        // so every warning would fire on every untouched config and none would mean anything.
        assert(cfg.reasoningAmount == Absent, s"a default config states no amount: ${cfg.reasoningAmount}")
        assert(Config.defaultReasoningBudget == 12000, s"the documented default budget is 12000: ${Config.defaultReasoningBudget}")
        // Stating nothing resolves to the entry's declared default, so the bytes are unchanged. Read
        // on a BUDGETED entry: the provider default here sizes its own reasoning, and an entry that
        // sizes itself has no amount to resolve, which is the next assertion.
        assert(
            Config.Anthropic.haiku_4_5.resolvedAmount == Present(Config.Amount.Budget(Config.defaultReasoningBudget)),
            s"an unstated amount resolves to the entry's declared default: ${Config.Anthropic.haiku_4_5.resolvedAmount}"
        )
        assert(
            cfg.resolvedAmount == Absent,
            s"an entry that sizes its own reasoning states no amount at all: ${cfg.resolvedAmount}"
        )
        assert(!cfg.disableReasoning.reasoningEnabled, "disableReasoning is the explicit opt-out")
        assert(cfg.disableReasoning.resolvedAmount == Absent, "and nothing rides while reasoning is off")
        // The amount is HELD, not dropped: one config re-aims across providers, and an off/on round
        // trip must not silently lose what the caller stated.
        val held = Config.Anthropic.haiku_4_5.reasoningBudget(777).disableReasoning
        assert(
            held.reasoningAmount == Present(Config.Amount.Budget(777)),
            s"a stated amount survives being switched off: ${held.reasoningAmount}"
        )
        assert(held.resolvedAmount == Absent, "held, but not sent")
    }

    "the output ceiling is sized from the declared reasoning kind, not from a budget the wire may refuse" in {
        // A neutral id with declared facts: the ceiling must follow the declaration, and nothing may
        // be inferred from what the model is called.
        def cfg(encoding: Config.ReasoningEncoding, maxOut: Int) =
            Config.Anthropic.default.model(
                Config.Anthropic,
                "test-model",
                200000,
                Config.OutputMaximum.Verified(maxOut),
                encoding,
                true,
                true
            )

        // Budgeted, unset: the model's own maximum, like every other kind. Sizing it as the budget plus
        // room for a result left only that room for the answer once reasoning had spent its budget,
        // which truncated any caller whose replies are large; the budget bounds REASONING, and says
        // nothing about how long an answer may be.
        val budgeted = cfg(Config.ReasoningEncoding.TokenBudget, 64000).reasoningBudget(12000)
        assert(
            budgeted.effectiveMaxOutputTokens == 64000,
            s"an unset ceiling is the model's maximum, got ${budgeted.effectiveMaxOutputTokens}"
        )
        assert(
            cfg(Config.ReasoningEncoding.TokenBudget, 64000).disableReasoning.effectiveMaxOutputTokens == 64000,
            "and switching reasoning off does not shrink it either"
        )
        // What the budget still does is rescue a ceiling set BELOW it: this wire refuses a request
        // whose ceiling does not exceed its own reasoning budget, so an explicit small ask is raised
        // to clear it rather than sent to be rejected.
        assert(
            cfg(Config.ReasoningEncoding.TokenBudget, 64000).reasoningBudget(12000).maxTokens(256).effectiveMaxOutputTokens == 16096,
            "an explicit ceiling below the budget is raised to clear it with room to answer"
        )
        assert(
            cfg(Config.ReasoningEncoding.TokenBudget, 10000).reasoningBudget(12000).effectiveMaxOutputTokens == 10000,
            "the model's own maximum clamps the budgeted ceiling"
        )

        // Adaptive: no budget bounds reasoning, so sizing from one manufactures avoidable stops. The
        // model's own maximum is the only ceiling that does not. This is the incident in one line.
        val adaptive = cfg(Config.ReasoningEncoding.Adaptive, 64000).reasoningBudget(12000)
        assert(
            adaptive.effectiveMaxOutputTokens == 64000,
            s"a declared budget must not size an adaptive ceiling, got ${adaptive.effectiveMaxOutputTokens}"
        )
        assert(
            cfg(Config.ReasoningEncoding.Adaptive, 64000).maxTokens(256).effectiveMaxOutputTokens == 256,
            "an explicit ceiling is honored"
        )
        assert(
            cfg(Config.ReasoningEncoding.Adaptive, 64000).maxTokens(999999).effectiveMaxOutputTokens == 64000,
            "an explicit ceiling above the model's maximum is clamped"
        )

        // Unsupported: no reasoning control at all, so the plain default applies.
        assert(
            cfg(Config.ReasoningEncoding.Unavailable, 64000).reasoningBudget(12000).effectiveMaxOutputTokens == 64000,
            "a wire with no reasoning control ignores a budget entirely, and its unset ceiling is the model's maximum"
        )
    }

    "a reasoning statement the entry's wire cannot express is reported, and never fails the request" in {
        // Re-aiming one config across providers is the point of the type, so a statement the wire
        // cannot carry must not fail. It must not vanish silently either: a dropped parameter that
        // says nothing is indistinguishable from one that was honored, which is the exact defect this
        // module already carries a detector for on the output ceiling.
        val budgeted = Config.Anthropic.haiku_4_5
        val graded   = Config.DeepSeek.deepseek_v4_flash

        // A statement the encoding CAN express is silent.
        assert(budgeted.reasoningBudget(9000).reasoningMismatch == Absent, "a budget on a budgeted entry is expressible")
        assert(graded.reasoningLevel("high").reasoningMismatch == Absent, "a declared level on a graded entry is expressible")

        // A default states nothing, so it never warns. This is what makes the warning mean something:
        // a default that stated an amount would fire on every untouched config.
        assert(budgeted.reasoningMismatch == Absent, "an untouched config states nothing")
        assert(graded.reasoningMismatch == Absent, "on either encoding")

        // Crossing the encodings is reported, and the request still resolves to the entry's default.
        val crossed = graded.reasoningBudget(9000)
        assert(crossed.reasoningMismatch.isDefined, "a budget stated on a graded entry is reported")
        assert(
            crossed.resolvedAmount == Present(Config.Amount.Level("low")),
            s"and the entry's declared default applies instead: ${crossed.resolvedAmount}"
        )
        val crossedBack = budgeted.reasoningLevel("high")
        assert(crossedBack.reasoningMismatch.isDefined, "a level stated on a budgeted entry is reported")
        assert(
            crossedBack.resolvedAmount == Present(Config.Amount.Budget(Config.defaultReasoningBudget)),
            s"and the budgeted default applies instead: ${crossedBack.resolvedAmount}"
        )

        // An off-levels level is reported but still RIDES: a stale levels must never refuse a value the
        // endpoint would accept, and the endpoint enumerates its own levels when it refuses one.
        val undeclaredLevel = graded.reasoningLevel("minimal")
        assert(undeclaredLevel.reasoningMismatch.isDefined, "a level outside the declared levels is reported")
        assert(
            undeclaredLevel.resolvedAmount == Present(Config.Amount.Level("minimal")),
            s"but it rides anyway, leaving the endpoint as the authority: ${undeclaredLevel.resolvedAmount}"
        )

        // Nothing rides while reasoning is off, so a held amount never warns.
        assert(crossed.disableReasoning.reasoningMismatch == Absent, "a held amount is not a statement that rides")
    }

    "Provider.defaultCandidates probes the command harnesses first, then the HTTP providers in a fixed order" in {
        val names = Config.Provider.defaultCandidates.map(_.name)
        assert(
            names == Chunk(
                "Claude Code",
                "Codex",
                "Anthropic",
                "OpenAI",
                "DeepSeek",
                "Gemini",
                "Groq",
                "xAI",
                "Moonshot",
                "Baseten",
                "OpenRouter"
            ),
            s"defaultCandidates order: $names"
        )
    }

    "provider override flags use the documented names" in {
        assert(provider.name == "kyo.ai.provider")
        assert(provider.envName == "KYO_AI_PROVIDER")
    }

    "Provider.all carries exactly the providers in order, each with a valid Completion backend" in {
        val names = Config.Provider.all.map(_.name)
        assert(names == Chunk(
            "Anthropic",
            "OpenAI",
            "DeepSeek",
            "Gemini",
            "Groq",
            "xAI",
            "Moonshot",
            "Baseten",
            "OpenRouter",
            "Claude Code",
            "Codex"
        ))
        assert(Config.Provider.apiKeyProviders.map(_.name) == Chunk(
            "Anthropic",
            "OpenAI",
            "DeepSeek",
            "Gemini",
            "Groq",
            "xAI",
            "Moonshot",
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
        System.let(customSystem)(Config.credentialed(Config.Codex.default)).map {
            cfg =>
                assert(cfg.provider eq Config.Codex)
                assert(cfg.apiKey.isEmpty)
                assert(cfg.apiOrg.isEmpty)
        }
    }

    "an unset ceiling is the model's own maximum, whatever the entry's reasoning kind" in {
        // Two entries that are equally unset used to resolve an order of magnitude apart: one whose
        // reasoning is adaptive took its model's maximum while one with reasoning off took a fixed
        // 8192. Nothing about a caller's config expressed that difference, so an arm left defaulted
        // ran into a ceiling far below its neighbour and stopped mid-answer, which reads as a weaker
        // model and is not. A downstream caller carried a hand-set ceiling to work around it.
        Config.Provider.all.foreach { p =>
            p.entries.foreach { entry =>
                assert(
                    entry.effectiveMaxOutputTokens == entry.modelMaxOutputTokens,
                    s"${p.name}/${entry.modelName}: an unset ceiling must be the model's maximum, " +
                        s"got ${entry.effectiveMaxOutputTokens} against ${entry.modelMaxOutputTokens}"
                )
            }
        }
        // A ceiling the caller does set is still honored, and still clamped to what the model accepts.
        val entry = Config.OpenAI.gpt_5_4
        assert(entry.maxTokens(256).effectiveMaxOutputTokens == 256, "an explicit ceiling is honored")
        assert(
            entry.maxTokens(entry.modelMaxOutputTokens * 4).effectiveMaxOutputTokens == entry.modelMaxOutputTokens,
            "and an explicit ceiling above the model's maximum is clamped to it"
        )
        succeed
    }

    "named catalog entries are pure Config literals (key absent) with the right model and token cap" in {
        assert(Config.OpenAI.gpt_5_5.modelName == "gpt-5.5")
        assert(Config.OpenAI.gpt_5_5.modelContextWindow == 1050000)
        assert(Config.OpenAI.gpt_5_5.apiKey.isEmpty, "a catalog entry leaves the key absent (filled at use)")
        assert(Config.Anthropic.opus_4_8.modelName == "claude-opus-4-8")
        assert(Config.Groq.gpt_oss_120b.modelContextWindow == 131072)
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
        System.let(sys)(Config.init(
            Config.OpenAI,
            "gpt-4o",
            128000,
            Config.OutputMaximum.Verified(16384),
            Config.ReasoningEncoding.Unavailable,
            true,
            acceptsImages = true
        )).map { cfg =>
            assert(cfg.modelName == "gpt-4o" && cfg.provider.name == "OpenAI")
            assert(cfg.apiKey == Present("k"), s"apiKey: ${cfg.apiKey}")
            assert(cfg.apiOrg == Present("o"), s"apiOrg: ${cfg.apiOrg}")
        }
    }

    "model resets apiUrl to the new provider's baseUrl" in {
        val cfg =
            Config.OpenAI.default.apiUrl("http://override").model(
                Config.Anthropic,
                "claude-x",
                1000,
                Config.OutputMaximum.Verified(1000),
                Config.ReasoningEncoding.Adaptive,
                false,
                acceptsImages = true
            )
        assert(cfg.apiUrl == Config.Anthropic.baseUrl, s"apiUrl: ${cfg.apiUrl}")
        assert(cfg.modelName == "claude-x" && cfg.modelContextWindow == 1000 && cfg.provider.name == "Anthropic")
    }

    "every catalog entry declares an output maximum that fits inside its context window" in {
        // A model's reply limit is always well below its input limit, so this also catches the two
        // sizes being declared in the wrong order, which no type can distinguish.
        Config.Provider.all.foreach { p =>
            p.entries.foreach { entry =>
                assert(
                    entry.modelMaxOutputTokens > 0,
                    s"${p.name}/${entry.modelName}: output maximum must be positive"
                )
                assert(
                    entry.modelMaxOutputTokens <= entry.modelContextWindow,
                    s"${p.name}/${entry.modelName}: output maximum ${entry.modelMaxOutputTokens} exceeds the " +
                        s"context window ${entry.modelContextWindow}, which means the two are transposed"
                )
                assert(
                    entry.effectiveMaxOutputTokens <= entry.modelMaxOutputTokens,
                    s"${p.name}/${entry.modelName}: the request ceiling must never exceed the model's maximum"
                )
            }
        }
        succeed
    }

    "every catalog entry keeps the effective output cap above the default reasoning budget" in {
        // Anthropic rejects a request whose max_tokens does not exceed thinking.budget_tokens, and thinking
        // is on by default, so no catalog entry may carry a model cap small enough for the clamp in
        // effectiveMaxOutputTokens to land at or below the default budget. Provider.entries is the explicit
        // catalog list; the default-membership check below is what catches a model val added without
        // extending the list.
        Config.Provider.all.foreach { p =>
            assert(p.entries.nonEmpty, s"${p.name} must list its catalog entries")
            assert(
                p.entries.exists(entry => entry.modelName == p.default.modelName),
                s"${p.name}: the default entry (${p.default.modelName}) must be in the catalog list"
            )
            // Only a declared-budgeted entry can send a budget, and the rejection this guards against is
            // the provider refusing max_tokens <= thinking.budget_tokens. Adaptive and unsupported
            // entries send no budget at all, so the comparison has nothing to protect there.
            p.entries.filter(_.modelReasoning == Config.ReasoningEncoding.TokenBudget).foreach { entry =>
                assert(
                    entry.effectiveMaxOutputTokens > Config.defaultReasoningBudget,
                    s"${p.name}/${entry.modelName}: effectiveMaxOutputTokens ${entry.effectiveMaxOutputTokens} must exceed the " +
                        s"default reasoning budget ${Config.defaultReasoningBudget}"
                )
            }
        }
        succeed
    }

    "every provider default is a pure catalog entry with positive cap, absent key, matching provider" in {
        Config.Provider.all.foreach { p =>
            val d = p.default
            assert(d.modelContextWindow > 0, s"${p.name} default token cap not positive: ${d.modelContextWindow}")
            assert(d.apiKey.isEmpty, s"${p.name} default must leave the key absent (filled at use)")
            assert(d.provider eq p, s"${p.name} default provider mismatch: ${d.provider.name}")
        }
        succeed
    }

    "the ceiling field follows the endpoint, by model switch or on its own" in {
        // The field is a property of the endpoint, so re-aiming a config at a different one has to
        // carry it across; a config left naming one endpoint's URL while speaking another's request
        // shape loses its ceiling silently, which is the failure the declaration exists to stop.
        val readsMaxTokens = Config.DeepSeek.default
        assert(readsMaxTokens.outputTokensParam == Config.OutputTokensParam.MaxTokens)
        assert(Config.OpenAI.default.outputTokensParam == Config.OutputTokensParam.MaxCompletionTokens)
        val switched =
            Config.OpenAI.default.model(
                Config.DeepSeek,
                "deepseek-v4-flash",
                1048576,
                Config.OutputMaximum.Verified(393216),
                Config.ReasoningEncoding.Unavailable,
                true,
                acceptsImages = true
            )
        assert(
            switched.outputTokensParam == Config.OutputTokensParam.MaxTokens,
            "switching model re-declares the field from the target endpoint, as it re-points apiUrl"
        )
        assert(
            Config.OpenAI.default.outputTokensParam(Config.OutputTokensParam.MaxTokens).outputTokensParam ==
                Config.OutputTokensParam.MaxTokens,
            "and the builder re-declares it for a config re-aimed without switching model"
        )
    }

    "each provider default points at the documented catalog entry" in {
        assert(Config.Anthropic.default.modelName == "claude-opus-4-8")
        assert(Config.OpenAI.default.modelName == "gpt-5.4")
        // The provider retired its non-reasoning alias, so the default is the plain id. Reasoning is
        // NOT switched off on it: the entry grades reasoning by level, and the level it declares is
        // what rides (see Config.DeepSeek).
        assert(Config.DeepSeek.default.modelName == "deepseek-v4-flash")
        assert(Config.Gemini.default.modelName == "gemini-3.5-flash")
        assert(Config.Groq.default.modelName == "openai/gpt-oss-120b")
        assert(Config.XAI.default.modelName == "grok-4.5")
        assert(Config.Moonshot.default.modelName == "kimi-k3")
        assert(Config.Baseten.default.modelName == "deepseek-ai/DeepSeek-V4-Pro")
        assert(Config.OpenRouter.default.modelName == "deepseek/deepseek-v4-pro")
        assert(Config.ClaudeCode.default.modelName == "sonnet")
        assert(Config.Codex.default.modelName == "")
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

    "every entry that cannot disable reasoning names a level its own values accept" in {
        // CannotDisable(lowest) sends `lowest`, so the declaration is only meaningful with a graded
        // encoding whose values include that word. Declared against any other encoding, or a word not in
        // the values, the request would state a level the endpoint refuses by name on every off request,
        // and one such entry rejects every forced turn on an endpoint that refuses the pairing, naming
        // nothing. Cheaper to refuse it here. Membership is checkable; the old head-of-list order was not.
        Config.Provider.defaultCandidates.foreach { provider =>
            provider.entries.foreach { entry =>
                entry.reasoningOff match
                    case Config.ReasoningOff.CannotDisable(lowest) =>
                        entry.modelReasoning match
                            case Config.ReasoningEncoding.GradedLevel(values, _) =>
                                assert(
                                    values.contains(lowest),
                                    s"${provider.name}/${entry.modelName} declares CannotDisable('$lowest') " +
                                        s"but its values are ${values.mkString(", ")}"
                                )
                            case other =>
                                assert(
                                    false,
                                    s"${provider.name}/${entry.modelName} declares CannotDisable but its encoding " +
                                        s"is $other, which names no level to send"
                                )
                    case _ => ()
            }
        }
        succeed("every CannotDisable entry names a level its values accept")
    }

    "per-entry and re-declare overrides for the newest declarations" - {
        "an entry inherits its provider's declarations by default" in {
            // OpenAI declares neither, so its entry reads the provider defaults.
            val c = Config.OpenAI.gpt_5_4_mini
            assert(c.systemInstructions == Config.SystemMessages.AllDelivered, "default delivers all system messages")
            assert(c.invalidToolCalls == Config.InvalidToolCalls.Returned, "default returns invalid calls")
        }

        "catalog entries override the provider default" in {
            // Anthropic declares SystemMessages.FirstOnly at provider level; Groq declares a rejection code.
            assert(Config.Anthropic.haiku_4_5.systemInstructions == Config.SystemMessages.FirstOnly, "Anthropic is single-slot")
            Config.Groq.gpt_oss_20b.invalidToolCalls match
                case Config.InvalidToolCalls.Rejected(code) => assert(code == "tool_use_failed", s"Groq's code: $code")
                case other                                  => assert(false, s"Groq must declare Rejected: $other")
        }

        "the model re-aim carries per-entry overrides" in {
            // A config re-aimed at another model can re-state both delivery facts.
            val reaimed = Config.OpenAI.gpt_5_4_mini.model(
                Config.OpenAI,
                "some-model",
                128000,
                Config.OutputMaximum.Verified(16384),
                Config.ReasoningEncoding.Unavailable,
                acceptsTemperature = true,
                acceptsImages = false,
                systemInstructions = Present(Config.SystemMessages.FirstOnly),
                invalidToolCalls = Present(Config.InvalidToolCalls.Rejected("bad_call"))
            )
            assert(reaimed.systemInstructions == Config.SystemMessages.FirstOnly, "override applied")
            reaimed.invalidToolCalls match
                case Config.InvalidToolCalls.Rejected(code) => assert(code == "bad_call", s"override code: $code")
                case other                                  => assert(false, s"expected Rejected: $other")
        }

        "the forcedToolChoice builder re-declares the pairing" in {
            val c = Config.OpenAI.gpt_5_4_mini.forcedToolChoice(Config.ForcedToolChoice.RefusedWhileReasoning)
            assert(c.forcedToolChoice == Config.ForcedToolChoice.RefusedWhileReasoning, "builder set it")
        }
    }

    "every graded entry's default level is one of its own values" in {
        // A GradedLevel default rides on every request that states no amount, so a slip that names a
        // level outside the entry's values would be refused by the endpoint on every default request.
        // Membership is checkable; nothing else guards it.
        Config.Provider.defaultCandidates.foreach { provider =>
            provider.entries.foreach { entry =>
                entry.modelReasoning match
                    case Config.ReasoningEncoding.GradedLevel(values, default) =>
                        assert(
                            values.contains(default),
                            s"${provider.name}/${entry.modelName} defaults to '$default', not in ${values.mkString(", ")}"
                        )
                    case _ => ()
            }
        }
        succeed("every graded default is a member of its values")
    }

end ConfigTest
