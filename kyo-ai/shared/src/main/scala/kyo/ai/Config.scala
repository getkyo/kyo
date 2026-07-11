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
    retrySchedule: Schedule = Schedule.repeat(10)
):
    def apiUrl(url: String): Config                    = copy(apiUrl = url)
    def apiKey(key: String): Config                    = copy(apiKey = Present(key))
    def apiOrg(org: String): Config                    = copy(apiOrg = Present(org))
    def temperature(temperature: Double): Config       = copy(temperature = Present(temperature.max(0).min(2)))
    def maxTokens(maxTokens: Int): Config              = copy(maxTokens = Present(maxTokens))
    def seed(seed: Int): Config                        = copy(seed = Present(seed))
    def meter(meter: Meter): Config                    = copy(meter = meter)
    def timeout(timeout: Duration): Config             = copy(timeout = timeout)
    def maxIterations(max: Int): Config                = copy(maxIterations = max)
    def retrySchedule(retrySchedule: Schedule): Config = copy(retrySchedule = retrySchedule)

    // Internal Maybe form for cross-run seed derivation, where the prior seed may be Absent.
    private[kyo] def seed(seed: Maybe[Int]): Config = copy(seed = seed)

    def model(provider: Config.Provider, modelName: String, modelMaxTokens: Int): Config =
        copy(
            provider = provider,
            modelName = modelName,
            modelMaxTokens = modelMaxTokens,
            apiUrl = provider.baseUrl
        )

end Config

private[kyo] object provider extends StaticFlag[String]("")

object Config:

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
            yield Config(provider.baseUrl, key, org, provider, modelName, modelMaxTokens)
        else
            Config(provider.baseUrl, Absent, Absent, provider, modelName, modelMaxTokens)

    /** A purely-constructed config for a provider's catalog entry (key/org left absent; filled at use via
      * the provider default path). The catalog values use this so a model literal is pure.
      */
    private[kyo] def catalog(provider: Provider, modelName: String, modelMaxTokens: Int): Config =
        Config(provider.baseUrl, Absent, Absent, provider, modelName, modelMaxTokens)

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
        val opus_4_8: Config   = catalog(this, "claude-opus-4-8", 1000000)
        val sonnet_4_6: Config = catalog(this, "claude-sonnet-4-6", 1000000)
        val haiku_4_5: Config  = catalog(this, "claude-haiku-4-5-20251001", 200000)
        val fable_5: Config    = catalog(this, "claude-fable-5", 1000000)
        val sonnet_4_5: Config = catalog(this, "claude-sonnet-4-5-20250929", 200000)
        def default: Config    = opus_4_8
    end Anthropic

    case object OpenAI extends Provider(
            "OpenAI",
            "https://api.openai.com/v1",
            "OPENAI_API_KEY",
            Completion.openAI
        ):
        val gpt_5_5: Config      = catalog(this, "gpt-5.5", 1050000)
        val gpt_5_4: Config      = catalog(this, "gpt-5.4", 1050000)
        val gpt_5_4_mini: Config = catalog(this, "gpt-5.4-mini", 400000)
        val gpt_5: Config        = catalog(this, "gpt-5", 400000)
        val gpt_5_mini: Config   = catalog(this, "gpt-5-mini", 400000)
        val gpt_5_nano: Config   = catalog(this, "gpt-5-nano", 400000)
        val gpt_4_1: Config      = catalog(this, "gpt-4.1", 1047576)
        val gpt_4_1_mini: Config = catalog(this, "gpt-4.1-mini", 1047576)
        val gpt_4o: Config       = catalog(this, "gpt-4o", 128000)
        val gpt_4o_mini: Config  = catalog(this, "gpt-4o-mini", 128000)
        val o3: Config           = catalog(this, "o3", 200000)
        val o4_mini: Config      = catalog(this, "o4-mini", 200000)
        def default: Config      = gpt_5_4
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
    end OpenRouter

    case object ClaudeCode extends Provider(
            "Claude Code",
            "",
            "CLAUDE_CODE",
            Completion.claudeCode,
            usesApiKey = false
        ):
        val opus: Config    = catalog(this, "opus", 1000000)
        val sonnet: Config  = catalog(this, "sonnet", 1000000)
        val haiku: Config   = catalog(this, "haiku", 200000)
        def default: Config = sonnet
    end ClaudeCode

    case object Codex extends Provider(
            "Codex",
            "",
            "CODEX",
            Completion.codex,
            usesApiKey = false
        ):
        val auto: Config       = catalog(this, "", 400000)
        val gpt_5_5: Config    = catalog(this, "gpt-5.5", 1050000)
        val gpt_5_4: Config    = catalog(this, "gpt-5.4", 1050000)
        val gpt_5: Config      = catalog(this, "gpt-5", 400000)
        val gpt_5_mini: Config = catalog(this, "gpt-5-mini", 400000)
        def default: Config    = auto
    end Codex

end Config
