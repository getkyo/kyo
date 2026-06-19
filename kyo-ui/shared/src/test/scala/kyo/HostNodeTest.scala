package kyo

import kyo.UI.Ast.*
import kyo.internal.HostPayload
import kyo.internal.HostValue
import kyo.internal.HtmlOp
import kyo.internal.HtmlRenderer

/** Tests for the host-node cross-platform bridge: tag rendering, non-void shape, HtmlContent child
  * acceptance, `mount = Absent` default, attr propagation, and const (no Bound.Ref) classification.
  * All tests call `HtmlRenderer.render` synchronously with no browser or DOM; they run on JVM, JS,
  * Native, and Wasm.
  */
class HostNodeTest extends kyo.test.Test[Any]:

    "host renders a canvas element with data-kyo-path" in {
        for html <- HtmlRenderer.render(UI.div(UI.host()), Seq.empty)
        yield assert(html.contains("<canvas data-kyo-path=\"0\"></canvas>"))
        end for
    }

    "host renders a custom tag when one is given" in {
        for html <- HtmlRenderer.render(UI.host("figure"), Seq.empty)
        yield
            assert(html.startsWith("<figure"))
            assert(html.contains("</figure>"))
        end for
    }

    "host renders an explicit closing tag (non-void)" in {
        for html <- HtmlRenderer.render(UI.host(), Seq.empty)
        yield
            assert(html.contains("></canvas>"))
            assert(!html.contains(" />"))
        end for
    }

    "host is a valid HtmlContent child of a container" in {
        val tree = UI.div(UI.span("a"), UI.host(), UI.span("b"))
        for html <- HtmlRenderer.render(tree, Seq.empty)
        yield assert(html.contains("<canvas data-kyo-path=\"1\">"))
        end for
    }

    "the bare UI.host factory yields mount = Absent" in {
        val h = UI.host()
        assert(h.mount == Absent)
    }

    "host renders its attrs (id) like any element" in {
        for html <- HtmlRenderer.render(UI.host().id("stage"), Seq.empty)
        yield assert(html.contains("id=\"stage\""))
        end for
    }

    "host carries no data-kyo-reactive attribute (const classification)" in {
        for html <- HtmlRenderer.render(UI.div(UI.host()), Seq.empty)
        yield
            val canvasStart = html.indexOf("<canvas")
            val canvasEnd   = html.indexOf("</canvas>", canvasStart)
            val canvasHtml  = html.substring(canvasStart, canvasEnd + "</canvas>".length)
            assert(!canvasHtml.contains("data-kyo-reactive"))
        end for
    }

    // HostUpdate is a pure construction (no effect row at the call site).
    "HostUpdate constructs as a plain HtmlOp value" in {
        val payload = HostPayload.Prop("n0", "color", HostValue.Col(16711680))
        val op      = HtmlOp.HostUpdate(Seq("0", "2"), payload)
        assert(op.path == Seq("0", "2"))
        assert(op.payload == payload)
        val op2 = HtmlOp.HostUpdate(Seq("0", "2"), payload)
        assert(op == op2)
    }

end HostNodeTest
