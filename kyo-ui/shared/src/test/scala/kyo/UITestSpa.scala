package kyo

import kyo.internal.Platform
import kyo.internal.spa.SpaHarnessLocation
import scala.language.implicitConversions

/** Base class for kyo-ui SPA-style tests that exercise real Scala.js code inside Chrome.
  *
  * Parallels [[UITest]] but instead of serving a server-rendered UI tree, this harness serves the linked
  * `kyo-ui-spa-harnessJS/Compile/fastLinkJS` bundle to Chrome as `<script type="module">`. Tests then invoke registered scenarios via
  * `window.kyoUiTest.runScenario(name)`, which routes back into Scala.js code running in the page.
  *
  * The harness bundle's Compile classpath holds ONLY `kyo-ui.js` (no kyo-browser, no Node-only transports), so the linked bundle is
  * Chrome-loadable; the kyo-uiJS Test bundle is not (it transitively pulls in `require("node:fs")` and similar Node-only references from
  * the kyo-browser test dep). This separate-subproject shape is the only way to combine "real Scala.js methods invoked from in-Chrome
  * tests" with "Node-only test dependencies on the classpath".
  *
  * On JVM and Native, [[SpaHarnessLocation]] is a stub that throws on `bundleDir` access; any concrete subclass of `UITestSpa` MUST
  * cancel/skip its tests on non-JS platforms. See [[UITestSpaSmokeTest]] for the established pattern.
  */
abstract class UITestSpa extends Test:

    override def timeout = 60.seconds

    /** Retry budget for transient Chrome-infrastructure failures: 2 retries (3 attempts total) with exponential backoff. Same shape as
      * [[UITest]]; see that base class for the rationale. Only `BrowserConnectionLostException` and `BrowserSetupFailedException` are
      * retried; assertion failures and every other [[BrowserException]] propagate immediately.
      */
    private val retrySchedule: Schedule =
        Schedule.exponentialBackoff(initial = 1.second, factor = 2, maxBackoff = 8.seconds).take(2)

    /** Polls `window.__kyoUiHarnessReady` until it becomes `true` or the budget runs out (40 polls at 50ms = 2s max). The HTML shell sets
      * this flag after importing the bundle's `kyoUiTest` export and assigning it to `window.kyoUiTest`; reading it removes a sleep-based
      * race from the test path.
      */
    private def waitForHarness(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Loop(0) { attempt =>
            if attempt >= 40 then
                Abort.panic(new RuntimeException("kyo-ui SPA harness did not become ready within 2 seconds"))
            else
                Browser.evalBoolean("window.__kyoUiHarnessReady === true").map {
                    case true  => Loop.done(())
                    case false => Async.sleep(50.millis).andThen(Loop.continue(attempt + 1))
                }
        }

    /** Polls `window.__kyoUiTestResult` (set by the success branch) or `window.__kyoUiTestError` (set by the catch branch) until one is
      * populated, or the budget runs out (100 polls at 50ms = 5s max). Panics on error or timeout so the test reports the underlying
      * scenario failure rather than a downstream JSON-decode error.
      */
    private def waitForScenarioResult(scenario: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Loop(0) { attempt =>
            if attempt >= 100 then
                Abort.panic(new RuntimeException(
                    s"kyo-ui SPA harness scenario '$scenario' did not resolve within 5 seconds"
                ))
            else
                Browser.eval(
                    "(window.__kyoUiTestResult !== undefined ? 'ok' : (window.__kyoUiTestError !== undefined ? 'err:' + window.__kyoUiTestError : 'wait'))"
                ).map {
                    case "ok"   => Loop.done(())
                    case "wait" => Async.sleep(50.millis).andThen(Loop.continue(attempt + 1))
                    case errMsg =>
                        Abort.panic(new RuntimeException(
                            s"kyo-ui SPA harness scenario '$scenario' rejected: ${errMsg.stripPrefix("err:")}"
                        ))
                }
        }

    /** Cancels the surrounding test on non-JS platforms. Call at the top of every test body in a `UITestSpa` subclass; the SPA harness is
      * JS-only by construction and cannot run on JVM or Native.
      */
    protected def requireJsPlatform()(using Frame): Unit =
        if !Platform.isJS then
            cancel("UITestSpa requires the JS platform; the SPA harness is a Scala.js bundle served to Chrome")

    // The bundle is an ES module: `@JSExportTopLevel("kyoUiTest")` becomes a named export, NOT a
    // window global. The inline `<script type="module">` imports the named export and re-attaches
    // it to `window.kyoUiTest` so in-page Browser.eval calls can reach it. A bare
    // `<script src="/main.js" type="module">` would never expose the export to the global scope.
    private val htmlShell: String =
        """<!DOCTYPE html>
          |<html>
          |  <head>
          |    <meta charset="utf-8">
          |    <title>kyo-ui SPA harness</title>
          |  </head>
          |  <body>
          |    <script type="module">
          |      import { kyoUiTest } from "/main.js";
          |      window.kyoUiTest = kyoUiTest;
          |      window.__kyoUiHarnessReady = true;
          |    </script>
          |  </body>
          |</html>
          |""".stripMargin

    /** Serves the SPA harness bundle to Chrome and invokes `f` with a JS expression that, when evaluated in the page, awaits
      * `window.kyoUiTest.runScenario(scenario)`. Composes with [[Browser.eval]] / [[Browser.evalJson]] in the caller.
      *
      * Use the returned expression as the inner JS for `Browser.eval` (or a typed variant). Example:
      * {{{
      *   withSpa("ping") { call =>
      *       Browser.evalJson[String](call).map(r => assert(r == "pong"))
      *   }
      * }}}
      *
      * `runShared()` matches [[UITest.withUI]]; the per-test Chrome cost is amortized across the suite.
      */
    def withSpa[A, S](scenario: String)(f: String => A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException] & S) =
        Retry[BrowserConnectionLostException | BrowserSetupFailedException](retrySchedule) {
            for
                bundleBytes <- Abort.run[FileReadException](Path(SpaHarnessLocation.bundleDir, "main.js").readBytes).map {
                    case Result.Success(bytes) => bytes
                    case Result.Failure(err)   => Abort.panic(err)
                    case Result.Panic(t)       => Abort.panic(t)
                }
                pageHandler = HttpRoute.getText("/").handler(_ =>
                    HttpResponse.ok(htmlShell).addHeader("Content-Type", "text/html; charset=utf-8")
                )
                bundleHandler = HttpRoute.getBinary("/main.js").handler(_ =>
                    HttpResponse.ok(bundleBytes).addHeader("Content-Type", "application/javascript; charset=utf-8")
                )
                server <- HttpServer.init(0, "localhost")(pageHandler, bundleHandler)
                result <- Browser.runShared() {
                    for
                        _ <- Browser.goto(s"http://localhost:${server.port}/")
                        _ <- waitForHarness
                        // `Browser.eval` / `evalJson` do NOT set awaitPromise on CDP Runtime.evaluate
                        // (only the private `evalJsAwaiting` does), and JSON.stringify on a Promise
                        // returns "{}". So we fire the scenario async, write its resolved value into
                        // `window.__kyoUiTestResult`, then poll until that holder is populated. `f`
                        // then evaluates against the resolved-value expression, which is now a plain
                        // synchronous JS value that `Browser.eval` and `Browser.evalJson` can return.
                        _ <- Browser.evalDiscard(
                            """(() => {
                              |  window.__kyoUiTestResult = undefined;
                              |  window.__kyoUiTestError = undefined;
                              |  window.kyoUiTest.runScenario('""".stripMargin + scenario + """')
                              |    .then(r => { window.__kyoUiTestResult = r; })
                              |    .catch(e => { window.__kyoUiTestError = String(e); });
                              |})()""".stripMargin
                        )
                        _      <- waitForScenarioResult(scenario)
                        result <- f("window.__kyoUiTestResult")
                    yield result
                }
            yield result
        }
    end withSpa

end UITestSpa
