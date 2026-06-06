package kyo

import scala.language.implicitConversions

abstract class UITest extends kyo.test.Test[Any]:

    override def timeout = 60.seconds

    // kyo-ui suites drive a single shared Chrome via Browser.runShared. Run each suite's leaves sequentially:
    // under kyo-test's default leaf parallelism the leaves hammer that one Chrome at once, producing
    // BrowserAssertionTimedOutExceptions and CDP timeouts (the same hazard BaseBrowserTest documents). The sbt
    // build already serializes suites (one forked JVM + Chrome per suite); .sequential closes the within-suite gap.
    //
    // failOnNoAssertion is disabled because kyo-ui suites assert through Browser.assert* (domain helpers that do not
    // flow through the kyo.test assert macros), so the no-assertion counter sees zero.
    override def config = super.config.sequential.failOnNoAssertion(false)

    /** Retry budget for transient Chrome-infrastructure failures: 2 retries (3 attempts total) with exponential backoff. Per-test fresh
      * Chrome occasionally drops its CDP connection or fails to launch under sustained full-suite load; a fresh attempt rides that out.
      * Backoff starts at 1s so OS resources from the failed attempt settle before the next launch. Transient flakes are independent at
      * ~1.3%, so 2 retries clear >99.98% of them while bounding a genuinely-broken test's worst case to ~3 attempts.
      */
    private val retrySchedule: Schedule =
        Schedule.exponentialBackoff(initial = 1.second, factor = 2, maxBackoff = 8.seconds).take(2)

    /** Marker substring in the unsupported-platform setup failure (kyo.internal.ChromeDownloader). Keep in sync with kyo-browser's BrowserTest. */
    private val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    /** On platforms with no chrome-headless-shell (linux-arm64, win-arm64), Chrome launch fails with a BrowserSetupFailedException carrying
      * install guidance. Translate that one case into a ScalaTest `cancel(...)` so those platforms report the browser-backed UI tests as
      * canceled (skipped) rather than red failures that each burn the retry budget and push the job past its timeout. Mirrors kyo-browser's
      * BrowserTest.cancelOnUnsupportedPlatform; every other failure propagates unchanged.
      */
    private def cancelOnUnsupportedPlatform[A, S](
        f: A < (Async & Scope & Abort[BrowserSetupException] & S)
    )(using Frame): A < (Async & Scope & Abort[BrowserSetupException] & S) =
        Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
            val msg = ex.getMessage
            if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
            else Abort.fail[BrowserSetupException](ex)
        } { f }

    def withUI[A, S](ui: UI < Async)(f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException] & S) =
        // JVM-shared Chrome (Browser.runShared). One Chrome process is launched lazily on the
        // first call and kept alive for the JVM; each call attaches its own tab and tears it
        // down via internal Scope.run. Amortizes the per-test Chrome boot across the suite
        // (~14% wall-clock saving on this machine; larger on slower CI runners where 2-core
        // process-launch cost dominates).
        //
        // An earlier runShared trial dropped the trailing focus event on focus-transition
        // tests because non-foregrounded shared tabs suppress focus events. That blocker was
        // resolved by BrowserTab.scala calling Emulation.setFocusEmulationEnabled(true) on
        // each tab attach, which forces Chrome to dispatch focus events regardless of tab
        // foregrounding.
        //
        // Retry is scoped to the two transient browser-infrastructure failure types: a dropped
        // CDP connection and a Chrome process that failed to launch. Retry[E] only retries
        // E-typed failures, so assertion failures and every other BrowserException propagate
        // immediately and are never masked.
        Retry[BrowserConnectionLostException | BrowserSetupFailedException](retrySchedule) {
            cancelOnUnsupportedPlatform {
                for
                    uiTree   <- ui
                    handlers <- UI.runHandlers("/")(uiTree)
                    server   <- HttpServer.init(0, "localhost")(handlers*)
                    result <- Browser.runShared() {
                        Browser.goto(s"http://localhost:${server.port}/").andThen(f)
                    }
                yield result
            }
        }
    end withUI

    /** Asserts that the body text contains the given substring. */
    def assertContains(text: String)(using Frame) =
        Browser.assertTextSatisfies(Browser.Selector.css("body"), s"contains '$text'")(_.contains(text))

end UITest
