package kyo

/** End-to-end harness verifier for [[UITestSpa]].
  *
  * Runs the single `ping` scenario registered by `kyo.internal.spa.SpaHarnessMain`: the in-Chrome `window.kyoUiTest.runScenario("ping")`
  * call routes into Scala.js code, which returns the JSON string `"pong"`. The page then resolves the Promise to that value and the test
  * decodes it via `Browser.evalJson[String]`. Validates the full path: linked-bundle served to Chrome, ES module loaded as
  * `<script type="module">`, registry populated at module init, scenario invoked, JSON Promise resolved back to the driver.
  *
  * Cancels on JVM and Native; the harness is JS-only by construction (a Scala.js bundle served to a real Chrome).
  */
class UITestSpaSmokeTest extends UITestSpa:

    "kyoUiTest.runScenario('ping') resolves to 'pong'" in run {
        requireJsPlatform()
        withSpa("ping") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "pong")
        }
    }

end UITestSpaSmokeTest
