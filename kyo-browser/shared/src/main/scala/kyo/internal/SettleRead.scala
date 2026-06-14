package kyo.internal

import kyo.*

/** Settle-on-reads helper: re-samples a single in-page value expression on the active `retrySchedule` until it holds constant across
  * `assertionStabilityWindow`, then lifts the stable string to `A` via `decode`. Unlike `BrowserAssertion.withStability`, which raises on a
  * non-matching FIRST probe, this keeps the value regardless of presence: a settled-ABSENT element produces a stable sentinel string that
  * `decode` maps to the read's twin return (`Absent` / `false` / abort / empty), so absence is detected WITHOUT raising a stability timeout.
  *
  * When `decode` itself aborts (a must-exist read finding an absent element), that abort is treated as an unstable sample and re-tried for the
  * full `retrySchedule` budget before the failure is surfaced to the caller.
  *
  * The whole in-page sampling loop runs inside `StabilitySampler.sampleWindow`'s single `awaitPromise=true` eval, and the bound is
  * the configurable `retrySchedule` from `SessionConfig`, never a hardcoded value.
  */
private[kyo] object SettleRead:

    /** Builds the typed instability failure used to retry the outer schedule when a value changed mid-window. It is a
      * `BrowserAssertionTimedOutException` (a `BrowserReadException`), so the read's `Abort[BrowserReadException]` row carries it.
      */
    private[kyo] def unstable(callee: String, raw: String)(using Frame): BrowserReadException =
        BrowserAssertionTimedOutException(callee, "stable value", raw)

    /** Settles a single read. `valueExpr` is a self-contained JS expression coercible to a string; `decode` maps the stable string to `A`,
      * mapping a settled-absent sentinel to the twin return.
      */
    private[kyo] def settle[A](callee: String, valueExpr: String)(decode: String => A < (Browser & Abort[BrowserReadException]))(using
        Frame
    ): A < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            val window = cfg.assertionStabilityWindow
            Retry[BrowserReadException](cfg.retrySchedule) {
                if window == Duration.Zero then BrowserEval.evalJs(valueExpr).map(decode)
                else
                    StabilitySampler.sampleWindow(valueExpr, window).map {
                        case StabilitySampler.Outcome.Stable(raw)   => decode(raw)
                        case StabilitySampler.Outcome.Unstable(raw) => Abort.fail(unstable(callee, raw))
                    }
            }
        }
end SettleRead
