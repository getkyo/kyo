package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class TextTest extends UITest:

    "text renders" in run {
        withUI(UI.div("hello").id("d")) {
            Browser.assertText(Selector.id("d"), "hello").andThen(succeed)
        }
    }

    "adjacent text" in run {
        withUI(UI.div("a", "b").id("d")) {
            Browser.assertText(Selector.id("d"), "ab").andThen(succeed)
        }
    }

    "html escaped" in run {
        withUI(UI.div("<b>not bold</b>").id("d")) {
            Browser.assertText(Selector.id("d"), "<b>not bold</b>").andThen(succeed)
        }
    }

end TextTest
