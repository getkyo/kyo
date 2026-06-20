package kyo.website

import kyo.*

/** Tests for DOM helper methods in [[WebsiteBundleMain]].
  *
  * Runs in JS only (JS placement via `kyo-website-bundle/js/src/test/`). DOM globals are not
  * available in the NodeJS test environment, so each leaf installs a fake `document` on
  * `globalThis` before invoking the helper and removes it afterwards, keeping the test
  * environment clean between runs.
  */
class WebsiteBundleMainTest extends kyo.test.Test[Any]:

    /** The `globalThis` object, accessed via eval to avoid Scala.js inlining the property
      * access to a bare `document` variable reference (which throws ReferenceError in NodeJS
      * when the global does not pre-exist).
      */
    private val globalThis: scala.scalajs.js.Dynamic =
        // Justified: js.eval returns js.Any; the cast sets dynamic properties on globalThis at the JS test-harness boundary, confined to this helper.
        scala.scalajs.js.eval("globalThis").asInstanceOf[scala.scalajs.js.Dynamic]

    /** Run a block with a fake `document` set on `globalThis`, then remove it afterwards.
      * The removal runs after the suspended Sync effect completes.
      */
    private def withDocument[A](
        fakeDocument: scala.scalajs.js.Dynamic
    )(block: => A < Sync)(using Frame): A < Sync =
        Sync.defer {
            globalThis.document = fakeDocument
            Abort.run[Throwable](Abort.catching[Throwable](block)).map { result =>
                scala.scalajs.js.eval("delete globalThis.document")
                result match
                    case Result.Success(a) => a
                    case Result.Failure(e) => throw e
                    case Result.Panic(e)   => throw e
                end match
            }
        }
    end withDocument

    // Case 1: a fake document whose getElementById returns a stub element.
    // After addChartDrawn runs, classList.add must have been called (node is present).
    "addChartDrawn adds the chart-drawn class when the node is present" in {
        var classAdded = false
        val fakeEl = scala.scalajs.js.Dynamic.literal(
            classList = scala.scalajs.js.Dynamic.literal(
                add = (cls: String) =>
                    classAdded = true; ()
            )
        )
        val fakeDoc = scala.scalajs.js.Dynamic.literal(
            getElementById = (id: String) => if id == "gap-chart" then fakeEl else null
        )
        withDocument(fakeDoc) {
            WebsiteBundleMain.addChartDrawn("gap-chart").map { _ =>
                assert(classAdded, "classList.add must have been called when the element is present")
            }
        }
    }

    // Case 1b: the same mechanism is generalized by id, so it also arms the platforms connector.
    "addChartDrawn adds the chart-drawn class to the pf-connect node when present" in {
        var classAdded = false
        val fakeEl = scala.scalajs.js.Dynamic.literal(
            classList = scala.scalajs.js.Dynamic.literal(
                add = (cls: String) =>
                    classAdded = true; ()
            )
        )
        val fakeDoc = scala.scalajs.js.Dynamic.literal(
            getElementById = (id: String) => if id == "pf-connect" then fakeEl else null
        )
        withDocument(fakeDoc) {
            WebsiteBundleMain.addChartDrawn("pf-connect").map { _ =>
                assert(classAdded, "classList.add must have been called for pf-connect when present")
            }
        }
    }

    // Case 2: a fake document whose getElementById returns null (node absent).
    // addChartDrawn must complete without exception and must not call classList.add.
    "addChartDrawn is a no-op and throws nothing when the node is absent" in {
        var classAdded = false
        val fakeDoc = scala.scalajs.js.Dynamic.literal(
            getElementById = (id: String) => null
        )
        withDocument(fakeDoc) {
            WebsiteBundleMain.addChartDrawn("gap-chart").map { _ =>
                assert(!classAdded, "classList.add must NOT have been called when the element is absent")
            }
        }
    }

end WebsiteBundleMainTest
