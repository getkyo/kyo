package kyo

import scala.language.implicitConversions

abstract class UITest extends Test:

    override def timeout = 60.seconds

    /** Retry budget for transient Chrome-infrastructure failures: 2 retries (3 attempts total) with exponential backoff. Per-test fresh
      * Chrome occasionally drops its CDP connection or fails to launch under sustained full-suite load; a fresh attempt rides that out.
      * Backoff starts at 1s so OS resources from the failed attempt settle before the next launch. Transient flakes are independent at
      * ~1.3%, so 2 retries clear >99.98% of them while bounding a genuinely-broken test's worst case to ~3 attempts.
      */
    private val retrySchedule: Schedule =
        Schedule.exponentialBackoff(initial = 1.second, factor = 2, maxBackoff = 8.seconds).take(2)

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
            for
                uiTree   <- ui
                handlers <- UI.runHandlers("/")(uiTree)
                server   <- HttpServer.init(0, "localhost")(handlers*)
                result <- Browser.runShared() {
                    Browser.goto(s"http://localhost:${server.port}/").andThen(f)
                }
            yield result
        }
    end withUI

    /** Asserts that the body text contains the given substring. */
    def assertContains(text: String)(using Frame) =
        Browser.assertTextSatisfies(Browser.Selector.css("body"), s"contains '$text'")(_.contains(text))

end UITest
