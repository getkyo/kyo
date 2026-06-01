package kyo.internal

import kyo.*

/** Retry / stability / failure-translation plumbing for the `Browser.assert*` family.
  *
  * `withStability` is the workhorse: a single attempt is one cheap in-page read followed (if the read matches) by a
  * `StabilitySampler.sampleWindow` over the same JS expression. The outer `Retry` driver schedules attempts per
  * `Browser.configLocal.use(_.retrySchedule)` (or an explicit schedule override).
  *
  * The `retry*` family wraps `withStability` with the four failure-translation policies the public API needs: positive selector-bound
  * probe, negative selector-bound probe, selector-bound read, page-level read.
  *
  * `defaultOnElementMissing` is the read-side dual: substitute a default value when a `BrowserElementException` surfaces. Used by `count` /
  * `textAll` / `attributeAll`, all of which want "0 / empty" semantics rather than a typed missing-element exception.
  */
private[kyo] object BrowserAssertion:

    // ---- Stability core ----

    /** Retries an assertion per the active retry schedule, gated by an in-page stability window.
      *
      * `valueExpr` is a self-contained JS expression evaluating to the probed value (a number, string, or boolean, coerced to a string).
      * `parse` lifts that string back to the typed value `A` for `predicate` / `failure`.
      *
      * Each attempt is two-phase:
      *   1. A single cheap in-page read of `valueExpr`. If it already fails `predicate`, the attempt fails immediately and the schedule
      *      retries; no point sampling a stability window for a value that does not even match. This keeps assertions against a stable
      *      *non-matching* value (e.g. `assertEnabled` on a permanently-disabled control) as fast as the pre-stability single-shot probe.
      *   1. If the first read matches, [[kyo.internal.StabilitySampler]] runs: the value is sampled in-page every few ms for the whole
      *      `assertionStabilityWindow`. If it changes (`Unstable`) the attempt fails and the schedule retries; only a value that held
      *      constant for the entire window AND still satisfies `predicate` is accepted. Because the sampling loop runs on the page main
      *      thread it observes *every* value the page exhibits during the window: alias-proof against fast flickers regardless of CDP
      *      transport latency.
      *
      * When `assertionStabilityWindow == Duration.Zero` phase 2 is skipped: first-match behaviour, the escape hatch for callers that need
      * raw speed: `Browser.withConfig(_.assertionStabilityWindow(Duration.Zero))`.
      *
      * The `failure` factory produces the error value from the probed result `A` so the caller retains full control over the exception
      * message while `withStability` owns the retry / sampling logic.
      */
    private[kyo] def withStability[A](
        valueExpr: String,
        scheduleOverride: Maybe[Schedule]
    )(parse: String => A)(predicate: A => Boolean)(failure: A => BrowserReadException)(using
        Frame
    ): A < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            val effectiveSchedule: Schedule = scheduleOverride match
                case Absent      => cfg.retrySchedule
                case s: Schedule => s
            val window = cfg.assertionStabilityWindow
            Retry[BrowserReadException](effectiveSchedule) {
                BrowserEval.evalJs(valueExpr).map { firstRaw =>
                    val first = parse(firstRaw)
                    if !predicate(first) then raiseWithUrlContext(failure(first))
                    else if window == Duration.Zero then first
                    else
                        // `parse` is invoked at most once per outcome arm; the `Stable` arm caches the
                        // result in `val a` and reuses it for both the predicate check and the failure factory,
                        // so `parse(raw)` is never called twice per stable outcome.
                        StabilitySampler.sampleWindow(valueExpr, window).map {
                            case StabilitySampler.Outcome.Unstable(raw) =>
                                raiseWithUrlContext(failure(parse(raw)))
                            case StabilitySampler.Outcome.Stable(raw) =>
                                val a = parse(raw)
                                if predicate(a) then a else raiseWithUrlContext(failure(a))
                        }
                    end if
                }
            }
        }
    end withStability

    /** Raises `ex` after enriching its message with the current page URL. The page-URL context makes assertion failures self-diagnosing
      * when an unexpected navigation occurred: the developer reads "(current URL: ...)" alongside the matched value rather than having to
      * manually call `Browser.url` from a debugger.
      *
      * Currently enriches [[BrowserAssertionTimedOutException]] only (the dominant assertion-failure type). Other `BrowserReadException`s
      * pass through unchanged; their messages are usually self-describing already (selector descriptions, CDP wire-shape details), so URL
      * context adds less value.
      */
    private def raiseWithUrlContext(ex: BrowserReadException)(using
        Frame
    ): Nothing < (Browser & Abort[BrowserReadException]) =
        ex match
            case timed: BrowserAssertionTimedOutException =>
                Browser.url.map { url =>
                    Abort.fail(BrowserAssertionTimedOutException(
                        timed.check,
                        timed.expected,
                        s"${timed.actual} (current URL: $url)"
                    ))
                }
            case other => Abort.fail(other)
    end raiseWithUrlContext

    // ---- Failure factories ----

    /** Recognised sentinel-value vocabulary used by the in-page probes in [[ProbesJs]]. Any `actual` value passing through
      * [[elementProbeFailure]] that LOOKS like a sentinel (lowercase identifier or colon-prefixed code) but is NOT in this set indicates
      * either a typo on the JS-template side or a sentinel emitter that drifted away from the consumer. The Log.warn at the caller catches
      * those silently-misclassified strings rather than letting them surface as a plain timeout.
      */
    private[kyo] val recognisedProbeSentinels: Set[String] = Set(
        "not_attached",
        "visible",
        "hidden",
        "ancestor_hidden",
        "zero_size",
        "enabled",
        "disabled",
        "checked",
        "not_checked",
        "focused",
        "not_focused",
        "empty",
        "non_empty",
        "present",
        "absent",
        "unsupported",
        "ok",
        "noop",
        "clicked",
        "already",
        "needs_focus"
    )

    /** Failure factory for selector-bound probes: a `"not_attached"` stable value means the element is gone, so it raises
      * [[BrowserElementNotFoundException]] rather than a generic timeout; any other value produces a [[BrowserAssertionTimedOutException]]
      * tagged with the enclosing method name and `message`. `BrowserElementNotFoundException` is itself a [[BrowserAssertionException]],
      * so when the element flickers in and out of the DOM the enclosing `Retry` still retries on it and exhausts to a timeout.
      */
    private[kyo] def elementProbeFailure(selector: Selector, message: String)(using frame: Frame): String => BrowserReadException =
        actual =>
            if actual == "not_attached" then
                BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector)))
            else
                BrowserAssertionTimedOutException(frame.calleeName, message, actual)

    /** Returns true when `actual` looks like a probe sentinel (lowercase identifier or `prefix:detail` form) that is NOT in the recognised
      * vocabulary. Used by [[retryReadAssert]] to surface unrecognised sentinels via [[Log.warn]] for cross-template-drift diagnostics.
      */
    private def looksLikeUnrecognisedSentinel(actual: String): Boolean =
        val core = actual.takeWhile(_ != ':')
        actual.nonEmpty &&
        actual.length <= 32 &&
        core.forall(c => c.isLower || c == '_') &&
        !recognisedProbeSentinels.contains(core)
    end looksLikeUnrecognisedSentinel

    // ---- Retry-and-assert family ----

    /** Retries a passive in-page probe against the active retry schedule, gated by the stability window, until the probe's value equals
      * `expected`. The probe is supplied as a self-contained JS expression so the stability sampler can sample it in-page. The check label
      * is built from the enclosing public method's name via `Frame.calleeName` with the selector description appended. Used by the
      * `assertVisible` / `assertEnabled` / `assertDisabled` / `assertChecked` / `assertNoVisibleText` / `assertValueEmpty` / `assertFocused` / `assertNotFocused`
      * family.
      */
    private[kyo] def retryAssert(selector: Selector, expected: String, schedule: Maybe[Schedule])(
        probeExprJs: String
    )(using frame: Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        val label = s"${frame.calleeName} ${Browser.selectorNodeDescription(Selector.toNode(selector))}"
        // A `"not_attached"` value is surfaced as a plain timeout (not BrowserElementNotFoundException): for the positive
        // passive-probe family a missing element is just one more way the asserted condition is unmet, and the public
        // contract for `assertVisible` / `assertFocused` / `assertNotFocused` / … is `BrowserAssertionTimedOutException`.
        // (The negative family in `retryNegativeProbe` differs: a missing element there is `BrowserElementNotFoundException`.)
        withStability(probeExprJs, schedule)(identity)(_ == expected)(actual =>
            BrowserAssertionTimedOutException(label, expected, actual)
        ).unit
    end retryAssert

    /** Retries a negative passive-probe against the active retry schedule, gated by the stability window, until `failureDetector` stops
      * matching the probe's value. A `"not_attached"` value raises [[BrowserElementNotFoundException]] (a detached element cannot become
      * attached by waiting). Used by the negative passive-probe assertions `Browser.assertNotVisible` and `Browser.assertNotChecked`.
      */
    private[kyo] def retryNegativeProbe(
        expected: String,
        selector: Selector,
        schedule: Maybe[Schedule]
    )(probeExprJs: String)(
        failureDetector: String => Boolean
    )(using
        frame: Frame
    ): Unit < (Browser & Async & Abort[BrowserReadException]) =
        val label = s"${frame.calleeName} ${Browser.selectorNodeDescription(Selector.toNode(selector))}"
        // The accepted predicate is the negation of the failure detector AND not "not_attached": a stable value that the failure
        // detector matches is a genuine assertion failure; a stable "not_attached" is a missing element. Either way the failure
        // factory below produces the right typed exception. A flicker (Unstable) routes through the same factory.
        withStability(probeExprJs, schedule)(identity)(v => v != "not_attached" && !failureDetector(v)) { actual =>
            if actual == "not_attached" then BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector)))
            else BrowserAssertionTimedOutException(label, expected, actual)
        }.unit
    end retryNegativeProbe

    /** Retries a selector-bound read assertion (`assertText` / `assertAttribute`) against the active retry schedule, gated by the stability
      * window, until `predicate` holds on the read value. `valueExprJs` builds the self-contained JS read expression (which returns the
      * `"not_attached"` sentinel for a missing element) from the element-resolver JS for `selector`; the resolver JS is built here, in the
      * single canonical place for the `assertText` / `assertAttribute` overload families. The check label is derived from the enclosing
      * public method's name.
      */
    private[kyo] def retryReadAssert(selector: Selector, message: String, schedule: Maybe[Schedule])(
        valueExprJs: String => String,
        predicate: String => Boolean
    )(using
        frame: Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        withStability(valueExprJs(jsExpr), schedule)(identity)(v => v != "not_attached" && predicate(v))(
            elementProbeFailure(selector, message)
        ).unit.handle(Abort.recover[BrowserReadException] { ex =>
            // Detect probe-template drift: if the timeout's `actual` string LOOKS like a probe sentinel
            // (lowercase identifier or prefix:detail form) but is NOT in the recognised vocabulary, log a warning
            // so a drifted sentinel emitter does not get silently classified as a plain timeout.
            ex match
                case timed: BrowserAssertionTimedOutException if looksLikeUnrecognisedSentinel(timed.actual) =>
                    Log.warn(
                        s"retryReadAssert: probe at ${frame.calleeName} produced unrecognised sentinel '${timed.actual}'; classified as timeout."
                    ).andThen(Abort.fail(ex))
                case _ => Abort.fail(ex)
        })
    end retryReadAssert

    /** Page-level variant of [[retryReadAssert]] for reads that cannot raise [[BrowserElementException]] (e.g. `window.location.href`,
      * `document.title`). `valueExprJs` is the self-contained JS read expression.
      */
    private[kyo] def retryReadAssertPage(message: String, schedule: Maybe[Schedule])(
        valueExprJs: String,
        predicate: String => Boolean
    )(using
        frame: Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val label = frame.calleeName
        withStability(valueExprJs, schedule)(identity)(predicate)(actual =>
            BrowserAssertionTimedOutException(label, message, actual)
        ).unit
    end retryReadAssertPage

    // ---- Read-side missing-element default ----

    /** Runs `v` and substitutes `default` if it fails with a [[BrowserElementException]]. Panics are re-raised unchanged; other Abort
      * effects (e.g. [[BrowserConnectionException]]) propagate untouched. Used by `count` / `textAll` / `attributeAll` to translate "no
      * element ever appeared" into a neutral default value.
      */
    private[kyo] def defaultOnElementMissing[A](default: => A)(
        v: A < (Browser & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserElementException] { _ => default }(v)

end BrowserAssertion
