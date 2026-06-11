package kyo

import kyo.UI.*

class RawHtmlTest extends kyo.test.Test[Any]:

    // Helper: run the SSG renderer on a UI value and return the HTML string.
    private def render(ui: UI)(using Frame): String < Async =
        UI.runRender(ui).take(1).run.map(_.headMaybe.getOrElse(""))

    "rawHtml emits verbatim, bypasses esc (INV-005)" in {
        // Leaf 20: <b>x</b> must appear byte-identical, not escaped.
        render(rawHtml("<b>x</b>")).map { html =>
            assert(html.contains("<b>x</b>"))
            assert(!html.contains("&lt;b&gt;"))
        }
    }

    "text escapes (boundary side by side, INV-005)" in {
        // Leaf 21: UI.text must HTML-escape the same string.
        render(Ast.Text("<b>x</b>")).map { html =>
            assert(html.contains("&lt;b&gt;x&lt;/b&gt;"))
            assert(!html.contains("<b>x</b>"))
        }
    }

    "rawHtml inside an element renders inside its tags" in {
        // Leaf 22: the raw table must appear verbatim inside the div tags.
        val snippet = "<table><tr><td>1</td></tr></table>"
        render(div(rawHtml(snippet))).map { html =>
            assert(html.contains(snippet))
            assert(html.contains("<div"))
        }
    }

    "inline-HTML snippet round-trips byte-identical" in {
        // Leaf 23: an inline <img>/<a><img></a> snippet with attributes must be byte-identical.
        val snippet = """<a href="https://example.com"><img src="kyo.png" width="200" alt="Kyo"></a>"""
        render(div(rawHtml(snippet))).map { html =>
            assert(html.contains(snippet))
        }
    }

    "rawHtml of empty string renders empty, no crash" in {
        // Leaf 24: empty rawHtml is valid: Ast.RawHtml("") writes its value verbatim (an empty
        // string), so the rendered output is exactly the empty string with no injected markup.
        render(rawHtml("")).map { html =>
            assert(html == "", s"Expected empty string, got: '$html'")
            assert(!html.contains("null"), s"Must not contain 'null': '$html'")
            assert(!html.contains("undefined"), s"Must not contain 'undefined': '$html'")
        }
    }

    "rawHtml under the page path renders verbatim in body (cross-addition)" in {
        // Leaf 25: inside a full HTML document the raw <p> appears verbatim in the body.
        val doc = UI.runRenderPage(UI.PageHead(title = "t"))(div(rawHtml("<p>hi</p>")))
        doc.take(1).run.map { frames =>
            val html = frames.headMaybe.getOrElse("")
            assert(html.contains("<p>hi</p>"))
            assert(!html.contains("&lt;p&gt;hi&lt;/p&gt;"))
        }
    }

    "reactive rawHtml re-renders on change" in {
        // Leaf 26: driving a SignalRef whose value is mapped through UI.rawHtml produces
        // distinct frames, each containing the then-current raw HTML.
        for
            ref <- Signal.initRef("<b>initial</b>")
            ui = ref.render(rawHtml(_))
            html1 <- render(ui)
            _     <- ref.set("<i>updated</i>")
            html2 <- render(ui)
        yield
            assert(html1.contains("<b>initial</b>"), s"First frame: $html1")
            assert(html2.contains("<i>updated</i>"), s"Second frame: $html2")
        end for
    }

    "rawHtml pattern-matches as Ast.RawHtml (AST access)" in {
        // Leaf 27: UI.rawHtml returns Ast.RawHtml; case-class equality holds.
        val node: UI = rawHtml("x")
        val matched = node match
            case Ast.RawHtml("x") => true
            case _                => false
        assert(matched)
        assert(node == Ast.RawHtml("x"))
    }

    // Leaf 28 (JS, in-Chrome) folds into the existing in-Chrome smoke and is not
    // represented as a shared JVM test here.

end RawHtmlTest
