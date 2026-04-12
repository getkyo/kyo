package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TestSupport")
object JsTestSupport extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def runLiftTest[A, B](expected: A, body: B) =
        TestSupport.runLiftTest(expected)(body)


end JsTestSupport