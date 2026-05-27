package kyo.internal

import kyo.*

/** In-page assertion-stability sampler.
  *
  * An assertion's stability gate must answer "did the probed value hold constant for the whole stability window?". Sampling via N separate
  * CDP `Runtime.evaluate` round-trips spaced `window/N` apart is *aliasing-prone*: each CDP round-trip has non-trivial, load-dependent
  * latency (hundreds of ms on Scala Native under full-suite load), so the samples land at effectively random phases of a fast page flicker
  * spread across multiple seconds of wall-clock. Two consecutive slow samples can both happen to catch the target value while the page is
  * in fact flickering 3↔4 every few ms: a false "stable" verdict. Coarse sampling fundamentally cannot see a flicker faster than the sample
  * spacing, and that spacing is dictated by CDP latency, not by the configured window.
  *
  * Mirroring [[MutationSettlement]], this driver runs the *entire* sampling loop inside a single `awaitPromise`-backed JS eval. The loop
  * runs on the page's main thread and re-evaluates `valueExpr` every [[tickInterval]] (a few ms) for the whole `window`. Because it samples
  * in-page at a fine grain (independent of CDP latency) it observes *every* value the page exhibits during the window. The first time the
  * value differs from the initial sample it returns `unstable` immediately; if the value never changes for the full window it returns
  * `stable`. This is alias-proof: a flicker with any period shorter than the window is caught regardless of how fast it oscillates or how
  * slow the surrounding CDP transport is.
  */
private[kyo] object StabilitySampler:

    /** Outcome of one in-page stability window. */
    private[kyo] enum Outcome derives CanEqual:
        /** The value held constant for the whole window. `value` is that stable value. */
        case Stable(value: String)

        /** The value changed during the window. `value` is the value observed at the moment the change was detected. */
        case Unstable(value: String)
    end Outcome

    /** Runs one stability window in-page: samples `valueExpr` every [[tickInterval]] for `window`, returning [[Outcome.Stable]] if it never
      * changed and [[Outcome.Unstable]] (with the divergent value) the instant it did.
      *
      * `valueExpr` is an arbitrary JS expression evaluating to the probed value (a number, string, or boolean, coerced to a string for
      * comparison). It is evaluated repeatedly inside the page, so it must be cheap and side-effect free; the assertion probes in
      * `Browser.scala` / `ProbesJs` satisfy this (DOM reads only).
      *
      * The whole loop is a single CDP `Runtime.evaluate` with `awaitPromise = true`: the loop's `setTimeout` ticks, the page's own
      * `setInterval` flicker drivers, and the value re-evaluations all cooperate on the page main thread without CDP interleaving: the same
      * constraint [[MutationSettlement.awaitQuiescence]] documents.
      */
    /** JSON wire shape returned by the in-page sampler. `tag` is `"stable"` or `"unstable"`; `value` is the stable value or the divergent
      * value observed at the moment instability was detected.
      */
    private[internal] case class SampleReply(tag: String, value: String) derives Schema

    def sampleWindow(valueExpr: String, window: Duration)(using Frame): Outcome < (Browser & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            val windowMs = window.toMillis
            val tickMs   = cfg.stabilitySampleInterval.toMillis
            // `read()` coerces the probed value to a string and never throws: a transient exception mid-flicker
            // (e.g. a property read on a node that just detached) is itself a form of instability, so it is mapped
            // to a distinct sentinel that compares unequal to any real value and therefore registers as a change.
            val js = s"""(async () => {
                const read = () => { try { return String($valueExpr); } catch (e) { return '\\u0000kyo_probe_threw'; } };
                const sleep = (ms) => new Promise(r => setTimeout(r, ms));
                const first = read();
                const deadlineAt = Date.now() + $windowMs;
                while (Date.now() < deadlineAt) {
                    await sleep($tickMs);
                    const current = read();
                    if (current !== first) return JSON.stringify({tag: 'unstable', value: current});
                }
                return JSON.stringify({tag: 'stable', value: first});
            })()"""
            BrowserEval.evalJsAwaiting(js).map(parseOutcome)
        }
    end sampleWindow

    /** Decodes the JSON `{tag, value}` reply. An unrecognised tag is a JS-template / CDP-contract drift and surfaces as a typed protocol
      * error so it is observed loudly rather than silently mis-read as stable.
      */
    private[internal] def parseOutcome(raw: String)(using Frame): Outcome < Abort[BrowserReadException] =
        Json.decode[SampleReply](raw) match
            case Result.Success(SampleReply("stable", v))   => Outcome.Stable(v)
            case Result.Success(SampleReply("unstable", v)) => Outcome.Unstable(v)
            case _ => Abort.fail(BrowserProtocolErrorException.unexpectedReply("StabilitySampler.sampleWindow", raw))
    end parseOutcome

end StabilitySampler
