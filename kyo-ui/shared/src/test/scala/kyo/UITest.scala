package kyo

/** Base for kyo-ui suites that drive the shared Chrome against the shared UI server.
  *
  * It does not extend kyo-browser's [[BaseChromeTest]]: kyo-ui suites mix leaves that never open a browser with Chrome leaves, so the
  * unsupported-platform cancel and the Chrome launch stay inside [[withUI]] and [[cancelOnUnsupportedPlatform]], where the browser is
  * actually needed. The transient-failure retry is the one [[BaseChromeTest.retryTransient]] defines, applied to every leaf by
  * [[aroundLeaf]]; for a leaf that never raises those failures it is a no-op.
  */
abstract class UITest extends kyo.test.Test[Any]:

    override def timeout = 60.seconds

    // kyo-ui suites drive a single shared Chrome via Browser.runShared. Run each suite's leaves sequentially:
    // under kyo-test's default leaf parallelism the leaves hammer that one Chrome at once, producing
    // BrowserAssertionTimedOutExceptions and CDP timeouts (the same hazard BaseBrowserTest documents). The sbt
    // build already serializes suites (one forked JVM + Chrome per suite); .sequential closes the within-suite gap.
    //
    // failOnNoAssertion is disabled because kyo-ui suites assert through Browser.assert* (domain helpers that do not
    // flow through the kyo.test assert macros), so the no-assertion counter sees zero.
    //
    // The shared Chrome (Browser.runShared) is held for the whole run, so its CDP `socket:[inode]` and stdio
    // `pipe:[inode]` are opaque-inode descriptors no allowlist can match. Disable only those two descriptor categories,
    // keeping thread and fiber detection on (the kyo-http NioIoDriver fiber is built-in allowlisted). Same rationale as
    // kyo-browser's BaseBrowserTest.
    override def config =
        super.config.sequential.failOnNoAssertion(false).leakCheckSockets(false).leakCheckFileDescriptors(false)

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        BaseChromeTest.retryTransient(getClass.getName, BaseChromeTest.transientRetrySchedule, BaseChromeTest.transientFailures, Kyo.unit)(
            body
        )

    /** Marker substring in the unsupported-platform setup failure (kyo.internal.ChromeDownloader). */
    private val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    /** On platforms with no chrome-headless-shell (linux-arm64, win-arm64), Chrome launch fails with a BrowserSetupFailedException carrying
      * install guidance. Translate that one case into a kyo-test `cancel(...)` so those platforms report the browser-backed UI tests as
      * canceled (skipped) rather than red failures that each burn the retry budget and push the job past its timeout. Every other failure
      * propagates unchanged.
      */
    private[kyo] def cancelOnUnsupportedPlatform[A, S](
        f: A < (Async & Scope & Abort[BrowserSetupException] & S)
    )(using Frame): A < (Async & Scope & Abort[BrowserSetupException] & S) =
        Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
            val msg = ex.getMessage
            if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
            else Abort.fail[BrowserSetupException](ex)
        } { f }

    def withUI[A, S](ui: UI < Async)(f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException] & Abort[HttpBindException] & S) =
        // Shared Chrome AND shared server. Browser.runShared launches one Chrome process lazily and keeps it alive for
        // the run; each call attaches its own tab and tears it down via internal Scope.run. SharedUIServer likewise binds
        // ONE HttpServer for the run: this leaf's UI is stashed in the shared server's ref, then the shared Chrome
        // navigates to the server's single stable URL, whose page/WebSocket routes re-read that ref per request. Every
        // leaf thus lands on the same origin, and the run no longer churns a fresh ephemeral server + client sockets
        // per leaf (that churn exhausted Windows sockets: WSAENOBUFS / error 10055, the failure this fixes). Suite
        // wall-clock is unchanged on JVM+Chrome (measured: identical to the per-leaf-server version, since a localhost
        // bind is cheap and per-leaf cost is dominated by CDP navigation), so this is a correctness fix, not a speedup.
        // Safe because leaves never overlap (JS sequential + `.sequential`; one JVM per suite on the JVM), so the
        // set-then-navigate has no race.
        //
        // An earlier runShared trial dropped the trailing focus event on focus-transition tests because non-foregrounded
        // shared tabs suppress focus events. That blocker was resolved by BrowserTab.scala calling
        // Emulation.setFocusEmulationEnabled(true) on each tab attach, which forces Chrome to dispatch focus events
        // regardless of tab foregrounding.
        cancelOnUnsupportedPlatform {
            for
                uiTree <- ui
                _      <- SharedUIServer.set(uiTree)
                url    <- SharedUIServer.url
                result <- Browser.runShared() {
                    Browser.goto(url).andThen(f)
                }
            yield result
        }
    end withUI

    /** Asserts that the body text contains the given substring. */
    def assertContains(text: String)(using Frame) =
        Browser.assertTextSatisfies(Browser.Selector.css("body"), s"contains '$text'")(_.contains(text))

end UITest
