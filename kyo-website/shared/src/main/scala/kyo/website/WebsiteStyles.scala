// PUBLIC site stylesheet
package kyo.website

import kyo.*

/** The kyo website's global stylesheet, authored as a kyo-ui `Stylesheet` value.
  *
  * Every class name here corresponds to a `cssClass` call in the UI components (`LandingApp`,
  * `DocsApp`, and `DocsMarkdown`). The sheet is rendered to a CSS string and placed in
  * `PageHead.css` by `WebsitePage.wrap`; the same value is injected client-side via
  * `UI.runStylesheet` by the bundle entry. No raw CSS string is used anywhere in the site
  * (INV-012). `WebsiteStylesCoverageTest` renders both apps and asserts that every emitted class
  * has a matching rule here, so a future unstyled class fails the build.
  *
  * The palette, gradients, font stacks, spacing, and breakpoints port the reference design
  * (`kyo-landing.html` for the landing surface, `docs.html` for the docs shell).
  *
  * Layout model: kyo-ui's baseline reset gives every element `display: flex` (block-level tags
  * lay out as a column, inline tags as a row), so the sheet expresses layout through the flex
  * setters (`row`, `column`, `align`, `justify`, `gap`, `flexWrap`, `flexGrow`, `flexBasis`,
  * `width`) rather than CSS Grid. Multi-column card grids are `flex-wrap` + percentage widths; the
  * docs 3-pane shell is a flex row with fixed sidebar/TOC widths and a growing content column. The
  * reference's purely decorative `radial-gradient` glow overlays (drawn via `::before`
  * pseudo-elements) are not expressible through `Style` (no pseudo-element content, `bgGradient`
  * is linear-only); the dark sections instead carry the solid dark background and light text that
  * those overlays sit on top of, which is the load-bearing part of the look.
  */
object WebsiteStyles:

    // `lazy` so the helper `val`s below (color literals, `eyebrowStyle`) are initialized before
    // `buildSheet` reads them; an eager `val` here would run during object init while those fields
    // are still null.
    lazy val sheet: Stylesheet = buildSheet

    // ---- Reusable color literals (hex factory returns Maybe; resolve once) ----
    import Style.Color
    private def hex(s: String): Color = Color.hex(s).getOrElse(Color.transparent)

    // Dark-section palette (from kyo-landing.html --ink-section and the on-dark text tones)
    private val inkSection    = hex("#16150F")
    private val darkText      = hex("#F4F1EA")
    private val darkDim       = hex("#B9B4A8")
    private val darkAccentTxt = hex("#8E88E8")
    private val whiteBorder12 = Color.rgba(255, 255, 255, 0.12)
    private val whiteBorder14 = Color.rgba(255, 255, 255, 0.14)
    private val whiteBorder16 = Color.rgba(255, 255, 255, 0.16)
    private val whiteFill05   = Color.rgba(255, 255, 255, 0.05)
    private val whiteFill06   = Color.rgba(255, 255, 255, 0.06)
    private val whiteFill12   = Color.rgba(255, 255, 255, 0.12)
    private val shadowSoft    = Color.rgba(20, 20, 15, 0.05)

    private def buildSheet: Stylesheet =
        Stylesheet.empty
            // CSS custom properties (palette + typography tokens from kyo-landing.html / docs.html)
            .vars(
                "bg"           -> "#FAF8F4",
                "surface"      -> "#FFFFFF",
                "ink"          -> "#16150F",
                "dim"          -> "#56534A",
                "faint"        -> "#8C887C",
                "line"         -> "#E8E3D9",
                "line-soft"    -> "#F0ECE3",
                "accent"       -> "#4E46E0",
                "accent-deep"  -> "#332CB8",
                "accent-ghost" -> "rgba(78,70,224,.08)",
                "accent-line"  -> "rgba(78,70,224,.15)",
                "amber"        -> "#C98A2B",
                "jade"         -> "#2EA87E",
                "muted"        -> "#6B7280",
                "text"         -> "#16150F",
                "text-dim"     -> "#56534A",
                "ink-prose"    -> "#2D2C28",
                "ink-section"  -> "#16150F",
                "serif"        -> "\"Newsreader\", Georgia, \"Times New Roman\", serif",
                "sans"         -> "\"Inter\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif",
                "mono"         -> "\"JetBrains Mono\", ui-monospace, SFMono-Regular, Menlo, monospace",
                "radius"       -> "16px",
                "radius-sm"    -> "10px",
                "wrap"         -> "1120px",
                "sidebar-w"    -> "260px",
                "toc-w"        -> "220px",
                "header-h"     -> "60px"
            )
            ++ baseTypography
            ++ landingChrome
            ++ landingHero
            ++ landingSections
            ++ landingDark
            ++ landingGrids
            ++ landingPlatforms
            ++ landingFooter
            ++ docsShell
            ++ docsSidebar
            ++ docsContent
            ++ docsToc
            ++ docsProse
            ++ docsTokens
            ++ responsive
    end buildSheet

    // ---- Base typography: apply the Inter sans stack and base color to the page root ----
    private def baseTypography: Stylesheet =
        Stylesheet.empty
            .rule(
                Selector.tag("body"),
                Style.bg(_.variable("bg")).color(_.variable("ink"))
                    .fontFamily(Style.FontFamily.Custom("var(--sans)"))
                    .fontSize(17.px).lineHeight(1.6)
            )
            // Layout wrapper
            .rule(
                "wrap",
                Style.maxWidth(1120.px).margin(Length.Auto, Length.Auto).padding(0.px, 28.px).width(Length.Pct(100))
            )
            // Inline helpers
            .rule("eyebrow", eyebrowStyle)
            .rule("serif", Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).color(_.variable("accent")).italic.bold)
            .rule("accent", Style.color(_.variable("accent")))
    end baseTypography

    private val eyebrowStyle: Style =
        Style.fontFamily(Style.FontFamily.Custom("var(--mono)"))
            .fontSize(12.px).letterSpacing(0.14.em).textTransform(_.uppercase)
            .color(_.variable("accent")).fontWeight(_.w500)

    // ---- Landing: header / nav / buttons / version control ----
    private def landingChrome: Stylesheet =
        Stylesheet.empty
            // sticky header
            .rule(
                Selector.tag("header"),
                Style.position(_.flow).bg(_.variable("bg")).borderBottom(1.px, _.variable("line-soft"))
            )
            // Unified site header (G2): a full-bleed bar whose inner row is capped at 1500px (the
            // docs-shell width) and centered, so the one header sits above both the 1120px landing
            // body and the 1500px docs 3-pane without ever appearing narrower than the docs content.
            .rule(
                "site-header",
                Style.column.width(Length.Pct(100))
                    .bg(_.variable("bg")).borderBottom(1.px, _.variable("line-soft"))
            )
            .rule(
                "site-header-inner",
                Style.row.align(_.center).gap(24.px).height(60.px)
                    .maxWidth(1500.px).margin(0.px, Length.Auto).width(Length.Pct(100))
                    .padding(0.px, 24.px)
            )
            // The inert search-results region (always present for SSG<->bundle hydration parity).
            // Empty at the empty query; the populated dropdown styling lands in the search-wiring
            // phase. Kept off the normal flow so an empty container does not reserve header height.
            .rule(
                "search-results",
                Style.column.position(_.overlay)
            )
            .rule(
                "brand",
                Style.row.align(_.center).gap(10.px)
                    .color(_.variable("ink")).fontWeight(_.w600).fontSize(19.px)
                    .letterSpacing(Length.Em(-0.01)).textDecoration(_.none)
            )
            .rule("mark", Style.width(28.px).height(28.px))
            .rule(
                "links",
                Style.row.align(_.center).gap(26.px).margin(0.px, 0.px, 0.px, 14.px)
            )
            .rule(
                Selector.cls("links").descendant(Selector.tag("a")),
                Style.color(_.variable("dim")).fontSize(15.px).fontWeight(_.w500)
                    .hover(_.color(_.variable("ink")))
            )
            .rule(
                "right",
                Style.row.align(_.center).gap(10.px).margin(0.px, 0.px, 0.px, Length.Auto)
            )
            // buttons
            .rule(
                "btn",
                Style.row.align(_.center).gap(8.px)
                    .fontWeight(_.w600).fontSize(15.px)
                    .padding(12.px, 20.px).rounded(10.px)
                    .border(1.px, _.variable("line"))
                    .bg(_.variable("surface")).color(_.variable("ink"))
                    .cursor(_.pointer)
                    .hover(_.translate(0.px, Length.Px(-1)).shadow(0.px, 6.px, 22.px, 0.px, Color.rgba(20, 20, 15, 0.07)))
            )
            .rule(
                "btn-primary",
                Style.bg(_.variable("accent")).borderColor(_.variable("accent")).color(_.white)
                    .hover(_.bg(_.variable("accent-deep")).borderColor(_.variable("accent-deep")).color(_.white))
            )
            .rule(
                "btn-ghost",
                Style.bg(Color.transparent).borderColor(Color.transparent).color(_.variable("dim"))
                    .padding(12.px, 8.px)
                    .hover(_.color(_.variable("ink")))
            )
            // version control button (the `ver` dropdown trigger)
            .rule(
                "ver",
                Style.row.align(_.center).gap(6.px)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.5.px).fontWeight(_.w500)
                    .color(_.variable("dim"))
                    .border(1.px, _.variable("line")).rounded(8.px).padding(8.px, 11.px)
                    .bg(_.variable("surface")).cursor(_.pointer)
                    .hover(_.color(_.variable("ink")).borderColor(_.variable("faint")))
            )
    end landingChrome

    // ---- Landing: hero ----
    private def landingHero: Stylesheet =
        Stylesheet.empty
            .rule(
                "hero",
                Style.column.align(_.center).textAlign(_.center)
                    .padding(96.px, 0.px, 84.px, 0.px)
                    .position(_.flow).overflow(_.hidden)
            )
            .rule(
                Selector.cls("hero").descendant(Selector.tag("h1")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .letterSpacing(Length.Em(-0.018)).fontSize(64.px).lineHeight(1.02)
                    .margin(20.px, Length.Auto, 0.px, Length.Auto).maxWidth(720.px).textAlign(_.center)
                    .color(_.variable("ink"))
            )
            .rule(
                "lead",
                Style.fontSize(20.px).color(_.variable("dim")).maxWidth(620.px)
                    .margin(24.px, Length.Auto, 0.px, Length.Auto).lineHeight(1.55).textAlign(_.center)
            )
            .rule(
                "hero-cta",
                Style.row.justify(_.center).align(_.center).gap(12.px)
                    .margin(34.px, 0.px, 0.px, 0.px).flexWrap(_.wrap)
            )
            .rule(
                "trust",
                Style.row.justify(_.center).align(_.center).gap(22.px).flexWrap(_.wrap)
                    .margin(34.px, 0.px, 0.px, 0.px)
                    .fontSize(13.5.px).color(_.variable("faint"))
                    .fontFamily(Style.FontFamily.Custom("var(--mono)"))
            )
            .rule(
                Selector.cls("trust").descendant(Selector.tag("span")),
                Style.row.align(_.center).gap(8.px)
            )
            // stat callout
            .rule(
                "stat",
                Style.row.align(_.center).gap(26.px).textAlign(_.left)
                    .maxWidth(680.px).margin(42.px, Length.Auto, 0.px, Length.Auto)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(16.px)
                    .padding(28.px, 30.px)
                    .shadow(0.px, 6.px, 24.px, 0.px, shadowSoft)
            )
            .rule(
                "big",
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(62.px).lineHeight(1.0).color(_.variable("accent"))
                    .letterSpacing(Length.Em(-0.02)).flexShrink(0.0).row.align(_.start)
            )
            .rule(
                "stat-txt",
                Style.fontSize(15.5.px).color(_.variable("dim")).lineHeight(1.6)
            )
    end landingHero

    // ---- Landing: generic sections, problem, control row, depth ----
    private def landingSections: Stylesheet =
        Stylesheet.empty
            .rule("band", Style.column.padding(92.px, 0.px))
            .rule("build", Style.borderTop(1.px, _.variable("line-soft")))
            .rule(
                "sec-head",
                Style.column.maxWidth(420.px)
            )
            .rule(
                "center",
                Style.maxWidth(640.px).margin(0.px, Length.Auto).textAlign(_.center).align(_.center)
            )
            .rule(
                Selector.cls("sec-head").descendant(Selector.tag("h2")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .letterSpacing(Length.Em(-0.014)).fontSize(40.px).lineHeight(1.08)
                    .margin(14.px, 0.px, 0.px, 0.px).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("sec-head").descendant(Selector.tag("p")),
                Style.color(_.variable("dim")).fontSize(18.px).margin(18.px, 0.px, 0.px, 0.px).lineHeight(1.6)
            )
            // problem section: centered head + centered body + stat
            .rule(
                "problem",
                Style.column.align(_.center)
            )
            .rule(
                Selector.cls("problem").descendant(Selector.tag("p")),
                Style.maxWidth(620.px).margin(26.px, Length.Auto, 0.px, Length.Auto).textAlign(_.center)
                    .fontSize(19.px).color(_.variable("dim")).lineHeight(1.66)
            )
            // control / visibility row
            .rule(
                "ctrl-row",
                Style.row.flexWrap(_.wrap).gap(22.px).margin(46.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "ctrl",
                Style.column.flexGrow(1.0).flexBasis(280.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(16.px)
                    .padding(28.px, 26.px)
            )
            .rule(
                Selector.cls("ctrl").descendant(Selector.tag("h4")),
                Style.margin(0.px).fontSize(16.5.px).fontWeight(_.w600).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("ctrl").descendant(Selector.tag("p")),
                Style.margin(9.px, 0.px, 0.px, 0.px).fontSize(14.5.px).color(_.variable("dim")).lineHeight(1.55)
            )
            // depth section
            .rule(
                "depth",
                Style.bg(_.variable("surface")).borderTop(1.px, _.variable("line")).borderBottom(1.px, _.variable("line"))
            )
            .rule(
                "inner",
                Style.row.flexWrap(_.wrap).align(_.start).gap(54.px)
            )
            .rule(
                Selector.cls("depth").descendant(Selector.tag("p")),
                Style.fontSize(17.px).color(_.variable("dim")).lineHeight(1.7).margin(0.px, 0.px, 18.px, 0.px)
            )
            .rule(
                "plat",
                Style.row.flexWrap(_.wrap).gap(10.px).margin(26.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "t",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.5.px).color(_.variable("dim"))
                    .border(1.px, _.variable("line")).rounded(8.px).padding(7.px, 12.px)
            )
    end landingSections

    // ---- Landing: dark sections (promise, final CTA) ----
    private def landingDark: Stylesheet =
        Stylesheet.empty
            .rule(
                "dark",
                Style.bg(inkSection).color(darkText)
            )
            .rule(
                Selector.cls("dark").descendant(Selector.cls("sec-head")).descendant(Selector.tag("h2")),
                Style.color(_.white)
            )
            .rule(
                Selector.cls("dark").descendant(Selector.cls("sec-head")).descendant(Selector.tag("p")),
                Style.color(darkDim)
            )
            // approximate the accent glow overlay (radial in the reference) with a subtle vertical wash
            .rule(
                "promise",
                Style.position(_.flow).overflow(_.hidden)
                    .bgGradient(
                        _.toBottom,
                        (Color.rgba(78, 70, 224, 0.16), Length.Pct(0)),
                        (inkSection, Length.Pct(55))
                    )
            )
            // two-column predictability/reliability block
            .rule(
                "two",
                Style.row.flexWrap(_.wrap).gap(1.px)
                    .bg(whiteBorder12).border(1.px, whiteBorder14).rounded(16.px).overflow(_.hidden)
                    .margin(50.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "col",
                Style.column.flexGrow(1.0).flexBasis(320.px)
                    .padding(38.px, 34.px).bg(inkSection)
            )
            .rule(
                "k",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.px)
                    .letterSpacing(0.1.em).textTransform(_.uppercase).color(darkAccentTxt)
            )
            .rule(
                Selector.cls("col").descendant(Selector.tag("h3")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(26.px).margin(12.px, 0.px, 0.px, 0.px).color(_.white)
            )
            .rule(
                Selector.cls("col").descendant(Selector.tag("p")),
                Style.margin(14.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(16.px).lineHeight(1.62)
            )
            // final CTA
            .rule(
                "cta-final",
                Style.column.align(_.center).textAlign(_.center)
            )
            .rule(
                Selector.cls("cta-final").descendant(Selector.tag("h2")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(48.px).color(_.white).margin(0.px).letterSpacing(Length.Em(-0.014))
            )
            .rule(
                Selector.cls("cta-final").descendant(Selector.cls("hero-cta")),
                Style.margin(30.px, 0.px, 0.px, 0.px)
            )
            // on-dark button treatment
            .rule(
                Selector.cls("on-dark").descendant(Selector.cls("btn")),
                Style.bg(whiteFill06).borderColor(whiteBorder16).color(_.white)
                    .hover(_.bg(whiteFill12))
            )
            .rule(
                Selector.cls("on-dark").descendant(Selector.cls("btn-primary")),
                Style.bg(_.white).borderColor(_.white).color(_.variable("ink"))
                    .hover(_.bg(hex("#EFEDE6")))
            )
    end landingDark

    // ---- Landing: outcome grid + feature grid (flex-wrap multi-column) ----
    private def landingGrids: Stylesheet =
        Stylesheet.empty
            // outcome grid (6 cells)
            .rule(
                "grid",
                Style.row.flexWrap(_.wrap).gap(1.px)
                    .bg(_.variable("line")).border(1.px, _.variable("line")).rounded(16.px).overflow(_.hidden)
                    .margin(48.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "cell",
                Style.column.width(Length.Pct(33.33)).bg(_.variable("bg")).padding(30.px, 26.px, 32.px, 26.px)
            )
            .rule(
                Selector.cls("cell").descendant(Selector.tag("h3")),
                Style.margin(0.px).fontSize(18.5.px).fontWeight(_.w600).letterSpacing(Length.Em(-0.01)).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("cell").descendant(Selector.tag("p")),
                Style.margin(10.px, 0.px, 0.px, 0.px).fontSize(15.px).color(_.variable("dim")).lineHeight(1.58)
            )
            .rule(
                "by",
                Style.row.align(_.center).gap(7.px)
                    .margin(16.px, 0.px, 0.px, 0.px)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.5.px).color(_.variable("faint"))
            )
            .rule(
                "honest",
                Style.maxWidth(700.px).margin(32.px, Length.Auto, 0.px, Length.Auto).textAlign(_.center)
                    .color(_.variable("dim")).fontSize(15.5.px).lineHeight(1.62)
            )
            // feature overview grid (6 categories)
            .rule(
                "feat-grid",
                Style.row.flexWrap(_.wrap).gap(1.px)
                    .bg(_.variable("line")).border(1.px, _.variable("line")).rounded(16.px).overflow(_.hidden)
                    .margin(46.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "fcat",
                Style.column.width(Length.Pct(33.33)).bg(_.variable("bg")).padding(26.px, 24.px, 28.px, 24.px)
            )
            .rule(
                Selector.cls("fcat").descendant(Selector.tag("h4")),
                Style.row.align(_.center).gap(9.px)
                    .margin(0.px, 0.px, 16.px, 0.px).fontSize(16.px).fontWeight(_.w600).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("fcat").descendant(Selector.tag("ul")),
                Style.column.gap(10.px).margin(0.px).padding(0.px)
            )
            .rule(
                Selector.cls("fcat").descendant(Selector.tag("li")),
                Style.fontSize(14.5.px).color(_.variable("dim")).lineHeight(1.4)
                    .padding(0.px, 0.px, 0.px, 17.px).position(_.flow)
            )
    end landingGrids

    // ---- Landing: platforms band (dark inset card) ----
    private def landingPlatforms: Stylesheet =
        Stylesheet.empty
            .rule(
                "platforms",
                Style.column.margin(48.px, 0.px, 0.px, 0.px)
                    .bg(inkSection).rounded(16.px).padding(44.px, 40.px).color(darkText)
                    .position(_.flow).overflow(_.hidden)
            )
            .rule(
                "pf-head",
                Style.column.maxWidth(560.px)
            )
            .rule(
                Selector.cls("pf-head").descendant(Selector.tag("h3")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(30.px).margin(0.px).color(_.white).letterSpacing(Length.Em(-0.01))
            )
            .rule(
                Selector.cls("pf-head").descendant(Selector.tag("p")),
                Style.margin(13.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(16.px).lineHeight(1.6)
            )
            .rule(
                "pf-cards",
                Style.row.flexWrap(_.wrap).gap(16.px).margin(32.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "pf",
                Style.column.flexGrow(1.0).flexBasis(220.px)
                    .bg(whiteFill05).border(1.px, whiteBorder12).rounded(12.px).padding(22.px, 20.px)
            )
            .rule(
                "pf-k",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.12.em).textTransform(_.uppercase).color(darkAccentTxt)
            )
            .rule(
                "pf-n",
                Style.fontSize(19.px).fontWeight(_.w600).color(_.white).margin(7.px, 0.px, 0.px, 0.px)
            )
            .rule(
                Selector.cls("pf").descendant(Selector.tag("p")),
                Style.margin(13.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(13.5.px).lineHeight(1.55)
            )
    end landingPlatforms

    // ---- Landing: footer ----
    private def landingFooter: Stylesheet =
        Stylesheet.empty
            .rule(
                Selector.tag("footer"),
                Style.column.padding(64.px, 0.px, 56.px, 0.px).borderTop(1.px, _.variable("line-soft"))
            )
            .rule(
                "foot",
                Style.row.flexWrap(_.wrap).gap(34.px)
            )
            .rule(
                Selector.cls("foot").descendant(Selector.tag("h5")),
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.14.em).textTransform(_.uppercase).color(_.variable("faint"))
                    .margin(0.px, 0.px, 14.px, 0.px)
            )
            .rule(
                Selector.cls("foot").descendant(Selector.tag("a")),
                Style.color(_.variable("dim")).fontSize(14.5.px).padding(5.px, 0.px)
                    .hover(_.color(_.variable("accent")))
            )
            .rule(
                "note",
                Style.color(_.variable("faint")).fontSize(14.px).maxWidth(320.px)
                    .margin(14.px, 0.px, 0.px, 0.px).lineHeight(1.6)
            )
            .rule(
                "foot-bottom",
                Style.row.justify(_.spaceBetween).align(_.center).gap(14.px).flexWrap(_.wrap)
                    .margin(44.px, 0.px, 0.px, 0.px).padding(22.px, 0.px, 0.px, 0.px)
                    .borderTop(1.px, _.variable("line-soft"))
                    .fontSize(13.px).color(_.variable("faint"))
            )
    end landingFooter

    // ---- Docs: 3-pane shell ----
    private def docsShell: Stylesheet =
        Stylesheet.empty
            .rule(
                "docs-shell",
                Style.row.align(_.start).flexWrap(_.noWrap)
                    .maxWidth(1500.px).margin(0.px, Length.Auto).width(Length.Pct(100))
            )
            // The header search box (rendered by SiteApp). The dead docs-header chrome
            // (.docs-header/.docs-header-right/.docs-nav) is folded into the unified .site-header*
            // and .links rules, so those rules are removed here to keep the sheet honest.
            .rule(
                "search-input",
                Style.fontFamily(Style.FontFamily.Custom("var(--sans)")).fontSize(13.5.px)
                    .padding(8.px, 12.px).border(1.px, _.variable("line")).rounded(10.px)
                    .bg(_.variable("surface")).color(_.variable("ink"))
                    .focus(_.borderColor(_.variable("accent")))
            )
    end docsShell

    // ---- Docs: left sidebar ----
    private def docsSidebar: Stylesheet =
        Stylesheet.empty
            .rule(
                "docs-sidebar",
                Style.column.width(260.px).flexShrink(0.0)
                    .height(Length.Pct(100)).maxHeight(Length.Px(10000)).overflow(_.auto)
                    .bg(_.variable("bg")).borderRight(1.px, _.variable("line-soft"))
                    .padding(22.px, 14.px, 60.px, 24.px)
            )
            .rule(
                "sidebar-nav",
                Style.column.gap(4.px)
            )
            .rule(
                "sidebar-group",
                Style.column.gap(2.px).margin(0.px, 0.px, 20.px, 0.px)
            )
            .rule(
                "sidebar-group-name",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(10.5.px)
                    .letterSpacing(0.14.em).textTransform(_.uppercase).color(_.variable("faint"))
                    .padding(0.px, 8.px, 8.px, 8.px)
            )
            .rule(
                "nav-item",
                Style.row.align(_.center).gap(8.px)
                    .fontSize(13.5.px).lineHeight(1.3).color(_.variable("text-dim"))
                    .padding(7.px, 10.px).rounded(10.px).cursor(_.pointer)
                    .borderLeft(2.px, Color.transparent)
                    .hover(_.color(_.variable("text")).bg(_.variable("surface")))
            )
            .rule(
                "nav-item-active",
                Style.color(_.variable("accent")).bg(_.variable("accent-ghost"))
                    .borderLeft(2.px, _.variable("accent"))
            )
            // the inner anchor fills the row so the whole item is the link target
            .rule(
                Selector.cls("nav-item").descendant(Selector.tag("a")),
                Style.color(_.variable("text-dim")).width(Length.Pct(100)).textDecoration(_.none)
            )
    end docsSidebar

    // ---- Docs: main content column ----
    private def docsContent: Stylesheet =
        Stylesheet.empty
            .rule(
                "docs-content",
                Style.column.flexGrow(1.0).flexBasis(0.px).minWidth(0.px)
                    .overflow(_.auto).padding(40.px, 52.px, 90.px, 52.px)
            )
            // prev/next footer (two boxes)
            .rule(
                "prev-next",
                Style.row.justify(_.spaceBetween).align(_.center).gap(14.px)
                    .margin(56.px, 0.px, 0.px, 0.px)
            )
            .rule(
                Selector.cls("prev-next").descendant(Selector.tag("a")),
                Style.flexGrow(1.0).flexBasis(0.px)
                    .border(1.px, _.variable("line-soft")).rounded(16.px).padding(16.px, 18.px)
                    .color(_.variable("text")).fontSize(15.px).fontWeight(_.w500).cursor(_.pointer)
                    .hover(_.borderColor(_.variable("accent-line")))
            )
            .rule(
                "prev-next-disabled",
                Style.flexGrow(1.0).flexBasis(0.px)
                    .border(1.px, _.variable("line-soft")).rounded(16.px).padding(16.px, 18.px)
                    .color(_.variable("faint"))
            )
    end docsContent

    // ---- Docs: right table-of-contents ----
    private def docsToc: Stylesheet =
        Stylesheet.empty
            .rule(
                "docs-toc",
                Style.column.width(220.px).flexShrink(0.0)
                    .height(Length.Pct(100)).maxHeight(Length.Px(10000)).overflow(_.auto)
                    .padding(40.px, 24.px, 60.px, 8.px)
            )
            .rule(
                "toc-nav",
                Style.column.gap(2.px)
            )
            .rule(
                "toc-item",
                Style.row.align(_.center).fontSize(13.px).color(_.variable("muted"))
                    .padding(5.px, 0.px, 5.px, 12.px).borderLeft(1.px, _.variable("line-soft"))
                    .lineHeight(1.4)
                    .hover(_.color(_.variable("text")))
            )
            .rule(
                Selector.cls("toc-item").descendant(Selector.tag("a")),
                Style.color(_.variable("muted")).fontSize(13.px).width(Length.Pct(100)).textDecoration(_.none)
                    .hover(_.color(_.variable("text")))
            )
            .rule("toc-h1", Style.padding(5.px, 0.px, 5.px, 12.px))
            .rule("toc-h2", Style.padding(5.px, 0.px, 5.px, 12.px))
            .rule("toc-h3", Style.padding(5.px, 0.px, 5.px, 24.px))
            .rule("toc-h4", Style.padding(5.px, 0.px, 5.px, 36.px))
            .rule("sub", Style.fontSize(12.5.px).color(_.variable("faint")))
    end docsToc

    // ---- Docs: prose (content article), callouts, blockquote, tables ----
    private def docsProse: Stylesheet =
        Stylesheet.empty
            // article headings + paragraphs (the transpiled content lives inside docs-content)
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h2")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontSize(32.px)
                    .fontWeight(_.w600).letterSpacing(Length.Em(-0.01)).lineHeight(1.16)
                    .margin(0.px, 0.px, 4.px, 0.px).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h3")),
                Style.fontSize(19.px).fontWeight(_.w700).letterSpacing(Length.Em(-0.005)).color(_.variable("text"))
                    .margin(42.px, 0.px, 0.px, 0.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("p")),
                Style.color(_.variable("ink-prose")).margin(17.px, 0.px, 0.px, 0.px).fontSize(16.5.px).lineHeight(1.78)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("a")),
                Style.color(_.variable("accent")).fontWeight(_.w500)
                    .hover(_.underline)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("li")),
                Style.color(_.variable("ink-prose")).lineHeight(1.7).fontSize(16.px)
                    .padding(0.px, 0.px, 0.px, 24.px).position(_.flow)
            )
            // inline code in prose
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("code")),
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(0.84.em).color(_.variable("text"))
                    .bg(_.variable("accent-ghost")).border(1.px, _.variable("accent-line"))
                    .padding(1.px, 6.px).rounded(5.px)
            )
            // fenced code blocks
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")),
                Style.column.bg(_.variable("ink-section")).rounded(12.px).padding(18.px, 20.px)
                    .margin(24.px, 0.px, 0.px, 0.px).overflow(_.auto)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")).descendant(Selector.tag("code")),
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(13.5.px).lineHeight(1.7)
                    .color(darkText).bg(Color.transparent).border(0.px, Color.transparent).padding(0.px)
            )
            // tables
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("table")),
                Style.margin(20.px, 0.px, 0.px, 0.px)
                    .border(1.px, _.variable("line-soft")).rounded(12.px).overflow(_.hidden).fontSize(14.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("th")),
                Style.textAlign(_.left).padding(10.px, 14.px)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.08.em).textTransform(_.uppercase).color(_.variable("faint"))
                    .bg(_.variable("bg")).borderBottom(1.px, _.variable("line-soft"))
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("td")),
                Style.padding(10.px, 14.px).color(_.variable("ink-prose"))
                    .borderTop(1.px, _.variable("line-soft"))
            )
            // callouts: `callout` is the base; `callout-note`/`callout-caution` set the accent edge
            .rule(
                "callout",
                Style.row.align(_.start).gap(14.px).margin(24.px, 0.px, 0.px, 0.px).padding(16.px, 18.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line-soft"))
                    .borderLeft(2.px, _.variable("accent")).rounded(10.px)
                    .fontSize(15.px).color(_.variable("ink-prose")).lineHeight(1.62)
            )
            .rule(
                "callout-note",
                Style.borderLeft(2.px, _.variable("accent"))
            )
            .rule(
                "callout-caution",
                Style.borderLeft(2.px, _.variable("amber"))
            )
            // generic blockquote
            .rule(
                "blockquote",
                Style.column.margin(24.px, 0.px, 0.px, 0.px).padding(8.px, 18.px)
                    .borderLeft(3.px, _.variable("line")).color(_.variable("dim")).fontStyle(_.italic)
            )
            // bold inline run
            .rule(
                "md-strong",
                Style.fontWeight(_.w700).color(_.variable("ink"))
            )
            // version banner on older docs
            .rule(
                "version-banner",
                Style.row.align(_.center).gap(10.px).padding(9.px, 24.px)
                    .bg(_.variable("accent-ghost")).borderBottom(1.px, _.variable("accent-line"))
                    .color(_.variable("text")).fontSize(13.5.px)
            )
    end docsProse

    // ---- Docs: syntax-highlight token colors ----
    private def docsTokens: Stylesheet =
        Stylesheet.empty
            .rule("tok-keyword", Style.color(hex("#C792EA")))
            .rule("tok-string", Style.color(hex("#C3E88D")))
            .rule("tok-comment", Style.color(hex("#7E8AA0")).italic)
            .rule("tok-type", Style.color(hex("#82AAFF")))
            .rule("tok-number", Style.color(hex("#F78C6C")))
            .rule("tok-literal", Style.color(hex("#FF5370")))
    end docsTokens

    // ---- Responsive breakpoints ----
    private def responsive: Stylesheet =
        Stylesheet.empty
            // hide landing nav links + ghost button on narrow viewports
            .media(Stylesheet.MediaQuery.maxWidth(820.px))(
                Stylesheet.empty
                    .rule("links", Style.displayNone)
                    .rule("btn-ghost", Style.displayNone)
            )
            // outcome + feature grids collapse: 2-up at 880px, 1-up at 560px
            .media(Stylesheet.MediaQuery.maxWidth(880.px))(
                Stylesheet.empty
                    .rule("fcat", Style.width(Length.Pct(50)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(900.px))(
                Stylesheet.empty
                    .rule("cell", Style.width(Length.Pct(100)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(560.px))(
                Stylesheet.empty
                    .rule("fcat", Style.width(Length.Pct(100)))
                    .rule("stat", Style.column.align(_.center).textAlign(_.center).gap(14.px))
            )
            // docs 3-pane is side-by-side only on wide viewports; below, collapse the TOC then sidebar
            .media(Stylesheet.MediaQuery.minWidth(1024.px))(
                Stylesheet.empty.rule("docs-shell", Style.row.align(_.start).flexWrap(_.noWrap))
            )
            .media(Stylesheet.MediaQuery.maxWidth(1100.px))(
                Stylesheet.empty.rule("docs-toc", Style.displayNone)
            )
            .media(Stylesheet.MediaQuery.maxWidth(860.px))(
                Stylesheet.empty
                    .rule("docs-sidebar", Style.displayNone)
                    .rule("docs-content", Style.padding(28.px, 22.px, 80.px, 22.px))
            )
            // dark-mode palette override
            .media(Stylesheet.MediaQuery.prefersDark)(
                Stylesheet.empty.vars(
                    "bg"      -> "#1A1917",
                    "surface" -> "#242320",
                    "ink"     -> "#F0ECE3",
                    "dim"     -> "#A09A90",
                    "faint"   -> "#6B6760",
                    "line"    -> "#2E2C28"
                )
            )
    end responsive

end WebsiteStyles
