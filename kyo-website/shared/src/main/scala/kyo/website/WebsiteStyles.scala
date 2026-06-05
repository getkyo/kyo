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
  * docs 2-pane shell is a flex row with a fixed-width left rail and a growing content column. The
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
                "content-w"    -> "860px",
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
            ++ notFound
            ++ docsShell
            ++ docsSidebar
            ++ docsContent
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
            // Base header tag rule: reset the default flow positioning; the .site-header rule below
            // applies the sticky positioning with top+z-index explicitly.
            .rule(
                Selector.tag("header"),
                Style.position(_.flow).bg(_.variable("bg")).borderBottom(1.px, _.variable("line-soft"))
            )
            // Unified site header (G2): a full-bleed sticky bar whose inner row is capped at 1500px
            // (the docs-shell width) and centered, so the one header sits above both the 1120px
            // landing body and the 1500px docs 2-pane row without ever appearing narrower than the
            // docs content. position:sticky + top:0 + z-index:100 pin the bar at the viewport top on
            // scroll and layer it above page content (Position.sticky emits only position:sticky; the
            // offset and stacking are set explicitly so the docs rail can stick at its own top:60px
            // offset below this header). The .search-results dropdown uses Position.dropdown
            // (z-index:50) but is a descendant of .site-header so stacking-context rules ensure it
            // renders above siblings of the header, not below.
            .rule(
                "site-header",
                Style.column.width(Length.Pct(100))
                    .bg(_.variable("bg")).borderBottom(1.px, _.variable("line-soft"))
                    .position(_.sticky).top(0.px).zIndex(100)
            )
            .rule(
                "site-header-inner",
                Style.row.align(_.center).gap(24.px).height(60.px)
                    .maxWidth(1500.px).margin(0.px, Length.Auto).width(Length.Pct(100))
                    .padding(0.px, 24.px)
            )
            // The search-results dropdown: an absolutely-positioned panel anchored under the right
            // edge of the .right cluster (which is position: relative below). Always present for
            // SSG<->bundle hydration parity; empty at the empty query (no rows), so it reserves no
            // visible height. When populated it floats above the page content as a card.
            .rule(
                "search-results",
                Style.column.position(_.dropdown).gap(2.px)
                    .minWidth(320.px).maxHeight(420.px).overflow(_.auto)
                    .margin(8.px, 0.px, 0.px, 0.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(12.px)
                    .padding(6.px)
                    .shadow(0.px, 12.px, 32.px, 0.px, Color.rgba(20, 20, 15, 0.14))
            )
            .rule(
                "search-result",
                // align(_.start): the row is an <a> (a flex row in the baseline reset that inherits
                // align-items:center); flipping to column does not reset cross-axis alignment, so the
                // title + sub-label would center within the dropdown. Pin them to the left edge (B8).
                Style.column.align(_.start).gap(2.px)
                    .padding(8.px, 10.px).rounded(8.px).cursor(_.pointer)
                    .color(_.variable("ink")).textDecoration(_.none)
                    .hover(_.bg(_.variable("accent-ghost")))
            )
            // The keyboard-highlighted row (Arrow Up/Down): same affordance as a hover.
            .rule(
                "search-result-active",
                Style.bg(_.variable("accent-ghost"))
            )
            // The "No results" feedback row (non-empty query, zero hits): non-interactive, muted, no
            // hover affordance (B9).
            .rule(
                "search-no-results",
                Style.cursor(_.defaultCursor).color(_.variable("dim"))
                    .hover(_.bg(Color.transparent))
            )
            .rule(
                "search-result-title",
                Style.fontSize(14.px).fontWeight(_.w600).color(_.variable("ink"))
            )
            // The heading sub-label shown on a heading hit (the matched section under the module).
            .rule(
                "search-result-sub",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.5.px)
                    .color(_.variable("dim"))
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
                // position: relative so the absolutely-positioned .search-results dropdown anchors to
                // this cluster (under the search input) rather than the viewport.
                Style.row.align(_.center).gap(10.px).margin(0.px, 0.px, 0.px, Length.Auto)
                    .position(_.relative)
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
                // block (not the reset's flex-column): the headline mixes text, a <br>, and an inline
                // <span class="accent">, so as a flex column the words "holds" and the trailing "."
                // each dropped to their own centered line (the "." read as a stray lone dot under the
                // headline, B13). As a block the inline run flows naturally and the <br> still breaks
                // "Build with AI." from "Ship something that holds." where intended.
                Selector.cls("hero").descendant(Selector.tag("h1")),
                Style.block.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .letterSpacing(Length.Em(-0.018)).fontSize(64.px).lineHeight(1.02)
                    .margin(20.px, Length.Auto, 0.px, Length.Auto).maxWidth(720.px).textAlign(_.center)
                    .color(_.variable("ink")).textWrap(_.balance)
            )
            // the accent <span> inside the headline must flow inline within the block h1 (the reset
            // makes every span a flex row, which would push it onto its own line).
            .rule(
                Selector.cls("hero").descendant(Selector.tag("h1")).descendant(Selector.tag("span")),
                Style.inline
            )
            .rule(
                "lead",
                Style.fontSize(20.px).color(_.variable("dim")).maxWidth(620.px)
                    .margin(24.px, Length.Auto, 0.px, Length.Auto).lineHeight(1.55).textAlign(_.center).textWrap(_.pretty)
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
                    .margin(14.px, 0.px, 0.px, 0.px).color(_.variable("ink")).textWrap(_.balance)
            )
            .rule(
                Selector.cls("sec-head").descendant(Selector.tag("p")),
                Style.color(_.variable("dim")).fontSize(18.px).margin(18.px, 0.px, 0.px, 0.px).lineHeight(1.6).textWrap(_.pretty)
            )
            // problem section: centered head + centered body + stat
            .rule(
                "problem",
                Style.column.align(_.center)
            )
            .rule(
                Selector.cls("problem").descendant(Selector.tag("p")),
                Style.maxWidth(620.px).margin(26.px, Length.Auto, 0.px, Length.Auto).textAlign(_.center)
                    .fontSize(19.px).color(_.variable("dim")).lineHeight(1.66).textWrap(_.pretty)
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
            // A subtle indigo glow at the top of the dark band settling into the warm near-black,
            // approximating the radial accent glow of the reference with a vertical wash. Every stop is
            // OPAQUE and dark on purpose: an earlier version used semi-transparent indigo stops, which
            // composite over the cream page, so the top of the section rendered as light lavender while
            // the section's text stays light (white headings, darkDim body). That left the eyebrow,
            // heading, and intro paragraph light-on-light and unreadable until the fade reached the dark
            // end more than halfway down. Keeping every stop dark makes the light text legible from the
            // section's top edge. sRGB interpolation between these near-identical dark indigos avoids the
            // saturated purple midtone OKLCH swings through between a light and a dark color, and the
            // short luminance range keeps 8-bit banding below the visible threshold.
            .rule(
                "promise",
                Style.position(_.flow).overflow(_.hidden)
                    .bgGradient(
                        _.toBottom,
                        Style.GradientColorSpace.srgb,
                        (hex("#211D38"), Length.Pct(0)),
                        (hex("#1A1726"), Length.Pct(40)),
                        (inkSection, Length.Pct(82))
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
                    .fontSize(26.px).margin(12.px, 0.px, 0.px, 0.px).color(_.white).textWrap(_.balance)
            )
            .rule(
                Selector.cls("col").descendant(Selector.tag("p")),
                Style.margin(14.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(16.px).lineHeight(1.62).textWrap(_.pretty)
            )
            // The terminal CTA band carries a tighter, top/bottom-balanced vertical padding instead of
            // the generic 92px `.band` padding: the panel holds only a heading and two buttons, so the
            // full band padding left a tall empty slab of dark gradient below the buttons (the over-tall
            // CTA). 72px both sides sits the content centered in a panel sized to it.
            .rule(
                "cta-band",
                Style.padding(72.px, 0.px)
            )
            // final CTA
            .rule(
                "cta-final",
                Style.column.align(_.center).textAlign(_.center)
            )
            .rule(
                Selector.cls("cta-final").descendant(Selector.tag("h2")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(48.px).color(_.white).margin(0.px).letterSpacing(Length.Em(-0.014)).textWrap(_.balance)
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
                // 3-up flex grid. A flat width:33.33% plus the 1px gaps overflows the row (3*33.33% +
                // 2px > 100%), so the third card wrapped and the row painted only 2 cards with a wide
                // empty strip on the right (B7). flexBasis 30% + flexGrow 1 fits 3 cards per row and
                // grows them to share the full content width, gaps included.
                "cell",
                Style.column.flexBasis(Length.Pct(30)).flexGrow(1.0).bg(_.variable("bg")).padding(30.px, 26.px, 32.px, 26.px)
            )
            .rule(
                // A 2-line reserved min-height so cards whose heading wraps to one line and cards whose
                // heading wraps to two share one body baseline across the 3-up row (uneven heading heights
                // otherwise pushed each card's body to a different y). lineHeight 1.25 x 2 lines x 18.5px
                // ~= 46px. textWrap balance evens a 2-line heading's two lines.
                Selector.cls("cell").descendant(Selector.tag("h3")),
                Style.margin(0.px).fontSize(18.5.px).fontWeight(_.w600).letterSpacing(Length.Em(-0.01)).color(_.variable("ink"))
                    .lineHeight(1.25).minHeight(46.px).textWrap(_.balance)
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
                // Same 3-up flex fix as `cell` (B7): flexBasis 30% + flexGrow 1 so the six feature
                // categories pack 3 per row and fill the full content width instead of wrapping to 2.
                "fcat",
                Style.column.flexBasis(Length.Pct(30)).flexGrow(1.0).bg(_.variable("bg")).padding(26.px, 24.px, 28.px, 24.px)
            )
            .rule(
                // Reserve a min-height (2 lines) so the six feature-category headings keep one shared
                // baseline for their lists across the 3-up rows even when one title wraps and another does
                // not, matching the `.cell h3` reservation above.
                Selector.cls("fcat").descendant(Selector.tag("h4")),
                Style.row.align(_.center).gap(9.px)
                    .margin(0.px, 0.px, 16.px, 0.px).fontSize(16.px).fontWeight(_.w600).color(_.variable("ink"))
                    .lineHeight(1.3).minHeight(42.px)
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

    // ---- 404 page (B14) ----
    private def notFound: Stylesheet =
        Stylesheet.empty
            .rule(
                "notfound",
                Style.column.align(_.center).justify(_.center).gap(8.px).textAlign(_.center)
                    .padding(120.px, 28.px, 140.px, 28.px)
            )
            .rule(
                "notfound-code",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(15.px).fontWeight(_.w600)
                    .letterSpacing(0.18.em).color(_.variable("accent"))
            )
            .rule(
                "notfound-title",
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontSize(48.px).fontWeight(_.w500)
                    .letterSpacing(Length.Em(-0.018)).color(_.variable("ink")).margin(12.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "notfound-text",
                Style.fontSize(18.px).color(_.variable("dim")).maxWidth(480.px)
                    .margin(14.px, Length.Auto, 28.px, Length.Auto).lineHeight(1.6)
            )
    end notFound

    // ---- Docs: 2-pane shell ----
    private def docsShell: Stylesheet =
        Stylesheet.empty
            // The 2-pane row: a fixed-width left rail (.docs-sidebar) and a growing content column
            // (.docs-content, capped to a comfortable reading width). The right TOC pane is gone; its
            // content now nests in the rail under the active module.
            //
            // The shell is capped to the rail width (260px) + the article cap (860px) = 1120px and
            // centered (margin 0 auto), so on a wide viewport the rail+article group sits centered with
            // the leftover space split EVENLY on both sides. The old 1500px cap left the 820px article
            // pinned next to the rail with ~420px of dead space dumped on the right, making the page look
            // lopsided. 1120px also matches the landing `--wrap`, so the docs body lines up with the
            // landing body under the shared full-width header.
            .rule(
                "docs-shell",
                Style.row.align(_.start).flexWrap(_.noWrap)
                    .maxWidth(1120.px).margin(0.px, Length.Auto).width(Length.Pct(100))
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
            // The left rail FLOATS with the article: position:sticky pins it under the 60px sticky
            // header (top:60px, matching --header-h) once the page scrolls past, so a long article
            // scrolls while the nav stays in view. align-self:flex-start keeps the rail at its own
            // content height in the docs-shell flex row instead of stretching to the full article
            // height (a stretched item cannot stick). max-height:calc(100vh - 60px) caps the rail to
            // the viewport below the header, and overflow-y:auto lets a tall module list scroll WITHIN
            // the floating rail rather than off-screen. overflow-y (NOT the both-axis `overflow`)
            // is deliberate: the rail only ever overflows vertically, so enabling the horizontal axis
            // too would let the browser reserve/draw a horizontal scrollbar track along the rail bottom
            // (the stray horizontal bar) when a classic scrollbar OS shrinks the client box; scoping to
            // the vertical axis removes that bottom-edge bar while keeping the vertical scroll. Below
            // 860px the responsive block flips the rail to display:none (it becomes the toggle-revealed
            // drawer), where sticky no longer applies.
            .rule(
                "docs-sidebar",
                Style.column.width(260.px).flexShrink(0.0)
                    .position(_.sticky).top(60.px).alignSelf(_.start)
                    .maxHeight(Length.Calc("100vh - 60px")).overflowY(_.auto)
                    .bg(_.variable("bg")).borderRight(1.px, _.variable("line-soft"))
                    .padding(22.px, 14.px, 60.px, 24.px)
            )
            // sidebar-nav is a <nav>, which the base reset gives align-items:center; Style.column
            // flips the direction but leaves the center alignment, so each group would center at a
            // different x by its content width (the "misaligned menu"). align(_.stretch) makes every
            // group fill the sidebar width and share one left edge, and keeps the active-item
            // highlight full-width rather than shrinking it to the label.
            .rule(
                "sidebar-nav",
                Style.column.align(_.stretch).gap(4.px)
            )
            .rule(
                "sidebar-group",
                Style.column.align(_.stretch).gap(2.px).margin(0.px, 0.px, 20.px, 0.px)
            )
            .rule(
                "sidebar-group-name",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(10.5.px)
                    .letterSpacing(0.14.em).textTransform(_.uppercase).color(_.variable("faint"))
                    .padding(0.px, 8.px, 8.px, 8.px)
            )
            .rule(
                "nav-item",
                // A ~150ms ease transition so the hover/active background, color, and left bar fade in
                // smoothly rather than snapping. The hover is a SUBTLE accent tint (accent-ghost, the
                // .08 accent wash), NOT the page `surface` white: a white-on-warm-bg block read as an odd
                // pale slab. The tint sits clearly darker than the warm page bg and lighter than the
                // active .14 fill, so hover and active read as one accent family, and the label brightens
                // to full-strength `ink`.
                Style.row.align(_.center).gap(8.px)
                    .fontSize(13.5.px).lineHeight(1.3).color(_.variable("text-dim"))
                    .padding(7.px, 10.px).rounded(8.px).cursor(_.pointer)
                    .borderLeft(2.px, Color.transparent)
                    .transition(150, Style.Easing.ease)
                    .hover(_.color(_.variable("ink")).bg(_.variable("accent-ghost")))
            )
            // The active module is a COLUMN (not the base row): it stacks the module link above its
            // expanded in-page section outline (.sidebar-sections), so the sections read as a nested
            // tree beneath the link rather than sitting beside it. align(_.start) keeps the link and
            // the outline both pinned to the left edge.
            .rule(
                "nav-item-active",
                // A stronger highlight than the .08 accent-ghost hover tint: a .14 accent fill, a 3px
                // accent left bar, and a semibold label so the current module clearly reads as selected
                // rather than as a barely-there wash.
                Style.column.align(_.start)
                    .color(_.variable("accent")).bg(Color.rgba(78, 70, 224, 0.14)).fontWeight(_.w600)
                    .borderLeft(3.px, _.variable("accent"))
                    .transition(150, Style.Easing.ease)
            )
            // the inner anchor fills the row so the whole item is the link target. Scope to the DIRECT
            // anchor child so the nested section links (.sidebar-section, descendants of the same
            // .nav-item via the .sidebar-sections <ul>) are NOT swept up by this module-link rule and
            // keep their own dim color + left indent.
            .rule(
                Selector.cls("nav-item").child(Selector.tag("a")),
                Style.color(_.variable("text-dim")).width(Length.Pct(100)).textDecoration(_.none)
            )
            // The active module's nested in-page section list (the former right-TOC content, moved into
            // the rail). A <ul> is a flex element under the base reset that inherits align-items:center
            // from the <nav> ancestor; align(_.start) pins every section link to the rail's left edge
            // (fixing the old centered-TOC bug). A subtle left guide line + small top margin set the
            // outline apart from the module link above it; the slightly smaller dim text reads as
            // secondary navigation. Full width so the indented links share one left edge.
            .rule(
                "sidebar-sections",
                // When a module becomes active this outline appears; it ENTERS with a subtle slide-down
                // + fade (the `sidebar-sections-in` keyframes registered below): from opacity 0 +
                // translateY(-6px) to opacity 1 + translateY(0) over 200ms ease-out, `both` fill so it
                // never flashes the un-animated state. Tasteful and fast, no janky long sweep.
                Style.column.align(_.start).width(Length.Pct(100))
                    .gap(1.px).margin(6.px, 0.px, 2.px, 8.px)
                    .borderLeft(1.px, _.variable("accent-line"))
                    .animation("sidebar-sections-in", 200, Style.Easing.easeOut)
            )
            // A section link: clearly subordinate (smaller, dim, indented under the guide line), left-
            // aligned, no underline. Same ~150ms ease transition and the SAME subtle accent-ghost tint
            // on hover as the module rows above, so the whole rail hovers consistently (never a white
            // block) and the label brightens to ink. The rail is one level deep (only `## ` sections
            // render), so a single base indent suffices.
            .rule(
                "sidebar-section",
                Style.row.align(_.start).width(Length.Pct(100))
                    .fontSize(12.5.px).lineHeight(1.35).color(_.variable("muted"))
                    .padding(4.px, 8.px, 4.px, 12.px).rounded(6.px).textDecoration(_.none)
                    .transition(150, Style.Easing.ease)
                    .hover(_.color(_.variable("ink")).bg(_.variable("accent-ghost")))
            )
            // Mobile module-nav toggle (B6): a full-width disclosure button shown by default and
            // hidden on wide viewports by the >=861px media query (where the sidebar is always
            // visible). Clicking it flips the sidebar's `docs-sidebar-open` class, which the <860px
            // media query honors to override the sidebar's mobile `display:none`.
            .rule(
                "docs-nav-toggle",
                Style.row.align(_.center).justify(_.center).gap(8.px)
                    .width(Length.Pct(100)).margin(0.px, 0.px, 18.px, 0.px).padding(11.px, 16.px)
                    .border(1.px, _.variable("line")).rounded(10.px).bg(_.variable("surface"))
                    .color(_.variable("ink")).fontWeight(_.w600).fontSize(14.px).cursor(_.pointer)
                    .transition(150, Style.Easing.ease)
                    .hover(_.borderColor(_.variable("faint")))
            )
            // Entrance keyframes for the active module's .sidebar-sections outline: a subtle slide-down
            // + fade. Referenced by name from the .sidebar-sections rule's `animation` prop. Defined
            // once on the sheet; both the SSG (sheet.render in the page <head>) and the bundle
            // (UI.runStylesheet) emit the identical @keyframes block, so SSG/hydration CSS stays in
            // parity.
            .keyframes(
                "sidebar-sections-in",
                Stylesheet.Keyframe.from -> Style.opacity(0.0).translate(0.px, (-6).px),
                Stylesheet.Keyframe.to   -> Style.opacity(1.0).translate(0.px, 0.px)
            )
    end docsSidebar

    // ---- Docs: main content column ----
    private def docsContent: Stylesheet =
        Stylesheet.empty
            // The article column. With the right TOC pane gone, prose would stretch across the whole
            // remaining width and read poorly, so cap it to a comfortable measure (860px, which after the
            // 52px side padding leaves a ~756px text measure). The column flex-grows to claim its half of
            // the shell and the maxWidth holds the line length; because the shell itself is capped to
            // rail + this cap and centered, the article fills its column with no internal dead space and
            // the whole rail+article group is centered in the viewport.
            .rule(
                "docs-content",
                Style.column.flexGrow(1.0).flexBasis(0.px).minWidth(0.px)
                    .maxWidth(860.px)
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

    // ---- Docs: prose (content article), callouts, blockquote, tables ----
    private def docsProse: Stylesheet =
        Stylesheet.empty
            // Document flow for the markdown article. The kyo-ui base reset makes EVERY element
            // display:flex (block tags flex-column, inline tags flex-row), which shatters flowing
            // prose: a paragraph's inline code/links/emphasis stack as full-width rows, code-block
            // token spans stack vertically, and list items lose their markers. These rules opt the
            // article back into normal CSS flow: block-level boxes for paragraphs/headings/lists/
            // pre/tables, and inline boxes for the runs (links, spans, emphasis, inline code) so they
            // wrap WITHIN a line. The article lives inside .docs-content; descendant rules emitted
            // after the base reset already win the cascade, so display:inline overrides display:flex.
            // scroll-margin-top on every id-carrying heading (h1..h4): when a rail section link or a
            // native `#slug` anchor scrolls a heading into view, the browser (and scrollIntoView in
            // WebsiteBundleMain.scrollToHash) keeps 72px of room above it (the 60px sticky header plus a
            // 12px gap) so the heading lands fully visible just below the header instead of tucking under
            // it. Applied to all four heading levels because any of them can be an in-page anchor target.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h1")),
                Style.block.margin(0.px, 0.px, 16.px, 0.px).scrollMarginTop(72.px)
            )
            // article headings + paragraphs (the transpiled content lives inside docs-content).
            //
            // Vertical rhythm: .docs-content is a flex column, so child margins NEVER collapse (they
            // always add). The rhythm is therefore expressed on ONE side per block. Body blocks
            // (p/pre/table/callout/blockquote/ul/ol) carry a uniform 22px BOTTOM margin and zero top
            // margin, so any two consecutive blocks sit a calm 22px apart regardless of order (the old
            // top-margin-only scheme broke because a heading's 0 top margin left no gap after a code
            // block). Headings add a larger TOP margin to open a section break above them (stacking on
            // the preceding block's 22px gives ~40px) plus a small bottom margin so the heading stays
            // attached to the content it introduces.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h2")),
                Style.block.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontSize(32.px)
                    .fontWeight(_.w600).letterSpacing(Length.Em(-0.01)).lineHeight(1.16)
                    .margin(20.px, 0.px, 10.px, 0.px).color(_.variable("ink")).scrollMarginTop(72.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h3")),
                Style.block.fontSize(19.px).fontWeight(_.w700).letterSpacing(Length.Em(-0.005)).color(_.variable("text"))
                    .margin(16.px, 0.px, 6.px, 0.px).scrollMarginTop(72.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("h4")),
                Style.block.fontSize(16.px).fontWeight(_.w700).color(_.variable("text"))
                    .margin(12.px, 0.px, 4.px, 0.px).scrollMarginTop(72.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("p")),
                Style.block.color(_.variable("ink-prose")).margin(0.px, 0.px, 22.px, 0.px).fontSize(16.5.px).lineHeight(1.78)
            )
            // inline runs flow within a line of text (links, bold/italic spans, inline images)
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("a")),
                Style.inline.color(_.variable("accent")).fontWeight(_.w500)
                    .hover(_.underline)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("span")),
                Style.inline
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("strong")),
                Style.inline.fontWeight(_.w700).color(_.variable("ink"))
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("em")),
                Style.inline.fontStyle(_.italic)
            )
            // lists: ul/ol are block boxes that carry their markers via list-style-type (the reset
            // suppressed markers with list-style:none); li renders as a list-item so the bullet/number
            // shows. padding-left indents the markers and nests sublists one level deeper.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("ul")),
                Style.block.listStyle(_.disc).padding(0.px, 0.px, 0.px, 26.px).margin(0.px, 0.px, 22.px, 0.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("ol")),
                Style.block.listStyle(_.decimal).padding(0.px, 0.px, 0.px, 26.px).margin(0.px, 0.px, 22.px, 0.px)
            )
            // a nested list tightens its spacing under its parent item: a small top margin sets it
            // apart from the item text, and no bottom margin so it does not inherit the list's 22px
            // block gap inside the surrounding list.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("li")).descendant(Selector.tag("ul")),
                Style.margin(4.px, 0.px, 0.px, 0.px)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("li")),
                Style.listItem.color(_.variable("ink-prose")).lineHeight(1.7).fontSize(16.px)
                    .margin(4.px, 0.px, 0.px, 0.px)
            )
            // inline code in prose: an inline-block pill so its padding/border render while it still
            // flows within the sentence (a bare inline box would clip the vertical padding).
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("code")),
                Style.inlineBlock.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(0.84.em).color(_.variable("text"))
                    .bg(_.variable("accent-ghost")).border(1.px, _.variable("accent-line"))
                    .padding(1.px, 6.px).rounded(5.px)
            )
            // fenced code blocks: a block <pre> with white-space:pre (UA default) preserves newlines;
            // the inner <code> is block too, and its token spans flow inline so each source line reads
            // left-to-right with the line breaks intact.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")),
                Style.block.bg(_.variable("ink-section")).rounded(12.px).padding(18.px, 20.px)
                    .margin(0.px, 0.px, 22.px, 0.px).overflow(_.auto)
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")).descendant(Selector.tag("code")),
                Style.block.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(13.5.px).lineHeight(1.7)
                    .color(darkText).bg(Color.transparent).border(0.px, Color.transparent).padding(0.px)
            )
            // token spans inside a code block flow horizontally on each line (they inherit the
            // article span->inline rule, but the pre-code descendant rule pins it explicitly so the
            // tokens never revert to stacked flex children regardless of selector specificity).
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")).descendant(Selector.tag("span")),
                Style.inline
            )
            // tables: border-collapse:collapse merges adjacent cell edges so the per-cell borders
            // below render as single shared row + column dividers (B4). The previous separate-collapse
            // default left the cell borders invisible, so the table had only an outer frame and a
            // tinted header.
            //
            // display:table (not block) is load-bearing for the column width: the <table> keeps the CSS
            // table-layout algorithm so width:100% is distributed across the columns and the table fills
            // the article column. display:block instead made the table a 100%-wide block box whose
            // table-row/table-cell children fell into an anonymous shrink-to-fit table, collapsing the
            // grid to ~55% of the column with dead space on the right.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("table")),
                Style.table.borderCollapse(_.collapse).width(Length.Pct(100)).margin(0.px, 0.px, 22.px, 0.px)
                    .border(1.px, _.variable("line")).rounded(12.px).overflow(_.hidden).fontSize(14.px)
            )
            // every cell carries a right + bottom divider; collapse merges them with the neighbor's
            // left/top edge into one crisp line, giving real row and column separators.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("th")),
                Style.textAlign(_.left).padding(10.px, 14.px)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.08.em).textTransform(_.uppercase).color(_.variable("faint"))
                    .bg(_.variable("bg")).borderStyle(_.solid).borderBottom(1.px, _.variable("line"))
                    .borderRight(1.px, _.variable("line"))
            )
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("td")),
                // borderStyle:solid is REQUIRED: borderTop/borderRight set width+color only, and CSS
                // renders a width as 0 unless border-style is non-none, so without this the dividers
                // were invisible (B4).
                Style.padding(10.px, 14.px).color(_.variable("ink-prose")).borderStyle(_.solid)
                    .borderTop(1.px, _.variable("line")).borderRight(1.px, _.variable("line"))
            )
            // callouts: `callout` is the base; `callout-note`/`callout-caution` set the accent edge.
            // Bottom-margin rhythm (22px) matches the other body blocks; var(--text) is the normal body
            // text color so the note/caution body reads at full strength rather than the muted ink-prose
            // tone it carried before.
            .rule(
                "callout",
                Style.row.align(_.start).gap(14.px).margin(0.px, 0.px, 22.px, 0.px).padding(16.px, 18.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line-soft"))
                    .borderLeft(2.px, _.variable("accent")).rounded(10.px)
                    .fontSize(15.px).color(_.variable("text")).lineHeight(1.62)
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
                Style.column.margin(0.px, 0.px, 22.px, 0.px).padding(8.px, 18.px)
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
            // On narrow viewports the unified header wraps to two rows so all nav targets stay
            // reachable (AF-3). Row 1: brand + nav links. Row 2: search + version + CTA.
            // .site-header-inner drops its fixed 60px height and gains flex-wrap; .right takes the
            // full width of the second row (ml-auto still pushes it right on wide viewports).
            // .btn-ghost (the GitHub icon-only button used elsewhere) is hidden on small screens
            // because the text "GitHub" link in .links already covers the target.
            .media(Stylesheet.MediaQuery.maxWidth(820.px))(
                Stylesheet.empty
                    .rule("site-header-inner", Style.flexWrap(_.wrap).height(Length.Auto).padding(8.px, 16.px).gap(8.px))
                    .rule("links", Style.gap(14.px).margin(0.px))
                    // Second row: search grows to fill, version pill + CTA stay on one line. The search
                    // input flex-grows (min-width 0 so it can shrink), and the version/CTA controls
                    // shrink their padding + text so "v1.0.0-RC2" and "Get started" each stay single-line
                    // instead of wrapping to two cramped rows (B11). textWrap(noWrap) keeps the CTA label
                    // intact; flexShrink(0) keeps the pill + CTA from being squeezed below their text.
                    .rule("right", Style.width(Length.Pct(100)).margin(0.px, 0.px, 8.px, 0.px).gap(8.px).align(_.center))
                    .rule("search-input", Style.flexGrow(1.0).minWidth(0.px).padding(7.px, 10.px))
                    .rule("ver", Style.flexShrink(0.0).padding(7.px, 8.px).gap(4.px))
                    .rule("btn", Style.flexShrink(0.0).padding(9.px, 14.px).textWrap(_.noWrap))
                    .rule("btn-ghost", Style.displayNone)
            )
            // outcome + feature grids collapse: 2-up at 880px, 1-up at 560px. Cards are flex items
            // sized by flexBasis (B7), so the narrow-viewport overrides set flexBasis too (a width
            // override would be ignored while flexBasis stays at the 3-up 30%).
            .media(Stylesheet.MediaQuery.maxWidth(880.px))(
                Stylesheet.empty
                    .rule("fcat", Style.flexBasis(Length.Pct(45)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(900.px))(
                Stylesheet.empty
                    .rule("cell", Style.flexBasis(Length.Pct(100)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(560.px))(
                Stylesheet.empty
                    .rule("fcat", Style.flexBasis(Length.Pct(100)))
                    .rule("stat", Style.column.align(_.center).textAlign(_.center).gap(14.px))
            )
            // docs 2-pane is side-by-side on wide viewports (rail + content); below 860px the rail
            // collapses into a toggle-revealed drawer (handled by the <860px block further down).
            .media(Stylesheet.MediaQuery.minWidth(1024.px))(
                Stylesheet.empty.rule("docs-shell", Style.row.align(_.start).flexWrap(_.noWrap))
            )
            // Wide viewports show the sidebar inline and have no need for the disclosure button.
            .media(Stylesheet.MediaQuery.minWidth(861.px))(
                Stylesheet.empty
                    .rule("docs-nav-toggle", Style.displayNone)
            )
            // Narrow viewports: the sidebar is collapsed by default; the docs-nav-toggle button reveals
            // it (B6). `docs-sidebar-open` is declared AFTER `docs-sidebar` so, when both classes are
            // present (open state), its `display:block` wins the equal-specificity cascade and overrides
            // the closed `display:none`, turning the sidebar into a full-width drawer above the article.
            .media(Stylesheet.MediaQuery.maxWidth(860.px))(
                Stylesheet.empty
                    // The shell wraps so the full-width toggle + revealed drawer stack ABOVE the content
                    // instead of sharing the row and squishing the article into a sliver (B6).
                    .rule("docs-shell", Style.row.flexWrap(_.wrap))
                    .rule("docs-sidebar", Style.displayNone.width(Length.Pct(100)))
                    // The revealed drawer is a normal full-width block above the article, NOT a sticky
                    // float: reset position back to static and top to 0 (overriding the wide-viewport
                    // position:sticky/top:60px on the base rule) and lift the calc max-height to a tall
                    // value, so the drawer expands inline to its content rather than pinning and
                    // scrolling within itself on a phone.
                    .rule(
                        "docs-sidebar-open",
                        Style.block.position(_.flow).top(0.px).width(Length.Pct(100)).maxHeight(Length.Px(10000))
                            .margin(0.px, 0.px, 12.px, 0.px).borderRight(0.px, Color.transparent)
                            .border(1.px, _.variable("line")).rounded(12.px).padding(16.px, 16.px, 20.px, 16.px)
                    )
                    // flex-basis 100% so the content always claims a full row of its own under the toggle;
                    // maxWidth back to 100% so the narrow article fills its row instead of keeping the
                    // wide-viewport 820px reading cap (which would leave dead space on a phone).
                    .rule("docs-content", Style.flexBasis(Length.Pct(100)).maxWidth(Length.Pct(100)).padding(28.px, 22.px, 80.px, 22.px))
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
