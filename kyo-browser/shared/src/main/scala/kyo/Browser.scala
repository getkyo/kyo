package kyo

import kyo.internal.*
import kyo.internal.CdpTypes.*
import kyo.internal.cdp.Accessibility
import kyo.internal.cdp.PageDownload
import kyo.kernel.Isolate

/** Drive a real browser (Chrome) from Kyo code.
  *
  * Use it for browser automation: end-to-end tests, scraping pages that require JavaScript, headless screenshots, or any task that needs a
  * full DOM and an event loop. Operations look like the things you'd do in a browser (navigate, click, fill, read text, assert visibility)
  * and return Kyo computations that fail typed errors instead of throwing.
  *
  * #### Minimal example
  *
  * ```scala
  * import kyo.*
  *
  * val effect =
  *     Browser.run { // launches Chrome-for-Testing (downloads on first use)
  *         Browser.goto("https://example.com").andThen {
  *             Browser.assertVisible(Browser.Selector.heading("Example Domain"))
  *         }
  *     }
  * ```
  *
  * `Browser.run` launches Chrome, attaches a fresh tab, runs the body, and tears the tab + Chrome process down when the body completes
  * (success, failure, or interruption). The Chrome lifetime is bound to the call itself: resources are discharged by an internal
  * `Scope.run`, so the caller's effect row does NOT carry `Scope`.
  *
  * For test suites where the Chrome boot cost adds up, [[Browser.runShared]] reuses a single Chrome process across many calls in the same
  * process.
  *
  * #### Why operations don't need explicit waits
  *
  * Browser automation usually fails not on the action itself but on the action firing before the page is ready. `Browser` answers this with
  * three settlement gates that all run automatically; you do not write `Async.sleep`. Each gate is a deliberate "wait until the page is
  * worth observing", with a sane default and an explicit escape hatch.
  *
  *   - **Navigation settlement.** [[Browser.goto]], [[Browser.back]], [[Browser.forward]], [[Browser.reload]], and
  *     [[Browser.expectNavigation]] block until the page reaches a requested [[Browser.Settle]] mode. Default
  *     [[Browser.Settle.NetworkIdle]]: `load` event fired AND no in-flight fetch/XHR for the configured window. Hides flake from
  *     third-party telemetry; costs a few hundred ms on chatty pages. Escape hatch: pass [[Browser.Settle.Load]] or
  *     [[Browser.Settle.DomContentLoaded]] for earlier returns.
  *   - **Mutation settlement.** [[Browser.click]], [[Browser.fill]], [[Browser.press]], [[Browser.check]], [[Browser.select]] first wait
  *     until the target element is actionable (attached, visible, not moving, hit-testable), then trigger the action, then wait for a quiet
  *     DOM window: no observed mutations for `mutationQuiescenceWindow`, bounded by `mutationSettlementTimeout`. Eliminates the "I clicked
  *     but the handler hadn't run yet" class of failures. Escape hatch: shrink the windows via [[Browser.withConfig]] or use
  *     [[kyo.internal.BrowserEval.evalJs]] / [[Browser.eval]] for raw JS.
  *   - **Assertion settlement.** [[Browser.assertText]], [[Browser.assertVisible]], [[Browser.assertCount]], [[Browser.waitForText]] and
  *     friends retry their predicate against `retrySchedule` until it passes or the schedule exhausts; a missing match is a typed timeout,
  *     not an instant `false`. Escape hatches: [[Browser.text]] / [[Browser.attribute]] for point-in-time reads, or
  *     [[Browser.waitFor]](jsCondition) for arbitrary JS predicates. Every retrying method also accepts a one-off `schedule: Schedule`.
  *
  * #### The four-layer lifecycle
  *
  * Everything you do happens inside a four-layer containment hierarchy. Higher layers contain lower; lower layers cease to exist when their
  * parent does. Most callers never think below "tab"; the layers exist so the API can give you safe scope-bounded resources.
  *
  * ```
  *   process       : one Chrome process; launched by `Browser.run(launch)` or shared across the JVM via `Browser.runShared`
  *     └─ context    : a fresh, cookie/storage-isolated "browser context" (Chrome's incognito-equivalent), created per `Browser.run` call
  *          └─ tab       : an attached target with its own session id; what the `Browser` effect carries via `Env[BrowserTab]`
  *               └─ iframe   : an in-tab nested document, scoped via `Browser.withIFrame`
  * ```
  *
  * Pick the entry point that matches your intent:
  *
  *   - [[Browser.run]]`(launch, session)`: launch a fresh Chrome, run the body, kill the process when the body completes. The usual
  *     production form.
  *   - [[Browser.runShared]]: reuse one Chrome process across many `Browser.runShared(...)` calls in the same run. Amortizes the ~2-3 s
  *     boot cost; intended for test suites with many short sessions.
  *   - [[Browser.run]]`(wsUrl)`: connect to an already-running Chrome via its DevTools URL.
  *
  * Inside the body, [[Browser.withNewTab]] opens a sibling tab in the same context, [[Browser.withFork]] runs a sub-computation against an
  * isolated tab (driven by an explicit `Isolate[Browser, ...]`), and [[Browser.withIFrame]] scopes operations to a child document.
  *
  * #### Configuration: two layers, decided at the right moment
  *
  * Configuration splits into two types because the two layers have different lifetimes:
  *
  *   - [[Browser.LaunchConfig]]: process-launch fields (`executable`, `headless`, `extraArgs`, `launchTimeout`). Frozen the moment Chrome
  *     starts; cannot be changed for the lifetime of that process. Override via `Browser.run(LaunchConfig.chromium("/opt/chrome"))(...)`.
  *   - [[Browser.SessionConfig]]: per-operation fields (`retrySchedule`, `loadSchedule`, network/mutation/assertion settle windows). Read
  *     by every retry and settlement loop, so it can change per scope. Override via [[Browser.withConfig]].
  *
  * [[Browser.withConfig]] only accepts `SessionConfig` updates; you cannot accidentally write `withConfig(_.executable("/opt/chrome"))` and
  * have it silently do nothing under `runShared`. The compiler stops you. For a per-scope time budget on retries + loads, the shortcut
  * [[Browser.withTimeout]] caps both via `maxDuration`.
  *
  * #### Errors
  *
  * Operations abort with typed Kyo errors. Categories all extend `BrowserException`:
  *
  *   - `BrowserConnectionException`: CDP transport failures: connection lost, protocol error, target detached
  *   - `BrowserElementException`: element not found, not actionable (off-screen, covered, disabled)
  *   - `BrowserScriptException`: JS evaluation threw, or returned a value that didn't match the expected wire shape
  *   - `BrowserAssertionException`: assertion timed out without matching
  *   - `BrowserSetupException`: failures launching Chrome (binary missing, port wouldn't bind, etc.)
  *
  * Catch the broad category with `Abort.run[BrowserException]`, or the specific one with `Abort.run[BrowserElementException]`. Every error
  * carries the `Frame` of the call site for diagnostics.
  *
  * #### Concurrent forks: Browser.isolate
  *
  * `Browser <: Env[BrowserTab] & Async`, but the opaque type hides the `Env` so two fibers cannot accidentally share a tab. Concurrent
  * combinators like [[kyo.Async.zip]] / [[kyo.Async.parallel]] / [[kyo.Loop.foreach]] require an `Isolate[Browser, ...]` to fork the
  * `Browser` effect across fibers; the compiler refuses to derive one automatically because there is no safe default split for a single CDP
  * session. `Browser.isolate` provides the two safe ones and you pick the right semantics explicitly.
  *
  *   - [[Browser.isolate.fresh]] gives each fork its own blank tab in a fresh browser context (cookies, localStorage, sessionStorage all
  *     start empty per fork). Use for "N independent searches", per-page scraping, parallel smoke tests against unrelated URLs.
  *   - [[Browser.isolate.clone]] snapshots the parent tab (URL + cookies + storage + form values + scroll + focus) and gives each fork a
  *     fresh browser context restored from that snapshot. Use when the per-fork work depends on the parent's logged-in state, current
  *     route, or in-flight form.
  *
  * Both isolate the cookie / storage jar at the browser-context level, so writes inside a fork do not leak back to the parent and forks
  * cannot observe each other. Both clean up their forked context when the surrounding scope completes (success, failure, or interruption).
  *
  * ```scala
  * Browser.isolate.fresh.use {
  *     Async.zip(
  *         Browser.goto(urlA).andThen(Browser.title),
  *         Browser.goto(urlB).andThen(Browser.title),
  *         Browser.goto(urlC).andThen(Browser.title)
  *     )
  * }
  * ```
  *
  * For a sequential isolated sub-computation (not parallel), use [[Browser.withFork]] (clone semantics) or [[Browser.withNewTab]]
  * (same-context sibling tab); neither requires an explicit `Isolate` value.
  *
  * @see
  *   **Lifecycle:** [[Browser.run]], [[Browser.runShared]], [[Browser.withNewTab]], [[Browser.withFork]], [[Browser.withIFrame]],
  *   [[Browser.isolate]].
  * @see
  *   **Navigation:** [[Browser.goto]], [[Browser.back]], [[Browser.forward]], [[Browser.reload]], [[Browser.expectNavigation]],
  *   [[Browser.Settle]].
  * @see
  *   **Interactions and reads:** [[Browser.click]], [[Browser.fill]], [[Browser.press]], [[Browser.dragAndDrop]], [[Browser.text]],
  *   [[Browser.attribute]], [[Browser.eval]].
  * @see
  *   **Settlement and assertions:** [[Browser.assertText]], [[Browser.assertVisible]], [[Browser.assertCount]], [[Browser.waitFor]],
  *   [[Browser.waitForText]], [[Browser.waitForNetworkIdle]].
  * @see
  *   **Configuration:** [[Browser.LaunchConfig]], [[Browser.SessionConfig]], [[Browser.withConfig]], [[Browser.withTimeout]].
  */
opaque type Browser <: Async = Env[BrowserTab] & Async

object Browser:

    // --- Lifecycle ---

    /** Selects which Chrome-for-Testing artifact [[chromeForTestingLaunchConfig]] downloads.
      *
      *   - [[HeadlessShell]] (default): `chrome-headless-shell`, Google's standalone headless build of Chrome (the same code path as
      *     `chrome --headless=new`, packaged without the GUI compositor / GPU stack / extension loader). ~120 MB compressed, faster
      *     startup, smaller memory footprint, fully CDP-compatible. This is Puppeteer's default since v22.
      *   - [[Chrome]]: the full `chrome` binary, ~190 MB compressed, with the GUI compositor included. Required for headed mode
      *     (`headless = false`) and for any feature that needs the UI surface (e.g. visible-window debugging). Behaves identically to
      *     [[HeadlessShell]] when launched with `headless = true`.
      */
    enum ChromeForTestingBuild derives CanEqual:
        case HeadlessShell
        case Chrome

    /** Downloads a Chrome-for-Testing binary (if not already cached) and returns a [[LaunchConfig]] pointing at the downloaded executable.
      * Equivalent to calling `LaunchConfig.default.copy(executable = <path>)` with the downloaded binary. The binary is cached under
      * `KYO_BROWSER_CACHE` (or the platform default) after the first download; each [[ChromeForTestingBuild]] caches independently, so
      * the two variants can coexist on disk.
      *
      * `build = ChromeForTestingBuild.HeadlessShell` (the default) fetches `chrome-headless-shell` (~120 MB, headless-only). Use
      * `build = ChromeForTestingBuild.Chrome` for the full `chrome` binary (~190 MB) when you need headed mode — call `.headless(false)`
      * on the returned config to launch with a visible window.
      *
      * `version = Absent` (the default) resolves the latest known-good Stable Chrome-for-Testing version dynamically by querying Google's
      * metadata endpoint; `Present(v)` pins to a caller-supplied build.
      *
      * Aborts with [[BrowserSetupException]] on platforms Google does not publish for (notably linux-arm64); the error text points users at
      * [[LaunchConfig.chromium]] for a system-installed Chromium.
      *
      * @see
      *   [[Browser.run(v)]], a convenience overload that calls this method (with default `build`) and immediately runs a computation
      */
    def chromeForTestingLaunchConfig(
        build: ChromeForTestingBuild = ChromeForTestingBuild.HeadlessShell,
        version: Maybe[String] = Absent
    )(using Frame): LaunchConfig < (Async & Abort[BrowserSetupException]) =
        ChromeDownloader.ensure(version, LaunchConfig.default.chromeDownloaderConfig, build)
            .map(exec => LaunchConfig.default.copy(executable = exec))

    /** Installs `session` as the active [[Browser.SessionConfig]] for the duration of `v`, then restores the previous config on body exit.
      *
      * This overload **replaces** the active config wholesale: any field set by an enclosing `withConfig` is discarded inside the body. Use
      * it when you have a complete `SessionConfig` and want it to apply exactly. To layer a single field on top of the current config
      * without losing other overrides, use the function-form overload `withConfig(f: SessionConfig => SessionConfig)` instead.
      *
      * Only session-time fields can be overridden via `withConfig`. Launch-time fields ([[LaunchConfig.executable]],
      * [[LaunchConfig.headless]], [[LaunchConfig.extraArgs]], [[LaunchConfig.launchTimeout]]) are not accessible here: by the time
      * `withConfig` runs, Chrome has already launched and its launch parameters are frozen for that process.
      */
    def withConfig[A, S](session: SessionConfig)(v: A < S)(using Frame): A < S = configLocal.let(session)(v)

    /** Applies `f` to the currently-active [[Browser.SessionConfig]] and installs the result for the duration of `v`, then restores the
      * previous config on body exit.
      *
      * Composes naturally with nesting: `f` sees the active config, so layering
      * `Browser.withConfig(_.retrySchedule(s1)) { Browser.withConfig(_.networkIdleWindow(w)) { ... } }` preserves the outer's
      * `retrySchedule` while the inner adds its own `networkIdleWindow` update. Contrast with the value-form overload
      * `withConfig(session: SessionConfig)`, which replaces the config wholesale.
      */
    def withConfig[A, S](f: SessionConfig => SessionConfig)(v: A < S)(using Frame): A < S =
        configLocal.use(c => configLocal.let(f(c))(v))

    /** Caps the retry/load schedule for every enclosed `Browser` operation at `timeout`.
      *
      * Equivalent to `withConfig(_.retrySchedule(_.maxDuration(timeout)).loadSchedule(_.maxDuration(timeout)))`. Useful as a shorthand for
      * setting a per-scope time budget on assertions, settlement waits, and navigation loads without having to compose the full
      * `withConfig` form. Does NOT abort on timeout the way `Async.timeout` does; it bounds the retry budget. An assertion that fails to
      * settle within `timeout` aborts with the usual `BrowserAssertionTimedOutException` (or the equivalent per-operation typed failure).
      *
      * @param timeout
      *   the maximum total duration the enclosed retry/load schedules may consume
      */
    def withTimeout[A, S](timeout: Duration)(v: A < (Browser & S))(using Frame): A < (Browser & S) =
        withConfig { cfg =>
            cfg.retrySchedule(cfg.retrySchedule.maxDuration(timeout))
                .loadSchedule(cfg.loadSchedule.maxDuration(timeout))
        }(v)

    /** Launches Chrome-for-Testing (downloading on the first call, cached afterwards) and runs the computation. Convenience for the common
      * "give me a browser, do stuff, clean up" case.
      *
      * Resource lifetime: Chrome + CDP client are bound to this call's scope and torn down when the body completes (success, failure, or
      * interruption). The caller's effect row does NOT include `Scope`; `Browser.run` discharges its own resources via an internal
      * `Scope.run`.
      */
    def run[A, S](v: A < (Browser & S))(using
        Frame
    ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        chromeForTestingLaunchConfig().map(launch => run(launch)(v))

    /** Launches a browser with the given launch + session config, runs the computation, and shuts down the process + client when the body
      * completes (success, failure, or interruption). Resource lifetime is bound to this call via an internal `Scope.run`, so the caller's
      * effect row does NOT include `Scope`.
      *
      * `configLocal` propagation: when `session` is `Absent` (the default), the outer caller's [[withConfig]] settings (if any) flow through
      * into the run's body. When `session` is `Present(sc)`, it acts as an explicit OVERRIDE: `withConfig(sc)` wraps the body and replaces
      * the entire `SessionConfig` for the inner scope.
      */
    def run[A, S](launch: Browser.LaunchConfig, session: Maybe[Browser.SessionConfig] = Absent)(v: A < (Browser & S))(using
        Frame
    ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        val body =
            for
                wsUrl  <- BrowserLauncher.launch(launch)
                client <- CdpClient.init(wsUrl, launch)
                tab    <- BrowserTabSetup.attachAndSetupTab(client)
                a      <- runOn(tab)(v)
            yield a
        Scope.run {
            session match
                case Absent      => body
                case Present(sc) => withConfig(sc)(body)
        }
    end run

    /** Connects to an existing browser via WebSocket URL and runs the computation. Closes the client when the body completes (success,
      * failure, or interruption). Resource lifetime is bound to this call via an internal `Scope.run`.
      */
    def run[A, S](wsUrl: String)(v: A < (Browser & S))(using Frame): A < (Async & Abort[BrowserReadException] & S) =
        Scope.run(CdpClient.init(wsUrl, Browser.LaunchConfig.default).map(client =>
            BrowserTabSetup.attachAndSetupTab(client).map(tab => runOn(tab)(v))
        ))

    /** Runs the computation against a JVM-shared Chrome process. The process is launched lazily on the first call, kept alive for the
      * lifetime of the JVM, and torn down via a shutdown hook. Each `runShared` call attaches its own tab and tears the tab down when the
      * body completes (success, failure, or interruption) via an internal `Scope.run`, so the caller's effect row does NOT include `Scope`.
      * Equivalent to launching a Chrome via `Browser.chromeForTestingLaunchConfig()` and running `Browser.run(url)(f)` against it, but
      * amortizes the ~2-3s Chrome boot cost across all calls in the same JVM.
      *
      * Intended for test suites that drive many short browser sessions. Not a substitute for `Browser.run(launch)` in production code,
      * where one-off browser sessions are normal.
      *
      * @param session
      *   session-time configuration installed for the inner computation. Launch-time fields cannot be overridden here: the shared Chrome
      *   was launched once with the canonical config at process start.
      */
    def runShared[A, S](session: Maybe[Browser.SessionConfig] = Absent)(f: A < (Browser & S))(using
        Frame
    ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        // Browser.run(url) already wraps its body in Scope.run; runShared inherits that absorption.
        // configLocal propagation mirrors Browser.run(launch, session): Present(sc) overrides via withConfig; Absent inherits outer Local.
        // withUrl (not init) so a dead shared Chrome is invalidated and relaunched once instead of cascading the dead URL to every caller.
        val body = SharedChrome.withUrl(url => Browser.run(url)(f))
        session match
            case Absent      => body
            case Present(sc) => withConfig(sc)(body)
    end runShared

    // --- Navigation ---

    /** Navigates the current tab to the given URL and waits for the page to settle per `settle` (default
      * [[kyo.Browser.Settle.NetworkIdle]], which waits for the `load` event plus a quiet network window, strictly stronger than
      * `readyState === 'complete'`).
      *
      * @param url
      *   destination URL
      * @param settle
      *   settle mode; pass [[kyo.Browser.Settle.DomContentLoaded]] or [[kyo.Browser.Settle.Load]] for earlier-returning behaviour
      * @param failOnHttpError
      *   when `true` (the default), a 4xx/5xx response raises [[BrowserNavigationFailedException]]. Pass `false` when you intend to read
      *   the page content of an error response, typical for bot-detection 4xx pages (some package registries serve a useful challenge body
      *   alongside a 403), auth-walled 401 pages whose content you want to inspect, or scraping flows that treat the error page as data.
      *   The default fails loud because, for most automation, a 4xx/5xx mid-flow is a real bug: silently returning would leave subsequent
      *   assertions to fail with confusing element-not-found errors against the error page.
      */
    def goto(
        url: String,
        settle: Browser.Settle = Browser.Settle.NetworkIdle,
        failOnHttpError: Boolean = true
    )(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        // `data:` URLs produce no network traffic; `NetworkIdle` would wait for an idle window that never opens. Auto-downgrade the
        // default to `Load`. An explicit non-default settle is honored as-is.
        val effectiveSettle =
            if url.startsWith("data:") && settle == Browser.Settle.NetworkIdle then Browser.Settle.Load
            else settle
        // Same-URL no-op: Chrome's `Page.navigate` against the URL the tab is already at does NOT actually navigate, but the
        // navigation watcher waits for a URL-change or push-state signal that will never arrive, timing out with "navigation
        // never committed". Mirror the browser-native `location.assign(currentUrl)` semantics: short-circuit when the tab is
        // already at the target URL. Callers wanting an explicit refresh have [[Browser.reload]].
        Browser.url.map { currentUrl =>
            if currentUrl == url then Kyo.unit
            else
                NavigationWatcher.armAroundNavigation(effectiveSettle, failOnHttpError) {
                    Env.use[BrowserTab](tab => CdpBackend.navigate(tab.session, NavigateParams(url)))
                }
        }
    end goto

    /** Returns the current tab's navigation history: every entry the tab has visited plus an index pointing to the active entry. Use to
      * display a back/forward UI, to reason about whether `back` or `forward` is meaningful, or to assert on the navigation trace from a
      * test. The wire-level CDP shape (id/url/title plus a current pointer) is exposed verbatim.
      */
    def history(using Frame): NavigationHistory < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.getNavigationHistory(tab.session).map { wire =>
                NavigationHistory(
                    currentIndex = wire.currentIndex,
                    entries = Chunk.from(wire.entries.map(e => NavigationEntry(e.id, e.url, e.title)))
                )
            }
        }

    /** Navigates back in the browser history.
      *
      * Raises [[BrowserAlreadyAtHistoryStartException]] when no prior entry exists (i.e., `history.currentIndex == 0`). Callers that want
      * browser-style no-op semantics at the boundary can recover explicitly:
      * ```scala
      * Abort.recover { case _: BrowserAlreadyAtHistoryStartException => () }
      * ```
      */
    def back(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        history.map { h =>
            if h.currentIndex > 0 then
                NavigationWatcher.navigateAndWait { tab =>
                    val entry = h.entries(h.currentIndex - 1)
                    CdpBackend.navigateToHistoryEntry(tab.session, NavigateToEntryParams(entry.id))
                }
            else Abort.fail(BrowserAlreadyAtHistoryStartException())
        }

    /** Navigates forward in the browser history.
      *
      * Raises [[BrowserAlreadyAtHistoryEndException]] when no later entry exists (i.e., `history.currentIndex == history.entries.size -
      * 1`). Callers that want browser-style no-op semantics at the boundary can recover explicitly:
      * ```scala
      * Abort.recover { case _: BrowserAlreadyAtHistoryEndException => () }
      * ```
      */
    def forward(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        history.map { h =>
            if h.currentIndex < h.entries.size - 1 then
                NavigationWatcher.navigateAndWait { tab =>
                    val entry = h.entries(h.currentIndex + 1)
                    CdpBackend.navigateToHistoryEntry(tab.session, NavigateToEntryParams(entry.id))
                }
            else Abort.fail(BrowserAlreadyAtHistoryEndException())
        }

    /** Reloads the current page.
      *
      * @param settle
      *   settle mode; defaults to [[Browser.Settle.NetworkIdle]]. Mirror of [[Browser.goto]]'s settle.
      * @param failOnHttpError
      *   when `true` (the default), a 4xx/5xx response on the reloaded entry raises [[BrowserNavigationFailedException]]. See
      *   [[Browser.goto]] for guidance.
      * @param hardReload
      *   when `true`, bypass HTTP cache (issues `Page.reload` with `ignoreCache = true`).
      */
    def reload(
        settle: Browser.Settle = Browser.Settle.NetworkIdle,
        failOnHttpError: Boolean = true,
        hardReload: Boolean = false
    )(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        NavigationWatcher.armAroundReload(settle, failOnHttpError) {
            Env.use[BrowserTab](tab => CdpBackend.reload(tab.session, ReloadParams(ignoreCache = hardReload)))
        }

    /** Wraps an arbitrary trigger that is expected to cause a navigation and waits for the page to settle per `settle`. Useful for form
      * submissions, custom JS calls, or any action where the caller knows a navigation will occur but [[Browser.click]] is not the entry
      * point.
      *
      * Unlike [[Browser.click]]'s built-in auto-wait (which skips the wait if no navigation starts within a grace window),
      * `expectNavigation` unconditionally waits for the navigation to commit, matching the semantics of [[Browser.goto]]. If the trigger
      * does not produce a navigation within the active `loadSchedule` budget, the call aborts with [[BrowserNavigationFailedException]].
      *
      * @param settle
      *   settle mode; defaults to [[Browser.Settle.NetworkIdle]]
      * @param failOnHttpError
      *   when `true` (the default), a 4xx/5xx response on the primary navigation entry raises [[BrowserNavigationFailedException]]. See
      *   [[Browser.goto]] for guidance on when to override.
      * @param trigger
      *   the computation that should initiate a navigation
      */
    def expectNavigation[A](
        settle: Settle = Settle.NetworkIdle,
        failOnHttpError: Boolean = true
    )(trigger: A < (Browser & Abort[BrowserReadException]))(using
        Frame
    )
        : A < (Browser & Abort[BrowserReadException]) =
        NavigationWatcher.armAroundNavigation(settle, failOnHttpError)(trigger)

    // --- Interactions ---

    /** Clicks the element matched by the selector. When the actionability gate reports that the target element has navigation intent (`<a
      * href>`, form submit button, element whose `onclick` calls `location.*`), this method auto-waits for the navigation to settle per
      * [[Browser.Settle.NetworkIdle]], so test code never sees the post-click state before the new page has loaded.
      */
    def click(selector: Selector)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { ref =>
                if ref.navigatesOnClick then
                    // Skip mutation settlement entirely on nav-intent clicks: the destination is a fresh DOM; settling against
                    // the old page's observer is moot (the observer itself is wiped on navigation).
                    NavigationWatcher.armAround(Browser.Settle.NetworkIdle, throwOnFailure = true)(BrowserEval.clickAtActionable(ref, 1))
                else
                    MutationSettlement.afterAction(BrowserEval.clickAtActionable(ref, 1))(scopeSelector = Present(selector))
            }
        }

    /** Double-clicks the element matched by the selector. */
    def doubleClick(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { ref =>
                BrowserEval.clickAtActionable(ref, 2)
            }
        }

    /** Hovers over the element matched by the selector.
      *
      * Note: a disabled element does NOT block `hover`. Real browsers still fire `mouseover` / `mousemove` events against disabled controls
      * (disabled tooltips and `:hover` styles must work), so the actionability gate skips the disabled probe for `hover`.
      */
    def hover(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = false) { ref =>
                Env.use[BrowserTab](tab => BrowserEval.dispatchMouse(tab, CdpTypes.MouseEventType.Moved, ref.x, ref.y, 0))
            }
        }

    /** Fills the input element matched by the selector with the given text.
      *
      * A single `Runtime.evaluate` JS dispatch sets the element's value via the prototype-resolved native setter
      * (`HTMLInputElement.prototype` / `HTMLTextAreaElement.prototype` / `HTMLSelectElement.prototype`), then dispatches exactly one
      * `input` event and exactly one `change` event (both `bubbles: true`). The native setter bypasses framework-overridden prototype
      * setters (e.g. React's controlled-input monkey-patch) so frameworks still observe a genuine `input` event from a real value mutation.
      * After dispatch the element is left focused with the caret at the end of its value, so a follow-up [[Browser.press]] operates on the
      * end of the value.
      *
      * Behavior contract:
      *   - Exactly one `input` and exactly one `change` event per `fill` call, regardless of prior content.
      *   - `fill("")` fires exactly one `input` event with empty value.
      *   - The input is left focused with caret at end of value.
      *   - All HTML5 input types route through the same shim: date, time, datetime-local, month, week, number, range, color, textarea, and
      *     any other type whose `value` is a `string`.
      *   - SELECT elements take a JS-internal short-circuit that mirrors [[Browser.select]]'s semantics (assign `el.value`, dispatch
      *     `change` only, no readback).
      *
      * After dispatch the value is read back via `Runtime.evaluate`: if the framework or the element type rejected / normalized the value
      * away from `text` (e.g. `<input type="number">` storing `""` for non-numeric input), the enclosing Retry loop catches
      * [[BrowserElementNotActionableException.Reason.FillDesync]] and retries until the schedule exhausts.
      *
      * Does not fire `compositionstart` / `compositionend` events; IME-aware listeners must subscribe to `input` events instead.
      */
    def fill(selector: Selector, text: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        // `requireFillable = false` so SELECT passes the actionability gate; `ProbesJs.fillViaJs` enforces the fillable check itself
        // (returning `'not_fillable'` for non-form-control targets, which surfaces as [[BrowserElementNotActionableException.Reason.NotFillable]])
        // and skips `verifyFilledValue` on SELECT to preserve `Browser.select`'s no-readback no-op semantics for missing options.
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { _ =>
                val fillJs
                    : Unit < (Browser & Abort[BrowserReadException]) =
                    ProbesJs.fillViaJs(selector, text).map { wasSelect =>
                        if wasSelect then ()
                        else ProbesJs.verifyFilledValue(selector, text)
                    }
                MutationSettlement.afterAction(fillJs)(scopeSelector = Present(selector))
            }
        }
    end fill

    /** Checks the checkbox matched by the selector. */
    def check(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        setCheckboxState(selector, target = true)

    /** Unchecks the checkbox matched by the selector. */
    def uncheck(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        setCheckboxState(selector, target = false)

    private def setCheckboxState(selector: Selector, target: Boolean)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        // Build the resolver JS once, outside the retry loop: `SelectorJs.resolveElementJs` is a pure function of `selector`, and the
        // retry tick only re-runs the DOM evaluation, not the JS-template construction. The JS short-circuits when the checkbox is
        // already in the target state so we don't fire a redundant `change` event.
        val jsExpr      = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val targetValue = target.toString
        val currentTest = if target then "!el.checked" else "el.checked"
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { _ =>
                val inner = BrowserEval.evalJs(s"""(() => {
                    const el = $jsExpr;
                    if ($currentTest) { el.checked = $targetValue; el.dispatchEvent(new Event('change', {bubbles: true})); }
                    return 'ok';
                })()""").unit
                MutationSettlement.afterAction(inner)(scopeSelector = Present(selector))
            }
        }
    end setCheckboxState

    /** Selects the option with the given value in a select element. */
    def select(selector: Selector, value: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        val escaped = JsStringUtil.escapeJsString(value)
        val jsExpr  = SelectorJs.resolveElementJs(Selector.toNode(selector))
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { _ =>
                val inner = BrowserEval.evalJs(s"""(() => {
                    const el = $jsExpr;
                    el.value = '$escaped';
                    el.dispatchEvent(new Event('change', {bubbles: true}));
                    return 'ok';
                })()""").unit
                MutationSettlement.afterAction(inner)(scopeSelector = Present(selector))
            }
        }
    end select

    /** Presses the given key without targeting a specific element; dispatches `Input.dispatchKeyEvent` against whatever currently has focus
      * (typically `document.body` when no element is focused). Useful for global shortcuts (e.g. `Key.Escape` to dismiss a modal, `Key.Tab`
      * to move focus). Settlement is rooted at `document.body` since there is no scoped target.
      */
    def press(key: Key)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        press(key, KeyModifiers.none)

    /** Like [[press(key)]] but holds the supplied `modifiers` (`shift`, `ctrl`, `alt`, `meta`) for both keyDown and keyUp. Modifiers OR
      * with `KeyInfo.mapKey(key).modifierBit`. Construct via the named-arg case-class apply, e.g. `KeyModifiers(shift = true)`, or via
      * the [[KeyModifiers.of]] factory.
      */
    def press(
        key: Key,
        modifiers: KeyModifiers
    )(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val info = KeyInfo.mapKey(key)
            val s    = tab.session
            val callerMods =
                (if modifiers.shift then 8 else 0) |
                    (if modifiers.ctrl then 2 else 0) |
                    (if modifiers.alt then 1 else 0) |
                    (if modifiers.meta then 4 else 0)
            val totalMods = info.modifierBit | callerMods
            val mods      = if totalMods > 0 then Present(totalMods) else Absent
            val markIfTab: Unit < (Browser & Abort[BrowserReadException]) =
                if info.keyName == "Tab" then ProbesJs.markActiveElementForTabAdvance else ()
            val inner = markIfTab.andThen(
                CdpBackend.dispatchKeyEvent(
                    s,
                    DispatchKeyEventParams(
                        CdpTypes.KeyEventType.Down,
                        Present(info.keyName),
                        info.text,
                        Present(info.domCode),
                        Present(info.keyCode),
                        mods
                    )
                ).andThen(
                    CdpBackend.dispatchKeyEvent(
                        s,
                        DispatchKeyEventParams(
                            CdpTypes.KeyEventType.Up,
                            Present(info.keyName),
                            Absent,
                            Present(info.domCode),
                            Present(info.keyCode),
                            mods
                        )
                    ).andThen {
                        if info.keyName == "Tab" then ProbesJs.runTabFocusAdvance(modifiers.shift)
                        else if info.keyName == "Space" then ProbesJs.runSpaceClickSynthesis
                        else ()
                    }
                )
            )
            MutationSettlement.afterAction(inner)(scopeSelector = Absent)
        }

    /** Presses `key` while `selector` is focused, with the given `modifiers` held for both keyDown and keyUp. Modifiers OR with
      * `KeyInfo.mapKey(key).modifierBit`, so `press(sel, Key.Shift)` is unchanged.
      *
      * `modifiers` defaults to [[KeyModifiers.none]]; supply a non-default value via the named-arg case-class apply, e.g.
      * `KeyModifiers(shift = true)`, or the [[KeyModifiers.of]] factory.
      *
      * Note: a disabled element does NOT block `press`. Real browsers still fire `keydown` / `keyup` against disabled inputs (the page's
      * keyboard handlers can observe focus + key state regardless), so the actionability gate skips the disabled probe for `press`.
      * Visibility / attached / stability / hittable checks all still run.
      */
    def press(
        selector: Selector,
        key: Key,
        modifiers: KeyModifiers = KeyModifiers.none
    )(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        // Transparent fallback to `document.activeElement` on a stale selector, ONLY when something other than
        // `<body>` is currently focused. SPA frameworks (Wikipedia typeahead, React-controlled inputs, etc.)
        // re-render the input after the first event, so a selector captured before fill / typing is no longer
        // attached when `press` runs. The intent of `press(selector, key)` is "type into THIS element", but the
        // selector points at the pre-render snapshot. Real-browser focus follows the re-rendered element, so falling
        // back to `activeElement` (the no-selector `press(key)` path) recovers the user's intent.
        //
        // If `activeElement` is `<body>` (no real focus), the fallback would silently dispatch at the page root,
        // which is wrong for a selector that NEVER attached. Preserve the original NotAttached abort in that case.
        Abort.recover[BrowserElementNotActionableException] {
            case ex if ex.reason == BrowserElementNotActionableException.Reason.NotAttached =>
                BrowserEval.evalJs(
                    "(() => { const ae = document.activeElement; return (ae && ae !== document.body) ? '1' : '0'; })()"
                ).map { hasRealFocus =>
                    if hasRealFocus == "1" then
                        Log.info(
                            s"Browser.press(selector, $key): selector ${ex.selector} no longer attached; falling back to " +
                                s"document.activeElement (the no-selector press(key) path)."
                        ).andThen(press(key, modifiers))
                    else
                        Abort.fail(ex)
                }
            case ex =>
                // Any reason other than NotAttached: re-raise unchanged so the original abort propagates
                // instead of falling through to a MatchError.
                Abort.fail(ex)
        } {
            pressOnSelector(selector, key, modifiers)
        }
    end press

    private def pressOnSelector(
        selector: Selector,
        key: Key,
        modifiers: KeyModifiers
    )(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = false) { _ =>
                val inner = ProbesJs.focusElementIfNotFocused(selector).andThen {
                    Env.use[BrowserTab] { tab =>
                        val info = KeyInfo.mapKey(key)
                        val s    = tab.session
                        val callerMods =
                            (if modifiers.shift then 8 else 0) |
                                (if modifiers.ctrl then 2 else 0) |
                                (if modifiers.alt then 1 else 0) |
                                (if modifiers.meta then 4 else 0)
                        val totalMods = info.modifierBit | callerMods
                        val mods      = if totalMods > 0 then Present(totalMods) else Absent
                        val markIfTab: Unit < (Browser & Abort[BrowserReadException]) =
                            if info.keyName == "Tab" then ProbesJs.markActiveElementForTabAdvance else ()
                        markIfTab.andThen(
                            CdpBackend.dispatchKeyEvent(
                                s,
                                DispatchKeyEventParams(
                                    CdpTypes.KeyEventType.Down,
                                    Present(info.keyName),
                                    info.text,
                                    Present(info.domCode),
                                    Present(info.keyCode),
                                    mods
                                )
                            ).andThen(
                                CdpBackend.dispatchKeyEvent(
                                    s,
                                    DispatchKeyEventParams(
                                        CdpTypes.KeyEventType.Up,
                                        Present(info.keyName),
                                        Absent,
                                        Present(info.domCode),
                                        Present(info.keyCode),
                                        mods
                                    )
                                ).andThen {
                                    if info.keyName == "Tab" then ProbesJs.runTabFocusAdvance(modifiers.shift)
                                    else if info.keyName == "Space" then ProbesJs.runSpaceClickSynthesis
                                    else ()
                                }
                            )
                        )
                    }
                }
                MutationSettlement.afterAction(inner)(scopeSelector = Present(selector))
            }
        }

    /** Focuses the element matched by the selector. */
    def focus(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(selector, requireFillable = false, requireEnabled = true) { _ =>
                ProbesJs.focusElement(selector)
            }
        }

    /** Drags the source element and drops it onto the target element. Both source and target are gated on actionability. */
    def dragAndDrop(source: Selector, target: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            Actionability.withActionable(source, requireFillable = false, requireEnabled = true) { srcRef =>
                Actionability.withActionable(target, requireFillable = false, requireEnabled = true) { tgtRef =>
                    Env.use[BrowserTab] { tab =>
                        for
                            _ <- BrowserEval.dispatchMouse(tab, CdpTypes.MouseEventType.Moved, srcRef.x, srcRef.y, 0)
                            _ <- BrowserEval.dispatchMouse(tab, CdpTypes.MouseEventType.Pressed, srcRef.x, srcRef.y, 1)
                            _ <- BrowserEval.dispatchMouse(tab, CdpTypes.MouseEventType.Moved, tgtRef.x, tgtRef.y, 0)
                            _ <- BrowserEval.dispatchMouse(tab, CdpTypes.MouseEventType.Released, tgtRef.x, tgtRef.y, 1)
                        yield ()
                    }
                }
            }
        }

    /** Sets files on a file input element. Each [[Path]] must be absolute; relative paths are rejected with
      * [[BrowserInvalidArgumentException]] before any CDP call is issued.
      *
      * The paths are passed verbatim to the CDP `DOM.setFileInputFiles` request, which is keyed by Chrome's view of the local filesystem.
      */
    def setFiles(selector: Selector, paths: Seq[Path])(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.withRetry {
            val strs        = Chunk.from(paths).map(_.toString)
            val nonAbsolute = strs.iterator.find(p => !isAbsolutePath(p))
            nonAbsolute match
                case Some(p) =>
                    Abort.fail(BrowserInvalidArgumentException("setFiles", s"every path must be absolute, got '$p'"))
                case None =>
                    setFilesCore(selector, strs)
            end match
        }

    // --- Assertions ---

    /** Asserts that at least one element matching the selector exists on the page. */
    def assertExists(selector: Selector, schedule: Maybe[Schedule] = Absent)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(_ > 0)(_ => BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector)))).unit

    /** Asserts that no elements matching the selector exist on the page. */
    def assertNotExists(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(_ == 0)(n => BrowserAssertionTimedOutException("0 elements", s"$n found")).unit

    /** Asserts that the text content of the element matches the expected string. */
    def assertText(selector: Selector, expected: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, expected, Absent)(SelectorJs.readTextExprJs, _ == expected)

    /** Asserts that the text content of the element matches the expected string, using a custom retry schedule. */
    def assertText(selector: Selector, expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, expected, schedule)(SelectorJs.readTextExprJs, _ == expected)

    /** Asserts that the named attribute of the element matches the expected string. */
    def assertAttribute(selector: Selector, name: String, expected: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, expected, Absent)(SelectorJs.readAttributeExprJs(name, _), _ == expected)

    /** Asserts that the named attribute of the element matches the expected string, using a custom retry schedule. */
    def assertAttribute(selector: Selector, name: String, expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, expected, schedule)(SelectorJs.readAttributeExprJs(name, _), _ == expected)

    /** Asserts the element matching `selector` has ARIA role `expected`. Retries under the active retry schedule. Raises
      * [[BrowserAssertionTimedOutException]] when the role doesn't match. The probe is an accessibility-tree round-trip (CDP), not an
      * attribute read; the AX role (computed by Chrome's name/role algorithm) is the source of truth.
      */
    def assertRole(selector: Selector, expected: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                role(selector).map {
                    case Present(actual) if actual == expected => ()
                    case Present(actual)                       => Abort.fail(BrowserAssertionTimedOutException(expected, actual))
                    case Absent                                => Abort.fail(BrowserAssertionTimedOutException(expected, "not_attached"))
                }
            }
        }

    /** Asserts the element matching `selector` has accessible name `expected`. Retries under the active retry schedule. Raises
      * [[BrowserAssertionTimedOutException]] when the name doesn't match. Honours `aria-label` priority over `textContent`, matching
      * Chrome's name-computation algorithm.
      */
    def assertAccessibleName(selector: Selector, expected: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                accessibleName(selector).map {
                    case Present(actual) if actual == expected => ()
                    case Present(actual)                       => Abort.fail(BrowserAssertionTimedOutException(expected, actual))
                    case Absent                                => Abort.fail(BrowserAssertionTimedOutException(expected, "not_attached"))
                }
            }
        }

    /** Asserts that the current page URL matches the expected string. */
    def assertUrl(expected: String)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(expected, Absent)("window.location.href", _ == expected)

    /** Asserts that the current page URL matches the expected string, using a custom retry schedule. */
    def assertUrl(expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(expected, schedule)("window.location.href", _ == expected)

    /** Asserts that the current page title matches the expected string. */
    def assertTitle(expected: String)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(expected, Absent)("document.title", _ == expected)

    /** Asserts that the current page title matches the expected string, using a custom retry schedule. */
    def assertTitle(expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(expected, schedule)("document.title", _ == expected)

    /** Asserts that the text content of the element satisfies the predicate.
      *
      * Reads `Browser.assertTextSatisfies(sel, "starts with Hello") { _.startsWith("Hello") }`. The message describes the property under test
      * and is surfaced verbatim in the assertion failure on mismatch.
      */
    def assertTextSatisfies(
        selector: Selector,
        message: String
    )(predicate: String => Boolean)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, message, Absent)(SelectorJs.readTextExprJs, predicate)

    /** Asserts that the named attribute of the element satisfies the predicate.
      *
      * Reads `Browser.assertAttributeSatisfies(sel, "href", "starts with https") { _.startsWith("https") }`.
      */
    def assertAttributeSatisfies(
        selector: Selector,
        name: String,
        message: String
    )(predicate: String => Boolean)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssert(selector, message, Absent)(SelectorJs.readAttributeExprJs(name, _), predicate)

    /** Asserts that the current page URL satisfies the predicate.
      *
      * Reads `Browser.assertUrlSatisfies("starts with data:") { _.startsWith("data:") }`.
      */
    def assertUrlSatisfies(
        message: String
    )(predicate: String => Boolean)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(message, Absent)("window.location.href", predicate)

    /** Asserts that the current page title satisfies the predicate.
      *
      * Reads `Browser.assertTitleSatisfies("contains Settings") { _.contains("Settings") }`.
      */
    def assertTitleSatisfies(
        message: String
    )(predicate: String => Boolean)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage(message, Absent)("document.title", predicate)

    /** Asserts that the given substrings all appear inside `document.body.innerText` in order. Each substring must occur after the previous
      * one's end, so the list defines a sequence, but consecutive substrings need not be contiguous in the page text. Retries against the
      * active retry schedule until every substring is found or the schedule exhausts, at which point it raises
      * [[BrowserAssertionTimedOutException]] whose actual value is `"missing:<substring>"` for the first substring that could not be
      * located.
      *
      * @param substrings
      *   the substrings to look for, in the order they must appear; matched against the page's whitespace-collapsed
      *   `document.body.innerText`. Each substring must be found at or after the previous match's end position; the matches need not be
      *   contiguous.
      * @param schedule
      *   optional override for the retry schedule; `Absent` uses the active configuration's default.
      * @throws BrowserAssertionTimedOutException
      *   when the retry schedule exhausts before all substrings are found in order; `actual` is `"missing:<substring>"` for the first
      *   substring that could not be located, or the last observed non-`"ok"` probe result.
      */
    def assertPageTextOrder(substrings: Seq[String], schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryReadAssertPage("ok", schedule)(ProbesJs.textOrderExprJs(substrings), _ == "ok")

    /** Asserts that the given selectors resolve to elements that appear in document-order (top-to-bottom) in the rendered DOM. Retries
      * against the active retry schedule until every adjacent pair satisfies the ordering check or the schedule exhausts, at which point it
      * raises [[BrowserAssertionTimedOutException]] whose actual value is `"not_attached:<index>"` (1-based) for the first selector that
      * fails to resolve, or `"out_of_order:<prev_index>:<curr_index>"` (1-based) for the first adjacent pair that breaks document-order.
      *
      * @param selectors
      *   the selectors whose elements must appear in document-order via `Node.compareDocumentPosition`; each pair `(prev, curr)` must
      *   satisfy `prev.compareDocumentPosition(curr) & Node.DOCUMENT_POSITION_FOLLOWING`.
      * @param schedule
      *   optional override for the retry schedule; `Absent` uses the active configuration's default.
      * @throws BrowserAssertionTimedOutException
      *   when the retry schedule exhausts before every adjacent pair is in document-order; `actual` is `"not_attached:<index>"` for the
      *   first unresolved selector or `"out_of_order:<prev_index>:<curr_index>"` for the first out-of-order pair.
      */
    def assertSelectorOrder(selectors: Seq[Selector], schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val jsList = selectors.map(s => SelectorJs.resolveElementJs(Selector.toNode(s)))
        BrowserAssertion.retryReadAssertPage("ok", schedule)(ProbesJs.elementOrderExprJs(jsList), _ == "ok")
    end assertSelectorOrder

    /** Asserts that the element is visible: attached to the DOM and not hidden by `display:none`, `visibility:hidden`, zero-size, or a
      * hidden ancestor.
      *
      * Passive read: the probe only reads computed style + bounding rect, it does not scroll or mutate the page. Retries until the
      * condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertVisible", "visible", actual)` where `actual` describes why the element is not visible.
      */
    def assertVisible(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "visible", schedule)(ProbesJs.visibilityExprJs(selector))

    /** Asserts that the element is present but NOT visible: `display:none`, `visibility:hidden`, zero-size, or hidden via an ancestor.
      *
      * Distinct from [[assertNotExists]]: a missing element fails fast with [[BrowserElementException]] rather than retrying. Use
      * `assertNotExists` when you want to assert the element is gone from the DOM entirely.
      */
    def assertNotVisible(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryNegativeProbe("not visible", selector, schedule)(ProbesJs.visibilityExprJs(selector))(_ == "visible")

    /** Asserts that the element is enabled: attached, visible, and neither `disabled` nor `aria-disabled="true"`.
      *
      * Passive read: the probe does not scroll or mutate the page. Retries until the condition holds or the retry schedule exhausts, at
      * which point it raises `BrowserAssertionTimedOutException("assertEnabled", "enabled", actual)`.
      */
    def assertEnabled(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "enabled", schedule)(ProbesJs.enabledExprJs(selector))

    /** Asserts that the element is disabled: attached AND (`element.disabled === true` OR `aria-disabled="true"`).
      *
      * Retries until the condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertDisabled", "disabled", actual)`.
      */
    def assertDisabled(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "disabled", schedule)(ProbesJs.disabledExprJs(selector))

    /** Asserts that the element's `checked` property is `true` (checkbox or radio input).
      *
      * Retries until the condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertChecked", "checked", actual)`.
      */
    def assertChecked(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "checked", schedule)(ProbesJs.checkedExprJs(selector))

    /** Asserts that the element's `checked` property is `false` (checkbox or radio input).
      *
      * A missing element fails fast with [[BrowserElementException]] rather than retrying; use [[assertNotExists]] when you want to assert
      * the element is gone from the DOM.
      */
    def assertNotChecked(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryNegativeProbe("not_checked", selector, schedule)(ProbesJs.checkedExprJs(selector))(_ != "not_checked")

    /** Asserts that the named attribute is ABSENT from the resolved element (`!el.hasAttribute(name)`), not merely empty or different from
      * some expected value. A missing element fails fast with [[BrowserElementException]] rather than retrying; use [[assertNotExists]] to
      * assert the element itself is gone from the DOM.
      *
      * @param selector
      *   the selector resolving the target element
      * @param name
      *   the attribute name whose absence is asserted
      * @param schedule
      *   optional override for the retry schedule
      * @see
      *   [[assertAttribute]] for the positive counterpart asserting an attribute value.
      */
    def assertNoAttribute(selector: Selector, name: String, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserAssertion.retryNegativeProbe("absent", selector, schedule)(ProbesJs.noAttributeExprJs(name, jsExpr))(_ == "present")
    end assertNoAttribute

    /** Asserts that exactly `expected` elements match the selector. Uses `Resolver.resolveAll` under the hood.
      *
      * Retries until the condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertCount", s"$expected matching", s"$actual matching")`.
      */
    def assertCount(selector: Selector, expected: Int, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(_ == expected)(actual =>
            BrowserAssertionTimedOutException(
                s"assertCount ${selectorNodeDescription(Selector.toNode(selector))}",
                s"$expected matching",
                s"expected $expected matching but got $actual"
            )
        ).unit
    end assertCount

    /** Asserts that the number of elements matching `selector` satisfies `predicate`. Use for "at least N" / "at most N" / range checks
      * that the exact-count overload cannot express.
      *
      * Reads `Browser.assertCountSatisfies(sel, "at least 5") { _ >= 5 }`. The message describes the property under test and is surfaced
      * verbatim in the assertion failure on mismatch.
      *
      * Retries until the predicate holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertCount", message, s"got $actual")`.
      */
    def assertCountSatisfies(
        selector: Selector,
        message: String
    )(predicate: Int => Boolean)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        assertCountSatisfies(selector, message, Absent)(predicate)

    /** Predicate-form [[assertCountSatisfies]] that accepts a per-call retry-schedule override. The exact-int overload already exposes the
      * `schedule` parameter; this overload brings the predicate flavour into line so users can tighten or loosen the schedule per call
      * (e.g. `Present(Schedule.fixed(50.millis).take(3))` for a quick smoke check or a longer schedule for a slow-loading page).
      */
    def assertCountSatisfies(
        selector: Selector,
        message: String,
        schedule: Maybe[Schedule]
    )(predicate: Int => Boolean)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(predicate)(actual =>
            BrowserAssertionTimedOutException(
                s"assertCount ${selectorNodeDescription(Selector.toNode(selector))}",
                message,
                s"expected $message but got $actual"
            )
        ).unit
    end assertCountSatisfies

    /** Asserts that the element has no visible text: `textContent.trim()` is empty.
      *
      * Designed for non-input elements. For `<input>` / `<textarea>` / `<select>` (which carry their content in the `value` property, not in
      * `textContent`), use [[assertValueEmpty]]. The two-method split makes the asserted property explicit at the call site, avoiding the
      * dual-semantics footgun where `<input value="hello">` has `textContent == ""` but a non-empty value.
      *
      * Retries until the condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertNoVisibleText", "empty", actual)`.
      */
    def assertNoVisibleText(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "empty", schedule)(ProbesJs.emptyTextExprJs(selector))

    /** Asserts that the element's `value` property is empty (`el.value === ''` or `null`).
      *
      * Designed for `<input>` / `<textarea>` / `<select>`. For non-input elements (where you want "has no visible text"), use
      * [[assertNoVisibleText]]. The two-method split makes the asserted property explicit at the call site.
      *
      * Retries until the condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertValueEmpty", "empty", actual)`.
      */
    def assertValueEmpty(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "empty", schedule)(ProbesJs.emptyValueExprJs(selector))

    /** Asserts that `document.activeElement` resolves to the element matched by the selector.
      *
      * Passive read: the probe compares the element to `document.activeElement`, it does not scroll or mutate the page. Retries until the
      * condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertFocused", "focused", actual)` where `actual` is `"not_focused"` when a different element
      * has focus or `"not_attached"` when the selector matches no element.
      */
    def assertFocused(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "focused", schedule)(ProbesJs.focusExprJs(selector))

    /** Asserts that the element matched by the selector is NOT `document.activeElement`.
      *
      * Passive read: the probe compares the element to `document.activeElement`, it does not scroll or mutate the page. Retries until the
      * condition holds or the retry schedule exhausts, at which point it raises
      * `BrowserAssertionTimedOutException("assertNotFocused", "not_focused", actual)` where `actual` is `"focused"` when this element has
      * focus or `"not_attached"` when the selector matches no element.
      */
    def assertNotFocused(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "not_focused", schedule)(ProbesJs.focusExprJs(selector))

    // --- Settlement ---

    /** Reads text content, retrying until the predicate is satisfied or the retry schedule exhausts. */
    def waitForText(selector: Selector, predicate: String => Boolean, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserAssertion.withStability(
            SelectorJs.readTextExprJs(jsExpr),
            schedule
        )(identity)(predicate)(BrowserAssertion.elementProbeFailure(selector, "predicate satisfied"))
    end waitForText

    /** Reads attribute value, retrying until the predicate is satisfied or the retry schedule exhausts. */
    def waitForAttribute(
        selector: Selector,
        name: String,
        predicate: String => Boolean,
        schedule: Maybe[Schedule] = Absent
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserAssertion.withStability(
            SelectorJs.readAttributeExprJs(name, jsExpr),
            schedule
        )(identity)(predicate)(BrowserAssertion.elementProbeFailure(selector, "predicate satisfied"))
    end waitForAttribute

    /** Convenience overload of [[waitForText]] that uses equality against `expected` as the predicate. Returns the matched text once the
      * element's text content equals `expected`. Mirrors [[assertText]]'s equality form for users who want the read-flavour wait without
      * spelling out `_ == expected`.
      */
    def waitForText(selector: Selector, expected: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForText(selector, _ == expected, Absent)

    /** Convenience overload of [[waitForText]] that uses equality against `expected` and accepts a `schedule` override. */
    def waitForText(selector: Selector, expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForText(selector, _ == expected, schedule)

    /** Convenience overload of [[waitForAttribute]] that uses equality against `expected` as the predicate. Returns the matched value
      * once the named attribute equals `expected`. Mirrors [[assertAttribute]]'s equality form for users who want the read-flavour wait
      * without spelling out `_ == expected`.
      */
    def waitForAttribute(selector: Selector, name: String, expected: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForAttribute(selector, name, _ == expected, Absent)

    /** Convenience overload of [[waitForAttribute]] that uses equality against `expected` and accepts a `schedule` override. */
    def waitForAttribute(selector: Selector, name: String, expected: String, schedule: Maybe[Schedule])(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForAttribute(selector, name, _ == expected, schedule)

    /** Waits until the current page URL satisfies `predicate`, then returns the matched URL. The probe reads `window.location.href` and
      * retries against the active retry schedule (or `schedule` when provided). Use for navigation-driven flows where the URL change is
      * the load-bearing signal (e.g. SPA route transitions, post-login redirect).
      */
    def waitForUrl(predicate: String => Boolean, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            ProbesJs.urlJs,
            schedule
        )(identity)(predicate)(actual =>
            BrowserAssertionTimedOutException("waitForUrl", "predicate satisfied", actual)
        )

    /** Convenience overload of [[waitForUrl]] that uses equality against `expected` as the predicate. */
    def waitForUrl(expected: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForUrl(_ == expected, Absent)

    /** Waits until the current page `document.title` satisfies `predicate`, then returns the matched title. The probe reads
      * `document.title` and retries against the active retry schedule (or `schedule` when provided).
      */
    def waitForTitle(predicate: String => Boolean, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            ProbesJs.titleJs,
            schedule
        )(identity)(predicate)(actual =>
            BrowserAssertionTimedOutException("waitForTitle", "predicate satisfied", actual)
        )

    /** Convenience overload of [[waitForTitle]] that uses equality against `expected` as the predicate. */
    def waitForTitle(expected: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        waitForTitle(_ == expected, Absent)

    /** Waits until the number of elements matching `selector` satisfies `predicate`, then returns the matched count. The probe runs
      * `SelectorJs.countExprJs` (the same probe `assertCount` uses) under the stability sampler, so the returned count is the first
      * value that satisfies `predicate` AND held constant across the stability window. Predicate is `Int => Boolean`: use `_ >= n`,
      * `_ <= n`, `_ == n`, `_ % 2 == 0`, etc.
      */
    def waitForCount(selector: Selector, predicate: Int => Boolean, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Int < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(predicate)(actual =>
            BrowserAssertionTimedOutException(
                s"waitForCount ${selectorNodeDescription(Selector.toNode(selector))}",
                "predicate satisfied",
                s"got $actual"
            )
        )

    /** Waits until the element matched by `selector` is visible (attached, not hidden by `display:none`, `visibility:hidden`, zero-size,
      * or an ancestor). Structurally identical to [[assertVisible]]; the separate name lets users land on the `waitFor*` family when
      * reading their tests top-down. Reuses `ProbesJs.visibilityExprJs` under the stability sampler.
      */
    def waitForVisible(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.retryAssert(selector, "visible", schedule)(ProbesJs.visibilityExprJs(selector))

    /** Waits until at least one element matches `selector`. Structurally identical to [[assertExists]]; the separate name lets users
      * land on the `waitFor*` family when reading their tests top-down. Reuses `SelectorJs.countExprJs` under the stability sampler.
      */
    def waitForExists(selector: Selector, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            SelectorJs.countExprJs(Selector.toNode(selector)),
            schedule
        )(_.toIntOption.getOrElse(0))(_ > 0)(_ =>
            BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector)))
        ).unit

    /** Waits until no network activity has occurred for the given duration.
      *
      * Installs a JavaScript-level interceptor (overriding `fetch` and `XMLHttpRequest`) to track pending requests. Then polls until the
      * pending request count has been zero for at least `idle` duration.
      */
    def waitForNetworkIdle(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserNetworkTracker.waitForNetworkIdleFor(idle = 500.millis, schedule = Absent)

    /** Like [[Browser.waitForNetworkIdle]] but with a per-call `idle` window override instead of the default 500 ms.
      *
      * @param idle
      *   the minimum duration of network silence required before resolving.
      */
    def waitForNetworkIdle(idle: Duration)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserNetworkTracker.waitForNetworkIdleFor(idle = idle, schedule = Absent)

    /** Like [[Browser.waitForNetworkIdle]] but with a per-call retry `schedule` override instead of the configured default.
      *
      * @param schedule
      *   the retry schedule used while polling; `Absent` falls back to `SessionConfig.retrySchedule`.
      */
    def waitForNetworkIdle(schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserNetworkTracker.waitForNetworkIdleFor(idle = 500.millis, schedule = schedule)

    /** Waits until the page makes a network request whose URL contains `urlPattern`, then returns the matched URL string.
      *
      * The return value is the URL of the first matching request, not a response body or status code. Useful for test-instrumented
      * endpoints: when a component triggers a specific XHR / fetch that you want to observe independently of the navigation auto-wait
      * (which already handles the primary nav response). Subscribes to fetch/XHR interceptors installed on `window`; returns when a
      * matching request's URL is seen.
      *
      * The response-URL observer is installed eagerly on every new document (via `Page.addScriptToEvaluateOnNewDocument`), so XHRs fired by
      * the action immediately preceding this call are observable without a pre-arm step. The canonical pattern is sequential: fire the
      * action that triggers the XHR, then call `waitForRequestUrl` to retrieve the recorded URL:
      *
      * ```scala
      * for
      *     _   <- Browser.fill(Browser.Selector.css("input[type=search]"), "query")
      *     url <- Browser.waitForRequestUrl("/api/search")
      * yield url
      * end for
      * ```
      *
      * `Async.zip(Browser.waitForRequestUrl(...), Browser.fill(...))` is not the right shape: the `Browser` effect requires an `Isolate` to
      * fork against the same tab, which is intentionally not provided. Sequential composition is sufficient because the tracker is already
      * armed at tab attach.
      *
      * @param urlPattern
      *   substring match against observed request URLs
      */
    def waitForRequestUrl(urlPattern: String, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        // `window.__kyoResponseObserved` is append-only; the stability re-probe always finds a previously matched URL still present
        // unless the caller clears the array between polls. Tests that want to exercise flicker detection clear the array in their
        // setInterval callback.
        BrowserNetworkTracker.ensureResponseTrackingInstalled.andThen {
            val escapedPattern = JsStringUtil.escapeJsString(urlPattern)
            // The JS returns a tagged string so the failure-path can carry tracker state ("how many URLs did we see
            // and what were the last few?") without re-querying after the timeout. "MATCH:<url>" on success;
            // "OBS:n=<count>:<url>|<url>|..." on miss; the predicate keys off the prefix.
            BrowserAssertion.withStability(
                s"""(() => {
                    const obs = window.__kyoResponseObserved || [];
                    for (let i = 0; i < obs.length; i++) {
                        if (obs[i].indexOf('$escapedPattern') !== -1) {
                            return 'MATCH:' + obs[i];
                        }
                    }
                    const sample = obs.slice(-5);
                    return 'OBS:n=' + obs.length + ':' + sample.join('|');
                })()""",
                schedule
            )(identity)(_.startsWith("MATCH:"))(actual =>
                val observed = actual.stripPrefix("OBS:")
                BrowserAssertionTimedOutException(s"url containing '$urlPattern'", s"none (observed=$observed)")
            ).map(_.stripPrefix("MATCH:"))
        }

    /** Waits until the JavaScript expression returns a truthy value, retrying per the retry schedule in the active config. */
    def waitFor(js: String, schedule: Maybe[Schedule] = Absent)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        BrowserAssertion.withStability(
            js,
            schedule
        )(identity)(r => r.nonEmpty && r != "false" && r != "null" && r != "undefined" && r != "0")(result =>
            BrowserAssertionTimedOutException("truthy", result)
        )

    // --- Reads ---

    /** Returns the text content of the element matched by the selector. */
    def text(selector: Selector)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                BrowserEval.readTextCore(selector, jsExpr)
            }
        }
    end text

    /** Returns the value of the named attribute on the element matched by the selector. */
    def attribute(selector: Selector, name: String)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                BrowserEval.readAttributeCore(selector, name, jsExpr)
            }
        }
    end attribute

    /** Returns the zero-based caret position of the resolved `<input>` / `<textarea>` element: its `selectionStart` property.
      *
      * @param selector
      *   the target element; must resolve to an `HTMLInputElement` or `HTMLTextAreaElement` whose `type` supports a selection model (text,
      *   search, password, tel, url, or any `<textarea>`).
      * @throws BrowserElementNotFoundException
      *   if the selector matches no element (or matches only a detached node).
      * @throws BrowserElementNotActionableException
      *   if the element is resolved but does not expose a caret position (e.g. `<input type="number">`, `<input type="email">`,
      *   `<input type="date">`, or any non-input/textarea element).
      * @return
      *   the integer caret position; `0` when the caret is at the start of the value.
      */
    def selectionStart(selector: Selector)(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserEval.evalJs(ProbesJs.selectionStartExprJs(jsExpr)).map { result =>
            if result == "not_attached" then
                Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
            else if result == "unsupported" then
                Abort.fail(
                    BrowserElementNotActionableException(
                        selectorNodeDescription(Selector.toNode(selector)),
                        BrowserElementNotActionableException.Reason.NotFillable("no-selectionStart")
                    )
                )
            else result.toInt
        }
    end selectionStart

    /** Returns the number of elements matching the selector. Retries on CDP transients (e.g. a navigation in flight that briefly destroys
      * the execution context) using the active `retrySchedule`, then returns the count.
      *
      * Important nuance: the retry covers `BrowserMutationException` only, NOT "the count is zero". `BrowserEval.locateCount` returns `0`
      * for "no element matches" without raising, so a permanently-empty selector returns `0` on the first probe and does NOT block. To
      * wait for at least one element to appear use either `assertExists(sel).andThen(count(sel))` or the predicate-based
      * `waitForCount(sel, _ > 0)`; both are explicit about the "wait then count" intent.
      *
      * Use [[countNow]] for an explicit no-retry-of-any-kind probe (e.g. negative timing assertions on absent selectors). For most
      * read-side callers `count` is the right choice.
      */
    def count(selector: Selector)(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                BrowserEval.locateCount(selector)
            }
        }

    /** Returns the number of elements matching the selector at the moment of the call. Point-in-time read with NO retry of any kind:
      * a transient CDP failure surfaces immediately as a typed `BrowserReadException` rather than being swallowed by a retry loop.
      *
      * Use when the test deliberately exercises a no-retry shape (e.g. asserting `countNow` returns `0` for a selector that becomes
      * present only after a delayed insertion). For most read-side callers, [[count]] is the right default; `countNow` is for negative
      * timing assertions.
      */
    def countNow(selector: Selector)(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        BrowserEval.locateCount(selector)

    /** Returns the runtime `el.value` property of the element matched by `selector`. Reads the JS property, NOT the `value` HTML
      * attribute: programmatic updates via `el.value = "..."` are reflected, while [[attribute]] `(sel, "value")` returns the
      * initial-render attribute string only. This is the same property `Browser.fill` writes to, so it is the right read for verifying
      * the post-fill state of an `<input>`, `<textarea>`, or `<select>`.
      *
      * For `<select>` the property is the currently selected option's `value` (or its text when no `value` attribute is set), matching
      * the HTMLSelectElement spec. Fails fast with [[BrowserElementNotFoundException]] when the selector matches no element, and with
      * [[BrowserElementNotActionableException]] (`NotFillable("no-value-property")`) when the element exposes no string `value`
      * property (e.g. `<div>`).
      *
      * Point-in-time: no retry, no stability window. For "wait until the value satisfies P", use
      * `waitForText` / `waitForAttribute` for text/attribute reads or compose `assertEnabled` / mutation settlement with `value`.
      */
    def value(selector: Selector)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(ProbesJs.valueExprJs(selector)).map {
            case "not_attached" =>
                Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
            case "unsupported" =>
                Abort.fail(
                    BrowserElementNotActionableException(
                        selectorNodeDescription(Selector.toNode(selector)),
                        BrowserElementNotActionableException.Reason.NotFillable("no-value-property")
                    )
                )
            case raw if raw.startsWith("V:") => raw.substring(2)
            case other =>
                Abort.fail(BrowserAssertionTimedOutException("value", "string", other))
        }
    end value

    /** Returns `true` when the element matched by `selector` is visible: attached to the DOM, with neither `display:none` nor
      * `visibility:hidden` on the element or any ancestor, and a non-zero bounding box. Returns `false` when the element is present but
      * not rendered (hidden by CSS, zero-size, ancestor-hidden). Fails with [[BrowserElementNotFoundException]] when the selector
      * matches no element (consistent with [[text]] / [[attribute]]).
      *
      * Point-in-time: no retry, no stability window. For "wait until visible" use [[waitForVisible]] / [[assertVisible]].
      *
      * Render-visibility only: `aria-hidden` on ancestors does NOT mark an element as not-visible here (that is accessibility-tree
      * territory, surfaced via [[role]] / [[accessibleName]]).
      */
    def isVisible(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.probeBoolean(selector, ProbesJs.visibilityExprJs(selector), "visible")

    /** Returns `true` when the element matched by `selector` is enabled: attached, visible, and neither `disabled` nor
      * `aria-disabled="true"`. Returns `false` for disabled or hidden controls. Fails with [[BrowserElementNotFoundException]] when the
      * selector matches no element.
      *
      * Point-in-time: no retry. For "wait until enabled" use [[assertEnabled]].
      */
    def isEnabled(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.probeBoolean(selector, ProbesJs.enabledExprJs(selector), "enabled")

    /** Returns `true` when the element matched by `selector` has its `checked` property set to `true` (checkbox or radio input).
      * Returns `false` for unchecked inputs and elements that have no `checked` property (e.g. `<button>`). Fails with
      * [[BrowserElementNotFoundException]] when the selector matches no element.
      *
      * Point-in-time: no retry. For "wait until checked" use [[assertChecked]].
      */
    def isChecked(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.probeBoolean(selector, ProbesJs.checkedExprJs(selector), "checked")

    /** Returns `true` when `document.activeElement` is the element matched by `selector`. Returns `false` when a different element has
      * focus. Fails with [[BrowserElementNotFoundException]] when the selector matches no element.
      *
      * Point-in-time: no retry. For "wait until focused" use [[assertFocused]].
      */
    def isFocused(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.probeBoolean(selector, ProbesJs.focusExprJs(selector), "focused")

    /** Returns `true` when at least one element matches `selector` on the page at the moment of the call. Returns `false` when no
      * element matches; does NOT fail with [[BrowserElementNotFoundException]] (the question this method asks is literally "is the
      * element here", so the negative answer is `false`, not an exception). Use [[assertExists]] when "absent" should be a typed
      * failure.
      *
      * Point-in-time: no retry, no stability window. Equivalent to `countNow(selector) > 0` with short-circuit on the first match.
      */
    def exists(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.locateCount(selector).map(_ > 0)

    /** Returns `true` when the named attribute is present on the element matched by `selector` (regardless of value, including empty
      * strings and HTML boolean attributes), `false` otherwise. Backed by `el.hasAttribute(name)`, NOT `el.getAttribute(name) != ""`:
      * `<input data-foo>` and `<input data-foo="">` both return `true` here, whereas `attribute(sel, "data-foo") != ""` returns `false`
      * for both because `getAttribute` collapses the absent-vs-empty distinction. Fails with [[BrowserElementNotFoundException]] when
      * the selector matches no element.
      *
      * Point-in-time: no retry. For "wait until the attribute appears" use [[waitForAttribute]] with a predicate.
      */
    def hasAttribute(selector: Selector, name: String)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserEval.probeBoolean(selector, ProbesJs.noAttributeExprJs(name, jsExpr), "present")

    /** Returns `true` when the element matched by `selector` has no visible text content: `textContent.trim()` is empty. Returns
      * `false` when the element has any non-whitespace text. Fails with [[BrowserElementNotFoundException]] when the selector matches
      * no element.
      *
      * For `<input>` / `<textarea>` / `<select>` elements (which carry their content in the `value` property, not in `textContent`),
      * use [[hasEmptyValue]]. The split between text and value avoids the dual-semantics footgun of a single `isEmpty`-style method:
      * `hasNoVisibleText` and `hasEmptyValue` make the asserted property explicit at the call site.
      *
      * Point-in-time: no retry.
      */
    def hasNoVisibleText(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(s"""(() => {
            const el = ${SelectorJs.resolveElementJs(Selector.toNode(selector))};
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            const text = (el.textContent || '').trim();
            return text === '' ? 'empty' : 'non_empty';
        })()""").map {
            case "not_attached" =>
                Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
            case v => v == "empty"
        }

    /** Returns `true` when the element matched by `selector` has an empty `value` property: `el.value === ''` (or `null`). Returns
      * `false` when `el.value` is any non-empty string. Fails with [[BrowserElementNotFoundException]] when the selector matches no
      * element.
      *
      * Designed for `<input>` / `<textarea>` / `<select>`. For non-input elements (where you want "has no visible text"), use
      * [[hasNoVisibleText]]. The two-method split makes the asserted property explicit at the call site and avoids the dual-semantics
      * footgun of a single `isEmpty`-style method.
      *
      * Point-in-time: no retry.
      */
    def hasEmptyValue(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(s"""(() => {
            const el = ${SelectorJs.resolveElementJs(Selector.toNode(selector))};
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            return (el.value === '' || el.value == null) ? 'empty' : 'non_empty';
        })()""").map {
            case "not_attached" =>
                Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
            case v => v == "empty"
        }

    /** Returns the settled bounding rectangle of the first element matching `selector`. Re-samples until the geometry stabilizes, then
      * performs an authoritative `DOM.getBoxModel` CDP read for the final value. Returns `Absent` when the selector matches nothing or the
      * element has no box model (`display:none`). Coordinates are CSS pixels in the page's top-level viewport coordinate system.
      *
      * Uses `SettleRead.settle` to wait for layout stability before the CDP box-model read.
      */
    def boundingRect(selector: Selector)(using Frame): Maybe[Browser.Bounds] < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val valueExpr =
            s"""(() => { const el = $jsExpr; if (!el) return '{"present":false}'; const r = el.getBoundingClientRect(); return JSON.stringify({present:true, x:r.x, y:r.y, w:r.width, h:r.height}); })()"""
        SettleRead.settle("boundingRect", valueExpr) { raw =>
            Json.decode[PresentFlagWire](raw) match
                case Result.Success(w) if !w.present => Maybe.empty[Browser.Bounds]
                case Result.Success(_) =>
                    Resolver.resolveOne(selector).map {
                        case Absent => Maybe.empty[Browser.Bounds]
                        case Present(ref) =>
                            Env.use[BrowserTab] { tab =>
                                Abort.recover[BrowserProtocolErrorException] { _ => Maybe.empty[Browser.Bounds] } {
                                    CdpBackend.getBoxModel(tab.session, GetBoxModelParams(backendNodeId = ref.backendNodeId)).map { bm =>
                                        val c = bm.model.content
                                        if c.size < 8 then Maybe.empty[Browser.Bounds]
                                        else
                                            val xs = Chunk(c(0), c(2), c(4), c(6))
                                            val ys = Chunk(c(1), c(3), c(5), c(7))
                                            val x  = xs.min
                                            val y  = ys.min
                                            Present(Browser.Bounds(x, y, xs.max - x, ys.max - y))
                                        end if
                                    }
                                }
                            }
                    }
                case _ => Abort.fail(BrowserProtocolErrorException.decodeFailure("boundingRect", raw))
        }
    end boundingRect

    /** Returns the settled computed CSS values for the named `properties` on the element matching `selector`. Re-samples until the values
      * stabilize. Aborts `BrowserElementNotFoundException` when no element matches (twin: `attribute`).
      */
    def computedStyles(selector: Selector, properties: Span[String])(using
        Frame
    ): Map[String, String] < (Browser & Abort[BrowserReadException]) =
        val jsExpr    = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val propsJson = properties.map(p => "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").mkString("[", ",", "]")
        val valueExpr =
            s"""(() => { const el = $jsExpr; if (!el) return '{"present":false}'; const cs = window.getComputedStyle(el); const vals = {}; const props = $propsJson; for (const p of props) vals[p] = cs.getPropertyValue(p); return JSON.stringify({present:true, vals}); })()"""
        SettleRead.settle("computedStyles", valueExpr) { raw =>
            Json.decode[ComputedStylesWire](raw) match
                case Result.Success(w) if !w.present =>
                    Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
                case Result.Success(w) =>
                    w.vals.getOrElse(Map.empty)
                case _ => Abort.fail(BrowserProtocolErrorException.decodeFailure("computedStyles", raw))
        }
    end computedStyles

    /** Returns the settled computed CSS value for `property` on the element matching `selector`. Delegates to `computedStyles`. Aborts
      * `BrowserElementNotFoundException` when no element matches.
      */
    def computedStyle(selector: Selector, property: String)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        computedStyles(selector, Span(property)).map(_(property))

    /** Returns whether the element matching `selector` is currently in the visible viewport. Settled read: re-samples until the result
      * stabilizes. Aborts `BrowserElementNotFoundException` when no element matches (twin: `isVisible`).
      */
    def inViewport(selector: Selector)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val valueExpr =
            s"""(() => { const el = $jsExpr; if (!el) return '{"present":false}'; const r = el.getBoundingClientRect(); const iv = r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < window.innerHeight; return JSON.stringify({present:true, value:iv}); })()"""
        SettleRead.settle("inViewport", valueExpr) { raw =>
            Json.decode[PresentBoolWire](raw) match
                case Result.Success(w) if !w.present =>
                    Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
                case Result.Success(w) =>
                    w.value.getOrElse(false)
                case _ => Abort.fail(BrowserProtocolErrorException.decodeFailure("inViewport", raw))
        }
    end inViewport

    /** Returns the current page scroll position (`window.scrollX` / `scrollY`, rounded to integers). Settled read: re-samples until the
      * offset stabilizes (useful after scroll-snap or smooth-scroll finishes). Total read: no element needed, never aborts on absent.
      */
    def scrollPosition(using Frame): Browser.ScrollPosition < (Browser & Abort[BrowserReadException]) =
        val valueExpr = "JSON.stringify({x: Math.round(window.scrollX), y: Math.round(window.scrollY)})"
        SettleRead.settle("scrollPosition", valueExpr) { raw =>
            Json.decode[Browser.ScrollPosition](raw) match
                case Result.Success(sp) => sp
                case _                  => Abort.fail(BrowserProtocolErrorException.decodeFailure("scrollPosition", raw))
        }
    end scrollPosition

    /** Waits until the page DOM stops mutating for the configured quiescence window, then returns. Aborts `BrowserAssertionTimedOutException`
      * when the DOM has not quiesced within `timeout`. Delegates to the strict `MutationSettlement.waitForStable` entry; the entire wait runs
      * inside a single `awaitPromise=true` eval.
      */
    def waitForStable(timeout: Duration)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        MutationSettlement.waitForStable(timeout)

    /** Returns the full DISCOVER snapshot for the element at page-document pixel `(x, y)` via `document.elementFromPoint`. Settled read:
      * re-samples until stable. Returns `Absent` when no element occupies that point. Aborts `BrowserInvalidArgumentException` before any
      * page eval when `x` or `y` is negative.
      */
    def elementAt(x: Int, y: Int)(using Frame): Maybe[Browser.ElementInfo] < (Browser & Abort[BrowserReadException]) =
        if x < 0 || y < 0 then Abort.fail(BrowserInvalidArgumentException("elementAt", "coordinates must be non-negative"))
        else
            installDiscover.andThen {
                val valueExpr =
                    s"""(() => { const el = document.elementFromPoint($x, $y); if (!el || el === document.documentElement || el === document.body) return '{"present":false}'; return JSON.stringify({present:true, info:window.__kyoDiscoverProbe(el)}); })()"""
                SettleRead.settle("elementAt", valueExpr)(decodeElementInfoMaybe("elementAt"))
            }
    end elementAt

    /** Returns the full DISCOVER snapshot for the first element matching `selector`. Settled read: re-samples until stable. Returns `Absent`
      * when no element matches (twin: `boundingRect`).
      */
    def element(selector: Selector)(using Frame): Maybe[Browser.ElementInfo] < (Browser & Abort[BrowserReadException]) =
        installDiscover.andThen {
            val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
            val valueExpr =
                s"""(() => { const el = $jsExpr; if (!el) return '{"present":false}'; return JSON.stringify({present:true, info:window.__kyoDiscoverProbe(el)}); })()"""
            SettleRead.settle("element", valueExpr)(decodeElementInfoMaybe("element"))
        }
    end element

    /** Returns DISCOVER snapshots for all elements matching `selector` in document order. Empty fast-path: when `BrowserEval.locateCount`
      * returns 0, returns `Chunk.empty` immediately without waiting for stability (twin: `textAll`). When elements are found, performs a
      * settled read of the full array.
      */
    def elements(selector: Selector = Selector.all)(using Frame): Chunk[Browser.ElementInfo] < (Browser & Abort[BrowserReadException]) =
        BrowserEval.locateCount(selector).map { n =>
            if n == 0 then Chunk.empty
            else
                installDiscover.andThen {
                    val jsExpr    = SelectorJs.resolveAllElementsJs(Selector.toNode(selector))
                    val valueExpr = s"JSON.stringify(($jsExpr).map(el => window.__kyoDiscoverProbe(el)))"
                    SettleRead.settle("elements", valueExpr) { raw =>
                        Json.decode[Seq[DiscoverJs.ElementInfoWire]](raw) match
                            case Result.Success(ws) => Chunk.from(ws).map(toElementInfo)
                            case _                  => Abort.fail(BrowserProtocolErrorException.decodeFailure("elements", raw))
                    }
                }
        }
    end elements

    /** Installs the in-page DISCOVER helper (`window.__kyoDiscoverProbe` / `window.__kyoUniqueSelector`) idempotently. The helper is gated by
      * `window.__kyoDiscoverInstalled` so repeated calls are cheap.
      */
    private def installDiscover(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(DiscoverJs.installJs).unit

    /** Converts the JSON wire record for one element into the public `Browser.ElementInfo`. */
    private def toElementInfo(w: DiscoverJs.ElementInfoWire): Browser.ElementInfo =
        Browser.ElementInfo(
            selector = w.selector,
            tag = w.tag,
            id = w.id,
            classes = w.classes,
            text = w.text,
            bounds = Browser.Bounds(w.x, w.y, w.width, w.height),
            visible = w.visible,
            inViewport = w.inViewport,
            topmost = w.topmost,
            interactive = w.interactive,
            role = w.role
        )

    /** Curried decoder for a settled element-info read. Returns `Absent` when the JS side returns `present:false`; decodes the wire object
      * and converts to `Browser.ElementInfo` on the present path.
      */
    private def decodeElementInfoMaybe(
        callee: String
    )(raw: String)(using Frame): Maybe[Browser.ElementInfo] < (Browser & Abort[BrowserReadException]) =
        Json.decode[ElementInfoEnvelope](raw) match
            case Result.Success(env) if !env.present => Maybe.empty[Browser.ElementInfo]
            case Result.Success(env) =>
                env.info match
                    case Present(w) => Present(toElementInfo(w))
                    case Absent     => Abort.fail(BrowserProtocolErrorException.decodeFailure(callee, raw))
            case _ => Abort.fail(BrowserProtocolErrorException.decodeFailure(callee, raw))

    /** Returns the flat accessibility node list for the current page. Each entry captures the role, name, and properties for a node Chrome
      * considers exposed to assistive tech. The iframe context honoured is whatever [[withIFrame]] last set; absent that, the top-level
      * document.
      */
    def accessibilityNodes(using Frame): Chunk[Browser.AxNode] < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            activeIFrameLocal.use {
                case Present(h) =>
                    Accessibility.getFullAXTreeForFrame(tab.session, h.frameId.value).map(_.map(toPublicAxNode))
                case Absent =>
                    Accessibility.getFullAXTree(tab.session).map(_.map(toPublicAxNode))
            }
        }

    /** Returns the ARIA role of the element matching `selector`, or `Absent` if no element matches. Probes the accessibility tree, NOT the
      * HTML `role` attribute; the AX role wins (for `<div role="alert">` this method returns `Present("alert")`, not the tag name).
      */
    def role(selector: Selector)(using Frame): Maybe[String] < (Browser & Abort[BrowserReadException]) =
        findAxNode(selector).map(_.map(_.role))

    /** Returns the accessible name of the element matching `selector`: Chrome's computed name as announced by a screen reader. Honours
      * `aria-label` priority over `textContent`. Returns `Present("")` for nodes whose AX layer reports an empty name (e.g.
      * `role="presentation"` decorative images); selector misses return `Absent`.
      */
    def accessibleName(selector: Selector)(using Frame): Maybe[String] < (Browser & Abort[BrowserReadException]) =
        findAxNode(selector).map(_.map(_.name))

    private def findAxNode(selector: Selector)(using Frame): Maybe[Browser.AxNode] < (Browser & Abort[BrowserReadException]) =
        Resolver.resolveOne(selector).map {
            case Absent => Maybe.empty[Browser.AxNode]
            case Present(ref) =>
                Env.use[BrowserTab] { tab =>
                    activeIFrameLocal.use { active =>
                        val tree = active match
                            case Present(h) => Accessibility.getFullAXTreeForFrame(tab.session, h.frameId.value)
                            case Absent     => Accessibility.getFullAXTree(tab.session)
                        tree.map { nodes =>
                            val target = ref.backendNodeId.toString
                            Maybe.fromOption(nodes.find(n => n.properties.get("backendDOMNodeId") == Present(target)))
                                .map(toPublicAxNode)
                        }
                    }
                }
        }

    private def toPublicAxNode(internal: Accessibility.AxNode): Browser.AxNode =
        Browser.AxNode(internal.nodeId, internal.role, internal.name, internal.ignored, internal.properties)

    /** Returns the text content of all elements matching the selector.
      *
      * Probes [[count]] once first; returns `Chunk.empty` immediately when no elements match (single CDP round-trip, no retry wait). When
      * at least one element is observed, retries through the configured `retrySchedule` until the read succeeds.
      */
    def textAll(selector: Selector)(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        BrowserEval.locateCount(selector).map { initial =>
            if initial == 0 then Chunk.empty
            else
                val jsExpr = SelectorJs.resolveAllElementsJs(Selector.toNode(selector))
                BrowserAssertion.defaultOnElementMissing(Chunk.empty) {
                    configLocal.use { cfg =>
                        Retry[BrowserMutationException](cfg.retrySchedule) {
                            BrowserEval.evalJs(s"""(() => {
                                const els = $jsExpr;
                                return JSON.stringify(els.map(el => el.innerText || el.textContent || ''));
                            })()""").map { json =>
                                CdpEvalDecoder.decodeStringListReply("textAll", json).map { chunk =>
                                    if chunk.isEmpty then
                                        Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
                                    else chunk
                                }
                            }
                        }
                    }
                }
        }
    end textAll

    /** Returns the attribute values of all elements matching the selector.
      *
      * Probes [[count]] once first; returns `Chunk.empty` immediately when no elements match (single CDP round-trip, no retry wait). When
      * at least one element is observed, retries through the configured `retrySchedule` until the read succeeds.
      */
    def attributeAll(selector: Selector, name: String)(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        BrowserEval.locateCount(selector).map { initial =>
            if initial == 0 then Chunk.empty
            else
                val escaped = JsStringUtil.escapeJsString(name)
                val jsExpr  = SelectorJs.resolveAllElementsJs(Selector.toNode(selector))
                BrowserAssertion.defaultOnElementMissing(Chunk.empty) {
                    configLocal.use { cfg =>
                        Retry[BrowserMutationException](cfg.retrySchedule) {
                            BrowserEval.evalJs(s"""(() => {
                                const els = $jsExpr;
                                return JSON.stringify(els.map(el => el.getAttribute('$escaped') || ''));
                            })()""").map { json =>
                                CdpEvalDecoder.decodeStringListReply("attributeAll", json).map { chunk =>
                                    if chunk.isEmpty then
                                        Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
                                    else chunk
                                }
                            }
                        }
                    }
                }
        }
    end attributeAll

    /** Returns the inner HTML of the first element matching the selector. */
    def html(selector: Selector)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        // `jsExpr` is a pure function of `selector`; hoist outside the retry loop so the retry tick re-runs only the DOM eval.
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                Actionability.requireResolved(selector) {
                    BrowserEval.evalJs(s"""(() => {
                        const el = $jsExpr;
                        return el.innerHTML;
                    })()""")
                }
            }
        }
    end html

    /** Returns the outer HTML of the first element matching the selector. */
    def outerHtml(selector: Selector)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        configLocal.use { cfg =>
            Retry[BrowserMutationException](cfg.retrySchedule) {
                Actionability.requireResolved(selector) {
                    BrowserEval.evalJs(s"""(() => {
                        const el = $jsExpr;
                        return el.outerHTML;
                    })()""")
                }
            }
        }
    end outerHtml

    /** Returns the current page URL. */
    def url(using Frame): String < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(ProbesJs.urlJs)

    /** Returns the current page title. */
    def title(using Frame): String < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(ProbesJs.titleJs)

    /** Captures the live current viewport (whatever `setViewport` / `withViewport` last established, else the natural viewport). The legacy
      * `1280x720` crop is dropped; this method no longer clips. Hold-still capture is the TARGET convention: animations are paused via a
      * `data-kyo-internal` freeze stylesheet, fonts are awaited, and the capture loops until two consecutive frames are byte-identical or the
      * `captureHoldStillTimeout` elapses.
      */
    def screenshot(
        format: ScreenshotFormat = ScreenshotFormat.Png,
        quality: Int = 90
    )(using Frame): Image < (Browser & Abort[BrowserReadException]) =
        HoldStill.withHoldStill {
            Env.use[BrowserTab] { tab =>
                CdpBackend.captureScreenshot(
                    tab.session,
                    ScreenshotParams(format, screenshotQuality(format, quality), clip = Absent)
                ).map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
            }
        }

    /** Captures a `width x height` region at document offset `(x, y)`. `captureBeyondViewport = true` allows the region to lie below or
      * beside the visible fold. Aborts `BrowserInvalidArgumentException` BEFORE any CDP call when `width <= 0` or `height <= 0`. Clip
      * coordinates are CSS px (`scale = 1.0`); an active DPR scales the output raster only. Hold-still capture is the TARGET convention.
      */
    def screenshotRegion(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        quality: Int = 90
    )(using Frame): Image < (Browser & Abort[BrowserReadException]) =
        if width <= 0 || height <= 0 then
            Abort.fail(BrowserInvalidArgumentException("screenshotRegion", "width and height must be positive"))
        else
            HoldStill.withHoldStill {
                Env.use[BrowserTab] { tab =>
                    CdpBackend.captureScreenshot(
                        tab.session,
                        ScreenshotParams(
                            format,
                            screenshotQuality(format, quality),
                            clip = Present(ScreenshotClip(x.toDouble, y.toDouble, width.toDouble, height.toDouble, scale = 1.0)),
                            captureBeyondViewport = Present(true)
                        )
                    ).map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
                }
            }

    /** Captures the entire scroll height as a `Chunk` of viewport-tall bands, top to bottom. Aborts
      * `BrowserCaptureLimitExceededException` BEFORE any capture when the page needs more than `maxBands` bands. The freeze stylesheet is
      * injected ONCE around the entire band loop so all bands reflect the same frozen animation state. Band coordinates are CSS px
      * (DPR-independent).
      */
    def screenshotFullPage(
        maxBands: Int = 50,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        quality: Int = 90
    )(using Frame): Chunk[Image] < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(
            "JSON.stringify({content: Math.ceil(document.documentElement.scrollHeight), viewport: Math.ceil(window.innerHeight), width: Math.ceil(window.innerWidth)})"
        ).map { raw =>
            Json.decode[FullPageDimsWire](raw) match
                case Result.Success(d) =>
                    val bandCount = math.ceil(d.content.toDouble / d.viewport.toDouble).toInt.max(1)
                    if bandCount > maxBands then
                        Abort.fail(BrowserCaptureLimitExceededException("screenshotFullPage", maxBands, bandCount))
                    else
                        // Freeze ONCE around the whole band loop so all bands are frozen consistently.
                        HoldStill.withFrozenPage {
                            Env.use[BrowserTab] { tab =>
                                Kyo.foreach(Chunk.from(0 until bandCount)) { i =>
                                    HoldStill.holdStillFrame {
                                        CdpBackend.captureScreenshot(
                                            tab.session,
                                            ScreenshotParams(
                                                format,
                                                screenshotQuality(format, quality),
                                                clip = Present(
                                                    ScreenshotClip(
                                                        0.0,
                                                        (i * d.viewport).toDouble,
                                                        d.width.toDouble,
                                                        d.viewport.toDouble,
                                                        scale = 1.0
                                                    )
                                                ),
                                                captureBeyondViewport = Present(true)
                                            )
                                        ).map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
                                    }
                                }
                            }
                        }
                    end if
                case _ => Abort.fail(BrowserProtocolErrorException.decodeFailure("screenshotFullPage", raw))
        }

    /** Extracts the main readable text content from the page using a readability algorithm. */
    def readableContent(using Frame): String < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(ProbesJs.readabilityScript)

    /** Generates a PDF of the current page. Only works in headless Chrome. */
    def pdf(using Frame): Span[Byte] < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            // Uses the cross-platform `kyo.Base64` decoder. The returned `Span` already owns a fresh array, so
            // no additional copy is needed. A malformed wire payload surfaces through the typed Abort channel
            // instead of escaping as a thrown `IllegalArgumentException`.
            CdpBackend.printToPDF(tab.session).map(pr =>
                CdpBase64Decode.decodeWireBase64("Page.printToPDF", pr.data)
            )
        }

    /** Captures a screenshot of a specific element. AUTO-WAITS for the element via `Actionability.withRetry` (retry channel
      * `BrowserElementException`, NEVER widened to `BrowserMutationException`); performs a box-stable check (two bounding-rect samples 16 ms
      * apart must agree within 1 px) and scrolls the element into view before capturing. Gains `transparentBackground`: when true, sets CDP
      * `Emulation.setDefaultBackgroundColorOverride` to fully transparent for the shot and restores it via `Scope.acquireRelease`. Hold-still
      * capture is the TARGET convention. Returns `Image` and ABORTS `BrowserElementNotFoundException` when the element never appears within
      * the configured `retrySchedule`; NOT changed to `Maybe`.
      */
    def screenshotElement(
        selector: Selector,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        quality: Int = 90,
        transparentBackground: Boolean = false
    )(using Frame): Image < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        // AUTO-WAIT: the retry resolves the element and waits until its box is stable, returning the resolved clip box.
        // The hold-still capture then runs ONCE outside the retry, threading that stable box through `.map`, so a capture
        // failure never re-enters the retry channel.
        Actionability.withRetry {
            // Resolve, box-stable check (two samples ~16 ms apart, agree within 1 px), then scroll into view and re-read the post-scroll rect.
            // found=false means the element does not exist (triggers BrowserElementNotFoundException for retry).
            // ok=false with found=true means the element exists but its bounding rect is still moving (triggers NotActionable for retry).
            BrowserEval.evalJsAwaiting(s"""(async () => {
                const el = $jsExpr; if (!el) return JSON.stringify({found:false,ok:false});
                const r1 = el.getBoundingClientRect();
                await new Promise(res => setTimeout(res, 16));
                const r2 = el.getBoundingClientRect();
                if (Math.abs(r1.x - r2.x) > 1 || Math.abs(r1.y - r2.y) > 1 || Math.abs(r1.width - r2.width) > 1 || Math.abs(r1.height - r2.height) > 1)
                    return JSON.stringify({found:true,ok:false});
                el.scrollIntoViewIfNeeded ? el.scrollIntoViewIfNeeded(true) : el.scrollIntoView({block:'center'});
                const r = el.getBoundingClientRect();
                return JSON.stringify({found:true,ok:true, x:r.x, y:r.y, width:r.width, height:r.height});
            })()""").map { result =>
                Json.decode[ElementClipWire](result) match
                    case Result.Success(w) if w.ok =>
                        ScreenshotClip(w.x, w.y, w.width, w.height, scale = 1.0)
                    case Result.Success(w) if !w.found =>
                        Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
                    case _ =>
                        Abort.fail(
                            BrowserElementNotActionableException(
                                selectorNodeDescription(Selector.toNode(selector)),
                                BrowserElementNotActionableException.Reason.NotVisible(
                                    BrowserElementNotActionableException.Reason.NotVisibleCause.ZeroComputedSize
                                )
                            )
                        )
            }
        }.map { clip =>
            // Capture ONCE with the resolved (actionable, ok) clip box, outside the retry.
            withTransparentBackground(transparentBackground) {
                HoldStill.withHoldStill {
                    Env.use[BrowserTab] { tab =>
                        CdpBackend.captureScreenshot(
                            tab.session,
                            ScreenshotParams(format, screenshotQuality(format, quality), clip = Present(clip))
                        ).map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
                    }
                }
            }
        }
    end screenshotElement

    /** Captures the viewport with numbered badges overlaid at each element in `marks` (1-based, top-left corner). The overlay is one
      * settlement-transparent `data-kyo-internal` subtree. Aborts `BrowserCaptureLimitExceededException` when `marks.size > maxMarks`. The
      * settle gate runs BEFORE mark injection (via `HoldStill.withFrozenPage`) so the marks overlay mutation does not reset quiescence;
      * marks are injected exactly once inside the frozen scope and removed via `Scope.acquireRelease`.
      *
      * The injected container carries a unique `data-kyo-token` minted by the inject eval, and the removal targets only the node bearing
      * that token rather than a shared global slot, so concurrent same-tab captures each tear down exactly their own overlay.
      */
    def screenshotMarks(
        marks: Chunk[Browser.ElementInfo],
        maxMarks: Int = 100,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        quality: Int = 90
    )(using Frame): Image < (Browser & Abort[BrowserReadException]) =
        if marks.size > maxMarks then Abort.fail(BrowserCaptureLimitExceededException("screenshotMarks", maxMarks, marks.size))
        else
            val badges = marks.zipWithIndex.map((m, i) => s"""{x:${m.bounds.x},y:${m.bounds.y},n:${i + 1}}""").mkString("[", ",", "]")
            val injectJs = s"""(() => {
                const root = document.createElement('div');
                const token = String((window.__kyoOverlayToken = (window.__kyoOverlayToken || 0) + 1));
                root.setAttribute('data-kyo-internal', 'marks');
                root.setAttribute('data-kyo-token', token);
                root.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';
                for (const b of $badges) {
                    const d = document.createElement('div');
                    d.setAttribute('data-kyo-internal', 'mark');
                    d.textContent = b.n;
                    d.style.cssText = 'position:absolute;left:'+(b.x+2)+'px;top:'+(b.y+2)+'px;background:#d00;color:#fff;font:12px sans-serif;padding:1px 4px;border-radius:3px;';
                    root.appendChild(d);
                }
                document.body.appendChild(root); return token;
            })()"""
            def removeJs(token: String) =
                val escaped = JsStringUtil.escapeJsString(token)
                s"""(() => { document.querySelectorAll('[data-kyo-token="$escaped"]').forEach(n => n.remove()); return 'unmarked'; })()"""
            // Settle BEFORE injecting marks: withFrozenPage runs settleForCapture + fonts.ready + freeze injection before entering the body.
            // Mark injection inside the frozen scope ensures the quiescence gate has already passed when the overlay mutation fires.
            HoldStill.withFrozenPage {
                Browser.use { tab =>
                    Scope.run {
                        Scope.acquireRelease(BrowserEval.evalJs(injectJs)) { token =>
                            Browser.releaseHook(tab)(BrowserEval.evalJs(removeJs(token)).unit)
                        }.andThen {
                            HoldStill.holdStillFrame {
                                CdpBackend.captureScreenshot(
                                    tab.session,
                                    ScreenshotParams(format, screenshotQuality(format, quality), clip = Absent)
                                ).map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
                            }
                        }
                    }
                }
            }

    /** Applies a transparent default background for `body`'s duration when `enabled`. Sets
      * `Emulation.setDefaultBackgroundColorOverride` to `{r:0,g:0,b:0,a:0}` on enter and clears it on exit (success, failure, or
      * interruption) via `Scope.acquireRelease`. A no-op when `enabled` is false.
      */
    private def withTransparentBackground[A, S](enabled: Boolean)(
        body: => A < (Browser & Abort[BrowserReadException] & S)
    )(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        if !enabled then body
        else
            Env.use[BrowserTab] { tab =>
                Scope.run {
                    Scope.acquireRelease(
                        CdpBackend.setDefaultBackgroundColorOverride(
                            tab.session,
                            SetDefaultBackgroundColorOverrideParams(Present(RgbaColor(0, 0, 0, Present(0.0))))
                        )
                    )(_ =>
                        Browser.releaseHook(tab)(
                            CdpBackend.setDefaultBackgroundColorOverride(
                                tab.session,
                                SetDefaultBackgroundColorOverrideParams(Absent)
                            )
                        )
                    ).andThen(body)
                }
            }

    /** Derives the CDP `quality` field for a screenshot. PNG output is lossless and Chrome ignores the field entirely, so we drop it via
      * [[Absent]] / `None` rather than send a value the server discards. JPEG and WEBP both clamp the integer to the wire range `0..100`.
      */
    private def screenshotQuality(fmt: ScreenshotFormat, quality: Int): Maybe[Int] =
        fmt match
            case ScreenshotFormat.Png                          => Absent
            case ScreenshotFormat.Jpeg | ScreenshotFormat.Webp => Present(quality.max(0).min(100))

    // --- Viewport ---

    /** Overrides the rendered viewport to `width × height` (and `deviceScaleFactor` for the device pixel ratio) until [[resetViewport]] is
      * called.
      *
      * Forwards to `Emulation.setDeviceMetricsOverride` with `deviceScaleFactor` (default `1.0`) and `mobile = false`. The override affects
      * how the page renders: responsive media queries match the new viewport, elements re-layout, and `window.devicePixelRatio` reflects the
      * override. It is sticky: subsequent operations on the same tab observe the overridden viewport until the caller invokes
      * [[resetViewport]] (or the tab is closed). Settles after via `MutationSettlement.afterAction` so the re-layout has quiesced on return.
      *
      * The override persists until [[resetViewport]] is called or the tab is closed; not scope-managed.
      *
      * **Per-tab state, does NOT cross tab boundaries.** The viewport override is bound to the CDP session of the currently-active tab. It
      * does NOT propagate into tabs opened by [[withNewTab]], [[withFork]], [[withPopup]], or fork tabs from `isolate.fresh` /
      * `isolate.clone`. Each child tab starts with its own natural viewport; re-apply `setViewport` inside the child if you need the same
      * override there. For a snapshot-driven workflow consider invoking `setViewport` per-tab as part of the child's setup.
      */
    def setViewport(width: Int, height: Int, deviceScaleFactor: Double = 1.0)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        MutationSettlement.afterAction {
            Env.use[BrowserTab] { tab =>
                // Cache write FIRST, then issue the CDP call. If the CDP call fails, the cache reflects intent;
                // the reverse order would risk a permanently-stale cache on a missed update following a successful CDP call.
                tab.viewportOverride.set(Present(BrowserTab.ViewportOverride(width, height, deviceScaleFactor))).andThen(
                    CdpBackend.setDeviceMetricsOverride(tab.session, ViewportParams(width, height, deviceScaleFactor))
                )
            }
        }(Absent)

    /** Removes any viewport override set by [[setViewport]], restoring the tab's natural viewport.
      *
      * Forwards to `Emulation.clearDeviceMetricsOverride`. No-op when no override is currently active. Settles after via
      * `MutationSettlement.afterAction` so any re-layout from clearing the override has quiesced on return.
      */
    def resetViewport(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        MutationSettlement.afterAction {
            Env.use[BrowserTab] { tab =>
                tab.viewportOverride.set(Absent).andThen(
                    CdpBackend.clearDeviceMetricsOverride(tab.session)
                )
            }
        }(Absent)

    /** Scoped form of [[setViewport]]: applies the `width x height` override (and `deviceScaleFactor` for the device pixel ratio) for the
      * duration of `body`, then restores the prior override on body exit (success, failure, or interruption).
      *
      * Use this instead of `setViewport(w, h).andThen(...)` when you want the override bounded to a specific block. Composes naturally with
      * the rest of the API: viewport-dependent assertions inside `body` see the override, code after `body` does not.
      *
      * The prior override (if any) is cached on the BrowserTab. On exit, the cache is consulted: if a prior override was active at entry, it
      * is re-applied via `setDeviceMetricsOverride` carrying that prior override's own `deviceScaleFactor`; otherwise the override is cleared
      * via `clearDeviceMetricsOverride`. This lets nested `withViewport` calls compose correctly in LIFO order. The apply settles via
      * `MutationSettlement.afterAction` before `body` runs; the restore on teardown does not add settlement.
      */
    def withViewport[A, S](width: Int, height: Int, deviceScaleFactor: Double = 1.0)(body: A < (Browser & S))(using
        Frame
    ): A < (Browser & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            Scope.run {
                tab.viewportOverride.get.map { prior =>
                    Scope.acquireRelease(
                        MutationSettlement.afterAction {
                            tab.viewportOverride.set(Present(BrowserTab.ViewportOverride(width, height, deviceScaleFactor))).andThen(
                                CdpBackend.setDeviceMetricsOverride(tab.session, ViewportParams(width, height, deviceScaleFactor))
                            )
                        }(Absent)
                    ) { _ =>
                        tab.viewportOverride.set(prior).andThen(
                            prior match
                                case Present(vo) =>
                                    CdpBackend.setDeviceMetricsOverride(tab.session, ViewportParams(vo.width, vo.height, vo.dpr))
                                case Absent =>
                                    CdpBackend.clearDeviceMetricsOverride(tab.session)
                        )
                    }.andThen(body)
                }
            }
        }

    /** Scoped wrapper that applies emulated media features for the duration of `body`, then restores the prior state.
      *
      * Only the features the caller actually requests are sent in the single `Emulation.setEmulatedMedia` call: `media` selects the
      * media type (`screen` / `print`) and is omitted when `Absent`; `colorScheme` sets `prefers-color-scheme` (`light` / `dark`, or
      * the empty-string clear for [[Browser.ColorScheme.NoPreference]] since W3C dropped that value) and is omitted when `Absent`;
      * `prefers-reduced-motion` is sent as `reduce` only when `reducedMotion = true` and is omitted otherwise. So a color-scheme-only
      * call leaves the page's real `prefers-reduced-motion` untouched, and `reducedMotion = false` means "do not emulate reduced
      * motion", not "force no-preference". The override is cached on the tab and re-applied on exit via `Scope.acquireRelease` inside
      * an inner `Scope.run`, so nested calls compose in LIFO order and the restore fires on success, failure, AND interruption. When no
      * prior override was active the restore clears all media emulation with an empty `Emulation.setEmulatedMedia` send, so the host's
      * real media values return rather than a forced override. The apply settles via `MutationSettlement.afterAction` so any
      * media-query re-layout has quiesced before `body` starts.
      */
    def withEmulation[A, S](
        colorScheme: Maybe[Browser.ColorScheme] = Absent,
        media: Maybe[Browser.MediaType] = Absent,
        reducedMotion: Boolean = false
    )(body: A < (Browser & S))(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            val params = emulatedMediaParams(media.map(_.wire), colorScheme.map(_.wire), reducedMotion)
            Scope.run {
                tab.emulationOverride.get.map { prior =>
                    Scope.acquireRelease(
                        MutationSettlement.afterAction {
                            tab.emulationOverride.set(Present(BrowserTab.EmulatedMediaState(
                                colorScheme.map(_.wire),
                                media.map(_.wire),
                                reducedMotion
                            ))).andThen(
                                CdpBackend.setEmulatedMedia(tab.session, params)
                            )
                        }(Absent)
                    ) { _ =>
                        tab.emulationOverride.set(prior).andThen(
                            prior match
                                case Present(s) =>
                                    CdpBackend.setEmulatedMedia(
                                        tab.session,
                                        emulatedMediaParams(s.media, s.colorScheme, s.reducedMotion)
                                    )
                                case Absent =>
                                    // No prior override: clear all media emulation so the host's real values return. An empty
                                    // features list with empty media drops every prefers-* override back to the environment value.
                                    CdpBackend.setEmulatedMedia(tab.session, clearEmulatedMediaParams)
                        )
                    }.andThen(body)
                }
            }
        }
    end withEmulation

    /** Composes a `SetEmulatedMediaParams` carrying only the features the caller requested.
      *
      * `media` becomes the top-level media-type override when `Present`, omitted otherwise. `colorScheme` (already a CDP wire string,
      * `""` for the [[Browser.ColorScheme.NoPreference]] clear) contributes a `prefers-color-scheme` feature only when `Present`.
      * `reducedMotion = true` contributes a `prefers-reduced-motion: reduce` feature; `false` contributes nothing, so an unrelated
      * media feature is never perturbed. Used by both the apply path and the restore-to-a-prior-state path so the two stay in lockstep.
      */
    private[kyo] def emulatedMediaParams(
        media: Maybe[String],
        colorScheme: Maybe[String],
        reducedMotion: Boolean
    ): SetEmulatedMediaParams =
        val features = Chunk(
            colorScheme.map(cs => EmulatedMediaFeature("prefers-color-scheme", cs)),
            if reducedMotion then Present(EmulatedMediaFeature("prefers-reduced-motion", "reduce")) else Absent
        ).flatMap(_.toChunk)
        SetEmulatedMediaParams(media, Present(features.toSeq))
    end emulatedMediaParams

    /** The `SetEmulatedMediaParams` that clears every media-feature override back to the host.
      *
      * Empty media plus an empty features list drops every `prefers-*` override back to the environment value, rather than enumerating
      * each feature (which can silently miss one). Sent by the restore path when no prior override was active.
      */
    private[kyo] val clearEmulatedMediaParams: SetEmulatedMediaParams =
        SetEmulatedMediaParams(Present(""), Present(Seq.empty))

    /** Injects a settlement-transparent overlay (a dashed box per annotation, plus a label when one is provided) for the duration of
      * `body`, then removes it on exit.
      *
      * The overlay is one container subtree tagged `data-kyo-internal`, so the mutation observer ignores both its insertion and its
      * removal: the overlay does NOT arm the mutation gate, and a capture inside `body` sees the boxes while a settlement inside
      * `body` stays transparent to them. Each annotation resolves its element via the same selector machinery the rest of the API
      * uses; an annotation whose selector matches nothing is skipped. The inject eval completes before `body` runs, so screenshots
      * taken inside `body` include the overlays. The container is removed via `Scope.acquireRelease` inside an inner `Scope.run`, so
      * the removal fires on success, failure, AND interruption.
      *
      * Each invocation tags its container with a unique `data-kyo-token` minted by the inject eval (an in-page monotonic sequence) and
      * removes ONLY the node carrying that token, never a shared global slot. Nested or interleaved `withHighlights` blocks therefore
      * each tear down exactly their own overlay: an inner block's exit cannot make the outer block's removal a no-op.
      */
    def withHighlights[A, S](annotations: Span[Browser.Annotation])(body: A < (Browser & S))(using
        Frame
    ): A < (Browser & Abort[BrowserReadException] & S) =
        val specs = annotations.map { a =>
            val sel   = SelectorJs.resolveElementJs(Selector.toNode(a.selector))
            val color = s""""${JsStringUtil.escapeJsString(a.color.getOrElse("#d00"))}""""
            val label = s""""${JsStringUtil.escapeJsString(a.label.getOrElse(""))}""""
            s"""{ sel: () => $sel, color: $color, label: $label }"""
        }.mkString("[", ",", "]")
        val injectJs = s"""(() => {
            const root = document.createElement('div');
            const token = String((window.__kyoOverlayToken = (window.__kyoOverlayToken || 0) + 1));
            root.setAttribute('data-kyo-internal', 'highlights');
            root.setAttribute('data-kyo-token', token);
            root.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483646;';
            for (const a of $specs) {
                const el = a.sel(); if (!el) continue;
                const r = el.getBoundingClientRect();
                const box = document.createElement('div');
                box.setAttribute('data-kyo-internal', 'highlight');
                box.style.cssText = 'position:absolute;left:'+r.left+'px;top:'+r.top+'px;width:'+r.width+'px;height:'+r.height+'px;outline-width:2px;outline-style:dashed;outline-color:'+a.color+';';
                if (a.label) {
                    const b = document.createElement('div');
                    b.setAttribute('data-kyo-internal', 'label');
                    b.textContent = a.label;
                    b.style.cssText = 'position:absolute;left:'+r.left+'px;top:'+(r.top-16)+'px;background:'+a.color+';color:#fff;font:11px sans-serif;padding:0 3px;';
                    root.appendChild(b);
                }
                root.appendChild(box);
            }
            document.body.appendChild(root);
            return token;
        })()"""
        def removeJs(token: String) =
            val escaped = JsStringUtil.escapeJsString(token)
            s"""(() => {
            document.querySelectorAll('[data-kyo-token="$escaped"]').forEach(n => n.remove());
            return 'unhighlighted';
        })()"""
        end removeJs
        Env.use[BrowserTab] { tab =>
            Scope.run {
                Scope.acquireRelease(BrowserEval.evalJs(injectJs))(token =>
                    releaseHook(tab)(BrowserEval.evalJs(removeJs(token)).unit)
                ).andThen(body)
            }
        }
    end withHighlights

    // --- Keyboard ---

    /** Types the given text character by character via keyboard events. */
    def typeText(text: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val s   = tab.session
            val len = text.length
            Loop.indexed { i =>
                if i >= len then Loop.done(())
                else
                    val char = text.substring(i, i + 1)
                    CdpBackend.dispatchKeyEvent(
                        s,
                        DispatchKeyEventParams(CdpTypes.KeyEventType.Down, Present(char), Present(char), Absent, Absent)
                    )
                        .andThen(
                            CdpBackend.dispatchKeyEvent(
                                s,
                                DispatchKeyEventParams(CdpTypes.KeyEventType.Up, Present(char), Absent, Absent, Absent)
                            )
                        )
                        .andThen(Loop.continue)
                end if
            }
        }

    /** Dispatches a keyDown event for the given key. */
    def keyDown(key: Key)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val info = KeyInfo.mapKey(key)
            // Modifier bit must be sent on keyDown so the resulting DOM event carries shiftKey/ctrlKey/etc.
            val mods = if info.modifierBit > 0 then Present(info.modifierBit) else Absent
            CdpBackend.dispatchKeyEvent(
                tab.session,
                DispatchKeyEventParams(
                    CdpTypes.KeyEventType.Down,
                    Present(info.keyName),
                    info.text,
                    Present(info.domCode),
                    Present(info.keyCode),
                    mods
                )
            )
        }

    /** Dispatches a keyUp event for the given key. */
    def keyUp(key: Key)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val info = KeyInfo.mapKey(key)
            // Keep the modifier bit set on keyUp so the matching DOM event carries shiftKey/ctrlKey/etc.
            val mods = if info.modifierBit > 0 then Present(info.modifierBit) else Absent
            CdpBackend.dispatchKeyEvent(
                tab.session,
                DispatchKeyEventParams(
                    CdpTypes.KeyEventType.Up,
                    Present(info.keyName),
                    Absent,
                    Present(info.domCode),
                    Present(info.keyCode),
                    mods
                )
            )
        }

    // --- Scrolling ---

    /** Scrolls the window to the document coordinate `(x, y)` via `window.scrollTo`.
      *
      * Settles after via `MutationSettlement.afterAction` so any layout reaction to the scroll has quiesced on return.
      */
    def scrollTo(x: Int, y: Int)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        MutationSettlement.afterAction {
            BrowserEval.evalJs(s"window.scrollTo($x, $y)").unit
        }(Absent)

    /** Scrolls the page until the element matched by the selector is visible.
      *
      * Auto-waits for the element via `Actionability.withRetry`: the read is retried over the configured `retrySchedule` until the element
      * resolves, then `scrollIntoView` runs. The retry channel is `BrowserElementException` (never widened to `BrowserMutationException`).
      * Aborts `BrowserElementNotFoundException` when the element never appears within the schedule. Settles after.
      */
    def scrollToElement(selector: Selector)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        MutationSettlement.afterAction {
            Actionability.withRetry {
                Actionability.requireResolved(selector) {
                    BrowserEval.evalJs(s"""(() => {
                        const el = $jsExpr;
                        el.scrollIntoView({behavior:'instant'});
                        return 'ok';
                    })()""").unit
                }
            }
        }(Absent)
    end scrollToElement

    /** Scrolls the page to the top. */
    def scrollToTop(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs("window.scrollTo(0, 0)").unit

    /** Scrolls the page to the bottom. */
    def scrollToBottom(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs("window.scrollTo(0, document.body.scrollHeight)").unit

    // --- JavaScript ---

    /** Evaluates the given JavaScript expression in the page and returns the result as a string. */
    def eval(js: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJsChecked(js)

    /** Evaluates JavaScript and decodes the result as the given type.
      *
      * Pass a bare JS expression; `evalJson` already wraps it in `JSON.stringify(...)` on the page side.
      *
      * Example:
      * ```scala
      * case class Point(x: Int, y: Int) derives Schema
      *
      * Browser.evalJson[Point]("({ x: 10, y: 20 })")          // ✓ library JSON.stringifies the result
      * Browser.evalJson[Point]("JSON.stringify({x:10,y:20})") // ✗ double-stringifies; decode fails
      * ```
      */
    def evalJson[A: Schema](js: String)(using
        Frame
    ): A < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJsChecked(s"JSON.stringify(($js))").map { json =>
            Json.decode[A](json) match
                case Result.Success(a) => a
                case Result.Failure(err) =>
                    Abort.fail(BrowserDecodingException("evalJson", err.toString))
        }

    /** Evaluates `js` and decodes the JSON result as a `Boolean`. Thin convenience wrapper around [[evalJson]]. */
    def evalBoolean(js: String)(using Frame): Boolean < (Browser & Abort[BrowserReadException]) =
        evalJson[Boolean](js)

    /** Evaluates `js` and decodes the JSON result as an `Int`. Thin convenience wrapper around [[evalJson]]. */
    def evalInt(js: String)(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        evalJson[Int](js)

    /** Evaluates `js` and decodes the JSON result as a `Long`. Thin convenience wrapper around [[evalJson]]. */
    def evalLong(js: String)(using Frame): Long < (Browser & Abort[BrowserReadException]) =
        evalJson[Long](js)

    /** Evaluates `js` and decodes the JSON result as a `Double`. Thin convenience wrapper around [[evalJson]]. */
    def evalDouble(js: String)(using Frame): Double < (Browser & Abort[BrowserReadException]) =
        evalJson[Double](js)

    /** Evaluates `js` for its side effects and discards the result. Built on [[eval]] (not [[evalJson]]) so the script is not required to
      * return a JSON-encodable value.
      */
    def evalDiscard(js: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        eval(js).unit

    /** Returns all console messages captured since the last call (or since page load on the first call) and clears the buffer.
      *
      * The `console.debug` / `console.info` / `console.log` / `console.warn` / `console.error` override is installed eagerly when the tab is
      * attached, so messages emitted during page load are captured even before the first `consoleLogs` call. Each call drains and clears the
      * buffer. Each entry carries a typed [[Browser.ConsoleLevel]], the joined message text, and an `offsetMs` relative to the page-side
      * capture baseline (`window.__kyoConsoleT0`, the `Date.now()` at install time).
      */
    def consoleLogs(using Frame): Chunk[Browser.ConsoleMessage] < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs("(window.__kyoConsoleT0 || 0)").map { t0Raw =>
            val t0Ms = t0Raw.toLongOption.getOrElse(0L)
            BrowserEval.evalJs("""(() => {
                const logs = window.__kyoConsoleLogs || [];
                window.__kyoConsoleLogs = [];
                return JSON.stringify(logs);
            })()""").map { json =>
                if json.isEmpty then Chunk.empty
                else
                    Json.decode[Seq[ConsoleMessageWire]](json) match
                        case Result.Success(list) =>
                            Kyo.foreach(Chunk.from(list))(w => decodeConsoleMessage(w, t0Ms))
                        case other =>
                            Log.warn(s"consoleLogs: unexpected wire shape decoding Seq[ConsoleMessageWire]: $other; raw=$json")
                                .andThen(Abort.fail(BrowserProtocolErrorException.decodeFailure("consoleLogs", s"$other; raw=$json")))
            }
        }

    /** Filtering overload of [[consoleLogs]] that drains the buffer and returns only entries whose level equals `level`. */
    def consoleLogs(level: Browser.ConsoleLevel)(using Frame): Chunk[Browser.ConsoleMessage] < (Browser & Abort[BrowserReadException]) =
        consoleLogs.map(_.filter(_.level == level))

    private[kyo] def decodeConsoleMessage(wire: ConsoleMessageWire, t0Ms: Long)(using
        Frame
    ): Browser.ConsoleMessage < Abort[BrowserReadException] =
        val levelE: Browser.ConsoleLevel < Abort[BrowserReadException] = wire.level match
            case "log"   => Browser.ConsoleLevel.Log
            case "info"  => Browser.ConsoleLevel.Info
            case "warn"  => Browser.ConsoleLevel.Warn
            case "error" => Browser.ConsoleLevel.Error
            case "debug" => Browser.ConsoleLevel.Debug
            case other =>
                Abort.fail(BrowserProtocolErrorException.decodeFailure(
                    "consoleLogs",
                    s"unknown level '$other' (expected log|info|warn|error|debug)"
                ))
        levelE.map(lv => Browser.ConsoleMessage(lv, wire.message, Absent, wire.timestamp - t0Ms))
    end decodeConsoleMessage

    /** Decodes a CDP `Runtime.consoleAPICalled` wire record into a typed [[Browser.ConsoleMessage]]. The 18 CDP `type` literals are mapped
      * or aborted explicitly: the 10 non-structural types fold onto the five [[Browser.ConsoleLevel]] severities (`warning` maps to `Warn`,
      * `trace` to `Debug`, `dir`/`dirxml`/`table` to `Log`, `assert` to `Error`), and the 8 structural types (`count`, `countReset`,
      * `timeEnd`, `startGroup`, `startGroupCollapsed`, `endGroup`, `clear`, `profile`/`profileEnd`) abort
      * [[BrowserProtocolErrorException.decodeFailure]] so a new CDP type surfaces as a typed protocol error rather than a silent `Log`.
      *
      * The CDP path knows `warning`, never the drain path's `warn` spelling; the two spellings never collide.
      */
    private[kyo] def decodeConsoleApiCalled(wire: ConsoleApiCalledWire, t0Ms: Long)(using
        Frame
    ): Browser.ConsoleMessage < Abort[BrowserReadException] =
        val levelE: Browser.ConsoleLevel < Abort[BrowserReadException] = wire.`type` match
            case "log"     => Browser.ConsoleLevel.Log
            case "info"    => Browser.ConsoleLevel.Info
            case "warning" => Browser.ConsoleLevel.Warn
            case "error"   => Browser.ConsoleLevel.Error
            case "debug"   => Browser.ConsoleLevel.Debug
            case "trace"   => Browser.ConsoleLevel.Debug
            case "dir"     => Browser.ConsoleLevel.Log
            case "dirxml"  => Browser.ConsoleLevel.Log
            case "table"   => Browser.ConsoleLevel.Log
            case "assert"  => Browser.ConsoleLevel.Error
            case structural @ ("count" | "countReset" | "timeEnd" | "startGroup" | "startGroupCollapsed" | "endGroup" | "clear" |
                "profile" | "profileEnd") =>
                Abort.fail(BrowserProtocolErrorException.decodeFailure(
                    "recordConsole",
                    s"unmapped structural console type '$structural'"
                ))
            case other =>
                Abort.fail(BrowserProtocolErrorException.decodeFailure("recordConsole", s"unknown console type '$other'"))
        levelE.map { lv =>
            val text = wire.args.flatMap(a => a.value.orElse(a.description).toChunk).mkString(" ")
            val location = wire.stackTrace.flatMap(st =>
                Maybe.fromOption(st.callFrames.headOption).flatMap(cf => cf.url.map(u => u + ":" + cf.lineNumber.getOrElse(0)))
            )
            Browser.ConsoleMessage(lv, text, location, computeOffsetMs(wire.timestamp, t0Ms))
        }
    end decodeConsoleApiCalled

    /** Converts a CDP event `timestamp` (milliseconds since epoch when present) into an offset relative to the recording baseline `t0Ms`.
      * An absent timestamp yields `0L`.
      */
    private def computeOffsetMs(ts: Maybe[Double], t0Ms: Long): Long =
        ts match
            case Present(v) => math.round(v) - t0Ms
            case Absent     => 0L

    /** Auto-handles JavaScript dialogs (alert, confirm, prompt, beforeunload) opened during the body's execution.
      *
      * Three intent-revealing entry points:
      *   - [[withDialogs.accept]]: accept the dialog; `confirm()` returns true, `prompt()` returns the empty string.
      *   - [[withDialogs.dismiss]]: dismiss the dialog; `confirm()` returns false, `prompt()` returns null.
      *   - [[withDialogs.prompt]]: accept the dialog and supply a specific value for `prompt()`.
      *
      * Internally, each entry point registers a per-session dialog handler on the underlying `CdpClient`. When Chrome fires
      * `Page.javascriptDialogOpening`, the CDP reader fiber looks up the handler for the event's session ID and enqueues
      * `(accept, promptText)` for `Page.handleJavaScriptDialog`.
      *
      * Per-session isolation: dialogs are keyed by CDP session ID, so concurrent `withDialogs` calls in different tabs (e.g. concurrent
      * test runs) do not interfere with each other. Each tab handles only its own dialogs.
      *
      * Outside any `withDialogs` scope, dialogs for a session are auto-dismissed with `accept = false, promptText = ""`, preventing the
      * reader from stalling.
      *
      * Nesting: each `withDialogs` call atomically updates the handler for its session and restores the previous value on exit (via
      * `Scope.ensure`, which fires on success, failure, AND interruption). Nested calls correctly restore the outer handler when the inner
      * exits.
      *
      * Effect set: each entry point widens `S` to `Browser & S`. Dialog handlers are keyed on the active tab's CDP session, so a `Browser`
      * (active tab) is required, unlike [[withConfig]], which only manipulates a `Local` and is usable outside [[Browser.run]].
      *
      * @example
      *   ```scala
      *   Browser.withDialogs.prompt("Alice") {
      *     Browser.click(Selector.id("promptBtn")).andThen {
      *         Browser.eval("window.__promptResult").map(...)
      *     }
      *   }
      *   ```
      */
    object withDialogs:

        /** Accepts dialogs opened during `v`: `confirm()` returns true and `prompt()` returns the empty string. */
        def accept[A, S](v: A < S)(using Frame): A < (Browser & S) =
            install(accept = true, promptText = "")(v)

        /** Dismisses dialogs opened during `v`: `confirm()` returns false and `prompt()` returns null. */
        def dismiss[A, S](v: A < S)(using Frame): A < (Browser & S) =
            install(accept = false, promptText = "")(v)

        /** Accepts prompt dialogs opened during `v`, supplying `text` as the value `prompt()` returns. */
        def prompt[A, S](text: String)(v: A < S)(using Frame): A < (Browser & S) =
            install(accept = true, promptText = text)(v)

        private def install[A, S](accept: Boolean, promptText: String)(v: A < S)(using Frame): A < (Browser & S) =
            Env.use[BrowserTab] { tab =>
                val client = tab.client
                // Key the handler map by CDP session ID so concurrent tabs don't clobber each other's entries.
                val sidKey = tab.sessionId.value
                client.dialogHandlers.getAndUpdate(m => m.update(sidKey, (accept, promptText))).map { previousMap =>
                    val restore = client.dialogHandlers.getAndUpdate { m =>
                        previousMap.get(sidKey) match
                            case Present(prev) => m.update(sidKey, prev)
                            case Absent        => m.remove(sidKey)
                    }.unit
                    // `Scope.run + Scope.ensure` (not `Sync.ensure`): `Sync.ensure` doesn't fire on Abort short-circuits and would
                    // leak the per-session handler. The inner `Scope.run` bounds the cleanup to this call so nested `withDialogs`
                    // calls keep their LIFO restore semantics.
                    Scope.run(Scope.ensure(restore).andThen(v))
                }
            }

        /** Captures every JavaScript dialog event (`alert`, `confirm`, `prompt`, `beforeunload`) observed during `body` into an in-memory
          * [[Chunk]]. Returns a pair `(events, result)` where `events` is the arrival-ordered chunk of every event the page emitted and
          * `result` is the value `body` produced.
          *
          * `recorded` is a passive observer: it does NOT change the auto-handler's behaviour. Compose it with [[accept]] / [[dismiss]] /
          * [[prompt]] to drive a typed response; without an inner handler the per-session auto-dismiss path (accept=false, promptText="")
          * still fires AND is captured. Each [[DialogEvent.response]] records what the active handler returned: `Present("text")` for
          * `prompt` mode with text, `Absent` for `accept` / `dismiss` / auto-dismiss.
          *
          * Mirror of [[Browser.recordDownloads]] in shape: an internal `AtomicRef[Chunk[DialogEvent]]` is registered as the per-session
          * dialog recorder on entry, then unregistered via `Scope.ensure` on body completion (success, failure, OR interruption).
          *
          * Per-tab isolation: the recorder is keyed by CDP session ID; a `recorded` block in tab A does not capture dialogs fired inside
          * tab B (e.g. inside [[Browser.withNewTab]] / [[Browser.withFork]]).
          */
        def recorded[A, S](body: A < (Browser & Async & Abort[BrowserReadException] & S))(using
            Frame,
            Isolate[S, Sync, S]
        ): (Chunk[Browser.DialogEvent], A) < (Browser & Async & Abort[BrowserReadException] & S) =
            Env.use[BrowserTab] { tab =>
                val client = tab.client
                val sidKey = tab.sessionId.value
                AtomicRef.init(Chunk.empty[Browser.DialogEvent]).map { recorder =>
                    client.dialogRecorders.getAndUpdate(_.update(sidKey, recorder)).map { previousMap =>
                        val restore = client.dialogRecorders.getAndUpdate { m =>
                            previousMap.get(sidKey) match
                                case Present(prev) => m.update(sidKey, prev)
                                case Absent        => m.remove(sidKey)
                        }.unit
                        Scope.run {
                            Scope.ensure(restore).andThen(body).map { result =>
                                recorder.get.map(events => (events, result))
                            }
                        }
                    }
                }
            }
    end withDialogs

    // --- Frames ---

    /** Scope every nested action to `frame`'s document for the duration of `v`.
      *
      * Restores the previous frame scope on exit (success, abort, or fiber interruption). Honors nesting: an inner [[withIFrame]] overrides
      * the outer; on inner exit the outer scope is restored. Concurrent fibers are isolated per the per-fiber semantics of `Local`.
      *
      * Aborts with [[BrowserIFrameInvalidException]] inside the body if the frame's execution context is destroyed (e.g. parent navigation,
      * iframe removed from DOM); the next action issued through the scope will fail loudly rather than silently re-rooting at main.
      *
      * Effect set: returns `Browser & S`. The frame handle was obtained from a live tab via [[iframe]] / [[mainFrame]] / [[iframes]], so an
      * active `Browser` is required to interpret the scoped actions. (Contrast with [[withConfig]], which is pure `Local.let` and does NOT
      * add `Browser` to the effect set.)
      *
      * @example
      *   ```scala
      *   Browser.iframe(Selector.css("iframe#editor")).map { f =>
      *       Browser.withIFrame(f) {
      *           Browser.click(Selector.button("Save"))
      *       }
      *   }
      *   ```
      */
    def withIFrame[A, S](frame: IFrame)(v: A < (Browser & S))(using Frame): A < (Browser & S) =
        import IFrame.handle
        activeIFrameLocal.let(Present(frame.handle))(v)

    /** Resolve the iframe element identified by `selector` to an [[IFrame]] handle.
      *
      *   - Aborts with [[BrowserElementNotFoundException]] if the selector does not match.
      *   - Aborts with [[BrowserIFrameInvalidException]] (`reason = BrowserIFrameInvalidException.Reason.NotAFrame`) if the matched element is not an
      *     `<iframe>` / `<frame>` / browsable `<object>`.
      *   - Aborts with [[BrowserIFrameInvalidException]] (`reason = BrowserIFrameInvalidException.Reason.ContextNotObserved`) if the frame element is in the
      *     DOM but its document has not produced a default execution context yet (e.g. mid-load). Callers can wrap in
      *     `Browser.assertExists(selector)` first to ensure DOM attachment, but the resolver also retries on the active `retrySchedule`, so
      *     transient mid-load misses recover automatically.
      */
    def iframe(selector: Selector, schedule: Maybe[Schedule] = Absent)(using Frame): IFrame < (Browser & Abort[BrowserReadException]) =
        configLocal.use { cfg =>
            val effectiveSchedule = schedule.getOrElse(cfg.retrySchedule)
            Retry[BrowserMutationException](effectiveSchedule) {
                IFrameResolver.resolveIFrameHandle(selector).map(IFrame(_))
            }
        }

    /** Handle for the top-level frame of the current tab. Always available on a stable, fully-seeded tab.
      *
      * Aborts with [[BrowserConnectionException]] if the underlying CDP connection has dropped. Aborts with
      * [[BrowserIFrameInvalidException]] (`reason = BrowserIFrameInvalidException.Reason.RootNotSeeded`) if the tab's root frame id has not yet been seeded
      * by `attachTab`'s initial `Page.getFrameTree`, and with `reason = BrowserIFrameInvalidException.Reason.ContextNotObserved` if the root frame id is
      * known but its default execution context has not yet been observed.
      */
    def mainFrame(using Frame): IFrame < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            tab.rootFrameId.get.map {
                case Present(fid) =>
                    tab.frameContexts.get.map { ctxMap =>
                        ctxMap.get(fid) match
                            case Present(cid) =>
                                IFrame(IFrameHandle(fid, cid))
                            case Absent =>
                                Abort.fail(
                                    BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextNotObserved)
                                )
                    }
                case Absent =>
                    Abort.fail(
                        BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.RootNotSeeded)
                    )
            }
        }

    /** Snapshot of every same-origin frame currently known on the active tab in document order (depth-first traversal of the frame tree,
      * main first).
      *
      * Frames whose execution context is not yet observed (e.g. an iframe mid-load) are skipped; `iframes` is a snapshot, not a barrier.
      * Callers that want "every frame, blocking until the contexts are visible" wrap individual selectors in [[iframe]] (which retries)
      * instead.
      */
    def iframes(using Frame): Chunk[IFrame] < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.getFrameTree(tab.session).map { tree =>
                tab.frameContexts.get.map { ctxMap =>
                    Chunk.from(IFrameResolver.flattenFrameTree(tree.frameTree).flatMap { fid =>
                        ctxMap.get(fid) match
                            case Present(cid) => Seq(IFrame(IFrameHandle(fid, cid)))
                            case Absent       => Seq.empty
                    })
                }
            }
        }

    // --- Network ---

    /** Registers a mock response for the given URL. Interception is JS-level only (overrides `window.fetch` and `XMLHttpRequest`); it does
      * **not** intercept navigation requests, requests made by service workers, or any request that fires before this script is installed.
      *
      * On first call, installs a page-level fetch/XHR interceptor. Subsequent requests matching the exact URL receive the mocked response.
      * The interceptor is installed idempotently; calling `mockFetchResponse` multiple times is safe and will not reinstall it.
      *
      * To intercept navigation-level or pre-script requests, a full CDP `Fetch` domain integration would be required; that is out of scope
      * for this method.
      *
      * **Per-tab state, does NOT cross tab boundaries.** The mock registry lives on the page (`window.__kyoMocks`) of the currently-active
      * tab. Child tabs opened via [[withNewTab]], [[withFork]], [[withPopup]], or `isolate.fresh` / `isolate.clone` start with a clean page
      * and have no mocks installed; re-register mocks inside the child block if you need the same interception there. Navigating away with
      * [[goto]] also clears the mock registry, as the new page brings its own window.
      */
    def mockFetchResponse(url: String, status: Int, body: String, headers: Seq[(String, String)] = Seq.empty)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        ensureInterceptEnabled.andThen {
            val escapedUrl = JsStringUtil.escapeJsString(url)
            val wireHeaders =
                Chunk.from(headers).map((n, v) => MockHeader(n, v))
            val envelopeJson = JsStringUtil.escapeJsString(Json.encode(MockResponseEnvelope(status, body, wireHeaders)))
            BrowserEval.evalJs(s"""(() => {
                if (!window.__kyoMocks) window.__kyoMocks = {};
                window.__kyoMocks['$escapedUrl'] = JSON.parse('$envelopeJson');
                return 'ok';
            })()""").unit
        }
    end mockFetchResponse

    /** Removes all registered mock responses. */
    def clearMocks(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs("""(() => {
            window.__kyoMocks = {};
            return 'ok';
        })()""").unit

    private def ensureInterceptEnabled(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        // The JS payload short-circuits via the `__kyoInterceptInstalled` flag, so a Scala-side probe is not needed.
        // Mirrors `BrowserNetworkTracker.ensureInstalled`: re-invocations hit the JS guard in O(1).
        BrowserEval.evalJs("""(() => {
            if (window.__kyoInterceptInstalled) return 'ok';
            window.__kyoInterceptInstalled = true;
            if (!window.__kyoMocks) window.__kyoMocks = {};
            // --- fetch override ---
            // Mock-served requests short-circuit before the response tracker's wrapper runs (the tracker only sees URLs
            // via origFetch.apply(...).then(...)), so the mock interceptor must push the URL into
            // __kyoResponseObserved directly. Without this, Browser.waitForRequestUrl misses every mocked URL.
            const origFetch = window.fetch;
            window.fetch = function(input, init) {
                const url = typeof input === 'string' ? input : input.url;
                if (window.__kyoMocks[url]) {
                    if (!window.__kyoResponseObserved) window.__kyoResponseObserved = [];
                    window.__kyoResponseObserved.push(String(url));
                    const mock = window.__kyoMocks[url];
                    // headers is Array<{name, value}>. Convert to array-of-tuples [[name, value], ...] which
                    // the Response constructor accepts and which preserves duplicate header names.
                    const hdrs = Array.isArray(mock.headers)
                        ? mock.headers.map(h => [h.name, h.value])
                        : [];
                    return Promise.resolve(new Response(mock.body, {
                        status: mock.status,
                        headers: hdrs
                    }));
                }
                return origFetch.apply(this, arguments);
            };
            // --- XHR override ---
            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__kyoUrl = url;
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const url = this.__kyoUrl;
                if (window.__kyoMocks && window.__kyoMocks[url]) {
                    if (!window.__kyoResponseObserved) window.__kyoResponseObserved = [];
                    window.__kyoResponseObserved.push(String(url));
                    const mock = window.__kyoMocks[url];
                    Object.defineProperty(this, 'status', {get: () => mock.status});
                    Object.defineProperty(this, 'responseText', {get: () => mock.body});
                    Object.defineProperty(this, 'readyState', {get: () => 4});
                    if (this.onreadystatechange) this.onreadystatechange();
                    if (this.onload) this.onload();
                    return;
                }
                return origSend.apply(this, arguments);
            };
            return 'ok';
        })()""").unit

    // --- Downloads ---

    /** Cross-platform absolute-path check for the `toPath` argument to [[setDownloadBehavior]].
      *
      * Accepts POSIX-absolute paths (begin with `/`) and Windows-style drive-letter absolute paths (e.g. `C:\\…`, `C:/…`). This is a string
      * check by design: `java.io.File.isAbsolute` is JVM-only, and the abstraction layer here must compile on JS/Native.
      */
    private def isAbsolutePath(p: String): Boolean =
        p.startsWith("/") || p.matches("^[A-Za-z]:[/\\\\].*")

    /** Allows downloads on the current tab.
      *
      * Chrome saves files to `toPath` (must be an absolute path). Relative paths are rejected with
      * [[BrowserInvalidArgumentException]] before any CDP call is issued.
      *
      * **Per-tab state, does NOT cross tab boundaries.** The download policy is set via CDP `Browser.setDownloadBehavior` keyed to the
      * current tab's `browserContextId`. Children opened by [[withNewTab]] inherit the parent's context (so the policy IS inherited); but
      * children opened by [[withFork]], [[withPopup]], `isolate.fresh`, and `isolate.clone` live in fresh browser contexts and start with
      * Chrome's default policy. Re-call `allowDownloads` inside those blocks if downloads must be captured there too.
      */
    private[kyo] def allowDownloads(toPath: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        setDownloadBehavior(Browser.DownloadBehavior.Allow, Present(toPath))

    /** Denies downloads on the current tab; Chrome drops download-triggering navigations and emits no events.
      *
      * **Per-tab state, does NOT cross tab boundaries.** See [[allowDownloads]] for the inheritance rules; the same context-scoping applies
      * here.
      */
    private[kyo] def denyDownloads(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        setDownloadBehavior(Browser.DownloadBehavior.Deny, Absent)

    /** Scoped form of [[allowDownloads]]: sets the download policy to `Allow(toPath)` for the duration of `body`, then restores to `Deny`
      * (Chrome's default policy) on body exit (success, failure, or interruption).
      *
      * Use this for blocks that need to capture downloads without leaving the policy "allow" after the block returns. Composes with
      * [[onDownload]]: subscribe before this block, then trigger downloads inside.
      *
      * The prior download policy (if any) is cached on the BrowserTab. On exit, the cache is consulted: if a prior policy was active at
      * entry, it is re-applied via `setDownloadBehavior`; otherwise the policy is reset to `Deny` (Chrome's launch-time default). This
      * lets nested `withDownloads` calls compose correctly.
      */
    def withDownloads[A, S](toPath: String)(body: A < (Browser & S))(using
        Frame
    ): A < (Browser & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            Scope.run {
                tab.downloadPolicy.get.map { prior =>
                    Scope.acquireRelease(
                        setDownloadBehavior(Browser.DownloadBehavior.Allow, Present(toPath))
                    ) { _ =>
                        tab.downloadPolicy.set(prior).andThen(
                            prior match
                                case Present((behavior, p)) =>
                                    PageDownload.setDownloadBehavior(tab.session, behavior.toInternal, p)
                                case Absent =>
                                    PageDownload.setDownloadBehavior(tab.session, Browser.DownloadBehavior.Deny.toInternal, Absent)
                        )
                    }.andThen(body)
                }
            }
        }
    end withDownloads

    /** Sets the download policy explicitly. Public entry point for the full [[DownloadBehavior]] enum.
      *
      *   - Validates `toPath`: if `Present(p)` and `p` is not absolute, aborts with [[BrowserInvalidArgumentException]] before issuing the
      *     CDP call.
      *   - Issues `Page.setDownloadBehavior` on the current tab's session.
      *
      * Note: `eventsEnabled` is hard-wired to `true` at the internal layer, so [[onDownload]] subscribers always observe events for the
      * configured behavior.
      */
    private[kyo] def setDownloadBehavior(behavior: Browser.DownloadBehavior, toPath: Maybe[String] = Absent)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val validate: Unit < Abort[BrowserReadException] = toPath match
                case Present(p) if !isAbsolutePath(p) =>
                    Abort.fail(BrowserInvalidArgumentException("setDownloadBehavior", s"toPath must be absolute, got '$p'"))
                case Absent if behavior == Browser.DownloadBehavior.Allow =>
                    // chrome-headless-shell silently no-ops `Page.setDownloadBehavior` when `behavior=allow` and no explicit
                    // `downloadPath` is set, so the WillBegin event never fires and downloads silently disappear. Full Chrome
                    // historically accepted Absent and fell back to the OS default download dir, but kyo-browser auto-downloads
                    // chrome-headless-shell by default and we don't want a contract that silently breaks for that binary.
                    // Reject the combination at the entry point so the failure is explicit and the API works the same on both
                    // binaries.
                    Abort.fail(BrowserInvalidArgumentException(
                        "setDownloadBehavior",
                        "behavior=Allow requires a Present toPath (absolute path)"
                    ))
                case _ => Kyo.unit
            // Cache write FIRST (post-validate), then issue the CDP call. Skip caching the "Deny + Absent" tear-down state because that
            // matches the implicit "no override active" semantics of Absent in the cache, keeping the restore-to-Absent path correct.
            validate.andThen {
                val updateCache: Unit < Sync =
                    if behavior == Browser.DownloadBehavior.Deny && toPath.isEmpty then tab.downloadPolicy.set(Absent)
                    else tab.downloadPolicy.set(Present((behavior, toPath)))
                updateCache.andThen(PageDownload.setDownloadBehavior(tab.session, behavior.toInternal, toPath))
            }
        }
    end setDownloadBehavior

    /** Subscribes to download events for the duration of `action`. `f` is invoked for each `Page.downloadWillBegin` /
      * `Page.downloadProgress` event observed on the current tab.
      *
      * Implementation: events flow from the CDP reader through a per-session dispatcher onto a bounded `Channel[DownloadEvent]`; a forked
      * drainer fiber takes events off the channel and applies `f`. This isolates `f`'s effect row `S` from the dispatcher's `Sync`-only
      * value type. The channel and drainer fiber are bound to an internal `Scope.run`, so teardown happens when `action` completes
      * (success, failure, OR interruption); the caller's effect row does NOT carry `Scope`.
      *
      * Per-tab isolation: the dispatcher is keyed by CDP session ID; concurrent tabs do not cross-talk.
      *
      * Subscribe semantics: the handler is registered BEFORE `action` runs, so triggers inside `action` can emit events without being
      * missed.
      */
    def onDownload[A, S](f: Browser.DownloadEvent => Unit < (Browser & S))(
        action: A < (Browser & Async & Abort[BrowserReadException] & S)
    )(using
        Frame,
        Isolate[S, Sync, S]
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            val client = tab.client
            val sidKey = tab.sessionId.value
            // Bounded unscoped channel; closed explicitly via `Scope.ensure` inside the inner `Scope.run`. Keeping the channel unscoped
            // lets `onDownload`'s effect row stay free of `Scope` while still binding teardown to the body's lifetime.
            Channel.initUnscoped[Browser.DownloadEvent](PageDownload.onDownloadChannelCapacity).map { channel =>
                val handler: CdpEvent.Generic => Unit < Sync = ev =>
                    parseDownloadEvent(ev) match
                        case Present(de) =>
                            // Best-effort offer: if the channel is full (drainer slow) or closed (scope tearing down)
                            // we drop the event rather than block the CDP reader fiber. Abort[Closed] is swallowed.
                            Abort.run[Closed](channel.offer(de)).unit
                        case Absent => Kyo.unit
                // Register the per-session dispatcher BEFORE `action` runs so triggers inside `action` cannot race
                // the subscription. The trailing `Scope.run + Scope.ensure` (mirroring [[withDialogs.install]]) unregisters the dispatcher
                // and closes the channel on body completion (success, failure, OR interruption). The drainer fiber is `Scope`-bound via
                // `Fiber.init`, so it is interrupted on scope exit.
                client.downloadEventDispatchers.getAndUpdate(_.update(sidKey, handler)).map { previousMap =>
                    val restoreDispatcher = client.downloadEventDispatchers.getAndUpdate { m =>
                        previousMap.get(sidKey) match
                            case Present(prev) => m.update(sidKey, prev)
                            case Absent        => m.remove(sidKey)
                    }.unit
                    Scope.run {
                        Scope.ensure(restoreDispatcher).andThen(Scope.ensure(channel.close.unit)).andThen {
                            Fiber.init {
                                // Discharge `Env[BrowserTab]` per element so handlers that observe `Browser`
                                // (e.g. screenshot the page that triggered the download) resolve through the
                                // parent tab's session. The drainer fiber's effect row does NOT inherit the
                                // outer Env, so we wrap each invocation.
                                Abort.run[Closed](channel.stream().foreach(de => Env.run(tab)(f(de)))).unit
                            }.andThen(action)
                        }
                    }
                }
            }
        }
    end onDownload

    /** Captures every [[DownloadEvent]] observed during `body` into an in-memory [[Chunk]]. Returns a pair `(events, result)` where
      * `events` is the arrival-ordered chunk of every event the page emitted and `result` is the value `body` produced.
      *
      * Use this instead of [[onDownload]] when the test only needs to inspect the events after the body completes; no manual `AtomicRef`
      * or `Channel` bookkeeping is required.
      *
      * Implemented in terms of [[onDownload]] with a capture handler that appends each event to an internal `AtomicRef[Chunk]`. The
      * drainer fiber inside `onDownload` is single-fiber per session, so appends are serialised and arrival order is preserved.
      */
    def recordDownloads[A, S](body: A < (Browser & Async & Abort[BrowserReadException] & S))(using
        Frame,
        Isolate[S, Sync, S]
    ): (Chunk[Browser.DownloadEvent], A) < (Browser & Async & Abort[BrowserReadException] & S) =
        AtomicRef.init(Chunk.empty[Browser.DownloadEvent]).map { collected =>
            val captureHandler: Browser.DownloadEvent => Unit < Sync =
                ev => collected.updateAndGet(_.append(ev)).unit
            onDownload(captureHandler)(body).map { result =>
                collected.get.map(events => (events, result))
            }
        }
    end recordDownloads

    /** Decode a [[CdpEvent.Generic]] download event's wire JSON into a typed [[Browser.DownloadEvent]]. Returns `Absent` when the event is
      * not one of the two download methods or when the JSON cannot be parsed.
      *
      * Decoding is wire-shape tolerant: extra fields are ignored. Missing optional fields fall back to defaults that keep `f` callable
      * (`""` for strings, `0L` for numerics).
      */
    private def parseDownloadEvent(ev: CdpEvent.Generic)(using Frame): Maybe[Browser.DownloadEvent] =
        ev.method match
            case "Page.downloadWillBegin" =>
                Json.decode[CdpEventParams[PageDownload.DownloadWillBeginWire]](ev.paramsJson) match
                    case Result.Success(env) =>
                        val w = env.params
                        Present(Browser.DownloadEvent.WillBegin(w.guid, w.url, w.suggestedFilename))
                    case _ => Absent
            case "Page.downloadProgress" =>
                Json.decode[CdpEventParams[PageDownload.DownloadProgressWire]](ev.paramsJson) match
                    case Result.Success(env) =>
                        val w = env.params
                        Present(Browser.DownloadEvent.Progress(w.guid, w.totalBytes, w.receivedBytes, w.state))
                    case _ => Absent
            case _ => Absent
    end parseDownloadEvent

    // --- Console recording ---

    /** Bounded capacity for the per-tab console-event channel exposed via [[onConsole]] and [[recordConsole]]. Sized to ride out a short
      * consumer stall without dropping events; the channel drops the oldest event on overflow.
      */
    private val consoleChannelCapacity: Int = 256

    /** Subscribes `f` to every console message Chrome emits during `action`, mirroring [[onDownload]] in shape.
      *
      * Two CDP sources feed `f`: `Runtime.consoleAPICalled` (every `console.*` call) and `Runtime.exceptionThrown` (uncaught page errors,
      * surfaced as a `ConsoleLevel.Error` message). A `consoleAPICalled` whose CDP `type` is one of the 8 structural variants aborts inside
      * [[decodeConsoleApiCalled]]; that abort is recovered to a dropped event here so it never reaches the reader fiber. This recorder does
      * NOT settle: it records the page exactly as it logs.
      *
      * Implementation: events flow from the CDP reader through a per-session dispatcher onto a bounded `Channel[ConsoleMessage]`; a forked
      * drainer fiber takes events off the channel and applies `f`, isolating `f`'s effect row `S` from the dispatcher's `Sync`-only value
      * type. The channel and drainer fiber are bound to an internal `Scope.run`, so teardown (dispatcher restore + channel close) fires when
      * `action` completes (success, failure, OR interruption); the caller's effect row does NOT carry `Scope`. The handler is registered
      * BEFORE `action` runs, so console output inside `action` is not missed.
      */
    def onConsole[A, S](f: Browser.ConsoleMessage => Unit < (Browser & S))(
        action: A < (Browser & Async & Abort[BrowserReadException] & S)
    )(using
        Frame,
        Isolate[S, Sync, S]
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            val client = tab.client
            val sidKey = tab.sessionId.value
            Clock.now.map { t0 =>
                val t0Ms = t0.toJava.toEpochMilli
                // Bounded unscoped channel; closed explicitly via `Scope.ensure` inside the inner `Scope.run` so the effect row stays free
                // of `Scope` while teardown is still bound to the body's lifetime.
                Channel.initUnscoped[Browser.ConsoleMessage](consoleChannelCapacity).map { channel =>
                    val handler: CdpEvent.Generic => Unit < Sync = ev =>
                        consoleEventToMessage(ev, t0Ms).map {
                            case Present(msg) =>
                                // Best-effort offer: a full channel (drainer slow) or a closed channel (scope tearing down) drops the
                                // event rather than blocking the CDP reader fiber. Abort[Closed] is swallowed.
                                Abort.run[Closed](channel.offer(msg)).unit
                            case Absent => Kyo.unit
                        }
                    client.consoleEventDispatchers.getAndUpdate(_.update(sidKey, handler)).map { previousMap =>
                        val restoreDispatcher = client.consoleEventDispatchers.getAndUpdate { m =>
                            previousMap.get(sidKey) match
                                case Present(prev) => m.update(sidKey, prev)
                                case Absent        => m.remove(sidKey)
                        }.unit
                        Scope.run {
                            Scope.ensure(restoreDispatcher).andThen(Scope.ensure(channel.close.unit)).andThen {
                                Fiber.init {
                                    Abort.run[Closed](channel.stream().foreach(msg => Env.run(tab)(f(msg)))).unit
                                }.andThen(action)
                            }
                        }
                    }
                }
            }
        }
    end onConsole

    /** Captures every [[ConsoleMessage]] Chrome emits during `body` into an in-memory [[Chunk]]. Returns a pair `(messages, result)` where
      * `messages` is the arrival-ordered chunk of every console entry the page produced and `result` is the value `body` produced.
      *
      * Use this instead of [[onConsole]] when the test only needs to inspect the messages after the body completes; no manual `AtomicRef` or
      * `Channel` bookkeeping is required. Implemented in terms of [[onConsole]] with a capture handler that appends each message to an
      * internal `AtomicRef[Chunk]`; the drainer fiber inside [[onConsole]] is single-fiber per session, so arrival order is preserved.
      */
    def recordConsole[A, S](body: A < (Browser & Async & Abort[BrowserReadException] & S))(using
        Frame,
        Isolate[S, Sync, S]
    ): (Chunk[Browser.ConsoleMessage], A) < (Browser & Async & Abort[BrowserReadException] & S) =
        AtomicRef.init(Chunk.empty[Browser.ConsoleMessage]).map { collected =>
            val captureHandler: Browser.ConsoleMessage => Unit < Sync =
                msg => collected.updateAndGet(_.append(msg)).unit
            onConsole(captureHandler)(body).map { result =>
                collected.get.map(messages => (messages, result))
            }
        }
    end recordConsole

    /** Decodes a [[CdpEvent.Generic]] console event into a typed [[Browser.ConsoleMessage]] for the recorder handler. Reuses the wire-level
      * [[kyo.internal.CdpClient.parseConsoleEvent]] decoder: a `Left` is a `Runtime.consoleAPICalled` mapped through
      * [[decodeConsoleApiCalled]] (a structural-type abort recovers to `Absent` so the handler drops it), a `Right` is a
      * `Runtime.exceptionThrown` surfaced as a `ConsoleLevel.Error` message at its `url:line`. Returns `Absent` for any non-console event or
      * decode failure; never aborts.
      */
    private def consoleEventToMessage(ev: CdpEvent.Generic, t0Ms: Long)(using
        Frame
    ): Maybe[Browser.ConsoleMessage] < Sync =
        CdpClient.parseConsoleEvent(ev) match
            case Present(Left(consoleWire)) =>
                Abort.run(decodeConsoleApiCalled(consoleWire, t0Ms)).map {
                    case Result.Success(msg) => Present(msg)
                    case _                   => Absent
                }
            case Present(Right(exWire)) =>
                val d = exWire.exceptionDetails
                // CDP reports `text` as the bare "Uncaught" prefix and carries the real error message in
                // `exception.description`; join them so the message is meaningful.
                val text = d.exception.flatMap(_.description) match
                    case Present(desc) => d.text + " " + desc
                    case Absent        => d.text
                // Prefer the top-level `url:line`; fall back to the first stack frame when the throw carries no url.
                val topFrame = d.stackTrace.flatMap(st => Maybe.fromOption(st.callFrames.headOption))
                val location =
                    d.url.map(u => u + ":" + d.lineNumber.getOrElse(0))
                        .orElse(topFrame.flatMap(cf => cf.url.map(u => u + ":" + cf.lineNumber.getOrElse(0))))
                Present(Browser.ConsoleMessage(
                    Browser.ConsoleLevel.Error,
                    text,
                    location,
                    computeOffsetMs(exWire.timestamp, t0Ms)
                ))
            case Absent => Absent
    end consoleEventToMessage

    // --- Screencast ---

    /** Records a screencast of the page WHILE `body` runs. Drive an animation or transition inside `body` and get back the frames
      * Chrome rendered while it ran, paired with the body's result: events-first `(Chunk[ScreenshotFrame], A)`, the canonical `record*`
      * shape (twin [[recordDownloads]]). This method does NOT settle: it records the page exactly as it changes, so the caller is
      * responsible for driving the visual change to record.
      *
      * Each frame carries its `Image`, an `offsetMs` relative to the cast start (from the screencast metadata `timestamp` when present,
      * else a wall-clock fallback), and the page scroll offset at capture. Frames arrive in delivery order, so `offsetMs` is
      * non-decreasing.
      *
      * Two bounds protect against an unbounded recording, checked frame-count-first: when the recorded frame count exceeds `maxFrames`,
      * or the elapsed wall-clock time exceeds `maxDurationMs`, the cast is poisoned and the call aborts
      * [[BrowserCaptureLimitExceededException]] after `body` returns. The exception's two numbers always share one unit: the frame cap
      * reports `limit = maxFrames` against the frame count reached, and the duration cap reports `limit = maxDurationMs` against the
      * elapsed milliseconds reached. `Webp` has no screencast codec, so it maps to the `jpeg` codec (CDP screencast supports `jpeg`
      * and `png` only); the call still succeeds.
      *
      * Implementation: a per-session dispatcher is registered on `screencastEventDispatchers` BEFORE the cast starts. The dispatcher
      * decodes each `Page.screencastFrame`, issues the per-frame `Page.screencastFrameAck` from inside the handler (a detached fiber so
      * the CDP reader stays `Sync`-only and Chrome keeps delivering), appends the frame, and sets the poison flag on a cap. The dispatcher
      * restore and `Page.stopScreencast` are bound to an inner `Scope.run`, so teardown fires on success, failure, OR interruption; the
      * caller's effect row does NOT carry `Scope`. The body's effect row `S` is isolated from the dispatcher's `Sync`-only value type via
      * `Isolate[S, Sync, S]`, exactly as [[recordDownloads]].
      */
    def screenshotFrames[A, S](
        maxDurationMs: Long = 8000L,
        maxFrames: Int = 240,
        format: Browser.ScreenshotFormat = Browser.ScreenshotFormat.Jpeg,
        quality: Int = 80
    )(body: A < (Browser & Async & Abort[BrowserReadException] & S))(using
        Frame,
        Isolate[S, Sync, S]
    ): (Chunk[Browser.ScreenshotFrame], A) < (Browser & Async & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { tab =>
            val client  = tab.client
            val session = tab.session
            val sidKey  = tab.sessionId.value
            val wireFormat = format match
                case Browser.ScreenshotFormat.Png  => "png"
                case Browser.ScreenshotFormat.Jpeg => "jpeg"
                case Browser.ScreenshotFormat.Webp => "jpeg"
            Clock.now.map { t0 =>
                AtomicRef.init(Chunk.empty[Browser.ScreenshotFrame]).map { collected =>
                    // The poison cell, when set, carries the exact `(limit, reached)` pair for the abort, computed at the
                    // moment the cap is hit so both numbers share one unit: frame counts for the frame cap, milliseconds for
                    // the duration cap. The first cap to trip wins (the dispatcher only sets the cell when it is still Absent).
                    AtomicRef.init(Maybe.empty[(Int, Int)]).map { poisoned =>
                        // The dispatcher decodes each frame, acks it from a detached fiber so the reader fiber stays < Sync (the ack
                        // carries Async; Chrome keeps delivering once it sees the ack), appends to `collected`, then checks the caps
                        // frame-count-first: `cur.size > maxFrames` poisons on the frame bound (limit = maxFrames, reached = frame
                        // count); otherwise the elapsed-time check poisons on the duration bound (limit = maxDurationMs, reached =
                        // elapsed ms), so the two reported numbers always share the same unit.
                        val handler: CdpEvent.Generic => Unit < Sync = ev =>
                            parseScreencastFrame(ev, t0).map {
                                case Present((frame, sessionId)) =>
                                    Fiber.initUnscoped(using Isolate.derive[Any, Sync, Any])(
                                        Abort.run[BrowserReadException](
                                            CdpBackend.screencastFrameAck(session, ScreencastFrameAckParams(sessionId))
                                        ).unit
                                    ).andThen(collected.updateAndGet(_.append(frame))).map { cur =>
                                        def poison(cap: (Int, Int)): Unit < Sync =
                                            poisoned.updateAndGet(prev => if prev.isDefined then prev else Present(cap)).unit
                                        if cur.size > maxFrames then poison((maxFrames, cur.size))
                                        else
                                            Clock.now.map { now =>
                                                val elapsedMs = now.toJava.toEpochMilli - t0.toJava.toEpochMilli
                                                if elapsedMs > maxDurationMs then poison((maxDurationMs.toInt, elapsedMs.toInt))
                                                else Kyo.unit
                                            }
                                        end if
                                    }
                                case Absent => Kyo.unit
                            }
                        client.screencastEventDispatchers.getAndUpdate(_.update(sidKey, handler)).map { previousMap =>
                            val restore = client.screencastEventDispatchers.getAndUpdate { m =>
                                previousMap.get(sidKey) match
                                    case Present(prev) => m.update(sidKey, prev)
                                    case Absent        => m.remove(sidKey)
                            }.unit
                            // Best-effort stop on teardown: await the send so the cast is actually ended on normal completion,
                            // but swallow any read failure so a connection already tearing down (interruption) does not re-raise
                            // into the finalizer. The send's own request timeout bounds a silent Chrome, so this never hangs.
                            val stop = Abort.run[BrowserReadException](CdpBackend.stopScreencast(session)).unit
                            Scope.run {
                                // Scope finalizers run sequentially in reverse registration order, so the two cleanups
                                // must not be registered independently: that would always run `stop` before `restore`,
                                // gating the local dispatcher removal behind a network round-trip bounded only by the
                                // request timeout. On normal completion stop Chrome first so no in-flight frame is
                                // orphaned to the bounded event channel, then drop the dispatcher. On failure or
                                // interruption drop the dispatcher first so local cleanup is prompt and independent of
                                // the best-effort stop (the connection is tearing down anyway).
                                Scope.ensure {
                                    case Absent     => stop.andThen(restore)
                                    case Present(_) => restore.andThen(stop)
                                }.andThen {
                                    CdpBackend.startScreencast(
                                        session,
                                        StartScreencastParams(Present(wireFormat), screenshotQuality(format, quality))
                                    ).andThen {
                                        body.map { result =>
                                            poisoned.get.map { cap =>
                                                collected.get.map { frames =>
                                                    cap match
                                                        case Present((limit, reached)) =>
                                                            Abort.fail(BrowserCaptureLimitExceededException(
                                                                "screenshotFrames",
                                                                limit,
                                                                reached
                                                            ))
                                                        case Absent => (frames, result)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end screenshotFrames

    /** Decodes a `Page.screencastFrame` event into a `(ScreenshotFrame, sessionId)` pair. Returns `Absent` on a wrong-method event or any
      * decode failure (never aborts). `offsetMs` is `round(timestamp * 1000) - t0` when the metadata carries a `timestamp`, else the
      * wall-clock fallback `now - t0`. Distinct from the routing-layer `CdpClient.parseScreencastFrame`, which decodes only the wire
      * record; this variant materialises the `Image` and the public `ScreenshotFrame`.
      */
    private def parseScreencastFrame(ev: CdpEvent.Generic, t0: Instant)(using
        Frame
    )
        : Maybe[(Browser.ScreenshotFrame, Int)] < Sync =
        ev.method match
            case "Page.screencastFrame" =>
                Json.decode[CdpEventParams[ScreencastFrameWire]](ev.paramsJson) match
                    case Result.Success(env) =>
                        val w = env.params
                        Abort.run(CdpBase64Decode.decodeScreenshotImage("Page.screencastFrame", w.data)).map {
                            case Result.Success(img) =>
                                val t0Ms = t0.toJava.toEpochMilli
                                Clock.now.map { now =>
                                    val offsetMs = w.metadata.timestamp match
                                        case Present(ts) => math.round(ts * 1000) - t0Ms
                                        case Absent      => now.toJava.toEpochMilli - t0Ms
                                    Present((
                                        Browser.ScreenshotFrame(
                                            img,
                                            offsetMs,
                                            Browser.ScrollPosition(
                                                math.round(w.metadata.scrollOffsetX).toInt,
                                                math.round(w.metadata.scrollOffsetY).toInt
                                            )
                                        ),
                                        w.sessionId
                                    ))
                                }
                            case _ => (Absent: Maybe[(Browser.ScreenshotFrame, Int)])
                        }
                    case _ => Absent
            case _ => Absent
    end parseScreencastFrame

    // --- Cookies ---

    /** Returns all cookies for the current page.
      *
      * Returns the full cookie jar for the active page; filter or project on the returned `Chunk[Cookie]` directly. The idiomatic
      * single-name lookup is:
      * ```scala
      * Browser.cookies.map(_.filter(_.name == "session"))
      * ```
      *
      * Callers that want to filter by URL can call `Browser.cookies.map(_.filter(predicate))`; the in-memory filter is allocation-cheap.
      */
    def cookies(using Frame): Chunk[Cookie] < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.getCookies(tab.session).map { r =>
                Chunk.from(r.cookies.map(CookieWire.toCookie))
            }
        }

    /** Returns the cookies that the given URL would send in a request; Chrome filters by Domain and Path against the supplied URL. Useful
      * for asserting that a specific endpoint sees a specific cookie without having to navigate to it.
      *
      * Strict subset of [[cookies]]: every cookie returned here is also in `cookies`, but the converse is not.
      */
    def cookies(forUrl: String)(using Frame): Chunk[Cookie] < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.getCookies(tab.session, NetworkGetCookiesParams(urls = Present(Seq(forUrl)))).map { r =>
                Chunk.from(r.cookies.map(CookieWire.toCookie))
            }
        }

    /** Sets a cookie with the given name, value, domain, and (optional) path. */
    def setCookie(name: String, value: String, domain: String, path: String = "/")(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.setCookie(
                tab.session,
                NetworkSetCookieParams(
                    name = name,
                    value = value,
                    domain = Present(domain),
                    path = Present(path)
                )
            )
        }

    /** Sets a cookie from a fully-populated [[Cookie]] value.
      *
      * Every populated [[Maybe]] field on the input is forwarded to the CDP `Network.setCookie` request unchanged. `Absent` is passed
      * through verbatim so cookies round-trip cleanly through [[cookies]]: an attribute that was absent on read is left absent on write
      * rather than being defaulted on Chrome's behalf. The CDP `url` parameter is left unset; supply `cookie.domain` for predictable
      * cookie-jar targeting. The `expires` [[Instant]] is flattened to the seconds-since-epoch `Double` shape CDP expects.
      */
    def setCookie(cookie: Cookie)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            val wire = CookieWire.fromCookie(cookie)
            CdpBackend.setCookie(
                tab.session,
                NetworkSetCookieParams(
                    name = wire.name,
                    value = wire.value,
                    domain = wire.domain,
                    path = wire.path,
                    expires = wire.expires,
                    httpOnly = wire.httpOnly,
                    secure = wire.secure,
                    sameSite = wire.sameSite
                )
            )
        }

    /** Deletes the named cookie from the cookie jar of the current page's URL. */
    def deleteCookie(name: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            // Use current page URL so CDP knows which cookie jar to target
            CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams("window.location.href")
            ).map(CdpEvalDecoder.parseAndExtractEvalValue).map { currentUrl =>
                CdpBackend.deleteCookies(
                    tab.session,
                    NetworkDeleteCookiesParams(name, url = Present(currentUrl))
                )
            }
        }

    /** Deletes the named cookie from the given domain's cookie jar. */
    def deleteCookie(name: String, domain: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Env.use[BrowserTab] { tab =>
            CdpBackend.deleteCookies(
                tab.session,
                NetworkDeleteCookiesParams(name, domain = Present(domain))
            )
        }

    /** Attempts to dismiss a cookie consent banner by clicking common accept buttons.
      *
      * Heuristic: matches CSS selector patterns common to English-language cookie banners. Does not cover non-English pages, shadow DOM
      * banners, or sites with non-standard markup; those callers should drive the dismiss through `Browser.eval` or [[Browser.click]]
      * against a site-specific selector.
      *
      * Returns `Present(selector)` carrying the matched CSS selector if a banner was found, clicked, and subsequently disappeared from the
      * DOM (or became invisible). Returns `Absent` if no matching banner was found. Aborts with `BrowserAssertionException` if a banner was
      * matched and clicked but failed to disappear within the configured load-schedule timeout.
      *
      * After clicking, waits for the matched banner element to disappear from the DOM (or become invisible) before returning.
      */
    def tryAcceptCookies(using Frame): Maybe[Selector] < (Browser & Abort[BrowserReadException]) =
        CookieBanner.tryAcceptCookiesWithSchedule(Absent)

    /** Like [[Browser.tryAcceptCookies]] but with a per-call retry `schedule` override instead of the configured default.
      *
      * @param schedule
      *   the retry schedule used while waiting for the banner to disappear; `Absent` falls back to `SessionConfig.loadSchedule`.
      */
    def tryAcceptCookies(schedule: Maybe[Schedule])(using Frame): Maybe[Selector] < (Browser & Abort[BrowserReadException]) =
        CookieBanner.tryAcceptCookiesWithSchedule(schedule)

    // --- Tab management ---

    /** Runs a trigger action, waits for a new tab to open, then runs the handler in that tab's context.
      *
      * Useful for capturing popups triggered by links with `target="_blank"` or `window.open()` calls. The trigger action is run first,
      * then the method polls for a new target to appear (comparing against the list of targets before the trigger). Once a new target is
      * found, the handler runs in that tab's context. The popup tab is closed when the handler completes (success, failure, or
      * interruption).
      *
      * Effect set: returns `Browser & Abort[BrowserReadException] & S`. The parent tab is required to observe the new target, so `Browser`
      * is in the effect set. The popup tab's cleanup is absorbed by an internal `Scope.run`, so `Scope` does NOT appear in the caller's
      * effect row. (Contrast with [[withConfig]], which is pure `Local.let` and does NOT add `Browser`.)
      */
    def withPopup[A, S](schedule: Maybe[Schedule] = Absent)(trigger: Unit < (Browser & S))(handler: A < (Browser & S))(using
        Frame
    ): A < (Browser & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { parent =>
            Scope.run {
                CdpBackend.getTargets(parent.client).map { before =>
                    val beforeIds = before.targetInfos.map(_.targetId).toSet
                    Env.run(parent)(trigger).andThen {
                        // Poll for new target
                        configLocal.use { cfg =>
                            val effectiveSchedule = schedule.getOrElse(cfg.retrySchedule)
                            Retry[BrowserReadException](effectiveSchedule) {
                                CdpBackend.getTargets(parent.client).map { after =>
                                    val newTargets = after.targetInfos.filter(t =>
                                        !beforeIds.contains(t.targetId) && t.`type` == "page"
                                    )
                                    if newTargets.isEmpty then
                                        Abort.fail(
                                            BrowserProtocolErrorException("withPopup", "no new tab detected")
                                        )
                                    else newTargets.head
                                    end if
                                }
                            }.map { newTarget =>
                                Scope.ensure(
                                    CdpBackend.closeTarget(parent.client, CloseTargetParams(newTarget.targetId))
                                ).andThen {
                                    // Attach to the new target
                                    CdpBackend.attachToTarget(parent.client, AttachParams(newTarget.targetId, flatten = true)).map {
                                        attached =>
                                            BrowserTabSetup.mkBrowserTab(
                                                TargetId(newTarget.targetId),
                                                SessionId(attached.sessionId),
                                                parent.client,
                                                parent.browserContextId
                                            ).map { tab =>
                                                BrowserTabSetup.installFrameContextTracker(tab).andThen {
                                                    BrowserTabSetup.enableDomains(tab.session).andThen {
                                                        BrowserTabSetup.seedRootFrameId(tab).andThen {
                                                            BrowserTabSetup.installConsoleCapture(tab).andThen {
                                                                BrowserTabSetup.installResponseTracker(tab).andThen {
                                                                    NavigationWatcher.waitForLoad(
                                                                        tab,
                                                                        Browser.SessionConfig.default.loadSchedule
                                                                    ).andThen {
                                                                        // Reset activeIFrameLocal: popup tab has its own session;
                                                                        // outer iframe handle would route at a stale executionContextId.
                                                                        activeIFrameLocal.let(
                                                                            Maybe.empty[IFrameHandle]
                                                                        )(Env.run(tab)(handler))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end withPopup

    /** Runs the given computation in a fresh tab (about:blank) inside the **same browser context** as the parent tab.
      *
      * "Same context" means cookies, localStorage, sessionStorage, and service workers are shared with the parent; the fresh tab just
      * starts on a blank page. For a tab that is also isolated from the parent's cookies/storage, use `Browser.isolate.fresh` (which
      * creates a fresh browser context per fork).
      *
      * Invariant: child tabs created via `withNewTab` must inherit the parent's `browserContextId`, otherwise Chrome lands them in the
      * default context and parent-context cookies/storage are not visible to the child.
      *
      * Effect set: returns `Browser & Abort[BrowserReadException] & S`. The parent tab is the source of the inherited `browserContextId`,
      * so `Browser` is required. The new tab's cleanup is absorbed by an internal `Scope.run`, so `Scope` does NOT appear in the caller's
      * effect row. (Contrast with [[withConfig]], which is pure `Local.let` over the fiber-scoped config and does NOT add `Browser`.)
      */
    def withNewTab[A, S](v: A < (Browser & S))(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        Env.use[BrowserTab] { parent =>
            val parentCtx = parent.browserContextId
            Scope.run {
                for
                    created <- CdpBackend.createTarget(parent.client, CreateTargetParams("about:blank", parentCtx))
                    _ <- Scope.ensure(
                        CdpBackend.closeTarget(parent.client, CloseTargetParams(created.targetId))
                    )
                    attached <- CdpBackend.attachToTarget(parent.client, AttachParams(created.targetId, flatten = true))
                    tab <- BrowserTabSetup.mkBrowserTab(
                        TargetId(created.targetId),
                        SessionId(attached.sessionId),
                        parent.client,
                        parent.browserContextId
                    )
                    _ <- BrowserTabSetup.installFrameContextTracker(tab)
                    _ <- BrowserTabSetup.enableDomains(tab.session)
                    _ <- BrowserTabSetup.seedRootFrameId(tab)
                    _ <- BrowserTabSetup.installConsoleCapture(tab)
                    _ <- BrowserTabSetup.installResponseTracker(tab)
                    // Reset activeIFrameLocal: the outer iframe handle (if any) is bound to the parent tab's session and would route
                    // CDP calls at a stale executionContextId inside the new tab. The new tab starts fresh; the user can acquire a
                    // handle inside the block via Browser.iframe(...) and withIFrame against it.
                    a <- activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))
                yield a
                end for
            }
        }

    /** Runs the given computation in an isolated browser context whose initial state is snapshotted from the parent tab (URL, localStorage,
      * sessionStorage, cookies, form fields, scroll position, and focus).
      *
      * Each `withFork` call creates a **new, isolated browser context**; mutations inside the block (new cookies, storage writes,
      * navigations) do not propagate back to the parent. The "fork" naming emphasises isolation: this is not a shared-context clone, it is
      * a private copy.
      *
      * For a tab that starts blank (no snapshot) in the same browser context as the parent (sharing cookies/storage), use
      * `Browser.withNewTab`. For parallel isolation without a snapshot, use `Browser.isolate.fresh`.
      *
      * Note: JavaScript in-memory state and dynamically added DOM elements are not captured in the snapshot. The fork starts from a fresh
      * page load of the parent's URL with storage/cookies restored.
      *
      * Note: `withFork` is **total isolation**. The parent tab is NOT reachable from inside the body; there is no `inParent` escape hatch by
      * design. Callers who need to read parent state must capture it (e.g. into a `val`) BEFORE entering `withFork`. Re-binding to the parent
      * requires exiting the fork.
      *
      * Effect set: returns `Browser & Abort[BrowserReadException] & S`. The parent tab is snapshotted, so an active `Browser` is required.
      * The forked tab + browser-context cleanup is absorbed by an internal `Scope.run`, so `Scope` does NOT appear in the caller's effect
      * row. (Contrast with [[withConfig]], which is pure `Local.let` and does NOT add `Browser`.)
      */
    def withFork[A, S](v: A < (Browser & S))(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        // The body emits `Abort[BrowserReadException]` directly via `captureSnapshot`, `createChildTab`, and
        // `restoreSnapshot`. Nested `Browser.isolate.{fresh,clone}.use` calls also surface their typed Abort through
        // the same channel because their `Isolate.Keep` includes `Abort[BrowserReadException]`.
        Env.use[BrowserTab] { parent =>
            Scope.run {
                for
                    snapshot <- BrowserSnapshot.captureSnapshot(parent)
                    tab      <- BrowserTabSetup.createChildTab(parent)
                    _        <- BrowserSnapshot.restoreSnapshot(tab, snapshot)
                    // Reset activeIFrameLocal; see withNewTab for the rationale.
                    a <- activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))
                yield a
            }
        }

    // --- Isolation ---

    object isolate:

        /** Creates an isolate that gives each fork its own fresh tab (about:blank).
          *
          * Tab creation happens in `isolate` (inside `Scope.run`). The `capture` phase just reads the parent tab. `Env.run` strips the
          * `Env[BrowserTab]` component of the opaque Browser type. The `Isolate.Keep` channel includes `Abort[BrowserReadException]` so a
          * typed Abort raised by `createChildTab` (or by the user computation) flows through the Isolate ABI directly, without
          * throw-tunneling.
          */
        def fresh(using Frame): Isolate[Browser, Async & Abort[BrowserReadException], Any] =
            new Isolate[Browser, Async & Abort[BrowserReadException], Any]:
                type State        = BrowserTab
                type Transform[A] = A
                def capture[A, S2](f: BrowserTab => A < S2)(using Frame) =
                    Env.use[BrowserTab](f)
                def isolate[A, S2](state: BrowserTab, v: A < (S2 & Browser))(using Frame) =
                    Scope.run {
                        BrowserTabSetup.createChildTab(state).map { tab =>
                            // Reset activeIFrameLocal; handle is session-pinned to parent tab.
                            activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))
                        }
                    }
                def restore[A, S2](v: A < S2)(using Frame) = v

        /** Creates an isolate that gives each fork a cloned tab (same URL + storage).
          *
          * Snapshot is captured in `capture`. Tab creation and restoration happen in `isolate` (inside `Scope.run`). `Env.run` strips the
          * `Env[BrowserTab]` component of the opaque Browser type. The `Isolate.Keep` channel includes `Abort[BrowserReadException]` so
          * typed Aborts from snapshot capture, child tab creation, snapshot restore, or the user computation flow through the Isolate ABI
          * directly, without throw-tunneling.
          */
        def clone(using Frame): Isolate[Browser, Async & Abort[BrowserReadException], Any] =
            new Isolate[Browser, Async & Abort[BrowserReadException], Any]:
                type State        = (BrowserTab, BrowserSnapshot.BrowserSnapshot)
                type Transform[A] = A
                def capture[A, S2](f: ((BrowserTab, BrowserSnapshot.BrowserSnapshot)) => A < S2)(using Frame) =
                    Env.use[BrowserTab] { tab =>
                        BrowserSnapshot.captureSnapshot(tab).map(snapshot => f((tab, snapshot)))
                    }
                def isolate[A, S2](state: (BrowserTab, BrowserSnapshot.BrowserSnapshot), v: A < (S2 & Browser))(using Frame) =
                    val (parent, snapshot) = state
                    Scope.run {
                        BrowserTabSetup.createChildTab(parent).map { tab =>
                            BrowserSnapshot.restoreSnapshot(tab, snapshot).andThen {
                                // Reset activeIFrameLocal; handle is session-pinned to parent tab.
                                activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))
                            }
                        }
                    }
                end isolate
                def restore[A, S2](v: A < S2)(using Frame) = v

    end isolate

    // --- Utilities ---

    /** Builds an inline `data:text/html` URL containing the given HTML. Useful for demos, smoke tests, and small fixtures that don't want a
      * separate HTTP server. The HTML is percent-encoded per RFC 3986 (spaces become `%20`, not `+`), so the resulting URL is safe to pass
      * to [[Browser.goto]] without manual escaping.
      *
      * ```scala
      * val url = Browser.dataUrl("<h1>hello</h1>")
      * Browser.goto(url).andThen(Browser.title)
      * ```
      */
    def dataUrl(html: String): String =
        s"data:text/html;charset=utf-8,${PercentEncode(html)}"

    // --- Nested types ---

    /** A single entry in a tab's navigation history as returned by [[Browser.history]].
      *
      * Mirrors the CDP `Page.NavigationEntry` wire shape verbatim.
      */
    final case class NavigationEntry(id: Int, url: String, title: String) derives CanEqual, Schema

    /** The full navigation history of a tab as returned by [[Browser.history]].
      *
      * `currentIndex` is the zero-based index into `entries` that identifies the currently-visible page.
      */
    final case class NavigationHistory(currentIndex: Int, entries: Chunk[NavigationEntry]) derives CanEqual, Schema:
        /** True when [[Browser.back]] would advance to a previous entry. */
        def canGoBack: Boolean = currentIndex > 0

        /** True when [[Browser.forward]] would advance to a later entry. */
        def canGoForward: Boolean = currentIndex < entries.size - 1

        /** The currently-visible entry. */
        def current: NavigationEntry = entries(currentIndex)
    end NavigationHistory

    /** Geometry artifact returned by [[boundingRect]]. Fields are CSS pixels relative to the page document. `right`, `bottom`, and `area`
      * are derived accessors (pure, total, no `Frame`).
      */
    final case class Bounds(x: Double, y: Double, width: Double, height: Double) derives Schema, CanEqual:
        def right: Double  = x + width
        def bottom: Double = y + height
        def area: Double   = width * height
    end Bounds

    /** Page scroll offset (`window.scrollX` / `scrollY`, rounded to integers). Returned by [[scrollPosition]] and carried by
      * `ScreenshotFrame`.
      */
    final case class ScrollPosition(x: Int, y: Int) derives Schema, CanEqual

    /** One frame recorded by [[screenshotFrames]]. `image` is the captured frame; `offsetMs` is `round(timestamp * 1000) - t0` from the
      * screencast metadata (with a wall-clock fallback), relative to the cast start; `scrollOffset` is the page scroll at capture. Carries
      * an `Image`, so derives only `CanEqual`.
      */
    final case class ScreenshotFrame(image: Image, offsetMs: Long, scrollOffset: Browser.ScrollPosition) derives CanEqual

    /** Emulated `prefers-color-scheme` value for [[withEmulation]].
      *
      * [[NoPreference]] clears the override: the W3C dropped `no-preference` as a settable value for this feature, so it maps to the
      * empty-string clear sent to `Emulation.setEmulatedMedia`.
      */
    enum ColorScheme derives CanEqual:
        case Light, Dark, NoPreference

    object ColorScheme:
        /** CDP wire form for the `prefers-color-scheme` feature: `"light"` / `"dark"` / `""` (cleared). */
        extension (c: ColorScheme)
            private[kyo] def wire: String = c match
                case Light        => "light"
                case Dark         => "dark"
                case NoPreference => ""
        end extension
    end ColorScheme

    /** Emulated media type for [[withEmulation]]. */
    enum MediaType derives CanEqual:
        case Screen, Print

    object MediaType:
        /** CDP wire form for the emulated media type: `"screen"` / `"print"`. */
        extension (m: MediaType)
            private[kyo] def wire: String = m match
                case Screen => "screen"
                case Print  => "print"
        end extension
    end MediaType

    /** Input to [[withHighlights]]: a dashed box drawn over the element matched by `selector`, with an optional `label` and `color`.
      *
      * `label` and `color` default to `Absent`; `color` is a CSS color string (defaulting to a red when absent). Carries a
      * [[Selector]] (an opaque type), so it derives only `CanEqual`.
      */
    final case class Annotation(
        selector: Selector,
        label: Maybe[String] = Absent,
        color: Maybe[String] = Absent
    ) derives CanEqual

    /** DISCOVER artifact: a resolved element snapshot. Optional fields (`id` / `text` / `role`) are `Maybe`, never null; `classes` is a
      * `Chunk`; `selector` is the generated stable unique CSS path. The companion holds the pure `leaves` filter. `area` delegates to
      * `bounds.area`.
      */
    final case class ElementInfo(
        selector: String,
        tag: String,
        id: Maybe[String],
        classes: Chunk[String],
        text: Maybe[String],
        bounds: Browser.Bounds,
        visible: Boolean,
        inViewport: Boolean,
        topmost: Boolean,
        interactive: Boolean,
        role: Maybe[String]
    ) derives Schema, CanEqual:
        def area: Double = bounds.area
    end ElementInfo

    object ElementInfo:
        /** Pure leaf filter: keeps elements that are NOT an ancestor of any other element in `elems`. Ancestry is determined from the unique
          * `selector` path: A is an ancestor of B when B's `selector` starts with A's `selector + " > "`. Total function; no `Frame`, no
          * effect row.
          */
        def leaves(elems: Chunk[Browser.ElementInfo]): Chunk[Browser.ElementInfo] =
            elems.filter(a => !elems.exists(b => b.selector != a.selector && b.selector.startsWith(a.selector + " > ")))
    end ElementInfo

    /** Accessibility-tree node: what a screen reader sees. Returned by [[accessibilityNodes]] and probed by [[role]] / [[accessibleName]] /
      * [[assertRole]] / [[assertAccessibleName]].
      *
      * Field shape mirrors the CDP `AXNode` wire surface. The `properties` map carries common AX state (`disabled`, `checked`, `expanded`,
      * …) plus `"backendDOMNodeId"` keyed to the underlying DOM node's CDP backend id (stringified) when the node maps to a DOM element.
      * The `"backendDOMNodeId"` entry is the join key used by [[role]] / [[accessibleName]] to align a [[Selector]]-resolved [[NodeRef]]
      * with its AX-tree entry.
      */
    final case class AxNode(
        nodeId: String,
        role: String,
        name: String,
        ignored: Boolean,
        properties: Dict[String, String]
    ) derives Schema, CanEqual

    /** A single console entry captured during a [[Browser.recordConsole]] body or drained by [[Browser.consoleLogs]].
      *
      * `level` distinguishes the five console severities without prefix smuggling in the text; `text` is the joined argument string;
      * `location` is the originating `url:line` when the source carried a stack frame, else `Absent`; `offsetMs` is the milliseconds from
      * the recording start (or buffer baseline for the drain path) to the moment the entry was emitted.
      */
    final case class ConsoleMessage(
        level: ConsoleLevel,
        text: String,
        location: Maybe[String],
        offsetMs: Long
    ) derives Schema, CanEqual

    /** Level enum for [[ConsoleMessage]]. The five severities mirror the `console.*` overrides installed by
      * [[kyo.internal.BrowserTabSetup]] (`debug`, `info`, `log`, `warn`, `error`) and the CDP `Runtime.consoleAPICalled` types folded onto
      * them.
      */
    enum ConsoleLevel derives Schema, CanEqual:
        case Log, Info, Warn, Error, Debug

    /** A single JavaScript dialog event captured by [[Browser.withDialogs.recorded]].
      *
      * `kind` distinguishes alert / confirm / prompt / beforeunload; `message` is the page-side dialog text; `response` records what the
      * active auto-handler (`withDialogs.accept` / `dismiss` / `prompt`) returned, or `Absent` when no handler is active (auto-dismissed).
      */
    final case class DialogEvent(
        kind: DialogType,
        message: String,
        response: Maybe[String]
    ) derives Schema, CanEqual

    /** Kind discriminator for [[DialogEvent]]. */
    enum DialogType derives Schema, CanEqual:
        case Alert, Confirm, Prompt, BeforeUnload

    /** Settle mode for a navigation wait. */
    enum Settle derives CanEqual:
        /** Resolve when `document.readyState` reaches at least `'interactive'` (DOMContentLoaded has fired). */
        case DomContentLoaded

        /** Resolve when `document.readyState` is `'complete'` (the `load` event has fired). */
        case Load

        /** Resolve when the `load` event has fired AND no fetch/XHR has been in-flight for the network idle window.
          *
          * Default mode for `Browser.goto`.
          */
        case NetworkIdle
    end Settle

    /** How Chrome should handle downloads triggered by the page.
      *
      *   - [[Allow]]: Chrome saves the file (to the path supplied to [[allowDownloads]] / [[setDownloadBehavior]] if given) and emits
      *     `Page.downloadWillBegin` / `Page.downloadProgress` events.
      *   - [[Deny]]: Chrome drops the download; no file is written and no events are emitted.
      *   - [[Default]]: restores Chrome's normal behaviour (no explicit policy; no events).
      */
    enum DownloadBehavior derives CanEqual:
        case Allow, Deny, Default

    object DownloadBehavior:
        /** CDP wire form: `"allow"` / `"deny"` / `"default"`. */
        extension (b: DownloadBehavior)
            private[kyo] def wire: String = b match
                case Allow   => "allow"
                case Deny    => "deny"
                case Default => "default"

            /** Maps the public [[DownloadBehavior]] to the internal CDP-wire enum used by `kyo.internal.cdp.PageDownload`. */
            private[kyo] def toInternal: PageDownload.Behavior = b match
                case Allow   => PageDownload.Behavior.Allow
                case Deny    => PageDownload.Behavior.Deny
                case Default => PageDownload.Behavior.Default
        end extension
    end DownloadBehavior

    /** A download lifecycle event emitted while an [[onDownload]] subscription is active.
      *
      *   - [[WillBegin]]: fires once when Chrome resolves the download's URL and filename.
      *   - [[Progress]]: fires repeatedly until the download finishes, with `state` advancing through `"inProgress"` → `"completed"` (or
      *     `"canceled"`).
      *
      * Both events carry the same `guid` for a given download, so handlers can correlate them.
      */
    enum DownloadEvent derives CanEqual:
        case WillBegin(guid: String, url: String, suggestedFilename: String)
        case Progress(guid: String, totalBytes: Long, receivedBytes: Long, state: String)

    /** Image encoding for [[Browser.screenshot]] and [[Browser.screenshotElement]]. PNG is lossless and ignores the `quality` argument;
      * JPEG and WEBP are lossy and honor it.
      */
    enum ScreenshotFormat derives CanEqual:
        case Png, Jpeg, Webp

    object ScreenshotFormat:
        /** CDP wire form: `"png"` / `"jpeg"` / `"webp"`. */
        extension (fmt: ScreenshotFormat)
            private[kyo] def wire: String = fmt match
                case Png  => "png"
                case Jpeg => "jpeg"
                case Webp => "webp"
        end extension

        /** Serialises as the CDP wire string. Used by [[kyo.internal.ScreenshotParams]]. */
        given Schema[ScreenshotFormat] = Schema.stringSchema.transform[ScreenshotFormat] {
            case "png"  => Png
            case "jpeg" => Jpeg
            case "webp" => Webp
            case other  => throw new IllegalArgumentException(s"Unknown CDP screenshot format wire value: $other")
        }(_.wire)
    end ScreenshotFormat

    object LaunchConfig:
        /** Canonical default [[LaunchConfig]] used when no custom launch config is installed. */
        val default: LaunchConfig = LaunchConfig(
            executable = "chromium",
            headless = true,
            extraArgs = Chunk.empty,
            launchTimeout = 90.seconds,
            requestTimeout = 60.seconds,
            closeGrace = 30.seconds,
            tmpDirRemovalSchedule = Schedule.fixed(200.millis).maxDuration(30.seconds),
            devToolsActivePortPollInterval = 50.millis,
            chromeDownloaderConfig = ChromeDownloaderConfig.default
        )

        /** Returns [[LaunchConfig.default]] with `executable` set to `chromium` (or the provided path). */
        def chromium(executable: String = "chromium"): LaunchConfig = default.copy(executable = executable)

        /** Returns [[LaunchConfig.default]] with `executable` set to `google-chrome` (or the provided path). */
        def chrome(executable: String = "google-chrome"): LaunchConfig = default.copy(executable = executable)

        /** Nested launch-time configuration for the on-demand `chrome-headless-shell` downloader used by
          * [[Browser.chromeForTestingLaunchConfig]] and the zero-arg [[Browser.run]] overload. Downloads target the Chrome-for-Testing
          * archive's `chrome-headless-shell-{platform}.zip` artifact.
          *
          * @param metadataUrl
          *   URL of the Chrome-for-Testing last-known-good-versions metadata endpoint; used to resolve "latest" when no explicit version is
          *   passed
          * @param fallbackVersion
          *   offline backstop version used when the metadata endpoint is unreachable
          * @param downloadTimeout
          *   per-download time budget; sized for the ~120 MB `chrome-headless-shell` zip on slow links and CI environments
          */
        final case class ChromeDownloaderConfig(
            metadataUrl: String,
            fallbackVersion: String,
            downloadTimeout: Duration
        ):
            /** Returns a copy with the [[metadataUrl]] set to `v`. */
            def metadataUrl(v: String): ChromeDownloaderConfig = copy(metadataUrl = v)

            /** Returns a copy with the [[fallbackVersion]] set to `v`. */
            def fallbackVersion(v: String): ChromeDownloaderConfig = copy(fallbackVersion = v)

            /** Returns a copy with the [[downloadTimeout]] set to `v`. */
            def downloadTimeout(v: Duration): ChromeDownloaderConfig = copy(downloadTimeout = v)
        end ChromeDownloaderConfig

        object ChromeDownloaderConfig:
            /** Canonical default [[ChromeDownloaderConfig]] populated with the current Chrome-for-Testing metadata endpoint URL, the
              * checked-in fallback version, and the per-download time budget.
              */
            val default: ChromeDownloaderConfig = ChromeDownloaderConfig(
                metadataUrl = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions.json",
                fallbackVersion = "147.0.7727.57",
                downloadTimeout = 5.minutes
            )
        end ChromeDownloaderConfig

    end LaunchConfig

    /** Process-launch configuration. Read once when Chrome is spawned, frozen for the lifetime of that process. Cannot be modified via
      * [[Browser.withConfig]]; the launch has already happened by the time any operation runs.
      *
      * @param executable
      *   path or name of the browser binary to launch (e.g. `"chromium"`, `"google-chrome"`)
      * @param headless
      *   when `true`, launch the browser without a visible window
      * @param extraArgs
      *   additional command-line arguments forwarded verbatim to the browser process
      * @param launchTimeout
      *   maximum time to wait for the browser process to come up and report a CDP endpoint
      *
      * @see
      *   [[LaunchConfig.default]], [[LaunchConfig.chromium]], [[LaunchConfig.chrome]]: preset factories
      * @see
      *   [[Browser.run]], [[Browser.runShared]]: entry points that consume a launch config
      */
    final case class LaunchConfig(
        executable: String,
        headless: Boolean,
        extraArgs: Chunk[String],
        launchTimeout: Duration,
        requestTimeout: Duration,
        closeGrace: Duration,
        tmpDirRemovalSchedule: Schedule,
        devToolsActivePortPollInterval: Duration,
        chromeDownloaderConfig: LaunchConfig.ChromeDownloaderConfig
    ):
        /** Returns a copy with the browser [[executable]] set to `v`. */
        def executable(v: String): LaunchConfig = copy(executable = v)

        /** Returns a copy with [[headless]] set to `v`. */
        def headless(v: Boolean): LaunchConfig = copy(headless = v)

        /** Returns a copy with [[extraArgs]] replaced by `v`. */
        def extraArgs(v: Seq[String]): LaunchConfig = copy(extraArgs = Chunk.from(v))

        /** Returns a copy with the [[launchTimeout]] set to `v`. */
        def launchTimeout(v: Duration): LaunchConfig = copy(launchTimeout = v)

        /** Returns a copy with the [[requestTimeout]] set to `v`. */
        def requestTimeout(v: Duration): LaunchConfig = copy(requestTimeout = v)

        /** Returns a copy with the [[closeGrace]] set to `v`. */
        def closeGrace(v: Duration): LaunchConfig = copy(closeGrace = v)

        /** Returns a copy with the [[tmpDirRemovalSchedule]] set to `v`. */
        def tmpDirRemovalSchedule(v: Schedule): LaunchConfig = copy(tmpDirRemovalSchedule = v)

        /** Returns a copy with the [[devToolsActivePortPollInterval]] set to `v`. */
        def devToolsActivePortPollInterval(v: Duration): LaunchConfig = copy(devToolsActivePortPollInterval = v)

        /** Returns a copy with the [[chromeDownloaderConfig]] set to `v`. */
        def chromeDownloaderConfig(v: LaunchConfig.ChromeDownloaderConfig): LaunchConfig = copy(chromeDownloaderConfig = v)
    end LaunchConfig

    object SessionConfig:
        /** Canonical default [[SessionConfig]] used when no custom session config is installed.
          *
          * Key defaults: `retrySchedule = 100 ms × up to 8 s`, `loadSchedule = 100 ms × up to 5 s`, `networkIdleWindow = 500 ms`,
          * `mutationQuiescenceWindow = 50 ms`, `mutationSettlementTimeout = 2 s`, `mutationFirstMutationGrace = 100 ms`,
          * `assertionStabilityWindow = 100 ms`.
          *
          * The 8-second retry budget accommodates SPA hydration on real-world pages (React/Vue/Angular apps that paint shells before
          * content). Callsites that want a tighter envelope (assertion-failure tests, fail-fast checks) should install a custom schedule
          * via `Browser.withConfig(_.retrySchedule(...))`.
          */
        val default: SessionConfig = SessionConfig(
            retrySchedule = Schedule.fixed(100.millis).maxDuration(8.seconds),
            loadSchedule = Schedule.fixed(100.millis).maxDuration(5.seconds),
            networkIdleWindow = 500.millis,
            mutationQuiescenceWindow = 50.millis,
            mutationSettlementTimeout = 2.seconds,
            mutationFirstMutationGrace = 100.millis,
            assertionStabilityWindow = 100.millis,
            mutationPollInterval = 20.millis,
            navigationPostSettleWindow = 300.millis,
            navigationPollInterval = 50.millis,
            navigationGraceWindow = 300.millis,
            stabilitySampleInterval = 4.millis,
            defaultActionTimeout = 8.seconds,
            defaultAssertionTimeout = 8.seconds,
            captureHoldStillTimeout = 1.second,
            captureHoldStillInterval = 50.millis
        )

    end SessionConfig

    /** Per-operation session configuration. Read by every retry, settle, and assertion loop. Can be overridden via [[Browser.withConfig]]
      * for the duration of a block.
      *
      * @param retrySchedule
      *   default schedule used by retrying methods (`click`, `fill`, reads) when an element is not yet present
      * @param loadSchedule
      *   schedule used by post-navigation `waitForLoad` polling; bounds how long history navigation / `reload` waits for
      *   `readyState == "complete"`
      * @param networkIdleWindow
      *   minimum quiet duration with no in-flight fetch/XHR before [[Browser.Settle.NetworkIdle]] resolves
      * @param mutationQuiescenceWindow
      *   minimum DOM-quiet duration after the last observed mutation before mutation settlement resolves
      * @param mutationSettlementTimeout
      *   upper bound on mutation settlement; if no quiet window opens within this budget, settlement gives up and proceeds
      * @param mutationFirstMutationGrace
      *   grace period after a mutation action during which the first DOM mutation is still expected; settlement does not declare "no
      *   observed mutation" before this grace elapses
      * @param assertionStabilityWindow
      *   how long an assertion result must hold steady before being accepted. The re-probe fires after this window elapses, and the
      *   assertion only succeeds if both the first probe and the re-probe satisfy the predicate. Default 100 ms = 1 polling interval at the
      *   default [[retrySchedule]] of 100 ms, long enough to catch DOM-mutation transients (typically < 16 ms / one frame) while adding
      *   negligible overhead to a typical assertion. Set to [[Duration.Zero]] to restore first-match behaviour (the re-probe is skipped
      *   entirely).
      * @param defaultActionTimeout
      *   total time budget for a single action (`click`, `fill`, etc.). Defaults to 8 seconds, the `maxDuration` of the default
      *   [[retrySchedule]].
      * @param defaultAssertionTimeout
      *   total time budget for a single assertion. Defaults to 8 seconds, the `maxDuration` of the default [[retrySchedule]].
      * @param captureHoldStillTimeout
      *   total best-effort bound for the two-identical-frames hold-still loop per capture. On timeout the last frame is returned; the loop
      *   never aborts. Default 1 second.
      * @param captureHoldStillInterval
      *   inter-capture pacing delay in the two-identical-frames hold-still loop. Default 50 milliseconds.
      *
      * @see
      *   [[Browser.withConfig]]: install a session config for a scope
      * @see
      *   [[Browser.configLocal]]: read the active session config
      */
    final case class SessionConfig(
        retrySchedule: Schedule,
        loadSchedule: Schedule,
        networkIdleWindow: Duration,
        mutationQuiescenceWindow: Duration,
        mutationSettlementTimeout: Duration,
        mutationFirstMutationGrace: Duration,
        assertionStabilityWindow: Duration,
        mutationPollInterval: Duration,
        navigationPostSettleWindow: Duration,
        navigationPollInterval: Duration,
        navigationGraceWindow: Duration,
        stabilitySampleInterval: Duration,
        defaultActionTimeout: Duration,
        defaultAssertionTimeout: Duration,
        captureHoldStillTimeout: Duration,
        captureHoldStillInterval: Duration
    ):
        /** Returns a copy with the [[retrySchedule]] set to `v`. */
        def retrySchedule(v: Schedule): SessionConfig = copy(retrySchedule = v)

        /** Returns a copy with the [[loadSchedule]] set to `v`. */
        def loadSchedule(v: Schedule): SessionConfig = copy(loadSchedule = v)

        /** Returns a copy with the [[networkIdleWindow]] set to `v`. */
        def networkIdleWindow(v: Duration): SessionConfig = copy(networkIdleWindow = v)

        /** Returns a copy with the [[mutationQuiescenceWindow]] set to `v`. */
        def mutationQuiescenceWindow(v: Duration): SessionConfig = copy(mutationQuiescenceWindow = v)

        /** Returns a copy with the [[mutationSettlementTimeout]] set to `v`. */
        def mutationSettlementTimeout(v: Duration): SessionConfig = copy(mutationSettlementTimeout = v)

        /** Returns a copy with the [[mutationFirstMutationGrace]] set to `v`. */
        def mutationFirstMutationGrace(v: Duration): SessionConfig = copy(mutationFirstMutationGrace = v)

        /** Returns a copy with the [[assertionStabilityWindow]] set to `v`. Set to [[Duration.Zero]] to restore first-match behaviour. */
        def assertionStabilityWindow(v: Duration): SessionConfig = copy(assertionStabilityWindow = v)

        /** Returns a copy with the [[mutationPollInterval]] set to `v`. */
        def mutationPollInterval(v: Duration): SessionConfig = copy(mutationPollInterval = v)

        /** Returns a copy with the [[navigationPostSettleWindow]] set to `v`. */
        def navigationPostSettleWindow(v: Duration): SessionConfig = copy(navigationPostSettleWindow = v)

        /** Returns a copy with the [[navigationPollInterval]] set to `v`. */
        def navigationPollInterval(v: Duration): SessionConfig = copy(navigationPollInterval = v)

        /** Returns a copy with the [[navigationGraceWindow]] set to `v`. */
        def navigationGraceWindow(v: Duration): SessionConfig = copy(navigationGraceWindow = v)

        /** Returns a copy with the [[stabilitySampleInterval]] set to `v`. */
        def stabilitySampleInterval(v: Duration): SessionConfig = copy(stabilitySampleInterval = v)

        /** Returns a copy with the [[defaultActionTimeout]] set to `v`. */
        def defaultActionTimeout(v: Duration): SessionConfig = copy(defaultActionTimeout = v)

        /** Returns a copy with the [[defaultAssertionTimeout]] set to `v`. */
        def defaultAssertionTimeout(v: Duration): SessionConfig = copy(defaultAssertionTimeout = v)

        /** Returns a copy with the [[captureHoldStillTimeout]] set to `v` (total best-effort hold-still bound per capture). */
        def captureHoldStillTimeout(v: Duration): SessionConfig = copy(captureHoldStillTimeout = v)

        /** Returns a copy with the [[captureHoldStillInterval]] set to `v` (inter-capture pacing in the two-identical-frames loop). */
        def captureHoldStillInterval(v: Duration): SessionConfig = copy(captureHoldStillInterval = v)
    end SessionConfig

    // CanEqual on SessionConfig powers the "explicit non-default session overrides; default inherits outer Local" gate inside
    // [[Browser.run]] / [[Browser.runShared]] / etc. See [[Browser.SessionConfig]] for details on the gate.
    given CanEqual[Browser.SessionConfig, Browser.SessionConfig] = CanEqual.derived

    /** A browser cookie as observed via [[Browser.cookies]].
      *
      * `Cookie` is a passive value type. All fields except `name` and `value` are optional because the browser may omit them depending on
      * the attributes set by the originating server. The type is intended for read-only inspection of the cookie jar from tests and
      * scripted automation; it does not provide cookie-jar mutation.
      *
      * The CDP wire shape (raw `Maybe[Double]` for `expires`, etc.) lives in the private `kyo.internal.CookieWire`; this public type uses
      * Kyo-idiomatic [[Maybe]] / [[Instant]]. Conversion happens at the CDP boundary via `CookieWire.fromCookie` / `CookieWire.toCookie`.
      *
      * @param name
      *   The cookie name.
      * @param value
      *   The cookie value as a UTF-8 string.
      * @param domain
      *   The domain the cookie is scoped to, when reported by the browser.
      * @param path
      *   The URL path the cookie is scoped to, when reported by the browser.
      * @param expires
      *   Expiry instant; absent for session cookies. Sub-second precision from CDP is preserved on the [[Instant]]'s nanosecond field.
      * @param httpOnly
      *   `true` when the cookie is marked HttpOnly and inaccessible to scripts.
      * @param secure
      *   `true` when the cookie is restricted to secure (HTTPS) connections.
      * @param sameSite
      *   Cookie's `SameSite` attribute when reported by the browser; use [[Cookie.SameSite]] values.
      *
      * @see
      *   [[Browser.cookies]] for the action that returns a sequence of these.
      */
    final case class Cookie(
        name: String,
        value: String,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent,
        expires: Maybe[Instant] = Absent,
        httpOnly: Maybe[Boolean] = Absent,
        secure: Maybe[Boolean] = Absent,
        sameSite: Maybe[Cookie.SameSite] = Absent
    ) derives CanEqual

    object Cookie:
        /** RFC 6265bis SameSite attribute. CDP wire form is the case-sensitive string. */
        enum SameSite derives CanEqual:
            case Strict, Lax, None

        object SameSite:
            /** The wire-format string used by CDP. */
            extension (s: SameSite)
                def wire: String = s match
                    case Strict => "Strict"
                    case Lax    => "Lax"
                    case None   => "None"
            end extension

            /** Parses a CDP wire string into the typed value. Returns `Absent` for unknown values. */
            def parse(raw: String): Maybe[SameSite] = raw match
                case "Strict" => Present(Strict)
                case "Lax"    => Present(Lax)
                case "None"   => Present(None)
                case _        => Absent
        end SameSite
    end Cookie

    /** Opaque handle on a discovered frame.
      *
      * The internal representation carries the CDP `frameId` plus the `executionContextId` observed for that frame at discovery time. The
      * contextId is snapshotted, NOT looked up lazily; same-origin frames keep the same contextId for the life of the frame.
      *
      * Acquire via [[Browser.iframe]] / [[Browser.mainFrame]] / [[Browser.iframes]]. Pass to [[Browser.withIFrame]] to scope every
      * subsequent action to the frame's document.
      */
    opaque type IFrame = IFrameHandle

    object IFrame:
        private[kyo] inline def apply(h: IFrameHandle): IFrame = h

        extension (f: IFrame)
            private[kyo] inline def handle: IFrameHandle = f

        given CanEqual[IFrame, IFrame] = summon[CanEqual[IFrameHandle, IFrameHandle]]
    end IFrame

    // --- Exports ---

    export kyo.internal.Image
    export kyo.internal.Key
    export kyo.internal.KeyModifiers
    export kyo.internal.Selector

    // --- Internal ---

    /** Per-scope active session configuration. Defaults to [[Browser.SessionConfig.default]] at the outermost scope. */
    private[kyo] val configLocal: Local[Browser.SessionConfig] = Local.init(Browser.SessionConfig.default)

    /** Per-fiber active frame scope. `Absent` means no frame override; every `Runtime.evaluate` runs against the top-level frame's default
      * execution context. `Present(handle)` routes the script to the iframe's execution context. Fiber-isolated.
      */
    private[kyo] val activeIFrameLocal: Local[Maybe[IFrameHandle]] = Local.init(Maybe.empty[IFrameHandle])

    /** Lets `kyo.internal` callers reach `Env.use[BrowserTab]` without seeing `Browser`'s opaque underlying type. */
    private[kyo] def use[A, S](f: BrowserTab => A < S)(using Frame): A < (Browser & S) =
        Env.use[BrowserTab](f)

    /** Lets `kyo.internal` callers reach `Env.run(tab)(v)` without seeing `Browser`'s opaque underlying type. Also resets
      * `activeIFrameLocal` to `Absent` because the local carries an `IFrameHandle` pinned to a specific session/executionContextId; using
      * an outer-tab's handle inside an inner-tab's session would route CDP calls at the wrong context.
      */
    private[kyo] def runOn[A, S](tab: BrowserTab)(v: A < (Browser & S))(using Frame): A < (Async & S) =
        activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))

    /** Wrapper that swallows cleanup-time `BrowserReadException` so a scope's release slot does not re-raise into a still-shutting-down
      * fiber. Used by `kyo.internal.NavigationWatcher` and `kyo.internal.MutationSettlement`.
      */
    private[kyo] def releaseHook(tab: BrowserTab)(
        v: Unit < (Browser & Abort[BrowserReadException])
    )(using Frame): Unit < (Async & Abort[Throwable]) =
        Abort.run[BrowserReadException](activeIFrameLocal.let(Maybe.empty[IFrameHandle])(Env.run(tab)(v))).unit

    private[kyo] def selectorNodeDescription(node: SelectorNode): String =
        node match
            case SelectorNode.TestId(v)             => s"""testId("$v")"""
            case SelectorNode.Label(t)              => s"""label("$t")"""
            case SelectorNode.Placeholder(t)        => s"""placeholder("$t")"""
            case SelectorNode.Title(t)              => s"""title("$t")"""
            case SelectorNode.Id(v)                 => s"""id("$v")"""
            case SelectorNode.Css(v)                => s"""css("$v")"""
            case SelectorNode.Text(v, exact)        => if exact then s"""text("$v", exact = true)""" else s"""text("$v")"""
            case SelectorNode.Aria(role, "")        => role
            case SelectorNode.Aria(role, name)      => s"""$role("$name")"""
            case SelectorNode.Within(parent, child) => s"${selectorNodeDescription(parent)}.find(${selectorNodeDescription(child)})"
            case SelectorNode.FirstOf(selectors) =>
                val parts = selectors.toSeq.map(selectorNodeDescription)
                if parts.isEmpty then "or()"
                else parts.tail.foldLeft(parts.head)((acc, p) => s"$acc.or($p)")
            case SelectorNode.Visible(inner) => s"${selectorNodeDescription(inner)}.visible"

    /** Core implementation for setFiles: resolves the element via `Resolver.resolveOne` to obtain the stable backend node id, then calls
      * `DOM.setFileInputFiles`.
      */
    private def setFilesCore(selector: Selector, paths: Chunk[String])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Resolver.resolveOne(selector).map {
            case Absent =>
                Abort.fail(BrowserElementNotFoundException(selectorNodeDescription(Selector.toNode(selector))))
            case Present(ref) =>
                Env.use[BrowserTab] { tab =>
                    CdpBackend.setFileInputFiles(
                        tab.session,
                        SetFileInputFilesParams(paths.toSeq, ref.backendNodeId)
                    )
                }
        }
    end setFilesCore

end Browser

/** Internal wire shape for `consoleLogs`: the JS shim pushes `{level, message, timestamp}` objects into `window.__kyoConsoleLogs`. The
  * Scala side decodes a `Seq[ConsoleMessageWire]` and maps to `Chunk[Browser.ConsoleMessage]`, parsing `level` via a small `match`.
  */
final private[kyo] case class ConsoleMessageWire(level: String, message: String, timestamp: Long) derives Schema

/** Wire record for reads whose JS probe returns `{present: Boolean, x?, y?, w?, h?}`. Used by `boundingRect` to detect a settled-absent
  * element (when `present` is false) before the authoritative `DOM.getBoxModel` CDP read.
  */
final private[kyo] case class PresentFlagWire(present: Boolean, x: Double = 0, y: Double = 0, w: Double = 0, h: Double = 0) derives Schema

/** Wire record for `computedStyles`: `{present: Boolean, vals?: {prop: value, ...}}`. `vals` is `Absent` on the absent-element path. */
final private[kyo] case class ComputedStylesWire(present: Boolean, vals: Maybe[Map[String, String]] = Absent) derives Schema

/** Wire record for `inViewport`: `{present: Boolean, value?: Boolean}`. `value` carries the boolean result on the present path. */
final private[kyo] case class PresentBoolWire(present: Boolean, value: Maybe[Boolean] = Absent) derives Schema

/** Wire envelope for a single-element DISCOVER read (`element` / `elementAt`): `{present: Boolean, info?: ElementInfoWire}`. */
final private[kyo] case class ElementInfoEnvelope(present: Boolean, info: Maybe[DiscoverJs.ElementInfoWire] = Absent) derives Schema
