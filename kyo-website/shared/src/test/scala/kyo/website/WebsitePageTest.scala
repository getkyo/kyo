package kyo.website

import kyo.*
import scala.language.implicitConversions

class WebsitePageTest extends Test:

    private val defaultOpts = WebsitePage.Options(
        title = "Kyo",
        description = "Build with AI. Ship something that holds.",
        canonical = "https://getkyo.io/",
        bundleHref = "main.js",
        bootScenario = "landing"
    )

    private def renderPage(opts: WebsitePage.Options, view: UI)(using Frame): String < Async =
        WebsitePage.wrap(opts)(view).take(1).run.map(_.headMaybe.getOrElse(""))

    "document structure: doctype, html lang=en, one head, one body, closing html (INV-002)" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.startsWith("<!DOCTYPE html>"))
            assert(html.contains("<html lang=\"en\""))
            assert(html.contains("<head>"))
            assert(html.contains("<body>"))
            assert(html.endsWith("</html>"))
        }
    }

    "head <style> contains WebsiteStyles marker rule (.feat-grid) AFTER UI.baseCss marker (INV-001/INV-012)" in run {
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

    "head contains Google Fonts link (INV-002 fonts)" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("fonts.googleapis.com"))
        }
    }

    "head contains <script type=\"module\"> with bundleHref (INV-002)" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("""<script type="module" src="main.js">"""))
        }
    }

    "body carries view markup and data-boot-scenario attribute" in run {
        renderPage(defaultOpts, UI.div.id("inner-content")).map { html =>
            val bodyStart = html.indexOf("<body>")
            val bodyEnd   = html.indexOf("</body>")
            val body      = html.substring(bodyStart, bodyEnd)
            assert(body.contains("inner-content"))
            assert(body.contains("data-boot-scenario"))
        }
    }

    "title is attribute-escaped" in run {
        renderPage(defaultOpts.copy(title = "<Kyo & Friends>"), UI.div).map { html =>
            assert(html.contains("<title>&lt;Kyo &amp; Friends&gt;</title>"))
        }
    }

    "description and canonical are included in meta/links" in run {
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

    "bundleHref with javascript: scheme falls back to main.js" in run {
        renderPage(defaultOpts.copy(bundleHref = "javascript:alert(1)"), UI.div).map { html =>
            assert(html.contains("src=\"main.js\""))
            assert(!html.contains("javascript:alert"))
        }
    }

    "bootScenario outside allowed set defaults to landing" in run {
        renderPage(defaultOpts.copy(bootScenario = "evil"), UI.div).map { html =>
            assert(html.contains("data-boot-scenario=\"landing\""))
            assert(!html.contains("data-boot-scenario=\"evil\""))
        }
    }

    "bootScenario = docs passes through" in run {
        renderPage(defaultOpts.copy(bootScenario = "docs"), UI.div).map { html =>
            assert(html.contains("data-boot-scenario=\"docs\""))
        }
    }

    "rendered sheet is byte-identical across two calls with different Options" in run {
        for
            h1 <- renderPage(defaultOpts, UI.div)
            h2 <- renderPage(defaultOpts.copy(title = "Other"), UI.div)
        yield
            val style1 = extractStyleBlock(h1)
            val style2 = extractStyleBlock(h2)
            assert(style1 == style2)
        end for
    }

    "pageHead passes UI.stylesheetCss(WebsiteStyles.sheet) not a raw string (INV-012)" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            val sheet = WebsiteStyles.sheet.render
            val css   = UI.stylesheetCss(WebsiteStyles.sheet)
            assert(css == sheet)
            assert(html.contains(sheet.take(40)))
        }
    }

    "<style> CSS is not HTML-escaped; <title> is" in run {
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

    "head links include web fonts" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(html.contains("fonts.googleapis.com"))
        }
    }

    "UI.stylesheetCss(WebsiteStyles.sheet) == WebsiteStyles.sheet.render (INV-012)" in {
        assert(UI.stylesheetCss(WebsiteStyles.sheet) == WebsiteStyles.sheet.render)
    }

    "exactly one doctype, one head, one body, one closing html (INV-002)" in run {
        renderPage(defaultOpts, UI.div).map { html =>
            assert(countOccurrences(html, "<!DOCTYPE html>") == 1)
            assert(countOccurrences(html, "<head>") == 1)
            assert(countOccurrences(html, "<body>") == 1)
            assert(countOccurrences(html, "</html>") == 1)
        }
    }

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
