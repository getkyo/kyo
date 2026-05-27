package kyo.internal

import kyo.*

/** Auto-wait-for-navigation primitive.
  *
  * [[armAround]] wraps a click (or any other trigger that may initiate a navigation) and blocks until the resulting navigation has settled
  * per the requested [[Settle]] mode. When the trigger does not cause a navigation the call returns the trigger's result immediately,
  * making [[armAround]] safe to wrap around any click.
  *
  * [[waitForNext]] is a pure wait primitive for the "navigation-triggering action has already happened" case.
  *
  * Detection strategy is JS-polling-based: we install a small recorder on `window` (idempotent) that monkey-patches `history.pushState` /
  * `history.replaceState` and listens for `beforeunload`. After the trigger we snapshot the URL + readyState + push-state counter and poll
  * until the readyState (and optionally the network idle window) matches the requested settle mode. This keeps the CDP event channel free
  * for other subscribers (downloads, tests).
  *
  * Navigation failure is detected via `performance.getEntriesByType('navigation')[0].responseStatus`: a 4xx/5xx there raises
  * [[BrowserNavigationFailedException]] when `throwOnFailure = true`.
  */
private[kyo] object NavigationWatcher:

    /** Arms the watcher around `trigger`. If `trigger` causes a navigation (URL change, `pushState`, `replaceState`, or `beforeunload`)
      * within a short grace window, the return type's inner value is produced only after the navigation settles per `settle`.
      *
      * If no navigation happens during `trigger`, this returns the trigger's result immediately; `armAround` is safe to wrap around any
      * click.
      *
      * Sequential `armAround` invocations serialise naturally: because `click(a).andThen(click(b))` runs b only after a's armAround
      * completes, the second click's watcher cannot install until the first's settle has resolved, so back-to-back nav-intent clicks on the
      * same tab do not interleave.
      *
      * On navigation failure (4xx/5xx on the primary navigation response) raises [[BrowserNavigationFailedException]] when
      * `throwOnFailure = true`. A cancellation of the parent fiber tears down the watcher cleanly via [[Scope.acquireRelease]].
      */
    def armAround[A](settle: Browser.Settle, throwOnFailure: Boolean)(
        trigger: A < (Browser & Async & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException]) =
        armInternal(settle, throwOnFailure, assumeWillNavigate = false)(trigger)

    /** Like [[armAround]] but waits for navigation unconditionally; used by `Browser.goto`, where we KNOW we've asked Chrome to navigate
      * and therefore must not apply the "did anything happen?" fast-path that [[armAround]] uses to stay cheap around nav-neutral clicks.
      */
    def armAroundNavigation[A](settle: Browser.Settle, throwOnFailure: Boolean)(
        trigger: A < (Browser & Async & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException]) =
        armInternal(settle, throwOnFailure, assumeWillNavigate = true)(trigger)

    /** Like [[armAroundNavigation]] but for reloads: we KNOW a navigation will occur (we just issued Page.reload), but the URL does NOT
      * change. Skips `pollNavigated` entirely (the recorder is wiped by the reload itself before we can read it) and goes straight to
      * `awaitSettle` with `expectedDifferentFrom = Absent` so the URL-change predicate is bypassed. Still raises
      * [[BrowserNavigationFailedException]] on 4xx/5xx when `throwOnFailure = true` because `awaitSettle` checks the response status
      * independently of URL change.
      */
    def armAroundReload[A](settle: Browser.Settle, throwOnFailure: Boolean)(
        trigger: A < (Browser & Async & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException]) =
        Browser.use { tab =>
            Scope.run {
                installWatcher.andThen {
                    Scope.acquireRelease(snapshotState)(_ => releaseWatcher(tab)).map { _ =>
                        trigger.map { result =>
                            Browser.configLocal.use { cfg =>
                                Clock.nowMonotonic.map { now =>
                                    val settleDeadline = now + loadScheduleTimeout(cfg.loadSchedule)
                                    awaitSettle(Absent, settle, settleDeadline, throwOnFailure).andThen(result)
                                }
                            }
                        }
                    }
                }
            }
        }

    private def armInternal[A](settle: Browser.Settle, throwOnFailure: Boolean, assumeWillNavigate: Boolean)(
        trigger: A < (Browser & Async & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException]) =
        Browser.use { tab =>
            Scope.run {
                installWatcher.andThen {
                    // acquireRelease: the snapshot is the acquired "resource", and releasing it
                    // resets the recorder state so a subsequent armAround isn't triggered spuriously.
                    // The release runs on scope exit whether the trigger succeeded, failed, or the
                    // parent fiber aborted, guaranteeing no dangling in-page state.
                    Scope.acquireRelease(snapshotState)(_ => releaseWatcher(tab)).map { snapshot =>
                        trigger.map { result =>
                            awaitSettleCore(snapshot, settle, throwOnFailure, assumeWillNavigate).andThen(result)
                        }
                    }
                }
            }
        }
    end armInternal

    /** Waits for the *next* navigation to settle per `settle`. Raises [[BrowserAssertionTimedOutException]] if nothing navigates within
      * `timeout`, and [[BrowserNavigationFailedException]] on a 4xx/5xx response.
      */
    def waitForNext(settle: Browser.Settle, timeout: Duration)(using
        Frame
    ): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.use { tab =>
            Scope.run {
                installWatcher.andThen {
                    Scope.acquireRelease(snapshotState)(_ => releaseWatcher(tab)).map { snapshot =>
                        Clock.nowMonotonic.map(_ + timeout).map { deadline =>
                            pollNavigated(snapshot, deadline).map {
                                case true => awaitSettle(Absent, settle, deadline, throwOnFailure = true)
                                case false =>
                                    Abort.fail(
                                        BrowserNavigationFailedException("(none)", s"no navigation within ${timeout}")
                                    )
                            }
                        }
                    }
                }
            }
        }

    /** Installs the navigation recorder on `window` (idempotent).
      *
      * The recorder monkey-patches `history.pushState` / `history.replaceState` and listens for `beforeunload`; these hooks write to
      * `window.__kyoNavRec` which later polls read. Installation is a no-op on subsequent calls.
      */
    private def installWatcher(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(installWatcherJs).unit

    /** JS body for [[installWatcher]]; held as a constant since the template has no interpolation and runs on every armAround / waitForNext
      * entry.
      */
    private val installWatcherJs: String =
        """(() => {
            if (window.__kyoNavRecInstalled) return 'ok';
            window.__kyoNavRecInstalled = true;
            window.__kyoNavRec = {
                pushStateCount: 0,
                beforeUnload: false
            };
            window.addEventListener('beforeunload', () => {
                if (window.__kyoNavRec) window.__kyoNavRec.beforeUnload = true;
            });
            try {
                const origPush = history.pushState;
                history.pushState = function() {
                    if (window.__kyoNavRec) window.__kyoNavRec.pushStateCount++;
                    return origPush.apply(this, arguments);
                };
                const origReplace = history.replaceState;
                history.replaceState = function() {
                    if (window.__kyoNavRec) window.__kyoNavRec.pushStateCount++;
                    return origReplace.apply(this, arguments);
                };
            } catch (e) { /* pushState override is best-effort */ }
            return 'ok';
        })()"""

    /** JSON wire shape returned by [[snapshotState]]; mirrors the fields of [[NavSnapshot]] verbatim. */
    private[internal] case class NavSnapshotWire(url: String, pushStateCount: Int, beforeUnload: Boolean) derives Schema, CanEqual

    /** Snapshots the recorder state just before the trigger runs. Used to detect "did anything change?" after the trigger. */
    private def snapshotState(using Frame): NavSnapshot < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(s"""(() => {
            const rec = window.__kyoNavRec || { pushStateCount: 0, beforeUnload: false };
            return JSON.stringify({url: location.href, pushStateCount: rec.pushStateCount, beforeUnload: !!rec.beforeUnload});
        })()""").map(decodeSnapshotState)

    /** Decoder for the JSON payload emitted by [[snapshotState]]'s in-page IIFE. Wire-shape drift surfaces as a typed
      * [[BrowserProtocolErrorException]] rather than degrading to a zero snapshot; the latter would silently mask a broken JS template by
      * making every subsequent `pollNavigated` observe a spurious URL change (empty snapshot URL vs the non-empty live URL).
      *
      * Mirrors the [[decodeSettleState]] treatment for the sibling settle-state envelope.
      */
    private[internal] def decodeSnapshotState(raw: String)(using Frame): NavSnapshot < Abort[BrowserReadException] =
        Json.decode[NavSnapshotWire](raw) match
            case Result.Success(w) => NavSnapshot(url = w.url, pushStateCount = w.pushStateCount, beforeUnload = w.beforeUnload)
            case Result.Failure(err) =>
                Abort.fail(
                    BrowserProtocolErrorException.unexpectedReply(
                        "NavigationWatcher.decodeSnapshotState",
                        s"snapshot wire decode failed: ${err.getMessage}"
                    )
                )
            case Result.Panic(t) =>
                Abort.fail(
                    BrowserProtocolErrorException.unexpectedReply(
                        "NavigationWatcher.decodeSnapshotState",
                        s"snapshot wire decode panicked: ${t.getMessage}"
                    )
                )
    end decodeSnapshotState

    /** Clears the `beforeUnload` flag after we've handled it, so a subsequent `armAround` call isn't spuriously triggered. */
    private def clearState(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(clearStateJs).unit

    /** JS body for [[clearState]]; held as a constant since the template has no interpolation and runs on every scope-exit. */
    private val clearStateJs: String =
        """(() => {
            if (window.__kyoNavRec) { window.__kyoNavRec.beforeUnload = false; }
            return 'ok';
        })()"""

    /** Scope-exit hook: runs [[clearState]] against the captured tab via [[Browser.releaseHook]], which swallows any cleanup-time
      * connection / iframe errors so the scope does not re-raise into a still-shutting-down fiber.
      */
    private def releaseWatcher(tab: BrowserTab)(using Frame): Unit < (Async & Abort[Throwable]) =
        Browser.releaseHook(tab)(clearState)

    /** Extracts the effective max-duration from a schedule's [[Schedule.internal.MaxDuration]] wrapper, falling back to 5 seconds. Used to
      * derive a wall-clock settle deadline from the active `loadSchedule`.
      */
    private[kyo] def loadScheduleTimeout(s: Schedule): Duration =
        s match
            case Schedule.internal.MaxDuration(_, d) => d
            case _                                   => defaultLoadScheduleTimeout

    /** After the trigger runs, checks whether a navigation appears to have started. If `assumeWillNavigate` is `false` we allow a short
      * grace window for the observation: if nothing changes, the trigger was navigation-neutral and we return immediately. If
      * `assumeWillNavigate` is `true` (e.g. `Browser.goto`) we skip the fast-path and wait for settle against the full scope timeout; the
      * caller explicitly asked Chrome to navigate, so "nothing is happening" is an error, not a skip condition.
      */
    private def awaitSettleCore(
        snapshot: NavSnapshot,
        settle: Browser.Settle,
        throwOnFailure: Boolean,
        assumeWillNavigate: Boolean
    )(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Clock.nowMonotonic.map { now =>
                val settleDeadline = now + loadScheduleTimeout(cfg.loadSchedule)
                if assumeWillNavigate then
                    // goto-style: we've asked Chrome to navigate, so "nothing changed" is not a skip condition; it means the
                    // target server isn't responding yet. Wait for the URL to actually change (nav committed) before evaluating
                    // the settle mode.
                    awaitSettle(Present(snapshot), settle, settleDeadline, throwOnFailure)
                else
                    val graceDeadline = now + cfg.navigationGraceWindow
                    pollNavigated(snapshot, graceDeadline).map {
                        case false => ()
                        case true  => awaitSettle(Absent, settle, settleDeadline, throwOnFailure)
                    }
                end if
            }
        }
    end awaitSettleCore

    /** Polls the recorder to detect whether a navigation started. Returns `true` if the URL changed, `pushState` was called, or
      * `beforeunload` fired. Polls until the deadline, returning `false` if nothing was observed.
      */
    private def pollNavigated(snapshot: NavSnapshot, deadline: Duration)(using
        Frame
    ): Boolean < (Browser & Async & Abort[BrowserReadException]) =
        // Hoist: snapshot.url and snapshot.pushStateCount are loop invariants (NavSnapshot is a final immutable
        // case class), so the interpolated JS template is too. Build it once before the Loop and capture in the
        // closure.
        val jsTemplate = s"""(() => {
                const rec = window.__kyoNavRec || { pushStateCount: 0, beforeUnload: false };
                const urlChanged = location.href !== '${JsStringUtil.escapeJsString(snapshot.url)}';
                const pushedMore = rec.pushStateCount > ${snapshot.pushStateCount};
                const unloaded = rec.beforeUnload;
                return (urlChanged || pushedMore || unloaded) ? '1' : '0';
            })()"""
        Browser.configLocal.use { cfg =>
            val pollInterval = cfg.navigationPollInterval
            Loop(()) { _ =>
                BrowserEval.evalJs(jsTemplate).map { observation =>
                    if observation == "1" then Loop.done(true)
                    else
                        Clock.nowMonotonic.map { now =>
                            if now >= deadline then Loop.done(false)
                            else Async.sleep(pollInterval).andThen(Loop.continue(()))
                        }
                }
            }
        }
    end pollNavigated

    /** Waits until the settle mode is satisfied: readyState plus (optionally) a network-idle window. Raises on 4xx/5xx if `throwOnFailure`.
      *
      * If `expectedDifferentFrom` is `Present(snapshot)`, the live URL must differ from the snapshot's URL before readiness is considered.
      * This handles the `goto`-with-unreachable-URL case where Chrome keeps the old page visible until a new nav commits.
      *
      * Network tracking is (re)installed inside the loop because a navigation wipes all JS state on the destination page; a pre-navigation
      * install would be gone by the time we poll the new document.
      */
    private def awaitSettle(
        expectedDifferentFrom: Maybe[NavSnapshot],
        settle: Browser.Settle,
        deadline: Duration,
        throwOnFailure: Boolean
    )(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        // Hoist: read config and build the settle-state JS template once before the Loop. `cfg.networkIdleWindow`
        // and `settle` are loop invariants for one awaitSettle call, so the interpolated JS string is too. The
        // Loop closure captures the prebuilt string and reuses it per tick.
        Browser.configLocal.use { cfg =>
            val jsTemplate       = buildSettleStateJs(settle, cfg.networkIdleWindow.toMillis)
            val pollInterval     = cfg.navigationPollInterval
            val postSettleWindow = cfg.navigationPostSettleWindow
            Loop(()) { _ =>
                ensureNetworkTracking(settle).andThen {
                    readSettleStateWith(jsTemplate).map {
                        case SettleStatus.Ready(navUrl, status) =>
                            val urlChanged = expectedDifferentFrom match
                                case Present(snap) => snap.url != navUrl
                                case Absent        => true
                            if !urlChanged then
                                Clock.nowMonotonic.map { now =>
                                    if now >= deadline then
                                        Abort.fail(
                                            BrowserNavigationFailedException(
                                                expectedDifferentFrom.fold(navUrl)(_.url),
                                                s"navigation never committed (still at original URL); settle mode ${settle}"
                                            )
                                        )
                                    else Async.sleep(pollInterval).andThen(Loop.continue(()))
                                }
                            else if throwOnFailure && status >= 400 && status < 600 then
                                Abort.fail(
                                    BrowserNavigationFailedException(navUrl, s"HTTP $status")
                                )
                            else postSettleBarrier(postSettleWindow).andThen(Loop.done(()))
                            end if
                        case SettleStatus.Pending(urlHint) =>
                            Clock.nowMonotonic.map { now =>
                                if now >= deadline then onPendingDeadline(settle, urlHint, throwOnFailure, postSettleWindow)
                                else Async.sleep(pollInterval).andThen(Loop.continue(()))
                            }
                    }
                }
            }
        }

    /** Deadline-exhaustion handler for [[awaitSettle]]. Two paths:
      *
      *   - For [[Browser.Settle.NetworkIdle]], re-probe with [[Browser.Settle.Load]] semantics: if the `load` event has fired the
      *     navigation is functionally complete, just chronically chatty (analytics heartbeats, RUM telemetry). Log a warning and return
      *     success, degrading to `Settle.Load`. This handles real-world pages like `crates.io/search` where the network never opens a quiet
      *     window inside `loadSchedule` but the page is fully usable.
      *   - For [[Browser.Settle.Load]] / [[Browser.Settle.DomContentLoaded]] (and any other future variant), raise the failure unchanged.
      *     Those modes already have the loosest possible contract; if they timed out the page genuinely never reached
      *     `readyState == complete`.
      *
      * Callers that need a hard NetworkIdle assertion post-navigation should use [[Browser.waitForNetworkIdle]] explicitly; that primitive
      * surfaces a typed timeout instead of degrading.
      */
    private def onPendingDeadline(settle: Browser.Settle, urlHint: String, throwOnFailure: Boolean, postSettleWindow: Duration)(using
        Frame
    ): Loop.Outcome[Unit, Unit] < (Browser & Async & Abort[BrowserReadException]) =
        settle match
            case Browser.Settle.NetworkIdle =>
                val loadJs = buildSettleStateJs(Browser.Settle.Load, 0L)
                readSettleStateWith(loadJs).map {
                    case SettleStatus.Ready(navUrl, status) =>
                        Log.warn(
                            s"Settle.NetworkIdle: network never quiesced within budget for $navUrl; degrading to Settle.Load"
                        ).andThen {
                            if throwOnFailure && status >= 400 && status < 600 then
                                Abort.fail(BrowserNavigationFailedException(navUrl, s"HTTP $status"))
                            else postSettleBarrier(postSettleWindow).andThen(Loop.done(()))
                        }
                    case SettleStatus.Pending(_) =>
                        Abort.fail(BrowserNavigationFailedException(
                            urlHint,
                            "settle timeout after NetworkIdle (load event also never fired)"
                        ))
                }
            case _ =>
                Abort.fail(BrowserNavigationFailedException(urlHint, s"settle timeout after ${settle}"))

    /** Settlement barrier before the nav-wait returns: yields the fiber for a short tick so Chrome can finish post-commit layout and
      * resource decoding before the next CDP command arrives. `postSettleWindow` is read from `SessionConfig.navigationPostSettleWindow`
      * by the enclosing `awaitSettle`.
      */
    private def postSettleBarrier(postSettleWindow: Duration)(using Frame): Unit < Async =
        Async.sleep(postSettleWindow)

    sealed private[internal] trait SettleStatus derives CanEqual
    private[internal] object SettleStatus:
        final case class Ready(url: String, status: Int) extends SettleStatus
        final case class Pending(urlHint: String)        extends SettleStatus
    end SettleStatus

    /** Installs a JS-level fetch/XHR tracker so `NetworkIdle` settle mode has something to observe. Re-uses the shape of
      * `Browser.waitForNetworkIdle`'s tracker so the two are compatible when a caller mixes them.
      */
    private[internal] def ensureNetworkTracking(settle: Browser.Settle)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        settle match
            case Browser.Settle.NetworkIdle => BrowserNetworkTracker.ensureInstalled
            case _                          => ()

    /** Builds the settle-state JS template for the given settle mode and network-idle window (in ms).
      *
      * Pure string construction: splits the interpolated values out so the awaitSettle Loop can build the JS string once at the top and
      * reuse it across every tick. The settle mode and `idleMs` are loop invariants for a single awaitSettle call, so the resulting string
      * is too.
      */
    private[internal] def buildSettleStateJs(settle: Browser.Settle, idleMs: Long): String =
        val targetState = settle match
            case Browser.Settle.DomContentLoaded => "interactive-or-complete"
            case Browser.Settle.Load             => "complete"
            case Browser.Settle.NetworkIdle      => "complete"
        val requireIdle = settle match
            case Browser.Settle.NetworkIdle => "true"
            case _                          => "false"
        s"""(() => {
            const rs = document.readyState;
            const rsOk = ${
                if targetState == "interactive-or-complete" then "rs === 'interactive' || rs === 'complete'" else "rs === 'complete'"
            };
            let idleOk = true;
            if ($requireIdle) {
                const pending = window.__kyoNetPending || 0;
                const last = window.__kyoNetLastActivity || 0;
                const now = Date.now();
                idleOk = pending === 0 && last > 0 && (now - last) >= $idleMs;
            }
            // HTTP status from primary navigation entry; 0 if unavailable (data: URLs, file: URLs).
            let status = 0;
            try {
                const entries = performance.getEntriesByType('navigation');
                if (entries && entries.length > 0 && typeof entries[0].responseStatus === 'number') {
                    status = entries[0].responseStatus;
                }
            } catch (e) { status = 0; }
            return JSON.stringify({
                ready: rsOk && idleOk,
                url: location.href,
                status: status
            });
        })()"""
    end buildSettleStateJs

    /** Reads the current settle state from the page using a prebuilt JS template (see [[buildSettleStateJs]]). Returns readyState + (for
      * NetworkIdle) network activity + primary navigation status.
      */
    private def readSettleStateWith(jsTemplate: String)(using
        Frame
    ): SettleStatus < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(jsTemplate).map(decodeSettleState)

    /** Decoder for the JSON payload emitted by [[readSettleStateWith]]'s in-page IIFE. Wire-shape drift surfaces as a typed
      * [[BrowserConnectionException]] rather than degrading to `Pending("(unknown)")`; the latter would silently mask a broken JS template
      * by keeping the navigation gate spinning forever.
      */
    private[internal] def decodeSettleState(raw: String)(using Frame): SettleStatus < Abort[BrowserReadException] =
        Json.decode[NavigationSettleState](raw) match
            case Result.Success(s) =>
                if s.ready then SettleStatus.Ready(s.url, s.status)
                else SettleStatus.Pending(s.url)
            case Result.Failure(err) =>
                Abort.fail(
                    BrowserProtocolErrorException("NavigationWatcher.decodeSettleState", s"settle wire decode failed: ${err.getMessage}")
                )
            case Result.Panic(t) =>
                Abort.fail(
                    BrowserProtocolErrorException("NavigationWatcher.decodeSettleState", s"settle wire decode panicked: ${t.getMessage}")
                )
    end decodeSettleState

    /** Fallback for the effective max-duration of `loadSchedule` when the schedule does not carry one (a degenerate
      * `Schedule.never` shape). Named so the magic 5-second window is auditable in one place rather than scattered.
      */
    private[kyo] val defaultLoadScheduleTimeout: Duration = 5.seconds

    /** Snapshot of the watcher state just before the trigger runs. */
    final private[internal] case class NavSnapshot(url: String, pushStateCount: Int, beforeUnload: Boolean)

    // --- Browser-level navigation orchestration ---

    private[kyo] def waitForLoad(tab: BrowserTab, schedule: Schedule)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        Retry[BrowserReadException](schedule) {
            CdpBackend.runtimeEvaluate(tab.client.withSession(tab.sessionId), EvalParams("document.readyState")).map { result =>
                CdpEvalDecoder.parseAndExtractEvalValue(result).map { value =>
                    if value == "complete" then ()
                    else
                        Abort.fail(
                            BrowserProtocolErrorException("waitForLoad", s"readyState=$value")
                        )
                    end if
                }
            }
        }
    end waitForLoad

    /** Runs `action` against the current tab and then waits for the page to settle per the active config's `loadSchedule`. Used by `back` /
      * `forward` / `reload` to share their navigate-then-wait-for-load shape.
      */
    private[kyo] def navigateAndWait(action: BrowserTab => Unit < (Async & Abort[BrowserReadException]))(
        using Frame
    ): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Browser.use { tab =>
                action(tab).andThen(waitForLoad(tab, cfg.loadSchedule))
            }
        }

end NavigationWatcher
