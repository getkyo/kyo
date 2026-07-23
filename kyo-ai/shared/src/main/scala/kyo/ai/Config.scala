package kyo.ai

import kyo.*
import kyo.ai.completion.*

/** Immutable provider/model/runtime settings for an LLM computation.
  *
  * `Config` is a copy-on-write settings record: every builder returns a modified copy. It names the
  * provider (which carries the wire backend), the model and its token cap, and the runtime knobs
  * (temperature, seed, timeout, retry schedule, meter, iteration cap). `temperature` is optional: it is
  * omitted from the request when unset (the model uses its own default) and clamped to `[0, 2]` when set.
  * `default` auto-selects the first provider marker or API key present (system properties before
  * environment variables, read via `kyo.System`, never raw `sys.props`/`sys.env`), falling back to
  * Anthropic. The active config is carried in `LLM.State.env.config`, read via `AI.config`
  * and scoped via `AI.withConfig`.
  */
final case class Config private (
    apiUrl: String,
    apiKey: Maybe[String],
    apiOrg: Maybe[String],
    provider: Config.Provider,
    modelName: String,
    modelMaxTokens: Int,
    temperature: Maybe[Double] = Absent,
    maxTokens: Maybe[Int] = Absent,
    seed: Maybe[Int] = Absent,
    meter: Meter = Meter.Noop,
    timeout: Duration = 2.minutes,
    maxIterations: Int = 5,
    retrySchedule: Schedule = Schedule.repeat(10),
    compaction: Config.Compaction = Config.Compaction.default,
    tokenizer: Maybe[Tokenizer] = Absent
):
    def apiUrl(url: String): Config                    = copy(apiUrl = url)
    def apiKey(key: String): Config                    = copy(apiKey = Present(key))
    def apiOrg(org: String): Config                    = copy(apiOrg = Present(org))
    def temperature(temperature: Double): Config       = copy(temperature = Present(temperature.max(0).min(2)))
    def maxTokens(maxTokens: Int): Config              = copy(maxTokens = Present(maxTokens)).validatedAxis
    def seed(seed: Int): Config                        = copy(seed = Present(seed))
    def meter(meter: Meter): Config                    = copy(meter = meter)
    def timeout(timeout: Duration): Config             = copy(timeout = timeout)
    def maxIterations(max: Int): Config                = copy(maxIterations = max)
    def retrySchedule(retrySchedule: Schedule): Config = copy(retrySchedule = retrySchedule)

    /** Tunes the grouped compaction knobs (watermarks, ceiling, drift, summarizer, retention
      * cap). The returned config is validated at construction: an override that reorders the
      * occupancy axis fails here with the violated inequality named, never at a boundary.
      */
    def compaction(f: Config.Compaction => Config.Compaction): Config =
        copy(compaction = f(compaction)).validatedAxis

    /** Replaces the provider's offline tiktoken default with a user token accountant. Occupancy
      * anchors on the provider's reported total either way; the tokenizer counts for
      * apportionment, where exactness is a quality property, not a safety one.
      */
    def tokenizer(tokenizer: Tokenizer): Config = copy(tokenizer = Present(tokenizer))

    /** Clears a user tokenizer set earlier in the chain, returning to the provider's offline tiktoken
      * default. Mirrors the other optional-field resets (`noContextCeiling`, `noDriftThreshold`).
      */
    def noTokenizer: Config = copy(tokenizer = Absent)

    // The output reservation counted once, on the hard-limit side: the user's maxTokens
    // when set, else a conservative default so window - reservation never over-reads what the
    // provider actually has left for input.
    private[kyo] def effectiveMaxOutput: Int =
        maxTokens.getOrElse(Config.defaultMaxOutputReservation)

    // effectiveHigh = min(highWatermark * window, contextCeiling): the boundary trigger.
    private[kyo] def effectiveHigh: Int =
        val frac = (compaction.highWatermark * modelMaxTokens).toInt
        compaction.contextCeiling match
            case Present(ceiling) => math.min(frac, ceiling)
            case Absent           => frac
    end effectiveHigh

    // effectiveLow = lowWatermark * effectiveHigh: the render-down target pass 2 stops at.
    private[kyo] def effectiveLow: Int = (compaction.lowWatermark * effectiveHigh).toInt

    // The prepare line: prepareWatermark * effectiveHigh, where speculative compaction arms.
    private[kyo] def prepareLine: Int = (compaction.prepareWatermark * effectiveHigh).toInt

    // The overflow backstop: hardLimit * (window - maxOutputTokens), the reservation counted once.
    private[kyo] def hardLimitTokens: Int =
        (compaction.hardLimit * (modelMaxTokens - effectiveMaxOutput)).toInt

    // Construction-time axis validation: the full ordering
    //   effectiveLow < prepareWatermark*effectiveHigh <= effectiveHigh < hardLimit*(window-maxOutput)
    // (the middle collapsing to equality only at prepareWatermark == 1.0). A reordering override
    // fails here with the violated inequality named, never at a boundary; the override that would
    // push the effective high above the hard-limit line is thus unconstructible.
    private[kyo] def validatedAxis: Config =
        // The prepareWatermark per-field clamp guarantees prepareWatermark > lowWatermark as a FRACTION,
        // but the axis is enforced on the toInt projections (effectiveLow, prepareLine), and truncation
        // can collapse two distinct fractions onto the same integer (e.g. effectiveHigh=128000,
        // lowWatermark=0.6, prepareWatermark=nextUp(0.6) both project to 76800). When the fraction
        // ordering already holds, raise prepareWatermark to the smallest fraction whose prepareLine.toInt
        // strictly exceeds effectiveLow.toInt, so a repairable builder value constructs cleanly. A genuine
        // reorder (prepareWatermark NOT above lowWatermark as a fraction) is left untouched for the
        // require below to reject.
        val needsRepair =
            compaction.lowWatermark < compaction.prepareWatermark && effectiveLow >= prepareLine && effectiveHigh > 0
        val checked =
            if needsRepair then copy(compaction = compaction.copy(prepareWatermark = repairedPrepareWatermark))
            else this
        val lo   = checked.effectiveLow
        val prep = checked.prepareLine
        val high = checked.effectiveHigh
        val hard = checked.hardLimitTokens
        require(lo < prep, s"compaction axis: effectiveLow ($lo) must be < prepareWatermark*effectiveHigh ($prep)")
        require(prep <= high, s"compaction axis: prepareWatermark*effectiveHigh ($prep) must be <= effectiveHigh ($high)")
        require(high < hard, s"compaction axis: effectiveHigh ($high) must be < hardLimit*(window-maxOutput) ($hard)")
        checked
    end validatedAxis

    // The smallest prepareWatermark fraction whose (fraction*effectiveHigh).toInt strictly exceeds
    // effectiveLow, found by advancing one ulp at a time from the nominal (effectiveLow+1)/effectiveHigh
    // so the search uses the exact projection prepareLine applies; capped at 1.0 (speculation off).
    private def repairedPrepareWatermark: Double =
        val high = effectiveHigh
        val low  = effectiveLow
        @scala.annotation.tailrec
        def loop(p: Double): Double =
            if p >= 1.0 then 1.0
            else if (p * high).toInt > low then p
            else loop(math.nextUp(p))
        loop((low + 1).toDouble / high.toDouble)
    end repairedPrepareWatermark

    // Internal Maybe form for cross-run seed derivation, where the prior seed may be Absent.
    private[kyo] def seed(seed: Maybe[Int]): Config = copy(seed = seed)

    def model(provider: Config.Provider, modelName: String, modelMaxTokens: Int): Config =
        copy(
            provider = provider,
            modelName = modelName,
            modelMaxTokens = modelMaxTokens,
            apiUrl = provider.baseUrl
        ).validatedAxis

end Config

private[kyo] object provider extends StaticFlag[String]("")

object Config:

    /** The grouped compaction knobs. Every watermark is a fraction; the occupancy axis
      * they derive is validated at Config construction, never at a boundary. Per-field builders
      * clamp each knob to its own range; the cross-field ordering is enforced by
      * Config.validatedAxis. The raw-eviction watermark pair rides rawRetentionCap
      * internally and is NOT on this surface. Default starting values, tunable.
      */
    final case class Compaction(
        highWatermark: Double = 0.5,                   // boundary trigger: fraction of the window
        contextCeiling: Maybe[Int] = Present(128_000), // absolute clamp on the trigger; Absent = pure fraction
        lowWatermark: Double = 0.6,                    // render-down depth: fraction of the effective high
        prepareWatermark: Double = 0.8,                // prepare line: fraction of effective high; 1.0 = no speculative compaction
        hardLimit: Double = 0.9,                       // overflow backstop vs window - maxOutputTokens; clamped > 0
        driftThreshold: Maybe[Double] = Present(0.15), // relevance trigger fraction of effectiveLow; Absent = size-only
        summarizer: Maybe[Config] = Absent,            // Absent = warm route, provider.small degraded; Present = pinned fills
        rawRetentionCap: Maybe[Int] = Absent           // Absent = several window-widths; raw-memory backstop
    ):
        def highWatermark(f: Double): Compaction    = copy(highWatermark = f.max(0.0).min(1.0))
        def contextCeiling(tokens: Int): Compaction = copy(contextCeiling = Present(tokens.max(1)))
        def noContextCeiling: Compaction            = copy(contextCeiling = Absent)
        def lowWatermark(f: Double): Compaction     = copy(lowWatermark = f.max(0.0).min(1.0))
        // prepareWatermark clamps to (lowWatermark, 1.0]: above lowWatermark so a boundary's
        // render-down always disarms preparation, and 1.0 turns speculative compaction off.
        def prepareWatermark(f: Double): Compaction = copy(prepareWatermark = f.max(math.nextUp(lowWatermark)).min(1.0))
        def hardLimit(f: Double): Compaction        = copy(hardLimit = f.max(math.nextUp(0.0)).min(1.0))
        // driftThreshold Present clamps to (0.0, 1.0); Absent disables the relevance trigger.
        def driftThreshold(f: Double): Compaction    = copy(driftThreshold = Present(f.max(math.nextUp(0.0)).min(math.nextDown(1.0))))
        def noDriftThreshold: Compaction             = copy(driftThreshold = Absent)
        def summarizer(config: Config): Compaction   = copy(summarizer = Present(config))
        def rawRetentionCap(tokens: Int): Compaction = copy(rawRetentionCap = Present(tokens.max(1)))
    end Compaction

    object Compaction:
        val default: Compaction = Compaction()

    // The output reservation counted once on the hard-limit side when the user pins no
    // maxTokens. Small enough that the default axis stays valid for every shipped
    // catalog window (16,384 up).
    private[kyo] val defaultMaxOutputReservation: Int = 4096 // conservative default; tunable

    private[kyo] def read(key: String)(using Frame): Maybe[String] < Sync =
        for
            prop <- System.property[String](key)
            env  <- if prop.isDefined then (prop: Maybe[String] < Sync) else System.env[String](key)
        yield env

    /** Resolves the default config by probing provider flags and API keys (sys props first, then env), via
      * `kyo.System`. The static `kyo.ai.provider` flag can force a provider by name. Without an explicit
      * provider, command harnesses are selected only when their marker variables are present, then API
      * providers are selected by key presence.
      */
    def default(using Frame): Config < Sync =
        val selected = provider().trim
        providerByName(selected.toLowerCase) match
            case Present(p) =>
                init(p, p.default.modelName, p.default.modelMaxTokens)
            case Absent if selected.nonEmpty =>
                throw IllegalArgumentException(s"Unsupported kyo.ai provider '$selected'.")
            case Absent =>
                Kyo.foreach(Provider.defaultCandidates)(p => read(p.keyName).map(_.isDefined -> p)).map { probes =>
                    probes.collectFirst { case (true, p) => p }.getOrElse(Anthropic)
                }.map(p => init(p, p.default.modelName, p.default.modelMaxTokens))
        end match
    end default

    def init(provider: Provider, modelName: String, modelMaxTokens: Int)(using Frame): Config < Sync =
        if provider.usesApiKey then
            for
                key <- read(provider.keyName)
                org <- read(provider.orgKey)
            yield Config(provider.baseUrl, key, org, provider, modelName, modelMaxTokens).validatedAxis
        else
            Config(provider.baseUrl, Absent, Absent, provider, modelName, modelMaxTokens).validatedAxis

    /** A purely-constructed config for a provider's catalog entry (key/org left absent; filled at use via
      * the provider default path). The catalog values use this so a model literal is pure.
      */
    private[kyo] def catalog(provider: Provider, modelName: String, modelMaxTokens: Int): Config =
        Config(provider.baseUrl, Absent, Absent, provider, modelName, modelMaxTokens).validatedAxis

    /** A provider: its display name, base URL, credential behavior, and completion backend. */
    abstract class Provider(
        val name: String,
        val baseUrl: String,
        val keyName: String,
        val completion: Completion,
        val usesApiKey: Boolean = true
    ):
        val orgKey: String = keyName + "_ORG"
        def default: Config

        /** The provider's cheap-tier catalog entry. Concrete with a `= default` fallback: a
          * `Provider` subclass that does not override it runs on `default`; the nine built-in catalogs
          * override it with their cheap entry.
          */
        def small: Config = default
    end Provider

    object Provider:
        def all: Chunk[Provider] =
            Chunk(Anthropic, OpenAI, DeepSeek, Gemini, Groq, Baseten, OpenRouter, ClaudeCode, Codex)
        def apiKeyProviders: Chunk[Provider] =
            all.filter(_.usesApiKey)
        def defaultCandidates: Chunk[Provider] =
            Chunk(ClaudeCode, Codex, Anthropic, OpenAI, DeepSeek, Gemini, Groq, Baseten, OpenRouter)
    end Provider

    private def providerByName(name: String): Maybe[Provider] =
        name match
            case ""                                       => Absent
            case "claude-code" | "claude_code" | "claude" => Present(ClaudeCode)
            case "codex"                                  => Present(Codex)
            case "anthropic"                              => Present(Anthropic)
            case "openai"                                 => Present(OpenAI)
            case "deepseek"                               => Present(DeepSeek)
            case "gemini"                                 => Present(Gemini)
            case "groq"                                   => Present(Groq)
            case "baseten"                                => Present(Baseten)
            case "openrouter"                             => Present(OpenRouter)
            case _                                        => Absent
    end providerByName

    // Each provider exposes its catalog as named pure `Config` constants (key absent, filled at use), so
    // `Config.<Provider>.<model>` is a model literal; `default` points at the recommended entry.
    case object Anthropic extends Provider(
            "Anthropic",
            "https://api.anthropic.com/v1",
            "ANTHROPIC_API_KEY",
            Completion.anthropic
        ):
        val opus_4_8: Config       = catalog(this, "claude-opus-4-8", 1000000)
        val sonnet_4_6: Config     = catalog(this, "claude-sonnet-4-6", 1000000)
        val haiku_4_5: Config      = catalog(this, "claude-haiku-4-5-20251001", 200000)
        val fable_5: Config        = catalog(this, "claude-fable-5", 1000000)
        val sonnet_4_5: Config     = catalog(this, "claude-sonnet-4-5-20250929", 200000)
        def default: Config        = opus_4_8
        override def small: Config = haiku_4_5
    end Anthropic

    case object OpenAI extends Provider(
            "OpenAI",
            "https://api.openai.com/v1",
            "OPENAI_API_KEY",
            Completion.openAI
        ):
        val gpt_5_5: Config        = catalog(this, "gpt-5.5", 1050000)
        val gpt_5_4: Config        = catalog(this, "gpt-5.4", 1050000)
        val gpt_5_4_mini: Config   = catalog(this, "gpt-5.4-mini", 400000)
        val gpt_5: Config          = catalog(this, "gpt-5", 400000)
        val gpt_5_mini: Config     = catalog(this, "gpt-5-mini", 400000)
        val gpt_5_nano: Config     = catalog(this, "gpt-5-nano", 400000)
        val gpt_4_1: Config        = catalog(this, "gpt-4.1", 1047576)
        val gpt_4_1_mini: Config   = catalog(this, "gpt-4.1-mini", 1047576)
        val gpt_4o: Config         = catalog(this, "gpt-4o", 128000)
        val gpt_4o_mini: Config    = catalog(this, "gpt-4o-mini", 128000)
        val o3: Config             = catalog(this, "o3", 200000)
        val o4_mini: Config        = catalog(this, "o4-mini", 200000)
        def default: Config        = gpt_5_4
        override def small: Config = gpt_5_nano
    end OpenAI

    case object DeepSeek extends Provider(
            "DeepSeek",
            "https://api.deepseek.com/v1",
            "DEEPSEEK_API_KEY",
            Completion.openAI
        ):
        val deepseek_v4_flash: Config = catalog(this, "deepseek-v4-flash", 1000000)
        val deepseek_v4_pro: Config   = catalog(this, "deepseek-v4-pro", 1000000)
        def default: Config           = deepseek_v4_flash
        override def small: Config    = deepseek_v4_flash
    end DeepSeek

    case object Gemini extends Provider(
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "GEMINI_API_KEY",
            Completion.openAI
        ):
        val gemini_2_5_pro: Config        = catalog(this, "gemini-2.5-pro", 1048576)
        val gemini_2_5_flash: Config      = catalog(this, "gemini-2.5-flash", 1048576)
        val gemini_2_5_flash_lite: Config = catalog(this, "gemini-2.5-flash-lite", 1048576)
        def default: Config               = gemini_2_5_flash
        override def small: Config        = gemini_2_5_flash_lite
    end Gemini

    case object Groq extends Provider(
            "Groq",
            "https://api.groq.com/openai/v1",
            "GROQ_API_KEY",
            Completion.openAI
        ):
        val gpt_oss_120b: Config            = catalog(this, "openai/gpt-oss-120b", 131072)
        val gpt_oss_20b: Config             = catalog(this, "openai/gpt-oss-20b", 131072)
        val llama_3_3_70b_versatile: Config = catalog(this, "llama-3.3-70b-versatile", 131072)
        val llama_3_1_8b_instant: Config    = catalog(this, "llama-3.1-8b-instant", 131072)
        def default: Config                 = gpt_oss_120b
        override def small: Config          = llama_3_1_8b_instant
    end Groq

    case object Baseten extends Provider(
            "Baseten",
            "https://inference.baseten.co/v1",
            "BASETEN_API_KEY",
            Completion.openAI
        ):
        val deepseek_v4_pro: Config = catalog(this, "deepseek-ai/DeepSeek-V4-Pro", 1000000)
        val gpt_oss_120b: Config    = catalog(this, "openai/gpt-oss-120b", 131072)
        def default: Config         = deepseek_v4_pro
        override def small: Config  = gpt_oss_120b
    end Baseten

    case object OpenRouter extends Provider(
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY",
            Completion.openAI
        ):
        val grok_4_fast: Config               = catalog(this, "x-ai/grok-4-fast:nitro", 2000000)
        val grok_4: Config                    = catalog(this, "x-ai/grok-4:nitro", 256000)
        val deepseek_r1_0528_qwen3_8b: Config = catalog(this, "deepseek/deepseek-r1-0528-qwen3-8b", 131072)
        val llama_3_1_8b_instruct: Config     = catalog(this, "meta-llama/llama-3.1-8b-instruct:nitro", 131072)
        val llama_4_maverick: Config          = catalog(this, "meta-llama/llama-4-maverick:nitro", 1048576)
        val llama_4_scout: Config             = catalog(this, "meta-llama/llama-4-scout:nitro", 10000000)
        val qwq_32b: Config                   = catalog(this, "qwen/qwq-32b:nitro", 131072)
        val phi_4: Config                     = catalog(this, "microsoft/phi-4:nitro", 16384)
        val mistral_7b_instruct: Config       = catalog(this, "mistralai/mistral-7b-instruct:nitro", 32768)
        val minimax_m2: Config                = catalog(this, "minimax/minimax-m2:nitro", 204800)
        val kimi_k2_0905: Config              = catalog(this, "moonshotai/kimi-k2-0905:nitro", 262144)
        val kimi_k2_thinking: Config          = catalog(this, "moonshotai/kimi-k2-thinking:nitro", 262144)
        val gpt_oss_20b: Config               = catalog(this, "openai/gpt-oss-20b:nitro", 131072)
        val gpt_5_mini: Config                = catalog(this, "openai/gpt-5-mini", 400000)
        def default: Config                   = grok_4_fast
        override def small: Config            = llama_3_1_8b_instruct
    end OpenRouter

    case object ClaudeCode extends Provider(
            "Claude Code",
            "",
            "CLAUDE_CODE",
            Completion.claudeCode,
            usesApiKey = false
        ):
        val opus: Config           = catalog(this, "opus", 1000000)
        val sonnet: Config         = catalog(this, "sonnet", 1000000)
        val haiku: Config          = catalog(this, "haiku", 200000)
        def default: Config        = sonnet
        override def small: Config = haiku
    end ClaudeCode

    case object Codex extends Provider(
            "Codex",
            "",
            "CODEX",
            Completion.codex,
            usesApiKey = false
        ):
        val auto: Config           = catalog(this, "", 400000)
        val gpt_5_5: Config        = catalog(this, "gpt-5.5", 1050000)
        val gpt_5_4: Config        = catalog(this, "gpt-5.4", 1050000)
        val gpt_5: Config          = catalog(this, "gpt-5", 400000)
        val gpt_5_mini: Config     = catalog(this, "gpt-5-mini", 400000)
        def default: Config        = auto
        override def small: Config = gpt_5_mini
    end Codex

end Config
