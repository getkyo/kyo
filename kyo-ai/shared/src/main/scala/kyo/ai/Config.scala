package kyo.ai

import kyo.*
import kyo.ai.completion.*

/** Immutable provider/model/runtime settings for an LLM computation.
  *
  * Copy-on-write: every builder returns a modified copy. Names the provider (which carries the wire
  * backend), the model and its token cap, and the runtime knobs (temperature, seed, timeout, retry
  * schedule, meter, iteration cap). `temperature` is omitted when unset and clamped to `[0, 2]` when
  * set; it reaches the wire only where the model's catalog entry declares the parameter accepted, since
  * some models reject it. Not every knob reaches every backend: `seed` is carried only by the
  * OpenAI-compatible backends, and the command harnesses (Claude Code, Codex) use their own account
  * transports, so `apiUrl`, `apiKey`, and `temperature` do not reach them. The Claude Code CLI exposes
  * no temperature knob at all (no `--temperature` flag; `CLAUDE_CODE_TEMPERATURE`/`ANTHROPIC_TEMPERATURE`/`CLAUDE_TEMPERATURE`
  * are ignored), so `temperature` is inert there by a verified CLI limitation, not an omission.
  *
  * Reasoning is ON by default (`reasoningEnabled = true`), at the cost of output tokens and latency;
  * `disableReasoning` opts out, and how "off" is said varies by wire (see `Config.ReasoningOff`). How
  * much is asked for is separate: `reasoningAmount` is `Absent` on an untouched config, meaning the
  * entry's declared default applies. A stated amount the encoding cannot express warns rather than
  * failing, and the request is built as if it were absent (see `Config.ReasoningEncoding`).
  *
  * `default` auto-selects the first provider marker or API key present (system properties before
  * environment variables, read via `kyo.System`, never raw `sys.props`/`sys.env`), falling back to
  * Anthropic. The active config is carried in `LLM.State.env.config`, read via `AI.config` and scoped
  * via `AI.withConfig`.
  */
final case class Config private (
    apiUrl: String,
    apiKey: Maybe[String],
    apiOrg: Maybe[String],
    provider: Config.Provider,
    modelName: String,
    // The model's context window. Distinct from modelMaxOutputTokens: every model's reply limit is
    // far below its input limit, so clamping an output ceiling against the window never binds.
    modelContextWindow: Int,
    modelOutputMaximum: Config.OutputMaximum,
    modelReasoning: Config.ReasoningEncoding,
    modelAcceptsTemperature: Boolean,
    // Whether this model reads image content parts. Declared, not assumed: a text-only endpoint refuses
    // the whole request rather than ignoring the image, naming a JSON variant, not the picture.
    modelAcceptsImages: Boolean,
    // Which field the endpoint reads the ceiling from. Carried from the provider like apiUrl and
    // overridable with it: a config re-aimed at another endpoint must re-declare this, or it names one
    // endpoint's URL while speaking another's request shape.
    outputTokensParam: Config.OutputTokensParam,
    // How this endpoint says "do not reason". Carried from the provider and overridable, like
    // outputTokensParam. Safe at provider granularity only because an Unavailable entry never sends off
    // bytes: one endpoint can offer reasoning on one model and refuse the same off field on another
    // (verified: an OpenAI reasoning entry accepts reasoning_effort "none" while a non-reasoning entry
    // rejects the parameter outright).
    reasoningOff: Config.ReasoningOff,
    forcedToolChoice: Config.ForcedToolChoice,
    // How this endpoint delivers system instructions and surfaces an invalid tool call. Provider-level
    // by default, overridable per entry, since an aggregator routes two entries to endpoints that
    // disagree on both.
    systemInstructions: Config.SystemMessages,
    invalidToolCalls: Config.InvalidToolCalls,
    temperature: Maybe[Double] = Absent,
    maxTokens: Maybe[Int] = Absent,
    // Carried only by the OpenAI-compatible backends; inert on Anthropic and the command harnesses.
    seed: Maybe[Int] = Absent,
    meter: Meter = Meter.Noop,
    // Deadline for one completion call, covering that call's retries: a call that keeps failing
    // transiently surfaces AICompletionTimeoutException rather than running the schedule to exhaustion.
    // A generation issues one call per eval-loop turn, each with its own deadline.
    timeout: Duration = 2.minutes,
    maxIterations: Int = 5,
    // Exponential backoff with jitter, so a throttled provider gets room to recover. The attempt count
    // is generous because the deadline above is what actually caps the call.
    retrySchedule: Schedule =
        Schedule.exponentialBackoff(initial = 100.millis, factor = 2, maxBackoff = 5.seconds).jitter(0.2).take(10),
    // Whether the model reasons before answering, ON by default. STATE, owned by the caller; how the
    // wire expresses it belongs to the entry's encoding and the off encoding. A request carrying a
    // reasoning ACTIVATION field never forces the result tool (two wires refuse that combination); one
    // carrying a DEACTIVATION field still forces.
    reasoningEnabled: Boolean = true,
    // How much reasoning to ask for, when the caller states it. Absent means the entry's declared
    // default applies, so an untouched config states nothing and never warns; a warning fires only on a
    // STATED amount the encoding cannot express. Held rather than dropped where it cannot ride, since
    // one config re-aims across providers, and held while reasoning is off.
    reasoningAmount: Maybe[Config.Amount] = Absent,
    compaction: Config.Compaction = Config.Compaction.default,
    tokenizer: Maybe[Tokenizer] = Absent
):
    def apiUrl(url: String): Config              = copy(apiUrl = url)
    def apiKey(key: String): Config              = copy(apiKey = Present(key))
    def apiOrg(org: String): Config              = copy(apiOrg = Present(org))
    def temperature(temperature: Double): Config = copy(temperature = Present(temperature.max(0).min(2)))

    /** The output-token ceiling this request asks for, clamped to the model's declared maximum.
      *
      * Reasoning tokens are output tokens, spent from this same allowance, so what the number means
      * depends on the entry's reasoning encoding. Where reasoning is a bounded token budget, the
      * resolved ceiling clears it and the answer keeps a floor. Where reasoning is graded by level,
      * nothing bounds it in tokens from this side, so the answer gets whatever reasoning did not
      * consume, which on a hard generation can be very little. A reply that stops at the ceiling reports
      * how much went to reasoning, so a larger ceiling and less reasoning can be told apart.
      */
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

    // The output reservation counted once, on the hard-limit side. When the caller pins maxTokens the
    // reservation is the ceiling the wire actually sends: the pin clamped to the model's maximum and
    // raised past any reasoning budget (effectiveMaxOutputTokens), never the raw pin, so a pin above the
    // model's maximum reserves the real sent ceiling rather than an impossible number that would leave
    // window - reservation negative. With no pin, reserve the provider's verified output maximum where it
    // is known and small enough to keep the occupancy axis coherent under the current watermarks, so
    // window - reservation reflects what the provider actually leaves for input. Fall back to a
    // conservative default both when no verified maximum exists (an Unverified stand-in that equals
    // the window would zero window - reservation) and when a verified maximum is large enough to
    // reorder the axis on its shipped defaults, so a catalog entry never fails to construct.
    private[kyo] def effectiveMaxOutput: Int =
        val candidate =
            if maxTokens.isDefined then effectiveMaxOutputTokens
            else sendableMaximum.getOrElse(Config.defaultMaxOutputReservation)
        if effectiveHigh < (compaction.hardLimit * (modelContextWindow - candidate)).toInt then candidate
        else Config.defaultMaxOutputReservation
    end effectiveMaxOutput

    // effectiveHigh = min(highWatermark * window, contextCeiling): the boundary trigger.
    private[kyo] def effectiveHigh: Int =
        val frac = (compaction.highWatermark * modelContextWindow).toInt
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
        (compaction.hardLimit * (modelContextWindow - effectiveMaxOutput)).toInt

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

    /** States a reasoning budget in model tokens, and turns reasoning on.
      *
      * Rides only where the entry declares [[Config.ReasoningEncoding.TokenBudget]]. On any other
      * encoding this is a statement the wire cannot express: the request warns and is built as if the
      * budget were absent, rather than failing, so one config stays re-aimable across providers.
      */
    def reasoningBudget(tokens: Int): Config = copy(reasoningEnabled = true, reasoningAmount = Present(Config.Amount.Budget(tokens)))

    /** States a reasoning level, and turns reasoning on.
      *
      * The value is the wire's own word, not a kyo vocabulary: levels genuinely differ between
      * endpoints, so a shared set would invent a fact. A value outside the entry's declared levels warns
      * and rides anyway, leaving the endpoint as the authority, since stale levels must never refuse a
      * value the wire would accept. Rides only where the entry declares
      * [[Config.ReasoningEncoding.GradedLevel]]; elsewhere it warns and the request is built as if absent.
      */
    def reasoningLevel(value: String): Config = copy(reasoningEnabled = true, reasoningAmount = Present(Config.Amount.Level(value)))

    /** Turns reasoning off, leaving any stated amount held but unsent.
      *
      * How "off" reaches the wire is the endpoint's encoding (see [[Config.ReasoningOff]]). Where an
      * entry does not reason at all this is satisfied without sending anything. Turning reasoning off
      * also restores the forced result tool, because only an ACTIVATION field is incompatible with
      * forcing.
      */
    def disableReasoning: Config = copy(reasoningEnabled = false)

    /** Re-declares how this config's endpoint says "do not reason".
      *
      * Needed alongside `apiUrl` for the same reason as `outputTokensParam`: a config re-aimed at
      * another endpoint otherwise speaks one wire's off encoding to another, which is either refused or,
      * worse, ignored while the model keeps reasoning.
      */
    def reasoningOff(off: Config.ReasoningOff): Config = copy(reasoningOff = off)

    /** Re-declares whether this config's endpoint refuses a forced tool choice while reasoning is active,
      * needed alongside `reasoningOff` when a re-aimed config's endpoint differs on the pairing.
      */
    def forcedToolChoice(choice: Config.ForcedToolChoice): Config = copy(forcedToolChoice = choice)

    /** The model's output maximum as a number, verified or stood-in alike.
      *
      * For sizing and reporting, where the provenance does not change the arithmetic. What rides the
      * wire uses [[sendableMaximum]] instead, because there the provenance is the whole question.
      */
    private[kyo] def modelMaxOutputTokens: Int =
        modelOutputMaximum match
            case Config.OutputMaximum.Verified(tokens)    => tokens
            case Config.OutputMaximum.Unverified(assumed) => assumed

    /** The ceiling to send when the caller states none, and the bound to clamp a stated one against.
      *
      * Present only where the maximum is the provider's own. Absent means nothing is known to send:
      * withholding leaves the endpoint's default in force, worse than stating the model's limit but
      * better than stating a number nobody verified.
      */
    private[kyo] def sendableMaximum: Maybe[Int] =
        modelOutputMaximum match
            case Config.OutputMaximum.Verified(tokens) => Present(tokens)
            case Config.OutputMaximum.Unverified(_)    => Absent

    /** What the caller stated about reasoning that this entry's wire cannot express, if anything.
      *
      * A statement the wire cannot carry must not fail the request (one config is re-aimed across
      * providers on purpose), but must not be silent either: a parameter dropped without a word is
      * indistinguishable from one that was honored. So it is reported, and the request is built as if
      * the statement were absent. Only a STATED amount is reported; a default, or a held amount while
      * reasoning is off, never warns because nothing rides.
      */
    private[kyo] def reasoningMismatch: Maybe[String] =
        if !reasoningEnabled then
            modelReasoning match
                case Config.ReasoningEncoding.Unavailable => Absent
                case _ =>
                    reasoningOff match
                        // Every other encoding has bytes for "off"; this one does not, so a wire that
                        // reasons by default keeps reasoning.
                        case Config.ReasoningOff.Omit
                            if modelReasoning == Config.ReasoningEncoding.Managed ||
                                modelReasoning.isInstanceOf[Config.ReasoningEncoding.GradedLevel] =>
                            val named = if modelName.nonEmpty then modelName else s"${provider.name}'s default model"
                            Present(
                                s"$named was asked not to reason, but its endpoint states no way to say so: " +
                                    "the model will reason anyway"
                            )
                        // A wire that cannot be switched off: the lowest level rides, it counts as
                        // reasoning-active, and the caller is told the ask could not be met.
                        case Config.ReasoningOff.CannotDisable(lowest) =>
                            val named = if modelName.nonEmpty then modelName else s"${provider.name}'s default model"
                            Present(
                                s"$named was asked not to reason, but its endpoint cannot disable reasoning: " +
                                    s"level '$lowest' is sent instead and the model will still reason"
                            )
                        case _ => Absent
        else
            reasoningAmount.flatMap { stated =>
                (stated, modelReasoning) match
                    case (Config.Amount.Budget(_), Config.ReasoningEncoding.TokenBudget)               => Absent
                    case (Config.Amount.Level(value), Config.ReasoningEncoding.GradedLevel(values, _)) =>
                        // A stale set of levels must never refuse a value the endpoint would accept, so an
                        // unrecognized level is reported and still rides: the endpoint stays the authority.
                        if values.contains(value) then Absent
                        else
                            Present(
                                s"reasoning level '$value' is not one $modelName declares (${values.mkString(", ")}); " +
                                    "it rides anyway, and the endpoint decides"
                            )
                    case (other, encoding) =>
                        val what = other match
                            case Config.Amount.Budget(tokens) => s"a reasoning budget of $tokens tokens"
                            case Config.Amount.Level(value)   => s"a reasoning level of '$value'"
                        Present(
                            s"$what was stated for $modelName, whose wire states reasoning as $encoding: the request is " +
                                "built as if it were absent, and the entry's own default applies"
                        )
            }
        end if
    end reasoningMismatch

    /** The reasoning amount that actually rides, resolving a stated amount against the entry's encoding.
      *
      * `Absent` means nothing rides beyond activation itself. This is the single resolution point: the
      * request bytes and [[effectiveMaxOutputTokens]] both read it, so the ceiling can never disagree
      * with the wire about how much reasoning was asked for.
      */
    private[kyo] def resolvedAmount: Maybe[Config.Amount] =
        if !reasoningEnabled then Absent
        else
            modelReasoning match
                case Config.ReasoningEncoding.TokenBudget =>
                    reasoningAmount match
                        case Present(b: Config.Amount.Budget) => Present(b)
                        case _                                => Present(Config.Amount.Budget(Config.defaultReasoningBudget))
                case Config.ReasoningEncoding.GradedLevel(_, default) =>
                    reasoningAmount match
                        case Present(l: Config.Amount.Level) => Present(l)
                        case _                               => Present(Config.Amount.Level(default))
                case _ => Absent

    /** The output-token ceiling a request carries, sized from the entry's declared reasoning kind.
      *
      * Reasoning tokens count against this ceiling, so sizing depends on whether anything bounds them.
      * A budget bounds them: the ceiling is that budget plus room for the result. Adaptive reasoning is
      * unbounded, so the ceiling is the model's own maximum; any smaller value lets reasoning consume
      * the whole allowance and stop the reply before it produces anything. With no reasoning control the
      * plain default applies. Always clamped to the model's declared maximum.
      *
      * Which backends apply it is decided by their wires: it always rides where the ceiling is a
      * required field and is exported where a harness accepts one, but on a wire whose ceiling field is
      * optional it rides only when `maxTokens` is set explicitly, since sending the default there would
      * cap a reply below the model's own limit. One wire offers no ceiling at all.
      */
    private[kyo] def effectiveMaxOutputTokens: Int =
        // An unset ceiling is the model's own maximum, whatever its reasoning kind. A ceiling is a cap,
        // not a target: a reply that finishes early costs what it costs, so a cap below the model's
        // maximum buys nothing and manufactures stops. Every entry declares the maximum its model
        // accepts, so the unset case has a real answer rather than a fixed constant.
        val base = maxTokens.getOrElse(modelMaxOutputTokens)
        val requested =
            resolvedAmount match
                case Present(Config.Amount.Budget(tokens)) =>
                    // The budget bounds reasoning, so the ceiling must clear it with room to answer,
                    // which matters when a caller sets a ceiling smaller than its own budget.
                    base.max(tokens + Config.resultTokenReserve)
                case _ =>
                    // A level names no token count, so nothing can be added: only the endpoint knows
                    // what a level costs, and guessing would put an invented number in the ceiling.
                    base
        requested.min(modelMaxOutputTokens)
    end effectiveMaxOutputTokens

    // Internal Maybe form for cross-run seed derivation, where the prior seed may be Absent.
    private[kyo] def seed(seed: Maybe[Int]): Config = copy(seed = seed)

    /** Points this config at a model on a given provider, declaring the facts the wire needs: the
      * context window, the model's own output maximum, how its reasoning is controlled, and whether it
      * accepts a temperature. Nothing is inferred from the name, so a model no catalog lists is usable
      * by stating what it supports.
      *
      * This switches provider, so it also resets `apiUrl` to that provider's base URL. Re-apply `apiUrl`
      * afterwards when pointing at a proxy or a local endpoint. To keep this config's provider and facts
      * and only change which id rides the wire, use `modelName`.
      */
    /** Re-declares provider, model id, and context window, leaving the remaining facts unstated: the
      * output maximum is an `Unverified` stand-in (so the once-counted reservation falls back to the
      * conservative default rather than a claimed ceiling), reasoning is `Unavailable`, and both
      * temperature and images are accepted. The convenience form for setting only the window on a
      * config whose other facts come from its provider default; the full [[model]] declares them all.
      */
    def model(provider: Config.Provider, modelName: String, contextWindow: Int): Config =
        model(
            provider,
            modelName,
            contextWindow,
            Config.OutputMaximum.Unverified(contextWindow),
            Config.ReasoningEncoding.Unavailable,
            acceptsTemperature = true,
            acceptsImages = true
        )

    def model(
        provider: Config.Provider,
        modelName: String,
        contextWindow: Int,
        outputMaximum: Config.OutputMaximum,
        reasoning: Config.ReasoningEncoding,
        acceptsTemperature: Boolean,
        acceptsImages: Boolean,
        // The provider's encoding is the default; an entry whose model disagrees says so. Two models on
        // one endpoint can differ: one takes a level word for none, the next refuses that word and
        // cannot be switched off at all. The same holds for the forced-choice pairing, measured to
        // differ between two models on one endpoint.
        reasoningOff: Maybe[Config.ReasoningOff] = Absent,
        forcedToolChoice: Maybe[Config.ForcedToolChoice] = Absent,
        systemInstructions: Maybe[Config.SystemMessages] = Absent,
        invalidToolCalls: Maybe[Config.InvalidToolCalls] = Absent
    ): Config =
        copy(
            provider = provider,
            modelName = modelName,
            modelContextWindow = contextWindow,
            modelOutputMaximum = outputMaximum,
            modelReasoning = reasoning,
            modelAcceptsTemperature = acceptsTemperature,
            modelAcceptsImages = acceptsImages,
            apiUrl = provider.baseUrl,
            outputTokensParam = provider.outputTokensParam,
            reasoningOff = reasoningOff.getOrElse(provider.reasoningOff),
            forcedToolChoice = forcedToolChoice.getOrElse(provider.forcedToolChoice),
            systemInstructions = systemInstructions.getOrElse(provider.systemInstructions),
            invalidToolCalls = invalidToolCalls.getOrElse(provider.invalidToolCalls)
        ).validatedAxis

    /** Re-declares which request field this config's endpoint reads the output ceiling from.
      *
      * Needed alongside `apiUrl`: re-aiming a config at another endpoint without re-declaring this
      * leaves it naming one endpoint's URL while speaking another's request shape, and an endpoint
      * handed the name it does not read drops the ceiling in silence rather than refusing.
      */
    def outputTokensParam(param: Config.OutputTokensParam): Config = copy(outputTokensParam = param)

    /** Re-points this config at another model id that shares its declared facts: a dated snapshot, a
      * fine-tuned derivative, or a proxy alias. The equivalence is the caller's to declare; nothing is
      * inferred from the new id.
      */
    def modelName(name: String): Config = copy(modelName = name)

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
    // maxTokens and no verified model maximum is available. Small enough that the default axis
    // stays valid for every shipped catalog window.
    private[kyo] val defaultMaxOutputReservation: Int = 4096 // conservative default; tunable

    /** The reasoning budget applied when an entry encodes reasoning as a token budget and the caller
      * states none.
      *
      * A policy default, not a fact about any model. Applies only where the entry declares
      * [[ReasoningEncoding.TokenBudget]]; a graded entry defaults to its own declared level, and an
      * entry that states no amount sends none.
      */
    val defaultReasoningBudget: Int = 12000

    /** Room kept below the ceiling for the result once bounded reasoning has spent its budget. */
    private[kyo] val resultTokenReserve: Int = 4096

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
                credentialed(p.default)
            case Absent if selected.nonEmpty =>
                throw IllegalArgumentException(s"Unsupported kyo.ai provider '$selected'.")
            case Absent =>
                Kyo.foreach(Provider.defaultCandidates)(p => read(p.keyName).map(_.isDefined -> p)).map { probes =>
                    probes.collectFirst { case (true, p) => p }.getOrElse(Anthropic)
                }.map(p => credentialed(p.default))
        end match
    end default

    def init(
        provider: Provider,
        modelName: String,
        contextWindow: Int,
        outputMaximum: OutputMaximum,
        reasoning: ReasoningEncoding,
        acceptsTemperature: Boolean,
        acceptsImages: Boolean,
        reasoningOff: Maybe[ReasoningOff] = Absent,
        forcedToolChoice: Maybe[ForcedToolChoice] = Absent
    )(using Frame): Config < Sync =
        credentialed(catalog(
            provider,
            modelName,
            contextWindow,
            outputMaximum,
            reasoning,
            acceptsTemperature,
            acceptsImages,
            reasoningOff,
            forcedToolChoice
        ))

    /** Attaches the provider's credentials to an already-declared config, so a catalog entry's facts
      * travel with it instead of being re-listed at every call site.
      */
    private[kyo] def credentialed(config: Config)(using Frame): Config < Sync =
        if config.provider.usesApiKey then
            for
                key <- read(config.provider.keyName)
                org <- read(config.provider.orgKey)
            yield config.copy(apiKey = key, apiOrg = org)
        else config

    /** A purely-constructed config for a provider's catalog entry (key/org left absent; filled at use via
      * the provider default path). The catalog values use this so a model literal is pure.
      */
    private[kyo] def catalog(
        provider: Provider,
        modelName: String,
        contextWindow: Int,
        outputMaximum: OutputMaximum,
        reasoning: ReasoningEncoding,
        acceptsTemperature: Boolean,
        acceptsImages: Boolean,
        reasoningOff: Maybe[ReasoningOff] = Absent,
        forcedToolChoice: Maybe[ForcedToolChoice] = Absent,
        systemInstructions: Maybe[SystemMessages] = Absent,
        invalidToolCalls: Maybe[InvalidToolCalls] = Absent
    ): Config =
        Config(
            provider.baseUrl,
            Absent,
            Absent,
            provider,
            modelName,
            contextWindow,
            outputMaximum,
            reasoning,
            acceptsTemperature,
            acceptsImages,
            provider.outputTokensParam,
            reasoningOff.getOrElse(provider.reasoningOff),
            forcedToolChoice.getOrElse(provider.forcedToolChoice),
            systemInstructions.getOrElse(provider.systemInstructions),
            invalidToolCalls.getOrElse(provider.invalidToolCalls)
        ).validatedAxis

    /** A model's maximum output tokens, and whether that number is known or stood in for.
      *
      * These are different facts and the difference decides what rides the wire. A maximum from the
      * provider's own limit response or published reference is a fact: it can be sent when a caller
      * states no ceiling, and a larger ask can be clamped to it. A number written down because none was
      * published is a stand-in: sending it would put an invented limit on the wire, and clamping to it
      * could cut a reply below what the model would have produced. The two are not distinguishable by
      * inspection, since a published maximum can legitimately equal its context window.
      *
      * The stand-ins that equal their context window were PROBED once keys existed, by sending an
      * over-large value so a refusal would name the real bound. It named none: one host refuses with the
      * same bound for every model it serves, including one whose entire context is half that bound (a
      * parameter validated, not a model described); an aggregator refuses by naming its maximum CONTEXT
      * length, already the declared window; and two providers accept 99,999,999 without complaint. They
      * stay stand-ins because these endpoints do not answer the question, not because it was never asked.
      */
    enum OutputMaximum derives CanEqual:
        /** Sourced from the provider: its limit response, or its published reference. */
        case Verified(tokens: Int)

        /** No published or probed bound. The number preserves prior behavior and is not a claim; an
          * over-large ask is left unclamped so the endpoint refuses it and names the real bound.
          */
        case Unverified(assumed: Int)
    end OutputMaximum

    /** How much reasoning a caller asked for, in the vocabulary the wire actually uses.
      *
      * Two encodings that cannot be converted into each other: a count of model tokens and a level
      * word. Mapping between them would invent a provider fact (nothing states what a level costs, or
      * which level a budget corresponds to), so they stay distinct and an amount the entry's encoding
      * cannot express is reported rather than translated.
      */
    enum Amount derives CanEqual:
        /** A bound in model tokens, for a wire that accepts a reasoning budget. */
        case Budget(tokens: Int)

        /** A level word from the wire's own levels. Not a kyo vocabulary: the levels genuinely differ
          * between endpoints, so a shared set would be a fiction.
          */
        case Level(value: String)
    end Amount

    /** How an entry's reasoning is expressed on the wire, declared per catalog entry.
      *
      * This says what the REQUEST can state under the conditions this module sends (a result tool is
      * always present), deliberately narrower than what a model can do: an endpoint that grades
      * reasoning only when no tool rides is, here, an endpoint that cannot be graded.
      *
      * The distinction between an encoding that ACTIVATES with a field and one that does not is load
      * bearing beyond byte-shaping: two wires refuse a forced tool choice alongside a reasoning
      * activation field, so the result tool is forced exactly when no activation field rides.
      *
      *   - `TokenBudget`: a budget field rides and bounds reasoning, so the output ceiling can be sized
      *     as that budget plus room for the result.
      *   - `Adaptive`: an activation field rides and the model sizes its own depth. Nothing bounds it,
      *     so reasoning shares the output ceiling and the model's own maximum is the only ceiling that
      *     does not manufacture avoidable stops.
      *   - `GradedLevel`: a level field rides, carrying a word from `values`. `default` is the level a
      *     request states when the caller states none, and always rides; it is not a claim about the
      *     endpoint's own default. Declared per entry, since a set of words does not say which the
      *     endpoint applies or which is lowest.
      *   - `Managed`: NO activation field exists. The wire reasons on its own; the amount cannot be
      *     stated, only the off encoding can suppress it.
      *   - `Unavailable`: this entry does not reason. Off is satisfied without sending anything, and
      *     reasoning surfacing on such an entry means the declaration is wrong.
      */
    enum ReasoningEncoding derives CanEqual:
        case TokenBudget
        case Adaptive
        case GradedLevel(values: Chunk[String], default: String)
        case Managed
        case Unavailable
    end ReasoningEncoding

    /** How a wire surfaces a tool call it judges invalid, declared per provider.
      *
      * The eval loop already repairs a malformed tool call by feeding the failure forward for the next
      * turn to correct. That assumes the call comes back at all. A wire that validates first answers
      * with a rejection instead, and the loop never sees the turn it would have repaired, so a
      * well-formed ask fails outright on one bad sample. The same rejection covers a forced call
      * answered with prose, so a wire declaring it reports both through one status.
      */
    enum InvalidToolCalls derives CanEqual:
        /** The call comes back as the model produced it; the eval loop reads and repairs it. */
        case Returned

        /** The endpoint refuses the request rather than returning the call, reporting `code` in the error
          * body's `error.code` field. The code discriminates a rejected tool call from an ordinary bad
          * request, both of which arrive as 400.
          */
        case Rejected(code: String)
    end InvalidToolCalls

    /** Whether the wire honors a forced tool choice while reasoning is active.
      *
      * Some wires refuse the pair outright, and refusing is the whole request: "tool_choice 'required'
      * is incompatible with thinking enabled", "Thinking mode does not support this tool_choice". Others
      * compel the call regardless.
      *
      * Declared because it is not derivable. Reading it off whether an activation FIELD rides is wrong
      * both ways: a wire that reasons with nothing stated would read as idle, and one that states a
      * level and honors the pair would read as refusing. Per ENTRY, not per provider: one endpoint
      * refuses while its model's thinking is on and honors the pair for the sibling that takes a level
      * word instead.
      */
    enum ForcedToolChoice derives CanEqual:
        /** The wire compels the call whatever reasoning is doing. */
        case Honored

        /** The wire rejects a forced choice while reasoning is active, so the request can only ask. */
        case RefusedWhileReasoning
    end ForcedToolChoice

    /** How many system instructions the wire delivers, declared per provider.
      *
      * A wire carrying a single system instruction does not refuse the extras: it answers 200, keeps
      * one, and charges nothing for the rest, so a caller whose instructions went missing sees a
      * plausible reply and no signal. Measured on one endpoint: a system message outside the surviving
      * position added zero input tokens, while the identical text in that position cost several hundred.
      *
      * Declared here because only the provider knows, and the completion impls must not: nothing reads a
      * provider name to shape a request.
      */
    enum SystemMessages derives CanEqual:
        /** Every system message is delivered, in the order given. */
        case AllDelivered

        /** One system message is delivered. The rest reach the model as user turns, since a turn that
          * arrives with the wrong role still arrives, and one that is dropped never does.
          */
        case FirstOnly
    end SystemMessages

    /** How an endpoint says "do not reason", declared per provider.
      *
      * A wire that reasons by default has to be told to stop, and endpoints disagree about how: one
      * omits the activation block, one takes a deactivation object, one takes a level word meaning none,
      * a harness takes an environment switch. Sending the wrong encoding is either refused or, worse,
      * ignored while the model keeps reasoning under a ceiling sized for a reply that does not.
      *
      * Provider granularity is safe only because an [[ReasoningEncoding.Unavailable]] entry never sends
      * off bytes at all. The same endpoint can offer reasoning on one model and reject the same off
      * field on another, so the entry's encoding gates the provider's.
      */
    enum ReasoningOff derives CanEqual:
        /** Send no activation field; absence is what "off" means on this wire. */
        case Omit

        /** Send the activation object carrying an explicit deactivation type. */
        case ThinkingDisabled

        /** Send the level field carrying this endpoint's word for off (for example "none"); reasoning
          * stops. The word is declared, so an endpoint whose off word is not "none" is expressible.
          */
        case Level(value: String)

        /** Export the harness's disable switch. */
        case EnvSwitch

        /** This endpoint refuses every disable request; send `lowest`, the least-effort level it accepts.
          *
          * The model still reasons, the request counts as reasoning-active, and a caller asking for off is
          * warned it cannot be honored. `lowest` carries the word rather than deriving it from a level
          * list's order, which no declaration states. Measured on an endpoint whose models reason with no
          * field set and refuse every word for none by name.
          */
        case CannotDisable(lowest: String)
    end ReasoningOff

    /** Which request field an endpoint reads the output ceiling from.
      *
      * The OpenAI-compatible endpoints do not agree. One family reads `max_completion_tokens` and
      * treats the older `max_tokens` as deprecated, refusing it outright on current models even when
      * both are sent. Another reads only `max_tokens`. An endpoint handed a field it does not recognize
      * does not reject the request: it drops the field and applies its own default, so a configured
      * ceiling goes missing in silence and the reply stops where the caller never asked.
      *
      * This is a property of the endpoint's request parser, not of any model, which is why it is
      * declared on the provider while the thinking kind and temperature acceptance are per entry. There
      * is deliberately no default: defaulting to either name silently reproduces this very failure on
      * every endpoint of the other family.
      */
    enum OutputTokensParam derives CanEqual:
        case MaxCompletionTokens, MaxTokens

    /** A provider: its display name, base URL, credential behavior, and completion backend, plus the
      * wire-behavior declarations its entries default from.
      *
      * DECLARING A NEW ENTRY. The shapes copy from a sibling; the VALUES must be measured, because the
      * defaults encode the OpenAI family's behavior and inherit silently. Eight probes, each settling one
      * declaration, against the endpoint being declared (not an aggregator routing to it):
      *
      *   1. Plain request: does `usage` count reasoning tokens, or does reasoning arrive only as content?
      *      Reasoning surfacing at all means the encoding is not `Unavailable`.
      *   2. Which effort levels are accepted, and which are refused BY NAME? The accepted set is `values`;
      *      an undocumented word the endpoint tolerates is not a `value`, since it can map to anything.
      *   3. What actually disables reasoning: omission (`Omit`), a thinking-disabled object
      *      (`ThinkingDisabled`), a level word (`Level(word)`), or nothing (`CannotDisable(lowest)`)?
      *   4. A forced tool choice with reasoning active: honored (`Honored`) or refused
      *      (`RefusedWhileReasoning`)?
      *   5. Two system messages: do both bill input tokens (`AllDelivered`), or only one (`FirstOnly`)?
      *   6. A malformed tool call: returned in the reply (`Returned`) or refused as a 400 whose
      *      `error.code` is the discriminator (`Rejected(code)`)?
      *   7. An image content part: accepted, or refused (`acceptsImages = false`)?
      *   8. An over-large ceiling ask: does the refusal name a real output bound (`Verified`), or does it
      *      validate a parameter / restate the context window / accept anything (`Unverified`)?
      */
    abstract class Provider(
        val name: String,
        val baseUrl: String,
        val keyName: String,
        val completion: Completion,
        val outputTokensParam: OutputTokensParam,
        val reasoningOff: ReasoningOff,
        val systemInstructions: SystemMessages = SystemMessages.AllDelivered,
        val invalidToolCalls: InvalidToolCalls = InvalidToolCalls.Returned,
        val forcedToolChoice: ForcedToolChoice = ForcedToolChoice.Honored,
        val usesApiKey: Boolean = true
    ):
        val orgKey: String = keyName + "_ORG"
        def default: Config

        /** The provider's cheap-tier catalog entry, used for the degraded warm summarizer route. Concrete
          * with a `= default` fallback: a `Provider` subclass that does not override it runs on `default`;
          * the built-in catalogs override it with their cheap entry.
          */
        def small: Config = default

        /** Every named catalog entry, listed explicitly: a new model `val` is added here in the same
          * diff, which is what puts it under the catalog-wide config tests.
          */
        private[kyo] def entries: Chunk[Config]
    end Provider

    object Provider:
        def all: Chunk[Provider] =
            Chunk(Anthropic, OpenAI, DeepSeek, Gemini, Groq, XAI, Moonshot, Baseten, OpenRouter, ClaudeCode, Codex)
        def apiKeyProviders: Chunk[Provider] =
            all.filter(_.usesApiKey)
        def defaultCandidates: Chunk[Provider] =
            Chunk(ClaudeCode, Codex, Anthropic, OpenAI, DeepSeek, Gemini, Groq, XAI, Moonshot, Baseten, OpenRouter)
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
            case "xai" | "grok"                           => Present(XAI)
            case "moonshot" | "kimi"                      => Present(Moonshot)
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
            Completion.anthropic,
            // Inert here: this wire carries its own required ceiling field, so neither OpenAI-family
            // name is ever sent. Declared because the type requires it.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.Omit,
            // This wire refuses a forced tool choice while thinking is on, for every
            // model it serves; measured, and asserted wire-wide below. Provider-level
            // because it is an API constraint, not a per-model one.
            forcedToolChoice = ForcedToolChoice.RefusedWhileReasoning,
            // One out-of-band system slot: the leading system run merges into it and later system
            // messages arrive as user turns, the shared transform's job, not this impl's.
            systemInstructions = SystemMessages.FirstOnly
        ):
        // Declared facts, with provenance. maxOutputTokens comes from the provider's own limit
        // response (a request above it is refused and names the maximum). The thinking kind and
        // temperature acceptance are this provider's documented per-generation contract.
        val opus_4_8: Config = catalog(
            this,
            "claude-opus-4-8",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Verified(128000),
            ReasoningEncoding.Adaptive,
            acceptsTemperature = false,
            acceptsImages = true
        )
        val sonnet_4_6: Config = catalog(
            this,
            "claude-sonnet-4-6",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Verified(128000),
            ReasoningEncoding.TokenBudget,
            acceptsTemperature = true,
            acceptsImages = true
        )
        val haiku_4_5: Config = catalog(
            this,
            "claude-haiku-4-5-20251001",
            contextWindow = 200000,
            outputMaximum = OutputMaximum.Verified(64000),
            ReasoningEncoding.TokenBudget,
            acceptsTemperature = true,
            acceptsImages = true
        )
        val fable_5: Config = catalog(
            this,
            "claude-fable-5",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Verified(128000),
            ReasoningEncoding.Adaptive,
            acceptsTemperature = false,
            acceptsImages = true
        )
        val sonnet_5: Config = catalog(
            this,
            "claude-sonnet-5",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Verified(128000),
            ReasoningEncoding.Adaptive,
            acceptsTemperature = false,
            acceptsImages = true
        )
        def default: Config        = opus_4_8
        override def small: Config = haiku_4_5
        private[kyo] val entries: Chunk[Config] =
            Chunk(opus_4_8, sonnet_4_6, haiku_4_5, fable_5, sonnet_5)
    end Anthropic

    case object OpenAI extends Provider(
            "OpenAI",
            "https://api.openai.com/v1",
            "OPENAI_API_KEY",
            Completion.openAI,
            // Verified against the endpoint: it refuses `max_tokens` on a current model, and refuses
            // it even when `max_completion_tokens` rides alongside, so exactly one name can be sent.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.Level("none")
        ):
        // Declared facts, with provenance. The output maximum comes from the provider's own limit
        // response; temperature acceptance from whether a request carrying one is refused for that
        // parameter.
        //
        // The 5.6 generation is deliberately absent: it refuses function tools while reasoning is on,
        // and every generation here is reached through a forced result tool, so an entry would fail on
        // first use. The refusal's ways out (another endpoint this backend does not speak, or no
        // reasoning, which gives up what makes the model worth choosing) do not apply. The omission
        // reverses when the endpoint supports tools with reasoning.
        val gpt_5_5: Config =
            catalog(
                this,
                "gpt-5.5",
                contextWindow = 1050000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.Managed,
                acceptsTemperature = false,
                acceptsImages = true
            )
        val gpt_5_4: Config =
            catalog(
                this,
                "gpt-5.4",
                contextWindow = 1050000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.Managed,
                acceptsTemperature = true,
                acceptsImages = true
            )
        val gpt_5_4_mini: Config =
            catalog(
                this,
                "gpt-5.4-mini",
                contextWindow = 400000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.Managed,
                acceptsTemperature = true,
                acceptsImages = true
            )
        def default: Config        = gpt_5_4
        override def small: Config = gpt_5_4_mini
        private[kyo] val entries: Chunk[Config] =
            Chunk(gpt_5_5, gpt_5_4, gpt_5_4_mini)
    end OpenAI

    case object DeepSeek extends Provider(
            "DeepSeek",
            "https://api.deepseek.com/v1",
            "DEEPSEEK_API_KEY",
            Completion.openAI,
            // Verified against the endpoint: handed `max_completion_tokens` it drops the field
            // without complaint and stops at its own default; handed `max_tokens` it honors the ask.
            OutputTokensParam.MaxTokens,
            ReasoningOff.ThinkingDisabled,
            forcedToolChoice = ForcedToolChoice.RefusedWhileReasoning
        ):
        // Declared facts, with provenance. The output maximum is what the provider's own limit response
        // names when a request exceeds it, resolving the rounded figure its pricing page publishes. The
        // same model served elsewhere carries a different number, so the aggregator entry is not this one.
        //
        // Neither entry reads image content parts. Measured, not assumed: handed one, this endpoint
        // refuses the whole request naming the JSON variant it did not expect rather than the attached
        // picture.
        //
        // Both entries grade reasoning by level. The levels are the endpoint's own, quoted from the
        // error it returns for a value outside them ("Invalid value: ... Supported values are: high,
        // low, medium, max, xhigh"); a neighbouring endpoint's levels accept words this one refuses.
        //
        // The default is declared, not computed: the levels are words, none saying which asks for the
        // least, so "lowest" is a judgment made when the entry is written. `low` is this wire's lowest
        // ACTIVE level, not a guarantee it fits a given ceiling.
        //
        // Reasoning activates by field, and this wire refuses a forced tool choice whenever an
        // activation field rides, so these entries take the unforced turn shape the harnesses already
        // run. Switching reasoning off restores the forced choice and sends the documented deactivation
        // object.
        private val deepSeekLevels: Chunk[String] = Chunk("low", "medium", "high", "xhigh", "max")

        val deepseek_v4_flash: Config = catalog(
            this,
            "deepseek-v4-flash",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(393216),
            ReasoningEncoding.GradedLevel(deepSeekLevels, default = "low"),
            acceptsTemperature = true,
            acceptsImages = false
        )
        val deepseek_v4_pro: Config = catalog(
            this,
            "deepseek-v4-pro",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(393216),
            ReasoningEncoding.GradedLevel(deepSeekLevels, default = "low"),
            acceptsTemperature = true,
            acceptsImages = false
        )
        def default: Config        = deepseek_v4_flash
        override def small: Config = deepseek_v4_flash
        private[kyo] val entries: Chunk[Config] =
            Chunk(deepseek_v4_flash, deepseek_v4_pro)
    end DeepSeek

    case object Gemini extends Provider(
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "GEMINI_API_KEY",
            Completion.openAI,
            // UNVERIFIED. This endpoint's compatibility documentation names neither field, and no live
            // request has settled it. The value below is the one already being sent, a placeholder that
            // preserves today's behavior, not a fact.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.Omit,
            // Measured, since the compatibility docs do not cover this: a ~2000-character system message
            // outside the surviving position added ZERO input tokens, the identical text in that
            // position cost 362. One system instruction is delivered; the extras are discarded silently
            // with a 200.
            SystemMessages.FirstOnly
        ):
        // Declared facts, from each model's own page. Every Gemini text model publishes the same 65536
        // output maximum against a 1048576 context window, so the two are never equal here. 2.5 Pro
        // stays because every Gemini 3 Pro variant is preview-only, leaving it the one stable Pro-tier
        // entry.
        val gemini_3_5_flash: Config = catalog(
            this,
            "gemini-3.5-flash",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.Unavailable,
            acceptsTemperature = true,
            acceptsImages = true
        )
        val gemini_3_1_flash_lite: Config = catalog(
            this,
            "gemini-3.1-flash-lite",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.Unavailable,
            acceptsTemperature = true,
            acceptsImages = true
        )
        val gemini_2_5_pro: Config = catalog(
            this,
            "gemini-2.5-pro",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.Unavailable,
            acceptsTemperature = true,
            acceptsImages = true
        )
        def default: Config        = gemini_3_5_flash
        override def small: Config = gemini_3_1_flash_lite
        private[kyo] val entries: Chunk[Config] =
            Chunk(gemini_3_5_flash, gemini_3_1_flash_lite, gemini_2_5_pro)
    end Gemini

    case object Groq extends Provider(
            "Groq",
            "https://api.groq.com/openai/v1",
            "GROQ_API_KEY",
            Completion.openAI,
            // The provider's own reference names this field as current and marks `max_tokens`
            // deprecated.
            OutputTokensParam.MaxCompletionTokens,
            // Measured: omitting the field still spends reasoning tokens, and every word for none is
            // refused by name ("must be one of low, medium, or high"). Reasoning cannot be switched
            // off here, so a request for none gets its lowest declared level.
            ReasoningOff.CannotDisable("low"),
            SystemMessages.AllDelivered,
            // Measured, and reported across the ecosystem: this wire answers 400 tool_use_failed
            // both when the model emits malformed tool arguments and when a forced call comes
            // back as prose, so the call never reaches the loop that would repair it. Observed
            // intermittent here: the same nested-schema request failed once in three runs.
            InvalidToolCalls.Rejected("tool_use_failed")
        ):
        // Declared facts, with provenance. Groq publishes a context window and a separate max
        // completion limit per model, and for most the two differ; taking the window as the output
        // maximum overstates it and the request is refused. Values below are Groq's own.
        //
        // Reasoning and image support were measured, both models alike, because the entries declared the
        // opposite and a run reported the contradiction: a plain request with no reasoning field spent
        // 18 reasoning tokens and carried a reasoning field, so the models reason by default. The levels
        // are the accepted set, rejected outside it by name. A content array is refused with "content
        // must be a string", so neither model reads image parts.
        private val groqLevels: Chunk[String] = Chunk("low", "medium", "high")

        val gpt_oss_120b: Config = catalog(
            this,
            "openai/gpt-oss-120b",
            contextWindow = 131072,
            outputMaximum = OutputMaximum.Verified(65536),
            // Default "medium" because an omitted field spends what medium spends (18 tokens against
            // low's 13), so it names the level the endpoint already applies rather than changing it.
            ReasoningEncoding.GradedLevel(groqLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false
        )
        val gpt_oss_20b: Config = catalog(
            this,
            "openai/gpt-oss-20b",
            contextWindow = 131072,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.GradedLevel(groqLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false
        )
        def default: Config        = gpt_oss_120b
        override def small: Config = gpt_oss_20b
        private[kyo] val entries: Chunk[Config] =
            Chunk(gpt_oss_120b, gpt_oss_20b)
    end Groq

    case object Baseten extends Provider(
            "Baseten",
            "https://inference.baseten.co/v1",
            "BASETEN_API_KEY",
            Completion.openAI,
            // UNVERIFIED, and more weakly than Gemini's: this endpoint's documentation has not been
            // checked for either field, a different position from having been read and found silent. The
            // value below is the one already being sent, a placeholder that preserves today's behavior,
            // not a fact.
            OutputTokensParam.MaxCompletionTokens,
            // Provider default set to the measured majority of this endpoint's entries, so an
            // un-overridden future entry inherits a value that was measured, not a placeholder.
            ReasoningOff.Level("none")
        ):
        // Declared facts, from Baseten's own Model APIs table. A limit is the host's, not the model's:
        // this host serves DeepSeek V4 Pro with a 262k window rather than the 1M it carries on
        // DeepSeek's own API, so this entry is smaller than the DeepSeek one. Reasoning is reported as
        // CONTENT, not counted tokens: a plain turn carries a reasoning body while usage reports zero,
        // so an entry declaring no reasoning was contradicted by the reply's content alone. The default
        // is kyo's ask, not a published per-model fact.
        private val basetenLevels: Chunk[String] = Chunk("low", "medium", "high")

        val deepseek_v4_pro: Config = catalog(
            this,
            "deepseek-ai/DeepSeek-V4-Pro",
            contextWindow = 262144,
            outputMaximum = OutputMaximum.Unverified(262144),
            ReasoningEncoding.GradedLevel(basetenLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: a request stating none returns no reasoning body and zero reasoning tokens, against 80 for a plain turn. An image part is refused outright.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        val gpt_oss_120b: Config = catalog(
            this,
            "openai/gpt-oss-120b",
            contextWindow = 131072,
            outputMaximum = OutputMaximum.Unverified(131072),
            ReasoningEncoding.GradedLevel(basetenLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: a request stating none cuts the reasoning body from 94 characters to 5; this endpoint counts no reasoning tokens for the model at all. An image part is refused outright.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        def default: Config        = deepseek_v4_pro
        override def small: Config = gpt_oss_120b
        private[kyo] val entries: Chunk[Config] =
            Chunk(deepseek_v4_pro, gpt_oss_120b)
    end Baseten

    case object OpenRouter extends Provider(
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY",
            Completion.openAI,
            // The provider's own reference documents only this field for the output length.
            OutputTokensParam.MaxTokens,
            // Provider default set to the measured majority of this endpoint's entries, so an
            // un-overridden future entry inherits a value that was measured, not a placeholder.
            ReasoningOff.Level("none")
        ):
        // Declared facts, with provenance. This aggregator publishes a per-model completion limit
        // without a credential, and the entries below take their sizes from it.
        //
        // The ids carry no routing suffix. A suffix picks which host serves the model, and hosts
        // disagree on the output maximum: one Kimi model runs 16384 to 262144 across twenty hosts, one
        // DeepSeek model 32768 to 1048576. Selecting a host by speed would leave the output maximum
        // unknowable when the entry is written, so the unsuffixed id and its published model-level limit
        // are used.
        //
        // The aggregator publishes ONE normalized level set for every model it routes to, descending
        // from max to none; ascending, with the disabling level left to the off-encoding, it is the set
        // below. The default is kyo's ask, not a provider fact: an unstated effort passes through to
        // whichever origin serves the model, and no per-model default is published.
        private val openRouterLevels: Chunk[String] = Chunk("minimal", "low", "medium", "high", "xhigh", "max")

        val deepseek_v4_pro: Config = catalog(
            this,
            "deepseek/deepseek-v4-pro",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(384000),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: a request stating none spends zero reasoning tokens.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        val minimax_m3: Config = catalog(
            this,
            "minimax/minimax-m3",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(512000),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = true,
            // Measured against this endpoint: a request stating none takes reasoning from 28 tokens to zero.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        val kimi_k2_6: Config = catalog(
            this,
            "moonshotai/kimi-k2.6",
            contextWindow = 262144,
            outputMaximum = OutputMaximum.Unverified(262144),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = true,
            // Measured against this endpoint: a request stating none takes reasoning from 43 tokens to zero.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        val qwen3_7_max: Config = catalog(
            this,
            "qwen/qwen3.7-max",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: a request stating none takes reasoning from 162 tokens to zero.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        val gemini_3_5_flash: Config = catalog(
            this,
            "google/gemini-3.5-flash",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(65536),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: none is refused as mandatory here, and the lowest level is accepted.
            reasoningOff = Present(ReasoningOff.CannotDisable("minimal"))
        )
        val llama_4_maverick: Config = catalog(
            this,
            "meta-llama/llama-4-maverick",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(16384),
            ReasoningEncoding.Unavailable,
            acceptsTemperature = true,
            acceptsImages = true
        )
        val gpt_oss_120b: Config = catalog(
            this,
            "openai/gpt-oss-120b",
            contextWindow = 131072,
            outputMaximum = OutputMaximum.Unverified(131072),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: none is refused as mandatory here, and the lowest level is accepted.
            reasoningOff = Present(ReasoningOff.CannotDisable("minimal"))
        )
        val gpt_oss_20b: Config = catalog(
            this,
            "openai/gpt-oss-20b",
            contextWindow = 131072,
            outputMaximum = OutputMaximum.Unverified(131072),
            ReasoningEncoding.GradedLevel(openRouterLevels, default = "medium"),
            acceptsTemperature = true,
            acceptsImages = false,
            // Measured against this endpoint: none is refused as mandatory here, and the lowest level is accepted.
            reasoningOff = Present(ReasoningOff.CannotDisable("minimal"))
        )
        def default: Config        = deepseek_v4_pro
        override def small: Config = gpt_oss_20b
        private[kyo] val entries: Chunk[Config] =
            Chunk(
                deepseek_v4_pro,
                minimax_m3,
                kimi_k2_6,
                qwen3_7_max,
                gemini_3_5_flash,
                llama_4_maverick,
                gpt_oss_120b,
                gpt_oss_20b
            )
    end OpenRouter

    case object XAI extends Provider(
            "xAI",
            "https://api.x.ai/v1",
            "XAI_API_KEY",
            Completion.openAI,
            // The provider's own reference names this field as current and marks `max_tokens`
            // deprecated.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.Omit
        ):
        // Declared facts, with provenance. xAI publishes a context window per model and no separate
        // output maximum, on its own docs and its first-party endpoint. The output maximum below is
        // therefore the context window because no narrower bound exists, not because one is unknown; a
        // published one gets that value here. The levels are the set the reference documents, low
        // through high; an undocumented word this endpoint also accepts is deliberately absent, since a
        // value outside the published set can map to anything.
        //
        // Default "high" because the reference states an omitted field defaults to high, so naming it
        // asks for the level already applied rather than changing it.
        private val xaiLevels: Chunk[String] = Chunk("low", "medium", "high")

        val grok_4_5: Config = catalog(
            this,
            "grok-4.5",
            contextWindow = 500000,
            outputMaximum = OutputMaximum.Unverified(500000),
            ReasoningEncoding.GradedLevel(xaiLevels, default = "high"),
            acceptsTemperature = true,
            acceptsImages = true,
            // Measured and documented alike: this model refuses the word for none ("does not support
            // `reasoning_effort` value `none`") and the reference states reasoning cannot be disabled,
            // so a request for none gets its lowest declared level.
            reasoningOff = Present(ReasoningOff.CannotDisable("low"))
        )
        val grok_4_3: Config = catalog(
            this,
            "grok-4.3",
            contextWindow = 1000000,
            outputMaximum = OutputMaximum.Unverified(1000000),
            ReasoningEncoding.GradedLevel(xaiLevels, default = "high"),
            acceptsTemperature = true,
            acceptsImages = true,
            // Measured: this model accepts the word for none and spends ZERO reasoning tokens for it, so
            // reasoning genuinely switches off here. The reference covers its siblings and says none is
            // unsupported, which holds for them and not this one; the entry states what its own model
            // does, which is why the encoding lives here rather than on the provider.
            reasoningOff = Present(ReasoningOff.Level("none"))
        )
        def default: Config = grok_4_5
        private[kyo] val entries: Chunk[Config] =
            Chunk(grok_4_5, grok_4_3)
    end XAI

    case object Moonshot extends Provider(
            "Moonshot",
            "https://api.moonshot.ai/v1",
            "MOONSHOT_API_KEY",
            Completion.openAI,
            // The provider's own reference names this field as current and marks `max_tokens`
            // deprecated.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.Omit
        ):
        // Declared facts, with provenance, and the two entries do NOT share one. The first's chat
        // reference states its ceiling outright ("defaults to 131072 and can be set up to 1048576"), so
        // its maximum is published, not assumed, and coincides with the context window because the
        // provider says so, not for want of a number. The second has no published output maximum, so it
        // carries the context window for want of a narrower bound and stays unverified. The same family
        // carries maximums from 16384 to 262144 across hosts, which is why these entries take this
        // provider's own numbers.
        val kimi_k3: Config = catalog(
            this,
            "kimi-k3",
            contextWindow = 1048576,
            outputMaximum = OutputMaximum.Verified(1048576),
            // The levels and default are the reference's own enum for this model. The endpoint also
            // accepts undocumented words without complaint, which is why the published set is declared:
            // an accepted but undocumented value can map to anything.
            ReasoningEncoding.GradedLevel(Chunk("low", "high", "max"), default = "max"),
            acceptsTemperature = true,
            acceptsImages = true,
            // The reference states this model always thinks and that reasoning cannot be disabled, so a
            // request for none gets its lowest declared level.
            reasoningOff = Present(ReasoningOff.CannotDisable("low"))
        )
        val kimi_k2_6: Config = catalog(
            this,
            "kimi-k2.6",
            contextWindow = 262144,
            outputMaximum = OutputMaximum.Unverified(262144),
            // The reference documents no effort control: thinking is on by default and the only control
            // is switching it off. The wire sizes reasoning itself with no field to state an amount, and
            // reasons whether or not the request says anything.
            ReasoningEncoding.Managed,
            acceptsTemperature = true,
            acceptsImages = true,
            // Measured and documented alike: the thinking object's disabled type drops a reply from 49
            // reasoning tokens to 1 and removes the reasoning field. Omitting the block does NOT switch
            // it off here.
            reasoningOff = Present(ReasoningOff.ThinkingDisabled),
            // Measured: with thinking on, a forced choice is refused outright ("tool_choice 'required'
            // is incompatible with thinking enabled"); with it disabled the same request is honored. The
            // sibling model, which takes a level word instead, honors the pair, so this is declared per
            // entry rather than for the provider.
            forcedToolChoice = Present(ForcedToolChoice.RefusedWhileReasoning)
        )
        def default: Config        = kimi_k3
        override def small: Config = kimi_k2_6
        private[kyo] val entries: Chunk[Config] =
            Chunk(kimi_k3, kimi_k2_6)
    end Moonshot

    case object ClaudeCode extends Provider(
            "Claude Code",
            "",
            "CLAUDE_CODE",
            Completion.claudeCode,
            // Inert here: this harness carries its ceiling to the command's environment instead of a
            // request body, so neither OpenAI-family name is ever sent. Declared because the type
            // requires it.
            OutputTokensParam.MaxCompletionTokens,
            ReasoningOff.EnvSwitch,
            usesApiKey = false
        ):
        val opus: Config =
            catalog(
                this,
                "opus",
                contextWindow = 1000000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.TokenBudget,
                acceptsTemperature = true,
                acceptsImages = true
            )
        val sonnet: Config =
            catalog(
                this,
                "sonnet",
                contextWindow = 1000000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.TokenBudget,
                acceptsTemperature = true,
                acceptsImages = true
            )
        val haiku: Config =
            catalog(
                this,
                "haiku",
                contextWindow = 200000,
                outputMaximum = OutputMaximum.Verified(64000),
                ReasoningEncoding.TokenBudget,
                acceptsTemperature = true,
                acceptsImages = true
            )
        def default: Config        = sonnet
        override def small: Config = haiku
        private[kyo] val entries: Chunk[Config] =
            Chunk(opus, sonnet, haiku)
    end ClaudeCode

    case object Codex extends Provider(
            "Codex",
            "",
            "CODEX",
            Completion.codex,
            // Inert here: this harness sends no ceiling at all, so neither OpenAI-family name is
            // ever sent. Declared because the type requires it.
            OutputTokensParam.MaxCompletionTokens,
            // This harness exposes no way to say "do not reason": nothing in its request shape carries
            // the instruction, and declaring an encoding it does not implement would silence the very
            // warning that reports the gap.
            ReasoningOff.Omit,
            usesApiKey = false
        ):
        // The empty model name asks the harness to pick, so no one model's sizes describe this entry.
        // Unlike the other backends, this one sends no output ceiling, so the sizes below reach no wire
        // and serve only the catalog-wide invariants; they are nominal, not a measured limit, which is
        // why the two are equal here.
        val auto: Config =
            catalog(
                this,
                "",
                contextWindow = 400000,
                outputMaximum = OutputMaximum.Unverified(400000),
                ReasoningEncoding.Managed,
                acceptsTemperature = true,
                acceptsImages = true
            )
        val gpt_5_5: Config =
            catalog(
                this,
                "gpt-5.5",
                contextWindow = 1050000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.Managed,
                acceptsTemperature = false,
                acceptsImages = true
            )
        val gpt_5_4: Config =
            catalog(
                this,
                "gpt-5.4",
                contextWindow = 1050000,
                outputMaximum = OutputMaximum.Verified(128000),
                ReasoningEncoding.Managed,
                acceptsTemperature = true,
                acceptsImages = true
            )
        def default: Config = auto
        private[kyo] val entries: Chunk[Config] =
            Chunk(auto, gpt_5_5, gpt_5_4)
    end Codex

end Config
