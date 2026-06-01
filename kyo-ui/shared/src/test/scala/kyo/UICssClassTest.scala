package kyo

import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

class UICssClassTest extends Test:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    "single cssClass renders class attribute" in run {
        renderHtml(UI.div.cssClass("hero")).map { s =>
            assert(s.contains("class=\"hero\""))
        }
    }

    "multiple cssClass calls space-join in call order" in run {
        renderHtml(UI.div.cssClass("a").cssClass("b").cssClass("c")).map { s =>
            assert(s.contains("class=\"a b c\""))
        }
    }

    "cssClass with a double-quote is attribute-escaped" in run {
        renderHtml(UI.div.cssClass("a\"b")).map { s =>
            assert(s.contains("class=\"a&quot;b\""))
            assert(!s.contains("a\"b"))
        }
    }

    "cssClass coexists with id and data attributes" in run {
        renderHtml(UI.div.id("x").data("k", "v").cssClass("c")).map { s =>
            assert(s.contains("id=\"x\""))
            assert(s.contains("data-k=\"v\""))
            assert(s.contains("class=\"c\""))
        }
    }

end UICssClassTest
