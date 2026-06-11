package kyo

import scala.language.implicitConversions

class UIRunRenderPageTest extends kyo.test.Test[Any]:

    private def renderPage(head: UI.PageHead, ui: UI)(using Frame): String < Async =
        UI.runRenderPage(head)(ui).take(1).run.map(_.headMaybe.getOrElse(""))

    "document structure: doctype, html lang, head, body, closing html" in {
        renderPage(UI.PageHead(title = "t"), UI.div).map { html =>
            assert(html.startsWith("<!DOCTYPE html>"))
            assert(html.contains("<html lang=\"en\""))
            assert(html.contains("<head>"))
            assert(html.contains("<body>"))
            assert(html.endsWith("</html>"))
        }
    }

    "baseCss appears strictly before head.css in the style block (INV-001)" in {
        val css = "custom-marker-xyz"
        renderPage(UI.PageHead(title = "t", css = css), UI.div).map { html =>
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            assert(styleStart >= 0 && styleEnd > styleStart)
            val styleContent = html.substring(styleStart, styleEnd)
            val baseCssIdx   = styleContent.indexOf(UI.baseCss.take(30))
            val customIdx    = styleContent.indexOf(css)
            assert(baseCssIdx >= 0)
            assert(customIdx >= 0)
            assert(baseCssIdx < customIdx)
        }
    }

    "title is rendered into <title> and attribute-escaped" in {
        renderPage(UI.PageHead(title = "<Hello & World>"), UI.div).map { html =>
            assert(html.contains("<title>&lt;Hello &amp; World&gt;</title>"))
        }
    }

    "meta pairs render <meta name=... content=...> with both escaped; charset and viewport always present" in {
        renderPage(
            UI.PageHead(title = "t", meta = Seq("description" -> "a<b&c")),
            UI.div
        ).map { html =>
            assert(html.contains("""<meta charset="utf-8">"""))
            assert(html.contains("""<meta name="viewport" content="width=device-width, initial-scale=1">"""))
            assert(html.contains("""<meta name="description" content="a&lt;b&amp;c">"""))
        }
    }

    "link pairs render <link rel=... href=...> escaped" in {
        renderPage(
            UI.PageHead(title = "t", links = Seq("canonical" -> "https://example.com/path?a=1&b=2")),
            UI.div
        ).map { html =>
            assert(html.contains("""<link rel="canonical" href="https://example.com/path?a=1&amp;b=2">"""))
        }
    }

    "moduleScript = Present emits script tag after body; Absent emits no script" in {
        for
            withScript    <- renderPage(UI.PageHead(title = "t", moduleScript = Present("main.js")), UI.div)
            withoutScript <- renderPage(UI.PageHead(title = "t", moduleScript = Absent), UI.div)
        yield
            assert(withScript.contains("""<script type="module" src="main.js">"""))
            // script must come after </body>
            assert(withScript.indexOf("""<script type="module"""") > withScript.indexOf("</body>"))
            assert(!withoutScript.contains("<script"))
        end for
    }

    "body contains the rendered UI fragment" in {
        renderPage(UI.PageHead(title = "t"), UI.div(UI.h1("Hi"))).map { html =>
            val bodyStart = html.indexOf("<body>")
            val bodyEnd   = html.indexOf("</body>")
            val body      = html.substring(bodyStart, bodyEnd)
            assert(body.contains(">Hi<"))
        }
    }

    "reactive UI re-emits complete document on signal change" in {
        // runRenderPage re-emits a full document on every signal change (inherited from runRender).
        // The subscribe step also triggers an immediate re-render; the stream produces:
        // (1) the initial render, (2) the subscribe-triggered re-render, (3) the signal-change
        // re-render. All carry complete documents; the third reflects the updated signal value.
        //
        // A Channel is used as the stream sink so we can take emissions one at a time and
        // synchronize deterministically: after taking 2 emissions the subscription waiter is
        // guaranteed to be registered, so ref.set is safe to call without any sleep.
        for
            ref <- Signal.initRef("initial")
            buf <- Channel.init[String](8)
            stream = UI.runRenderPage(UI.PageHead(title = "re-emit"))(ref.map(v => UI.span(v)))
            _     <- Fiber.initUnscoped(stream.take(3).foreach(buf.put(_)))
            first <- buf.take
            _     <- buf.take // subscribe-triggered re-render; discard, just confirms subscription is ready
            _     <- ref.set("updated")
            third <- buf.take
        yield
            // First emission is a complete document with the initial value
            assert(first.startsWith("<!DOCTYPE html>"))
            assert(first.contains("initial"))
            assert(first.contains("</html>"))
            // Signal-change emission is a complete document with the updated value
            assert(third.startsWith("<!DOCTYPE html>"))
            assert(third.contains("updated"))
            assert(third.contains("</html>"))
        end for
    }

    "PageHead derives CanEqual: two equal PageHead instances compare equal" in {
        val h1 = UI.PageHead(title = "t")
        val h2 = UI.PageHead(title = "t")
        assert(h1 == h2)
    }

    "minimal PageHead produces valid document with charset+viewport and style only carrying baseCss" in {
        renderPage(UI.PageHead(title = "t"), UI.div).map { html =>
            assert(html.contains("""<meta charset="utf-8">"""))
            assert(html.contains("""<meta name="viewport""""))
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            val style      = html.substring(styleStart + "<style>".length, styleEnd)
            // with empty css, the style block is exactly baseCss
            assert(style == UI.baseCss)
        }
    }

end UIRunRenderPageTest
