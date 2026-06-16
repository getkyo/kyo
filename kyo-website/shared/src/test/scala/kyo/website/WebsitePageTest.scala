package kyo.website

import kyo.*
import scala.language.implicitConversions

class WebsitePageTest extends WebsiteTest:

    private val defaultOpts = WebsitePage.Options(
        title = "Kyo",
        description = "Build with AI. Ship something that holds.",
        canonical = "https://getkyo.io/",
        bundleHref = "main.js"
    )

    private def renderPage(opts: WebsitePage.Options, view: UI)(using Frame): String < Async =
        WebsitePage.wrap(opts)(view).take(1).run.map(_.headMaybe.getOrElse(""))

    "document structure: doctype, html lang=en, one head, one body, closing html" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.startsWith("<!DOCTYPE html>"))
            assert(html.contains("<html lang=\"en\""))
            assert(html.contains("<head>"))
            assert(html.contains("<body>"))
            assert(html.endsWith("</html>"))
        }
    }

    "head <style> contains WebsiteStyles marker rule (.feat-grid) AFTER UI.baseCss marker" in {
        renderPage(defaultOpts, UI.div).map { html =>
            val styleStart  = html.indexOf("<style>")
            val styleEnd    = html.indexOf("</style>")
            val style       = html.substring(styleStart, styleEnd)
            val baseCssIdx  = style.indexOf(UI.baseCss.take(30))
            val featGridIdx = style.indexOf(".feat-grid")
            assert(baseCssIdx >= 0, "baseCss must be in style block")
            assert(featGridIdx >= 0, ".feat-grid rule must be in style block")
            assert(baseCssIdx < featGridIdx, "baseCss must precede .feat-grid")
        }
    }

    "head contains Google Fonts link" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("fonts.googleapis.com"))
        }
    }

    "head contains <script type=\"module\"> with bundleHref" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("""<script type="module" src="main.js">"""))
        }
    }

    "body carries the view markup directly (no boot-hook wrapper, G3)" in {
        renderPage(defaultOpts, UI.div.id("inner-content")).map { html =>
            val bodyStart = html.indexOf("<body>")
            val bodyEnd   = html.indexOf("</body>")
            val body      = html.substring(bodyStart, bodyEnd)
            assert(body.contains("inner-content"))
            // G3: the page root is the view directly; there is no data-boot-scenario wrapper anymore.
            assert(!body.contains("data-boot-scenario"), "boot-scenario wrapper must be gone (G3)")
        }
    }

    "title is attribute-escaped" in {
        renderPage(defaultOpts.copy(title = "<Kyo & Friends>"), UI.div).map { html =>
            assert(html.contains("<title>&lt;Kyo &amp; Friends&gt;</title>"))
        }
    }

    "description and canonical are included in meta/links" in {
        renderPage(
            defaultOpts.copy(
                description = "Desc<test>",
                canonical = "https://example.com/path"
            ),
            UI.div
        ).map { html =>
            assert(html.contains("Desc&lt;test&gt;"))
            assert(html.contains("https://example.com/path"))
        }
    }

    "bundleHref with javascript: scheme falls back to main.js" in {
        renderPage(defaultOpts.copy(bundleHref = "javascript:alert(1)"), UI.div).map { html =>
            assert(html.contains("src=\"main.js\""))
            assert(!html.contains("javascript:alert"))
        }
    }

    "rendered sheet is byte-identical across two calls with different Options" in {
        for
            h1 <- renderPage(defaultOpts, UI.div)
            h2 <- renderPage(defaultOpts.copy(title = "Other"), UI.div)
        yield
            val style1 = extractStyleBlock(h1)
            val style2 = extractStyleBlock(h2)
            assert(style1 == style2)
        end for
    }

    "pageHead passes UI.stylesheetCss(WebsiteStyles.sheet) not a raw string" in {
        renderPage(defaultOpts, UI.div).map { html =>
            val sheet = WebsiteStyles.sheet.render
            val css   = UI.stylesheetCss(WebsiteStyles.sheet)
            assert(css == sheet)
            assert(html.contains(sheet.take(40)))
        }
    }

    "<style> CSS is not HTML-escaped; <title> is" in {
        renderPage(defaultOpts.copy(title = "<Kyo>"), UI.div).map { html =>
            assert(html.contains("<title>&lt;Kyo&gt;</title>"))
            // CSS in <style> should contain literal chars, not HTML-escaped
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            val style      = html.substring(styleStart, styleEnd)
            // CSS uses : and ; which would be unescaped
            assert(style.contains(":"))
        }
    }

    "head links include web fonts" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("fonts.googleapis.com"))
        }
    }

    "head includes a favicon link so /favicon.ico does not 404" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("""<link rel="icon" href="/kyo.png">"""), s"head must carry the favicon link: $html")
        }
    }

    "UI.stylesheetCss(WebsiteStyles.sheet) == WebsiteStyles.sheet.render" in {
        assert(UI.stylesheetCss(WebsiteStyles.sheet) == WebsiteStyles.sheet.render)
    }

    "exactly one doctype, one head, one body, one closing html" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(countOccurrences(html, "<!DOCTYPE html>") == 1)
            assert(countOccurrences(html, "<head>") == 1)
            assert(countOccurrences(html, "<body>") == 1)
            assert(countOccurrences(html, "</html>") == 1)
        }
    }

    // ---- JSON-LD injection ----

    "wrap injects application/ld+json block when jsonLd is non-empty, escaping angle brackets" in {
        renderPage(defaultOpts.copy(jsonLd = """{"@type":"TechArticle","x":"</script>"}"""), UI.div).map { html =>
            assert(html.contains("""<script type="application/ld+json">"""), s"JSON-LD block must be present: $html")
            val block = extractJsonLd(html)
            assert(block.contains("@type"), s"JSON-LD must carry the payload: $block")
            assert(block.contains("TechArticle"), s"JSON-LD must carry the type: $block")
            // The escape turns the literal </script> into <.../> so it cannot close the element.
            assert(!block.contains("</script>"), s"JSON-LD must not contain a literal </script>: $block")
            assert(block.contains("\\u003c"), s"angle brackets must be unicode-escaped: $block")
            // The block sits inside the <head>.
            val ldIdx   = html.indexOf("application/ld+json")
            val headEnd = html.indexOf("</head>")
            assert(ldIdx >= 0 && ldIdx < headEnd, "JSON-LD must be inside the <head>")
        }
    }

    "wrap emits no application/ld+json block when jsonLd is empty (the default)" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(!html.contains("application/ld+json"), s"no JSON-LD block when jsonLd is empty: $html")
        }
    }

    "wrap renders ld+json via PageHead.jsonLd slot, byte-identical to prior splice behavior" in {
        renderPage(defaultOpts.copy(jsonLd = """{"@type":"TechArticle","x":"</script>"}"""), UI.div).map { html =>
            assert(html.contains("""<script type="application/ld+json">"""), s"ld+json block must be present: $html")
            val block   = extractJsonLd(html)
            val ldIdx   = html.indexOf("application/ld+json")
            val headEnd = html.indexOf("</head>")
            assert(ldIdx >= 0 && ldIdx < headEnd, "ld+json must be before </head>")
            assert(!block.contains("</script>"), s"ld+json body must not contain literal </script>: $block")
            assert(block.contains("\\u003c"), s"angle brackets must be unicode-escaped: $block")
            assert(!html.contains("id="), s"ld+json block must carry no id attribute: $html")
        }
    }

    "wrap emits no ld+json block when jsonLd is empty via PageHead.jsonLd default" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(!html.contains("application/ld+json"), s"no ld+json block with empty jsonLd: $html")
        }
    }

    "wrap renders Options.dataIslands before </body> when set" in {
        renderPage(
            defaultOpts.copy(dataIslands = Seq(UI.dataIsland("application/json", Present("docs-island"), """{"a":1}"""))),
            UI.div
        ).map { html =>
            val islandStr = """<script type="application/json" id="docs-island">{"a":1}</script>"""
            assert(html.contains(islandStr), s"island must be present: $html")
            val islandIdx = html.indexOf(islandStr)
            val bodyEnd   = html.indexOf("</body>")
            assert(islandIdx >= 0 && islandIdx < bodyEnd, s"island must be before </body>: $html")
        }
    }

    // ---- noindex robots meta ----

    "wrap emits noindex robots meta when noindex=true, absent when false" in {
        for
            on  <- renderPage(defaultOpts.copy(noindex = true), UI.div)
            off <- renderPage(defaultOpts, UI.div)
        yield
            assert(on.contains("""<meta name="robots" content="noindex">"""), s"noindex meta must be present: $on")
            assert(!off.contains("""content="noindex""""), s"noindex meta must be absent by default: $off")
        end for
    }

    // ---- no stray rel="alternate" duplicating the canonical ----

    "no stray rel=alternate link in the rendered head" in {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(!html.contains("""rel="alternate""""), s"head must not carry a stray rel=alternate: $html")
            // The canonical is still present (the removal only dropped the duplicate alternate).
            assert(html.contains("""<link rel="canonical" href="https://getkyo.io/">"""), s"canonical must remain: $html")
        }
    }

    private def extractJsonLd(html: String): String =
        val open  = """<script type="application/ld+json">"""
        val start = html.indexOf(open)
        if start < 0 then ""
        else
            val from = start + open.length
            val end  = html.indexOf("</script>", from)
            if end < 0 then "" else html.substring(from, end)
        end if
    end extractJsonLd

    private def extractStyleBlock(html: String): String =
        val start = html.indexOf("<style>")
        val end   = html.indexOf("</style>")
        if start >= 0 && end > start then html.substring(start, end)
        else ""
    end extractStyleBlock

    private def countOccurrences(s: String, sub: String): Int =
        var count = 0
        var idx   = s.indexOf(sub)
        while idx >= 0 do
            count += 1
            idx = s.indexOf(sub, idx + 1)
        count
    end countOccurrences

end WebsitePageTest
