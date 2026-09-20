package kyo.ai

import kyo.*
import kyo.ai.decider.TypeSafeDecider

/** A dedicated decision provider for [[kyo.Decider]] questions: which provider, and its endpoint, key, model
  * and transport settings.
  *
  * A `Config` carries one in its `decider` field, absent by default: then the config's own completion
  * provider answers decisions by structured output, with the model's own probability estimates. Setting
  * one routes decisions to that provider's model instead, one built to answer these questions with
  * calibrated probabilities: `LLM.run(_.decider(AI.DeciderConfig.TypeSafe.default))(...)` for a run,
  * `AI.withConfig(_.decider(...))` for a scope, `decider(Absent)` to return to the config's own model.
  *
  * Shaped like [[Config]]: a [[DeciderConfig.Provider]] names a backend and its endpoint facts, each
  * provider exposes pure catalog entries (key absent, filled at use) and a `default`, and the builders are
  * copy-on-write. The key is read from the provider's variable (system property first, then environment)
  * at `Config.default` and `Config.init`, or at first use when the decider was set afterwards.
  *
  * A decision's `timeout`, `meter` and `retrySchedule` default to the surrounding `Config`'s (`Absent`),
  * and can be set apart from them: a decision endpoint answers in a fraction of a second and has its own
  * rate limits, so the knobs sized for a completion provider are rarely the right ones. Providers are
  * extended only inside kyo: a new one is a change to kyo-ai, not a user extension.
  *
  * @see
  *   [[kyo.Decider]] for the decisions this configures
  * @see
  *   [[Config]] for the completion config that carries it
  */
final case class DeciderConfig private (
    provider: DeciderConfig.Provider,
    apiUrl: String,
    apiKey: Maybe[String],
    modelName: String,
    timeout: Maybe[Duration],
    meter: Maybe[Meter],
    retrySchedule: Maybe[Schedule]
) derives CanEqual:
    /** Re-points the provider at another endpoint (a proxy, a gateway). */
    def apiUrl(url: String): DeciderConfig = copy(apiUrl = url)

    /** Sets the key explicitly, instead of reading the provider's variable. */
    def apiKey(key: String): DeciderConfig = copy(apiKey = Present(key))

    /** Picks another model of the same provider, a versioned id included. */
    def modelName(name: String): DeciderConfig = copy(modelName = name)

    /** Bounds one decision, retries included, apart from the completion timeout. */
    def timeout(timeout: Duration): DeciderConfig = copy(timeout = Present(timeout))

    /** Bounds concurrent decisions, apart from the completion meter. */
    def meter(meter: Meter): DeciderConfig = copy(meter = Present(meter))

    /** Drives retries of a transient decision failure, apart from the completion schedule. */
    def retrySchedule(schedule: Schedule): DeciderConfig = copy(retrySchedule = Present(schedule))

    // Attaches a resolved key (Absent leaves the field as it is), the decider counterpart of
    // Config.credentialed; private so a catalog entry stays pure from the outside.
    private[kyo] def credentialed(key: Maybe[String]): DeciderConfig =
        if key.isDefined then copy(apiKey = key) else this
end DeciderConfig

object DeciderConfig:

    /** A decision provider and its endpoint facts, the decider counterpart of [[Config.Provider]]: its
      * name (for logs and failure messages), base URL, the system property or environment variable that
      * supplies its key, and the backend that speaks its wire.
      */
    abstract class Provider(
        val name: String,
        val baseUrl: String,
        val keyName: String,
        private[kyo] val backend: Decider.Backend
    ):
        def default: DeciderConfig

        /** Every named catalog entry, listed explicitly, so a new entry lands under the catalog-wide tests. */
        private[kyo] def entries: Chunk[DeciderConfig]
    end Provider

    object Provider:
        def all: Chunk[Provider] = Chunk(TypeSafe)

    /** TypeSafe AI's System One endpoint: every question kind, with calibrated probabilities. Its models
      * are the Jev family: `jevLatest` is the default, `jevPreview` the next version.
      */
    case object TypeSafe extends Provider("typesafe", "https://api.typesafe.ai/v1", "TYPESAFE_API_KEY", TypeSafeDecider):
        val jevLatest: DeciderConfig                   = catalog(this, "jev-latest")
        val jevPreview: DeciderConfig                  = catalog(this, "jev-preview")
        def default: DeciderConfig                     = jevLatest
        private[kyo] val entries: Chunk[DeciderConfig] = Chunk(jevLatest, jevPreview)
    end TypeSafe

    /** A pure entry: key absent, filled at use; transport settings inherited from the `Config`. */
    private[kyo] def catalog(provider: Provider, modelName: String): DeciderConfig =
        DeciderConfig(provider, provider.baseUrl, Absent, modelName, Absent, Absent, Absent)

end DeciderConfig
