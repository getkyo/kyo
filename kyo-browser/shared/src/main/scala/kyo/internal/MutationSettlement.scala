package kyo.internal

import CdpTypes.*
import kyo.*

/** DOM mutation settlement.
  *
  * After a state-changing action fires (click / fill / check / select / press / uncheck), we want the caller to see the resulting DOM
  * changes as a settled state, not the mid-render snapshot React/Vue are in at the instant the action's CDP call returns. This primitive
  * installs a scoped `MutationObserver` rooted at the target element (or `document.body` as a fallback), records the timestamp of the most
  * recent mutation, and polls until `now - lastMutation >= quiescenceWindow`.
  *
  * Timeouts bubble up as [[BrowserAssertionTimedOutException]]: "DOM never quiesced" is an assertion-like failure from the caller's
  * perspective. The observer is torn down via [[Scope.acquireRelease]] so a parent fiber abort cleans up cleanly.
  *
  * Concurrency: a single window-level observer is shared across active `afterAction` calls via a reference count (`window.__kyoMutObsRef`).
  * Each call's install bumps the count; release decrements; the observer disconnects when the count reaches 0. Back-to-back sequential
  * calls each get a clean install/teardown cycle. Truly simultaneous concurrent calls share one observer, correct for "did anything in this
  * tab mutate" questions.
  */
private[kyo] object MutationSettlement:

    /** Waits for DOM mutations to quiesce after `action` fires.
      *
      * The observer is rooted at `document.body` regardless of `scopeSelector`. Scoping to the action target's subtree is too narrow for
      * common patterns: onclick handlers often mutate sibling DOM (a click on `#trigger` updating `#root`), which a target-scoped observer
      * would never see, causing settlement to exit via the first-grace path long before mutations actually land. The `scopeSelector` arg is
      * retained for future opt-in scoping but is currently unused.
      *
      * The observer watches `childList`, `subtree`, `attributes`, and `characterData` mutations against `document.body`.
      *
      * If `quiescenceWindow` is `Duration.Zero`, returns immediately after the action; useful to opt out in tests where DOM churn is
      * expected.
      */
    def afterAction[A, S](action: A < (Browser & Async & Abort[BrowserReadException] & S))(
        scopeSelector: Maybe[Selector]
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException] & S) =
        val _ = scopeSelector
        Browser.configLocal.use { cfg =>
            val quiescenceWindow = cfg.mutationQuiescenceWindow
            val overallDeadline  = cfg.mutationSettlementTimeout
            val firstGrace       = cfg.mutationFirstMutationGrace
            val pollInterval     = cfg.mutationPollInterval
            if quiescenceWindow == Duration.Zero then action
            else
                Scope.run {
                    Browser.use { tab =>
                        Scope.acquireRelease(installObserver())(_ => releaseObserver(tab)).andThen {
                            action.map { result =>
                                // Snapshot the mutation counter at action-complete so we can distinguish "no post-action mutations yet"
                                // from "mutations observed, now quieting". The quiescence loop uses this to implement two regimes:
                                //   1. count == startCount: page hasn't mutated since the action fired. Keep polling until a mutation
                                //      lands OR `firstGrace` elapses. If grace exhausts, assume the DOM was already settled (no churn
                                //      to wait for) and return normally; this is the keep-fast path for no-op clicks
                                //      (window-property-only handlers).
                                //   2. count > startCount: at least one post-action mutation happened. Wait until
                                //      `now - __kyoMutLast >= quiescenceWindow` (standard quiescence) OR `overallDeadline` exhausts, in
                                //      which case the DOM never stopped churning and we raise a timeout.
                                //
                                // The startCount snapshot is captured INSIDE the quiescence JS (single CDP round-trip); see
                                // `awaitQuiescence` for the inline `const startCount = window.__kyoMutCount || 0;` at the top of
                                // the loop, keeping the whole post-action poll to a single eval.
                                awaitQuiescence(quiescenceWindow, overallDeadline, firstGrace, pollInterval).andThen(result)
                            }
                        }
                    }
                }
            end if
        }
    end afterAction

    /** STRICT on-demand quiescence wait backing `Browser.waitForStable`. Installs the observer with no action to settle after, runs the
      * existing single-`awaitPromise` `awaitQuiescence` loop with `overallDeadline = timeout`, and ABORTS `BrowserAssertionTimedOutException`
      * on timeout (it does NOT swallow the `SettlementResult.Timeout` path). The startCount baseline is captured at observer-install, so the
      * loop waits for quiescence from the current moment.
      */
    private[kyo] def waitForStable(timeout: Duration)(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            val quiescenceWindow = cfg.mutationQuiescenceWindow
            val firstGrace       = cfg.mutationFirstMutationGrace
            val pollInterval     = cfg.mutationPollInterval
            Scope.run {
                Browser.use { tab =>
                    Scope.acquireRelease(installObserver())(_ => releaseObserver(tab)).andThen {
                        awaitQuiescence(quiescenceWindow, timeout, firstGrace, pollInterval)
                    }
                }
            }
        }

    /** Best-effort CAPTURE-SETTLE entry: same as `waitForStable` but maps the timeout outcome to `()` instead of aborting (recovers the
      * `BrowserAssertionTimedOutException` to unit). The hold-still capture path calls it once before injecting the freeze stylesheet, so a
      * pre-capture DOM settle runs first; on timeout it proceeds to capture rather than aborting.
      */
    private[kyo] def settleForCapture(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Abort.recover[BrowserAssertionTimedOutException](_ => ()) {
                waitForStable(cfg.mutationSettlementTimeout)
            }
        }

    // --- Internal ---

    /** Installs (or increments the ref count on) the window-level mutation observer. Idempotent: a subsequent install while an observer
      * already exists just bumps `__kyoMutObsRef` and resets `__kyoMutLast`. The observer watches childList / subtree / attributes /
      * characterData on `document.body`, covering every meaningful DOM mutation React/Vue emit during a render cycle.
      *
      * Scope: always `document.body`. The action target's subtree is too narrow: onclick handlers often mutate sibling DOM (a click on
      * `#trigger` updating `#root`), which a target-scoped observer would never see. The body-rooted observer with `subtree: true` captures
      * every mutation under the document body regardless of which element the action was applied to.
      */
    private def installObserver()(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(s"""(() => {
            if (window.__kyoMutObs) {
                window.__kyoMutObsRef = (window.__kyoMutObsRef || 0) + 1;
                window.__kyoMutLast = Date.now();
                return 'shared';
            }
            window.__kyoMutLast = Date.now();
            window.__kyoMutCount = 0;
            window.__kyoMutObsRef = 1;
            window.__kyoMutObs = new MutationObserver((records) => {
                const inInternal = (n) => {
                    const el = (n && n.nodeType === 1) ? n : (n && n.parentElement);
                    return !!(el && el.closest && el.closest('[data-kyo-internal]'));
                };
                const real = records.filter(r => {
                    // Transparent when the record's target sits inside a data-kyo-internal subtree (covers attribute /
                    // characterData / childList mutations made WITHIN a tagged overlay).
                    if (inInternal(r.target)) return false;
                    // Transparent when the record INSERTS or REMOVES a tagged root: the target is the untagged parent
                    // (e.g. document.body), and the tagged node is in addedNodes / removedNodes. Treat the record as
                    // transparent only when it touches at least one node and EVERY added AND removed node is (or is
                    // within) a tagged subtree, so a mixed batch that also moves real nodes still arms the gate.
                    const touched = (r.addedNodes ? r.addedNodes.length : 0) + (r.removedNodes ? r.removedNodes.length : 0);
                    if (r.type === 'childList' && touched > 0) {
                        const added = Array.prototype.every.call(r.addedNodes || [], inInternal);
                        const removed = Array.prototype.every.call(r.removedNodes || [], inInternal);
                        if (added && removed) return false;
                    }
                    return true;
                });
                if (real.length === 0) return;
                window.__kyoMutCount = (window.__kyoMutCount || 0) + real.length;
                window.__kyoMutLast = Date.now();
            });
            const opts = { childList: true, subtree: true, attributes: true, characterData: true };
            window.__kyoMutObs.observe(document.body, opts);
            return 'installed';
        })()""").unit

    /** Scope-exit hook: decrements the ref count and disconnects when it hits 0. Delegated to [[Browser.releaseHook]], which binds the
      * computation to the captured tab and swallows cleanup-time connection / iframe errors so the scope does not re-raise into a
      * still-shutting-down fiber.
      */
    private def releaseObserver(tab: BrowserTab)(using Frame): Unit < (Async & Abort[Throwable]) =
        Browser.releaseHook(tab)(BrowserEval.evalJs(releaseObserverJs).unit)

    /** JS body for [[releaseObserver]]; held as a constant since the template has no interpolation and runs on every scope-exit. */
    private val releaseObserverJs: String =
        """(() => {
            if (!window.__kyoMutObs) return 'noop';
            window.__kyoMutObsRef = (window.__kyoMutObsRef || 1) - 1;
            if (window.__kyoMutObsRef <= 0) {
                window.__kyoMutObs.disconnect();
                delete window.__kyoMutObs;
                delete window.__kyoMutObsRef;
                delete window.__kyoMutLast;
                delete window.__kyoMutCount;
                return 'disconnected';
            }
            return 'decremented';
        })()"""

    /** Runs the entire quiescence wait INSIDE a single awaitPromise-backed JS eval.
      *
      * Constraint: MutationObserver callbacks must run inline on the page's main thread; polling the observer's state via repeated
      * `Runtime.evaluate` calls desyncs on Chrome: the observer callbacks stop delivering after the first batch when the CDP runtime
      * interleaves between mutations. Driving the entire wait loop in-page lets the observer, setInterval, and observer callbacks cooperate
      * on the main thread without CDP interruption.
      *
      * Return value: `"done|<count>|<deltaMs>"` on success, `"timeout|<count>|<deltaMs>"` on overall-deadline exhaustion with active churn,
      * `"postfail|<msg>"` when a framework's POST queue (`window.__kyoPostFailures > 0`) reported a failed event submission. When the
      * target subtree never mutates post-action, the loop resolves `"done|..."` at the deadline with `count == startCount`.
      */
    private def awaitQuiescence(
        quiescenceWindow: Duration,
        overallDeadline: Duration,
        firstGrace: Duration,
        pollInterval: Duration
    )(using
        Frame
    ): Unit < (Browser & Async & Abort[BrowserReadException]) =
        val windowMs     = quiescenceWindow.toMillis
        val deadlineMs   = overallDeadline.toMillis
        val firstGraceMs = firstGrace.toMillis
        // `startCount` is captured INLINE at the top of the JS loop, immediately after the action's CDP call
        // returns and BEFORE the polling loop begins, giving an "action-complete" mutation baseline.
        val js = s"""(async () => {
            const windowMs      = $windowMs;
            const deadlineAt    = Date.now() + $deadlineMs;
            const firstGraceAt  = Date.now() + $firstGraceMs;
            const pollMs        = ${pollInterval.toMillis};
            const sleep = (ms) => new Promise(r => setTimeout(r, ms));
            // Frameworks (e.g. kyo-ui) serialise event POSTs through `window._kyoPostQ`. A queued POST that
            // has not yet round-tripped will produce a DOM mutation AFTER our quiet window expires; awaiting
            // the queue lets the trailing mutation land before settlement begins counting. Bound the await
            // by `deadlineAt` via Promise.race so a hung POST chain cannot wedge settlement.
            const deadlineSentinel = sleep(Math.max(0, deadlineAt - Date.now()));
            await Promise.race([(window._kyoPostQ || Promise.resolve()), deadlineSentinel]);
            // After the POST queue settles, surface any framework-reported failures as a typed reply. Reset
            // the counters so the next action starts clean.
            if ((window.__kyoPostFailures || 0) > 0) {
                const errMsg = window.__kyoPostLastError || '(unknown)';
                window.__kyoPostFailures = 0;
                window.__kyoPostLastError = '';
                return JSON.stringify({tag: 'postfail', error: errMsg});
            }
            const startCount    = window.__kyoMutCount || 0;
            // Deadline-bounded inside the body: each iteration checks `deadlineAt` plus the settle/grace conditions, so the loop terminates within `overallDeadline` regardless of mutation activity.
            while (true) {
                const count = window.__kyoMutCount || 0;
                const last  = window.__kyoMutLast  || Date.now();
                const now   = Date.now();
                const delta = now - last;
                const sawMutation = count > startCount;
                if (sawMutation && delta >= windowMs) return JSON.stringify({tag: 'done', count: count, delta: delta});
                if (!sawMutation && now >= firstGraceAt) return JSON.stringify({tag: 'done', count: count, delta: delta});
                if (now >= deadlineAt) return JSON.stringify({tag: (sawMutation ? 'timeout' : 'done'), count: count, delta: delta});
                await sleep(pollMs);
            }
        })()"""
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            val ctxOpt = ctx.map(c => CdpTypes.ExecutionContextId.value(c))
            Browser.use { tab =>
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams(js, returnByValue = true, awaitPromise = true, contextId = ctxOpt)
                )
                    .map(BrowserEval.translateContextDestroyed)
                    .map { resultJson =>
                        CdpEvalDecoder.parseAndExtractEvalValue(resultJson).map { value =>
                            parseSettlementValue(value) match
                                case SettlementResult.Done => ()
                                case SettlementResult.Timeout(count, delta) =>
                                    Abort.fail(
                                        BrowserAssertionTimedOutException.notQuiesced(
                                            quiescenceWindow,
                                            delta,
                                            count,
                                            overallDeadline
                                        )
                                    )
                                case SettlementResult.PostFailed(error) =>
                                    Abort.fail(
                                        BrowserProtocolErrorException.unexpectedReply(
                                            "MutationSettlement.awaitQuiescence",
                                            s"event POST failed: $error"
                                        )
                                    )
                                case SettlementResult.Malformed =>
                                    // Json.decode rejected the wire shape: JS-side template drift. Logged so production observes
                                    // silent JS-template breakage.
                                    Log.warn(s"MutationSettlement.awaitQuiescence: unexpected wire shape: '$value'")
                                        .andThen(Abort.fail(
                                            BrowserProtocolErrorException.unexpectedReply("MutationSettlement.awaitQuiescence", value)
                                        ))
                            end match
                        }
                    }
            }
        }
    end awaitQuiescence

    /** Outcome of parsing a `{tag, count, delta, error}` settlement reply. */
    private[internal] enum SettlementResult derives CanEqual:
        case Done
        case Timeout(count: Long, delta: Long)
        case PostFailed(error: String)
        case Malformed
    end SettlementResult

    /** JSON wire shape returned by the in-page `awaitQuiescence` loop. `tag` is `"done"`, `"timeout"`, or `"postfail"`. */
    private case class SettlementWire(
        tag: String,
        count: Maybe[Long] = Absent,
        delta: Maybe[Long] = Absent,
        error: Maybe[String] = Absent
    ) derives Schema

    /** Decodes the JSON `{tag, count, delta, error}` settlement reply. Unknown tags and missing required fields collapse to
      * [[SettlementResult.Malformed]] so the caller logs and surfaces the wire-shape drift loudly.
      */
    private[internal] def parseSettlementValue(value: String)(using Frame): SettlementResult =
        Json.decode[SettlementWire](value) match
            case Result.Success(w) =>
                w.tag match
                    case "done" => SettlementResult.Done
                    case "timeout" =>
                        (w.count, w.delta) match
                            case (Present(c), Present(d)) => SettlementResult.Timeout(c, d)
                            case _                        => SettlementResult.Malformed
                    case "postfail" =>
                        w.error match
                            case Present(err) => SettlementResult.PostFailed(err)
                            case Absent       => SettlementResult.Malformed
                    case _ => SettlementResult.Malformed
            case _ => SettlementResult.Malformed
    end parseSettlementValue

end MutationSettlement
