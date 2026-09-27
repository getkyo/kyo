package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.HtmlRenderer

class DataAttrsTest extends kyo.test.Test[Any]:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    "data(name, value) renders data-user-id and does not collide with data-kyo-path" in {
        val html = renderHtml(UI.span.data("user-id", "42"))
        html.map { s =>
            assert(s.contains("""data-user-id="42""""))
            assert(s.contains("data-kyo-path"))
        }
    }

    "two data calls merge into one dataAttrs map and render both sorted by name" in {
        val html = renderHtml(
            UI.div.data("z-index", "10").data("app-id", "abc")
        )
        html.map { s =>
            assert(s.contains("""data-app-id="abc""""))
            assert(s.contains("""data-z-index="10""""))
            assert(s.indexOf("data-app-id") < s.indexOf("data-z-index"))
        }
    }

    "a key with a quote stays inside data-kyo-path" in {
        val key  = "x\" onmouseover=\"alert(1)"
        val keys = Signal.initConst(Chunk(key))
        renderHtml(
            UI.div(
                UI.ul(keys.foreachKeyed(identity)(k => UI.li(k))),
                Svg.svg(keys.foreachKeyed(identity)(_ => Svg.g))
            )
        ).map { s =>
            assert(!s.contains(""" onmouseover="alert(1)"""), s)
            assert(s.contains("""data-kyo-path="0.0.x&quot; onmouseover=&quot;alert(1)""""), s)
            assert(s.contains("""data-kyo-path="1.0.x&quot; onmouseover=&quot;alert(1)""""), s)
        }
    }

    "data with kyo- prefix throws IllegalArgumentException with message containing kyo-" in {
        val ex = intercept[IllegalArgumentException] {
            UI.div.data("kyo-internal", "x")
        }
        assert(ex.getMessage.contains("kyo-"))
    }

    "data varargs with kyo- prefix throws before any value is set" in {
        val ex = intercept[IllegalArgumentException] {
            UI.div.data(("kyo-a", "1"), ("ok", "2"))
        }
        assert(ex.getMessage.contains("kyo-"))
        val baseDiv = UI.Ast.Div()
        assert(baseDiv.attrs.dataAttrs.isEmpty)
    }

end DataAttrsTest
