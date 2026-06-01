package kyo

import scala.language.implicitConversions

class StylesheetTest extends Test:

    import Stylesheet.*

    "rule with class name renders selector plus CssStyleRenderer body" in {
        val css = Stylesheet.rule("btn", Style.bg(Style.Color.blue)).render
        assert(css.contains(".btn {"))
        assert(css.contains("background-color: #3b82f6;"))
    }

    "rule with hover pseudo-state renders base block and :hover block" in {
        val css = Stylesheet.rule("btn", Style.bg(Style.Color.blue).hover(_.bg(_.indigo))).render
        assert(css.contains(".btn {"))
        assert(css.contains(".btn:hover {"))
        assert(css.contains("background-color: #6366f1;"))
    }

    "Selector.cls descendant pseudo hover" in {
        val sel = Selector.cls("a").descendant(Selector.cls("b")).pseudo("hover")
        assert(sel.css == ".a .b:hover")
    }

    "Selector factories produce correct CSS text" in {
        assert(Selector.id("hero").css == "#hero")
        assert(Selector.data("active", "true").css == "[data-active=\"true\"]")
        assert(Selector.data("open").css == "[data-open]")
        assert(Selector.tag("body").css == "body")
    }

    "media query wraps inner rule in @media block" in {
        val css = Stylesheet.media(MediaQuery.minWidth(768.px))(
            Stylesheet.rule("grid", Style.column)
        ).render
        assert(css.contains("@media (min-width: 768px)"))
        assert(css.contains(".grid {"))
        assert(css.contains("flex-direction: column;"))
    }

    "MediaQuery.and combines conditions" in {
        val q = MediaQuery.minWidth(768.px).and(MediaQuery.prefersDark)
        assert(q.condition == "(min-width: 768px) and (prefers-color-scheme: dark)")
    }

    "vars produces :root { --k: v; } block" in {
        val css = Stylesheet.vars("accent" -> "#3b82f6", "gap" -> "16px").render
        assert(css.contains(":root {"))
        assert(css.contains("--accent: #3b82f6;"))
        assert(css.contains("--gap: 16px;"))
    }

    "Color.variable renders as var(--name) in a rule" in {
        val css = Stylesheet.rule("x", Style.color(Style.Color.variable("accent"))).render
        assert(css.contains("color: var(--accent);"))
    }

    "fontFace renders @font-face block with family, src, and font-display: swap" in {
        val css = Stylesheet.fontFace(
            FontFace("Inter", Seq("/f/inter.woff2" -> "woff2"))
        ).render
        assert(css.contains("@font-face {"))
        assert(css.contains("font-family: \"Inter\""))
        assert(css.contains("url(\"/f/inter.woff2\") format(\"woff2\")"))
        assert(css.contains("font-display: swap"))
    }

    "entry order is preserved: rule A before media M before vars V" in {
        val css = (
            Stylesheet.rule("a", Style.row) ++
                Stylesheet.media(MediaQuery.raw("screen"))(Stylesheet.rule("b", Style.column)) ++
                Stylesheet.vars("x" -> "1")
        ).render
        val idxA = css.indexOf(".a {")
        val idxM = css.indexOf("@media screen")
        val idxV = css.indexOf(":root {")
        assert(idxA >= 0 && idxM >= 0 && idxV >= 0)
        assert(idxA < idxM && idxM < idxV)
    }

    "Stylesheet.empty.render is empty string; ++ identity laws hold" in {
        assert(Stylesheet.empty.render == "")
        val a = Stylesheet.rule("a", Style.row)
        assert((a ++ Stylesheet.empty) == a)
        assert((Stylesheet.empty ++ a) == a)
    }

    "Stylesheet and Entry derive CanEqual; equal sheets compare equal" in {
        val a = Stylesheet.rule("btn", Style.bg(Style.Color.blue))
        val b = Stylesheet.rule("btn", Style.bg(Style.Color.blue))
        assert(a == b)
        assert(Stylesheet.empty == Stylesheet.empty)
    }

    "runRenderPage with sheet.render: baseCss strictly before .feat-grid rule (INV-001)" in run {
        val sheet = Stylesheet.rule("feat-grid", Style.row)
        val css   = sheet.render
        val head  = UI.PageHead(title = "t", css = css)
        UI.runRenderPage(head)(UI.div).take(1).run.map { frames =>
            val html       = frames.headMaybe.getOrElse("")
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            val style      = html.substring(styleStart, styleEnd)
            val baseCssIdx = style.indexOf(UI.baseCss.take(30))
            val gridIdx    = style.indexOf(".feat-grid {")
            assert(baseCssIdx >= 0)
            assert(gridIdx >= 0)
            assert(baseCssIdx < gridIdx)
        }
    }

    "cssClass renders class attribute and coexists with style attribute" in run {
        val html = kyo.internal.HtmlRenderer.render(
            UI.div.cssClass("feat-grid").cssClass("dark").style(Style.bg(Style.Color.blue)),
            Seq.empty
        )
        html.map { s =>
            assert(s.contains("class=\"feat-grid dark\""))
            assert(s.contains("style="))
        }
    }

end StylesheetTest
