package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class TextTest extends UITest:

    "text renders" in {
        withUI(UI.div("hello").id("d")) {
            Browser.assertText(Selector.id("d"), "hello").unit
        }
    }

    "adjacent text" in {
        withUI(UI.div("a", "b").id("d")) {
            Browser.assertText(Selector.id("d"), "ab").unit
        }
    }

    "html escaped" in {
        withUI(UI.div("<b>not bold</b>").id("d")) {
            Browser.assertText(Selector.id("d"), "<b>not bold</b>").unit
        }
    }

end TextTest
