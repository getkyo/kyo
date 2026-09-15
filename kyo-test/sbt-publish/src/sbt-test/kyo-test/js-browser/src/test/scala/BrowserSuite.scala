import kyo.*
import kyo.test.Test
import scala.scalajs.js

class BrowserSuite extends Test[Any]:
    "runs in a page" in {
        assert(js.typeOf(js.Dynamic.global.document) == "object")
        assert(js.Dynamic.global.navigator.userAgent.asInstanceOf[String].contains("HeadlessChrome"))
    }

    "suspends on the page's event loop" in {
        Async.sleep(10.millis).andThen(assert(js.typeOf(js.Dynamic.global.window) == "object"))
    }
end BrowserSuite

/** Deliberately red.
  *
  * A run that discovers nothing, or whose page never loads the tests, reports success, so only a required failure here separates a
  * working browser run from a silently empty one.
  */
class FailingSuite extends Test[Any]:
    "fails" in {
        assert(1 + 1 == 3)
    }
end FailingSuite
