package kyo.internal.spa

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExport
import scala.scalajs.js.annotation.JSExportTopLevel

/** Registry of named test scenarios exposed to in-Chrome test code as `window.kyoUiTest`.
  *
  * Scenarios are registered at module-initialization time by [[SpaHarnessMain]]. Each scenario is a thunk producing a [[Future]] of a
  * JSON-encoded result string; `runScenario` returns the same value to the page as a JS Promise.
  *
  * Adding a new scenario requires editing [[SpaHarnessMain]], re-linking the bundle, and restarting the test JVM. This is acceptable for
  * kyo-ui's test cadence and avoids the fragility of bridging Scala source into the bundle at test time.
  */
@JSExportTopLevel("kyoUiTest")
object UITestEntry:

    private val scenarios = new ConcurrentHashMap[String, () => Future[String]]()

    /** Invoked by in-Chrome test code via `kyoUiTest.runScenario(name)`. Returns a JS Promise that resolves to the JSON-encoded scenario
      * result, or rejects with the failing exception if the scenario throws or no scenario is registered under the given name.
      */
    @JSExport
    def runScenario(name: String): js.Promise[String] =
        Option(scenarios.get(name)) match
            case Some(scenario) => scenario().toJSPromise
            case None =>
                val rejected = js.Promise.reject(new RuntimeException(s"unknown scenario: $name"))
                rejected.asInstanceOf[js.Promise[String]]

    /** Registers a named scenario. Called from [[SpaHarnessMain.main]] at module-init time. */
    def register(name: String, scenario: () => Future[String]): Unit =
        scenarios.put(name, scenario)
        ()
    end register

end UITestEntry
