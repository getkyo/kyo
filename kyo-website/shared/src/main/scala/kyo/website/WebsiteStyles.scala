// PUBLIC site stylesheet
package kyo.website

import kyo.*

/** The kyo website's global stylesheet, authored as a kyo-ui `Stylesheet` value.
  *
  * Every class name here corresponds to a `cssClass` call in the UI components (`LandingApp`,
  * `DocsApp`, and `DocsMarkdown`). The sheet is rendered to a CSS string and placed in
  * `PageHead.css` by `WebsitePage.wrap`; the same value is injected client-side via
  * `UI.runStylesheet` by the bundle entry. No raw CSS string is used anywhere in the site
  * `WebsiteStylesCoverageTest` renders both apps and asserts that every emitted class
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

    // The themeable color tokens, one entry per token in each theme. `lightVars` is the :root default;
    // `darkVars` (same keys) is applied both by the `prefers-color-scheme: dark` media block (the OS
    // default, in `responsive`) and by the explicit `data-theme` toggle (in `themeOverrides`).
    // `--btn`/`--btn-deep` are the solid button fill, kept separate from `--accent` so the button stays
    // dark enough for white label text while `--accent` (links, emphasis) lightens in dark mode for
    // contrast. The intentionally-dark surfaces (the landing "foundation" sections, the fenced code
    // blocks) read `var(--ink-section)`, which elevates slightly above the page in dark mode.
    private val lightVars: Seq[(String, String)] = Seq(
        "bg"               -> "#FAF8F4",
        "surface"          -> "#FFFFFF",
        "ink"              -> "#16150F",
        "dim"              -> "#56534A",
        "faint"            -> "#8C887C",
        "line"             -> "#E8E3D9",
        "line-soft"        -> "#F0ECE3",
        "scroll-thumb"     -> "rgba(0,0,0,.18)",
        "scroll-thumb-hov" -> "rgba(0,0,0,.34)",
        "accent"           -> "#4E46E0",
        "btn"              -> "#4E46E0",
        "btn-deep"         -> "#332CB8",
        "accent-ghost"     -> "rgba(78,70,224,.08)",
        "accent-line"      -> "rgba(78,70,224,.15)",
        "amber"            -> "#C98A2B",
        "red"              -> "#D23B36",
        "jade"             -> "#2EA87E",
        "muted"            -> "#6B7280",
        "text"             -> "#16150F",
        "text-dim"         -> "#56534A",
        "ink-prose"        -> "#2D2C28",
        "ink-section"      -> "#16150F"
    )

    private val darkVars: Seq[(String, String)] = Seq(
        "bg"               -> "#14130D",
        "surface"          -> "#1D1B14",
        "ink"              -> "#F4F1EA",
        "dim"              -> "#B6B1A5",
        "faint"            -> "#8C887C",
        "line"             -> "rgba(255,255,255,.10)",
        "line-soft"        -> "rgba(255,255,255,.055)",
        "scroll-thumb"     -> "rgba(255,255,255,.18)",
        "scroll-thumb-hov" -> "rgba(255,255,255,.36)",
        "accent"           -> "#9D97F0",
        "btn"              -> "#6E66E8",
        "btn-deep"         -> "#5A52DC",
        "accent-ghost"     -> "rgba(157,151,240,.13)",
        "accent-line"      -> "rgba(157,151,240,.24)",
        "amber"            -> "#E0A94A",
        "red"              -> "#F26A60",
        "jade"             -> "#5BC79B",
        "muted"            -> "#9A968B",
        "text"             -> "#E9E5DC",
        "text-dim"         -> "#B6B1A5",
        "ink-prose"        -> "#D6D2C8",
        "ink-section"      -> "#1C1A13"
    )

    // Theme-independent tokens (fonts, radii, widths) shared by every theme.
    private val baseTokens: Seq[(String, String)] = Seq(
        "serif"     -> "\"Newsreader\", Georgia, \"Times New Roman\", serif",
        "sans"      -> "\"Inter\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif",
        "mono"      -> "\"JetBrains Mono\", ui-monospace, SFMono-Regular, Menlo, monospace",
        "radius"    -> "16px",
        "radius-sm" -> "10px",
        "wrap"      -> "1120px",
        "sidebar-w" -> "260px",
        "content-w" -> "860px",
        "header-h"  -> "60px"
    )

    // Explicit theme overrides for the nav toggle: a `data-theme` attribute on <html> forces a theme,
    // overriding the OS `prefers-color-scheme` default. Emitted LAST in the sheet so that, at equal
    // specificity with the media block's `:root`, source order makes the explicit choice win.
    private def themeOverrides: Stylesheet =
        Stylesheet.empty
            .scopedVars(Selector.data("theme", "dark"), darkVars*)
            .scopedVars(Selector.data("theme", "light"), lightVars*)

    // Webkit/Blink custom scrollbar for one scroll surface: a slim floating pill thumb (a transparent
    // border plus background-clip:padding-box insets the painted thumb inside its track) over a
    // transparent track, brightening on hover. Chrome and Safari render this and ignore the standard
    // scrollbar-width/scrollbar-color; Firefox ignores `::-webkit-*` and keeps the thin themed
    // scrollbar-color fallback set on the element itself. One helper themes every scroll surface alike.
    private def polishedScrollbar(target: Selector): Stylesheet =
        Stylesheet.empty
            .rule(target.pseudoElement("-webkit-scrollbar"), Style.width(10.px).height(10.px))
            .rule(target.pseudoElement("-webkit-scrollbar-track"), Style.bg(Color.transparent))
            .rule(
                target.pseudoElement("-webkit-scrollbar-thumb"),
                Style.bg(Color.variable("scroll-thumb")).rounded(99.px)
                    .border(2.px, Color.transparent).backgroundClip(_.paddingBox)
                    .hover(_.bg(Color.variable("scroll-thumb-hov")))
            )

    private def buildSheet: Stylesheet =
        Stylesheet.empty
            // CSS custom properties: the light palette plus the theme-independent tokens.
            .vars((lightVars ++ baseTokens)*)
            ++ baseTypography
            ++ landingChrome
            ++ landingHero
            ++ landingSections
            ++ landingDark
            ++ landingGrids
            ++ landingPlatforms
            ++ landingFooter
            // landingLadder is appended after the other landing sheets so its `.fcat` link treatment,
            // `.dark .honest` override, and the ladder/code/tag/pull/floor/whyx rules win the
            // equal-specificity cascade where they refine an earlier rule.
            ++ landingLadder
            ++ notFound
            ++ docsShell
            ++ docsSidebar
            ++ docsContent
            ++ docsProse
            ++ docsTokens
            ++ themeToggle
            // Polished custom scrollbars on the three scroll surfaces: the page, the docs rail, and a
            // fenced code panel that scrolls a long line horizontally. The landing `.code` panels get a
            // dedicated light-thumb scrollbar (see landingLadder) because they are a fixed dark surface
            // on which the page-theme `--scroll-thumb` would be invisible.
            ++ polishedScrollbar(Selector.tag("html"))
            ++ polishedScrollbar(Selector.cls("docs-sidebar"))
            ++ polishedScrollbar(Selector.cls("docs-content").descendant(Selector.tag("pre")))
            ++ responsive
            ++ themeOverrides
    end buildSheet

    // ---- Base typography: apply the Inter sans stack and base color to the page root ----
    private def baseTypography: Stylesheet =
        Stylesheet.empty
            // Reserve the page scrollbar gutter on the document scroll root so a route swap that grows or
            // shrinks the document past the viewport never toggles the classic scrollbar and shifts the
            // whole layout sideways (the left-rail "jump"). A no-op on overlay-scrollbar systems, where the
            // gutter is zero-width. The same thin, themed scrollbar the sidebar uses applies here so the
            // page scrollbar matches the rail rather than falling back to the chunky OS default.
            .rule(
                Selector.tag("html"),
                // both-edges reserves the scrollbar gutter on BOTH sides so centered content stays
                // centered relative to the viewport (with a one-edge gutter, every centered block sits
                // ~half the scrollbar width left of true center).
                Style.scrollbarGutter(_.stableBothEdges)
                    .scrollbarWidth(_.thin).scrollbarColor(Color.variable("scroll-thumb"), Color.transparent)
            )
            .rule(
                Selector.tag("body"),
                // Ligatures OFF for the whole document (the property inherits, so this reaches every code
                // panel and inline chip). The mono font (JetBrains Mono) fuses `--`, `<!--`, `-->`, `->`
                // into dash/arrow glyphs by default, which misrepresents literal source in the docs (a
                // doctest README shows `<!-- doctest:default -->` markers that must read as exact ASCII).
                Style.bg(_.variable("bg")).color(_.variable("ink"))
                    .fontFamily(Style.FontFamily.Custom("var(--sans)"))
                    .fontVariantLigatures(_.none)
                    .fontSize(17.px).lineHeight(1.6)
            )
            // Layout wrapper
            .rule(
                "wrap",
                Style.maxWidth(1120.px).margin(Length.Auto, Length.Auto).padding(0.px, 28.px).width(Length.Pct(100))
            )
            // Inline helpers
            .rule("eyebrow", eyebrowStyle)
            // On a dark band the light-surface accent indigo drops below the contrast floor, so the section
            // eyebrow switches to the lightened periwinkle every other dark-surface label already uses.
            .rule(Selector.cls("dark").descendant(Selector.cls("eyebrow")), Style.color(darkAccentTxt))
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
            // Unified site header (G2): a full-bleed sticky bar whose inner row is capped at 1120px and
            // centered, matching both the landing `.wrap` and the `.docs-shell` (both 1120px) so the
            // brand and nav share the exact left/right edge of the content beneath them on every route.
            // position:sticky + top:0 + z-index:100 pin the bar at the viewport top on
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
                    .maxWidth(1120.px).margin(0.px, Length.Auto).width(Length.Pct(100))
                    .padding(0.px, 28.px)
            )
            // The search-results dropdown: an absolutely-positioned panel anchored under the search
            // input via its .search-wrap container (position: relative; see that rule below). Always
            // present for SSG<->bundle hydration parity; empty at the empty query (no rows), so it
            // reserves no visible height. When populated it floats above the page content as a card.
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
                // title + sub-label would center within the dropdown. Pin them to the left edge.
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
            // hover affordance.
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
                // The header's right cluster. The search dropdown anchors to the inner .search-wrap (its
                // own positioned context), not here; relative is kept so the cluster is a stable
                // positioning root for any future overlay it owns.
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
                Style.bg(_.variable("btn")).borderColor(_.variable("btn")).color(_.white)
                    .hover(_.bg(_.variable("btn-deep")).borderColor(_.variable("btn-deep")).color(_.white))
            )
            .rule(
                "btn-ghost",
                Style.bg(Color.transparent).borderColor(Color.transparent).color(_.variable("dim"))
                    .padding(12.px, 8.px)
                    .hover(_.color(_.variable("ink")))
            )
            // The version selector is a native <select>: it expands with the browser's own option
            // list (no JS toggle, so it works identically on the static site and in the bundle). The
            // pill chrome lives directly on the control; the native chevron and option popup keep
            // their platform rendering. flexShrink(0) keeps the pill from being squeezed below its
            // text when the header row is tight.
            .rule(
                "ver",
                Style.flexShrink(0.0)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.5.px).fontWeight(_.w500)
                    .color(_.variable("dim"))
                    .border(1.px, _.variable("line")).rounded(8.px).padding(8.px, 28.px, 8.px, 11.px)
                    .bg(_.variable("surface")).cursor(_.pointer)
                    .hover(_.color(_.variable("ink")).borderColor(_.variable("faint")))
            )
            // The rendered options inherit the page text color so they stay readable in both themes
            // (some platforms paint the option popup with the control's color rather than the OS default).
            .rule(
                Selector.cls("ver").descendant(Selector.tag("option")),
                Style.color(_.variable("ink")).bg(_.variable("surface"))
            )
    end landingChrome

    // ---- Landing: hero ----
    private def landingHero: Stylesheet =
        Stylesheet.empty
            .rule(
                "hero",
                Style.column.align(_.center).textAlign(_.center)
                    .padding(74.px, 0.px, 56.px, 0.px)
                    .position(_.relative).overflow(_.hidden)
            )
            // The decorative arc backdrop, anchored to the top-right corner and clipped by the hero's
            // overflow. Low opacity so it reads as a quiet layer behind the content; z-index 0 keeps it
            // under the content (which gets z-index 1 below), so it never intercepts a click.
            .rule(
                "hero-bg",
                Style.position(_.absolute).top((-70).px).right((-80).px).zIndex(0).opacity(0.1)
            )
            .rule(
                Selector.cls("hero").descendant(Selector.cls("wrap")),
                Style.position(_.relative).zIndex(1)
            )
            .rule(
                // block (not the reset's flex-column): the headline mixes text, a <br>, and an inline
                // <span class="accent">, so as a flex column the words "holds" and the trailing "."
                // each dropped to their own centered line (the "." read as a stray lone dot under the
                // headline). As a block the inline run flows naturally and the <br> still breaks
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
                Style.row.justify(_.center).align(_.center).gap(10.px).flexWrap(_.wrap)
                    .margin(34.px, 0.px, 0.px, 0.px)
                    .fontSize(13.5.px).color(_.variable("faint"))
                    .fontFamily(Style.FontFamily.Custom("var(--mono)"))
            )
            // One trust item: a plain inline run carrying its leading `·` separator (glued to the label by a
            // non-breaking space in LandingApp). The `.trust` row wraps between whole items, so the middot
            // never dangles at the end of a wrapped line.
            .rule("trust-item", Style.inline)
            // stat callout: a chart card that stacks the compounding-failure chart on top of the
            // caption plus explanatory text.
            .rule(
                "stat",
                Style.column.gap(18.px).textAlign(_.left)
                    .maxWidth(680.px).margin(42.px, Length.Auto, 0.px, Length.Auto)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(16.px)
                    .padding(24.px, 26.px)
                    .shadow(0.px, 6.px, 24.px, 0.px, shadowSoft)
            )
            .rule("stat-chart", Style.width(Length.Pct(100)).minWidth(0.px))
            .rule(
                Selector.cls("stat-chart").descendant(Selector.tag("svg")),
                Style.display(_.block).width(Length.Pct(100)).height(Length.Auto)
            )
            .rule("stat-body", Style.column.gap(11.px))
            .rule(
                "stat-cap",
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500)
                    .fontSize(21.px).lineHeight(1.15).color(_.variable("ink"))
                    .letterSpacing(Length.Em(-0.01))
            )
            .rule(
                "stat-txt",
                Style.fontSize(15.5.px).color(_.variable("dim")).lineHeight(1.6)
            )
    end landingHero

    // ---- Landing: generic sections, problem, control row, depth ----
    private def landingSections: Stylesheet =
        Stylesheet.empty
            .rule("band", Style.column.padding(66.px, 0.px))
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
            // problem (gap) section: a centered column band that holds the two-column gap-grid (chart card
            // + left-aligned heading/text). The `.gap-text p` rule re-lefts the paragraph that the centered
            // `.problem p` base below would otherwise center.
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
                Style.bg(Color.rgba(255, 255, 255, 0.08)).borderColor(Color.rgba(255, 255, 255, 0.3)).color(_.white)
                    .hover(_.bg(whiteFill12))
            )
            .rule(
                Selector.cls("on-dark").descendant(Selector.cls("btn-primary")),
                // The button fill is always white (it sits on the always-dark CTA band), so its label
                // must be a FIXED dark. `ink` is theme-variable and flips light in dark mode, which made
                // this white-on-white in dark mode.
                Style.bg(_.white).borderColor(_.white).color(inkSection)
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
                // empty strip on the right. flexBasis 30% + flexGrow 1 fits 3 cards per row and
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
                // Same 3-up flex fix as `cell`: flexBasis 30% + flexGrow 1 so the six feature
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
            // The category glyph sits above the title in the brand accent (the SVG draws in currentColor).
            // Size it 1:1 with its 24-unit viewBox so the scale factor is exactly the device-pixel ratio and
            // the strokes stay on the pixel grid; a fractional size (e.g. 26px on a 24 viewBox) blurs them.
            .rule("fcat-ic", Style.block.color(_.variable("accent")).margin(0.px, 0.px, 14.px, 0.px))
            .rule(
                Selector.cls("fcat-ic").descendant(Selector.tag("svg")),
                Style.display(_.block).width(24.px).height(24.px)
            )
            // Adoption band: four entry-point cards, packed 4-up on wide screens (flexBasis ~22% + flexGrow
            // 1, the same fill rule as `.fcat`), wrapping to a tidy 2x2 at mid widths and a column on narrow
            // ones (handled by the responsive blocks below).
            .rule(
                "paths",
                Style.row.flexWrap(_.wrap).gap(16.px).margin(46.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "path",
                Style.column.flexBasis(Length.Pct(22)).flexGrow(1.0).minWidth(200.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(16.px)
                    .padding(24.px, 22.px, 26.px, 22.px)
            )
            .rule(
                Selector.cls("path").descendant(Selector.tag("h4")),
                Style.margin(0.px).fontSize(16.5.px).fontWeight(_.w600).color(_.variable("ink")).lineHeight(1.3)
            )
            .rule(
                Selector.cls("path").descendant(Selector.tag("p")),
                Style.margin(11.px, 0.px, 0.px, 0.px).fontSize(14.5.px).color(_.variable("dim")).lineHeight(1.55).flexGrow(1.0)
            )
            // The receipt chip on a path card sits on the light band, so it takes the page-theme
            // tokens (the ladder `.tag` is dark-band styled and would vanish here).
            .rule(
                Selector.cls("path").descendant(Selector.cls("tags")),
                Style.row.flexWrap(_.wrap).gap(8.px).margin(16.px, 0.px, 0.px, 0.px)
            )
            .rule(
                Selector.cls("path").descendant(Selector.cls("tag")),
                Style.row.align(_.center).fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.px)
                    .color(_.variable("dim")).bg(_.variable("bg")).border(1.px, _.variable("line")).rounded(7.px)
                    .padding(4.px, 10.px).textDecoration(_.none)
                    .hover(_.color(_.variable("ink")).borderColor(_.variable("accent")))
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
            // The "one codebase" SOURCE BOX: a small dark editor-style panel centered above the cards. It is
            // the head of the connector; the `.pf-connect` lines fan down from its center to the four cards.
            .rule("pf-source", Style.column.align(_.center).gap(8.px).margin(34.px, 0.px, 0.px, 0.px))
            .rule(
                "pf-source-box",
                Style.block.width(120.px).maxWidth(Length.Pct(100))
                    .bg(hex("#0E0D14")).border(1.px, whiteBorder12).rounded(10.px).padding(6.px)
            )
            .rule(
                Selector.cls("pf-source-box").descendant(Selector.tag("svg")),
                Style.display(_.block).width(Length.Pct(100)).height(Length.Auto)
            )
            .rule(
                "pf-source-cap",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.12.em).textTransform(_.uppercase).color(darkAccentTxt)
            )
            // The connector: a full-width strip carrying the SVG whose four lines fan from the source box
            // (50%) down to the four card centers (12.5% / 37.5% / 62.5% / 87.5%). The SVG stretches to the
            // strip via preserveAspectRatio=none + width 100%, so the x-fractions land on the card centers at
            // any width. A fixed height keeps the fan a consistent depth between the box and the cards.
            .rule("pf-connect", Style.block.width(Length.Pct(100)).height(52.px).margin(2.px, 0.px, 0.px, 0.px))
            .rule(
                Selector.cls("pf-connect").descendant(Selector.tag("svg")),
                Style.display(_.block).width(Length.Pct(100)).height(Length.Pct(100))
            )
            .rule(
                "pf-cards",
                Style.row.flexWrap(_.wrap).gap(16.px).margin(2.px, 0.px, 0.px, 0.px)
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
                Style.row.flexWrap(_.wrap).gap(34.px).justify(_.spaceBetween)
            )
            .rule(
                Selector.cls("foot").descendant(Selector.tag("h5")),
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.14.em).textTransform(_.uppercase).color(_.variable("dim"))
                    .margin(0.px, 0.px, 14.px, 0.px)
            )
            .rule(
                Selector.cls("foot").descendant(Selector.tag("a")),
                Style.color(_.variable("dim")).fontSize(14.5.px).padding(5.px, 0.px)
                    .hover(_.color(_.variable("accent")))
            )
            // A link carrying a brand glyph (GitHub, Discord) becomes a tight inline-flex row so the
            // filled mark sits on the text baseline; the glyph inherits the link color (currentColor)
            // and follows the hover state with it. Used in the footer and the nav chrome.
            .rule(
                "soc",
                Style.display(_.inlineFlex).align(_.center).gap(7.px)
            )
            .rule(
                "brand-ic",
                Style.display(_.inlineFlex).align(_.center).flexShrink(0.0)
            )
            .rule(
                Selector.cls("brand-ic").descendant(Selector.tag("svg")),
                Style.display(_.block).width(16.px).height(16.px)
            )
            .rule(
                "note",
                Style.color(_.variable("dim")).fontSize(14.px).maxWidth(320.px)
                    .margin(14.px, 0.px, 0.px, 0.px).lineHeight(1.6)
            )
            .rule(
                "foot-bottom",
                Style.row.justify(_.spaceBetween).align(_.center).gap(14.px).flexWrap(_.wrap)
                    .margin(44.px, 0.px, 0.px, 0.px).padding(22.px, 0.px, 0.px, 0.px)
                    .borderTop(1.px, _.variable("line-soft"))
                    .fontSize(13.px).color(_.variable("dim"))
            )
    end landingFooter

    // ---- Landing: the ladder (dark band), its code receipts, module tags, agent block, pull quote;
    // the platforms floor paragraph; the quiet why-this-exists band; and the
    // feature categories as links. Appended last so its cascade refinements win. ----
    private def landingLadder: Stylesheet =
        Stylesheet.empty
            // the dark ladder band shares the promise band's indigo-into-near-black wash
            .rule(
                "ladder",
                Style.position(_.flow).overflow(_.hidden)
                    .bgGradient(
                        _.toBottom,
                        Style.GradientColorSpace.srgb,
                        (hex("#211D38"), Length.Pct(0)),
                        (hex("#1A1726"), Length.Pct(34)),
                        (inkSection, Length.Pct(72))
                    )
            )
            // width 100% + min-width 0: as a flex item the `margin: auto` horizontal centering would
            // otherwise let `.rungs` size to its max-content (the non-wrapping code <pre>, ~580px) and
            // overflow a phone, clipped by the band's overflow:hidden. width 100% pins it to the column
            // (capped by max-width on wide screens); min-width 0 lets the inner `.code` scroll instead of
            // forcing the rung wide.
            .rule(
                "rungs",
                Style.column.width(Length.Pct(100)).minWidth(0.px).maxWidth(900.px).margin(40.px, Length.Auto, 0.px, Length.Auto)
                    .position(_.relative).padding(0.px, 0.px, 0.px, 38.px).borderLeft(2.px, whiteBorder12)
            )
            .rule(
                "rung",
                Style.row.flexWrap(_.wrap).gap(26.px).padding(30.px, 4.px).borderTop(1.px, whiteBorder12).position(_.relative)
            )
            // The numbered node sits on the `.rungs` left rail (38px gutter), at the beat's level, so the
            // four rungs read as an ordered layered-safety ladder.
            .rule(
                "rung-node",
                Style.row.align(_.center).justify(_.center)
                    .position(_.absolute).left((-49).px).top(26.px).width(26.px).height(26.px).rounded(99.px)
                    .bg(inkSection).border(2.px, darkAccentTxt).color(darkAccentTxt)
                    .fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.px).fontWeight(_.w600)
            )
            .rule(
                "beat",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.px)
                    .letterSpacing(0.08.em).textTransform(_.uppercase).color(darkAccentTxt)
                    .flexBasis(150.px).flexShrink(0.0)
            )
            .rule("rung-body", Style.column.flexGrow(1.0).flexBasis(380.px).minWidth(0.px))
            .rule(
                "rung-lead",
                Style.fontFamily(Style.FontFamily.Custom("var(--sans)")).fontSize(19.px).fontWeight(_.w600)
                    .color(_.white).margin(0.px).lineHeight(1.3)
            )
            .rule(
                Selector.cls("rung-body").descendant(Selector.tag("p")),
                Style.margin(12.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(15.5.px).lineHeight(1.62).textWrap(_.pretty)
            )
            // module receipt chips (links)
            .rule("tags", Style.row.flexWrap(_.wrap).gap(8.px).margin(16.px, 0.px, 0.px, 0.px))
            .rule(
                "tag",
                Style.row.align(_.center).fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(12.px)
                    .color(darkDim).bg(whiteFill06).border(1.px, whiteBorder12).rounded(7.px)
                    .padding(4.px, 10.px).textDecoration(_.none)
                    .hover(_.color(_.white).borderColor(whiteBorder16))
            )
            // landing code panel: a darker inset on the dark band; tokens use the shared tok-* palette.
            // Opt the pre/code/spans back into normal flow (out of the kyo-ui flex reset) so the line
            // reads left-to-right with the <br> break, the same fix the docs prose applies.
            .rule(
                "code",
                // minWidth 0 + maxWidth 100% let the panel shrink to its flex/column container instead
                // of being forced as wide as its longest non-wrapping code line (a flex item defaults to
                // min-width:auto = min-content). The box then tracks the viewport and `overflow:auto`
                // scrolls the long line horizontally inside it, with a visible scrollbar (see
                // `polishedScrollbar(.code)` in `buildSheet`).
                Style.bg(hex("#0E0D14")).border(1.px, whiteBorder12).rounded(10.px)
                    .padding(15.px, 17.px).margin(14.px, 0.px, 0.px, 0.px).overflow(_.auto)
                    .minWidth(0.px).maxWidth(Length.Pct(100)).textAlign(_.left)
                    .shadow(0.px, 14.px, 34.px, 0.px, Color.rgba(10, 10, 14, 0.26))
            )
            .rule(
                Selector.cls("code").descendant(Selector.tag("pre")),
                Style.block.margin(0.px).fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(13.px)
                    .lineHeight(1.7).color(darkText)
            )
            .rule(Selector.cls("code").descendant(Selector.tag("code")), Style.inline)
            .rule(Selector.cls("code").descendant(Selector.tag("span")), Style.inline)
            // The code panel is a fixed dark surface in both themes, so its scrollbar thumb is a fixed
            // light color: the page-theme `--scroll-thumb` is a near-black `rgba(0,0,0,.18)` in light
            // mode, invisible against the #0E0D14 panel. A persistent, clearly visible horizontal thumb
            // makes the off-screen code obvious on a narrow screen instead of silently cropped.
            .rule(Selector.cls("code").pseudoElement("-webkit-scrollbar"), Style.height(10.px).width(10.px))
            .rule(Selector.cls("code").pseudoElement("-webkit-scrollbar-track"), Style.bg(Color.transparent))
            .rule(
                Selector.cls("code").pseudoElement("-webkit-scrollbar-thumb"),
                Style.bg(Color.rgba(255, 255, 255, 0.34)).rounded(99.px)
                    .border(2.px, Color.transparent).backgroundClip(_.paddingBox)
                    .hover(_.bg(Color.rgba(255, 255, 255, 0.55)))
            )
            // agent block: a bordered callout closing the ladder
            .rule(
                "agent",
                Style.column.maxWidth(900.px).margin(36.px, Length.Auto, 0.px, Length.Auto)
                    .bg(whiteFill06).border(1.px, whiteBorder12).rounded(14.px).padding(26.px, 30.px)
            )
            .rule(
                Selector.cls("agent").descendant(Selector.tag("h3")),
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontWeight(_.w500).fontSize(22.px)
                    .color(_.white).margin(0.px).textWrap(_.balance)
            )
            .rule(
                Selector.cls("agent").descendant(Selector.tag("p")),
                Style.margin(12.px, 0.px, 0.px, 0.px).color(darkDim).fontSize(15.5.px).lineHeight(1.6).textWrap(_.pretty)
            )
            // pull quote
            .rule(
                "pull",
                Style.fontFamily(Style.FontFamily.Custom("var(--serif)")).fontSize(27.px).lineHeight(1.3)
                    .color(hex("#CFC9FF")).textAlign(_.center).maxWidth(760.px)
                    .margin(40.px, Length.Auto, 0.px, Length.Auto).textWrap(_.balance)
            )
            // closing "honest" line, dark variant
            .rule(Selector.cls("dark").descendant(Selector.cls("honest")), Style.color(darkDim))
            // platforms floor paragraph + centered cards (dark band)
            .rule(
                "floor",
                Style.maxWidth(760.px).margin(36.px, Length.Auto, 0.px, Length.Auto).textAlign(_.center)
                    .color(darkDim).fontSize(16.px).lineHeight(1.7).textWrap(_.pretty)
            )
            // why-this-exists: a quiet, tight band
            .rule("whyx", Style.padding(34.px, 0.px).borderTop(1.px, _.variable("line-soft")))
            .rule(
                // block (not the reset's flex-column): the line mixes a text run, the inline manifesto
                // link, and a trailing period; as a flex column those three stack onto separate lines.
                "whyx-line",
                Style.block.maxWidth(640.px).margin(0.px, Length.Auto).textAlign(_.center)
                    .color(_.variable("dim")).fontSize(16.px).lineHeight(1.6).textWrap(_.balance)
            )
            // inline so the link sits within the sentence (out of the flex reset that would otherwise
            // drop it and the trailing period onto their own lines)
            .rule("whyx-link", Style.inline.color(_.variable("accent")).fontWeight(_.w500))
            // feature categories are links now: drop the underline, add a hover wash
            // feature categories: gapped, equal-width bordered cards (the seamless line-grid left the
            // 3-over-2 bottom row uneven). A fixed basis keeps all five on one column track, so the last
            // row is two cards left-aligned under the first two, not two stretched half-width cards.
            .rule(
                // justify center makes the orphan last row (2 cards) a centered 3-over-2 pyramid at the
                // same card width as the top row, instead of leaving an empty cell in the bottom corner.
                "feat-grid",
                Style.row.flexWrap(_.wrap).justify(_.center).gap(16.px).margin(44.px, 0.px, 0.px, 0.px)
                    .bg(Color.transparent).border(0.px, Color.transparent).rounded(0.px).overflow(_.visible)
            )
            .rule(
                "fcat",
                Style.textDecoration(_.none).color(_.variable("ink")).cursor(_.pointer)
                    .flexGrow(0.0).flexBasis(Length.Pct(31.5)).minWidth(220.px)
                    .bg(_.variable("surface")).border(1.px, _.variable("line")).rounded(14.px)
                    .hover(_.bg(_.variable("accent-ghost")).borderColor(_.variable("accent-line")))
            )
            // Gap chart line draw: the `#gap-line` path renders fully drawn by default (its inline dash
            // base), so the chart is complete with scripting or reduced-motion off. When the chart scrolls
            // into view the bundle adds `.chart-drawn` to `#gap-chart`, and (motion allowed) the keyframe
            // tweens stroke-dashoffset 1 -> 0, drawing the line in over 700ms. Gating on the chart entering
            // the viewport, not on load, is the fix for the draw playing unseen below the fold.
            .keyframes(
                "gapdraw",
                Stylesheet.Keyframe.from -> Style.strokeDashoffset(1.0),
                Stylesheet.Keyframe.to   -> Style.strokeDashoffset(0.0)
            )
            .media(Stylesheet.MediaQuery.prefersReducedMotionNoPreference)(
                Stylesheet.empty.rule(
                    Selector.cls("chart-drawn").descendant(Selector.id("gap-line")),
                    Style.animation("gapdraw", 700, _.easeOut)
                )
            )
            // Platforms connector line draw: the four `#pf-line-*` paths reuse the gap chart's exact draw
            // mechanism. Each renders fully drawn by default (its inline dash base), so the connector is
            // complete with scripting or reduced-motion off. When the platforms section scrolls into view the
            // bundle adds `.chart-drawn` to `#pf-connect` (the same IntersectionObserver wiring the gap chart
            // uses), and (motion allowed) the shared `gapdraw` keyframe tweens each line's stroke-dashoffset
            // 1 -> 0, so the four lines draw in together as one fan.
            .media(Stylesheet.MediaQuery.prefersReducedMotionNoPreference)(
                Stylesheet.empty
                    .rule(Selector.cls("chart-drawn").descendant(Selector.id("pf-line-0")), Style.animation("gapdraw", 620, _.easeOut))
                    .rule(Selector.cls("chart-drawn").descendant(Selector.id("pf-line-1")), Style.animation("gapdraw", 620, _.easeOut))
                    .rule(Selector.cls("chart-drawn").descendant(Selector.id("pf-line-2")), Style.animation("gapdraw", 620, _.easeOut))
                    .rule(Selector.cls("chart-drawn").descendant(Selector.id("pf-line-3")), Style.animation("gapdraw", 620, _.easeOut))
            )
            // ---- shared editor code card (`.code-card`): the polished editor-style panel used by BOTH the
            // hero signature and the adoption code receipt, so the two read as the same surface ----
            // The conventional dev-tool code-card pattern, consistent with the site's existing code surfaces
            // (the ladder `.code` panels and the docs `.code-block` share the dark fill and the `tok-*`
            // palette). The hero's `.hero-sig` column centers the card vertically against the left text (the
            // `.hero-grid` align(center) does the centering); the card is the visual anchor of that column.
            .rule(
                "hero-sig",
                Style.column.flexGrow(1.0).flexBasis(440.px).minWidth(0.px).justify(_.center)
            )
            // The adoption code receipt sits on its own row under the four paths; `.code-card-wrap` centers
            // the card in the section and caps it to a readable measure (the two-line snippet would sprawl
            // edge to edge otherwise), mirroring the framed, contained feel of the hero card.
            .rule(
                "code-card-wrap",
                Style.column.align(_.center).maxWidth(600.px).margin(40.px, Length.Auto, 0.px, Length.Auto)
            )
            .rule(
                Selector.cls("code-card-wrap").descendant(Selector.cls("code-card")),
                Style.width(Length.Pct(100))
            )
            // The card frame: the editor panel that OWNS the framing (dark surface, hairline top-light border, a
            // soft drop shadow that lifts it off the page) and clips its header bar and code body to the rounded
            // corners (overflow:hidden), exactly as the docs `.code-block` panel does. Both header and body read
            // as one continuous pane.
            .rule(
                "code-card",
                Style.column.width(Length.Pct(100)).minWidth(0.px)
                    .bg(hex("#0E0D14")).border(1.px, whiteBorder12).rounded(12.px).overflow(_.hidden)
                    .shadow(0.px, 18.px, 44.px, 0.px, Color.rgba(10, 10, 14, 0.32))
            )
            // The editor chrome bar: three muted window dots at the left, an uppercase muted language label at
            // the right, over a faintly lighter strip with a hairline bottom rule that separates it from the code
            // body below.
            .rule(
                "code-bar",
                Style.row.align(_.center).justify(_.spaceBetween)
                    .padding(11.px, 15.px).bg(Color.rgba(255, 255, 255, 0.03))
                    .borderBottom(1.px, whiteBorder12)
            )
            // The three window dots, a small even row at the left of the bar.
            .rule("code-dots", Style.row.align(_.center).gap(7.px))
            .rule(
                "code-dot",
                Style.block.width(11.px).height(11.px).rounded(99.px).flexShrink(0.0)
                    .bg(Color.rgba(255, 255, 255, 0.16))
            )
            // The language label: a quiet uppercase-mono tag at the right of the bar, the same muted register the
            // docs uses for its code-surface chrome.
            .rule(
                "code-lang",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.14.em).textTransform(_.uppercase).color(Color.rgba(255, 255, 255, 0.34))
            )
            // The card body reuses the shared `.code` panel, but inside the framed card it is just the code
            // surface: drop the panel's own border, shadow, radius, top margin, and outer fill (the `.code-card`
            // owns all of those) and give it generous internal padding. The `.code` descendant pre/code/span
            // rules (mono font, line-height, tok-* colors, horizontal scroll) still apply. Left-align the body
            // so the snippet reads naturally even when the card is centered in its section.
            .rule(
                Selector.cls("code-card").descendant(Selector.cls("code")),
                Style.bg(Color.transparent).border(0.px, Color.transparent).rounded(0.px)
                    .shadow(0.px, 0.px, 0.px, 0.px, Color.transparent)
                    .margin(0.px).padding(20.px, 20.px).textAlign(_.left)
            )
            // ---- hero + gap as two-column compositions that use the full content width (the page used
            // to be a thin centered ribbon). The hero pairs left-aligned text with the code card; the gap
            // pairs the argument with the stat. Both stack to one column on narrow viewports. ----
            // align(center): the code card is shorter than the headline + lead + CTA + trust column, so
            // center it vertically against that column instead of pinning it to the top (align:start left a
            // large dead band below the card). On a narrow viewport the grid wraps to one column and the
            // cross-axis centering is a no-op, so the card simply sits under the text.
            .rule("hero-grid", Style.row.flexWrap(_.wrap).gap(48.px).align(_.center))
            .rule("hero-text", Style.column.flexGrow(1.0).flexBasis(440.px).minWidth(0.px).textAlign(_.left))
            .rule(
                Selector.cls("hero-text").descendant(Selector.tag("h1")),
                Style.textAlign(_.left).margin(0.px, 0.px, 0.px, 0.px).fontSize(54.px).maxWidth(Length.Pct(100))
            )
            .rule(
                Selector.cls("hero-text").descendant(Selector.cls("lead")),
                Style.textAlign(_.left).margin(22.px, 0.px, 0.px, 0.px).maxWidth(540.px)
            )
            .rule(Selector.cls("hero-text").descendant(Selector.cls("hero-cta")), Style.justify(_.start))
            .rule(Selector.cls("hero-text").descendant(Selector.cls("trust")), Style.justify(_.start))
            // row-reverse: the heading/text comes first in the DOM (reading order, and it stacks on top
            // when the grid wraps on narrow viewports), but on a wide row it renders second, so the chart
            // card sits to the LEFT of the "From it works..." heading.
            .rule("gap-grid", Style.rowReverse.flexWrap(_.wrap).gap(44.px).align(_.center))
            .rule("gap-text", Style.column.flexGrow(1.0).flexBasis(440.px).minWidth(0.px))
            .rule(Selector.cls("gap-text").descendant(Selector.cls("sec-head")), Style.maxWidth(Length.Pct(100)))
            .rule(
                // textAlign left: the gap-text column is left-aligned (eyebrow + heading + this paragraph),
                // so override the centered `.problem p` base (a leftover from the old centered single-column
                // band) that would otherwise center this paragraph under its left-aligned heading.
                Selector.cls("gap-text").descendant(Selector.tag("p")),
                Style.margin(20.px, 0.px, 0.px, 0.px).color(_.variable("dim")).fontSize(17.px).lineHeight(1.65)
                    .textAlign(_.left).textWrap(_.pretty)
            )
            .rule(
                Selector.cls("gap-grid").descendant(Selector.cls("stat")),
                Style.flexGrow(1.0).flexBasis(340.px).minWidth(0.px).margin(0.px)
            )
    end landingLadder

    // ---- 404 page ----
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
            // Relative anchor box for the input + its absolutely-positioned .search-results dropdown,
            // so the dropdown opens directly under the input. It grows to fill the header's free space
            // (the input fills it in turn) just as the bare input did before it gained this wrapper.
            .rule(
                "search-wrap",
                Style.row.position(_.relative).flexGrow(1.0).minWidth(0.px)
            )
            .rule(
                "search-input",
                Style.width(Length.Pct(100)).fontFamily(Style.FontFamily.Custom("var(--sans)")).fontSize(13.5.px)
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
                    .scrollbarWidth(_.thin).scrollbarColor(Color.variable("scroll-thumb"), Color.transparent)
                    .bg(_.variable("bg")).borderRight(1.px, _.variable("line-soft"))
                    // Symmetric horizontal padding so the active-module highlight block sits balanced
                    // between the rail edges; the old 24/14 left/right split read as right-shifted once the
                    // scrollbar occupied the right gutter.
                    .padding(22.px, 18.px, 60.px, 18.px)
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
                // Hover brightens the label to full-strength `ink` (a colour change only, no background
                // shade). The transition is scoped to `color` so only the label colour eases; the
                // background and left bar never transition.
                Style.row.align(_.center).gap(8.px)
                    .fontSize(13.5.px).lineHeight(1.3).color(_.variable("text-dim"))
                    .padding(7.px, 10.px).rounded(8.px).cursor(_.pointer)
                    .borderLeft(2.px, Color.transparent)
                    .transition(_.color, 150, _.ease)
                    .hover(_.color(_.variable("ink")))
            )
            // The active module is a COLUMN (not the base row): it stacks the module link above its
            // expanded in-page section outline (.sidebar-sections), so the sections read as a nested
            // tree beneath the link rather than sitting beside it. align(_.start) keeps the link and
            // the outline both pinned to the left edge.
            .rule(
                "nav-item-active",
                // The current module: an accent label, a semibold weight, a .14 accent fill box, and a 3px
                // accent left bar. No transition (the box appears with the freshly-rendered active node,
                // never fading), so it does not animate across the rail's re-renders.
                Style.column.align(_.start)
                    .color(_.variable("accent")).bg(Color.rgba(78, 70, 224, 0.14)).fontWeight(_.w600)
                    .borderLeft(3.px, _.variable("accent"))
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
                // When a module becomes active this outline appears directly (no entrance animation): the
                // sections expand in place the instant the module is the active item.
                Style.column.align(_.start).width(Length.Pct(100))
                    .gap(1.px).margin(6.px, 0.px, 2.px, 8.px)
                    .borderLeft(1.px, _.variable("accent-line"))
            )
            // A section link: clearly subordinate (smaller, dim, indented under the guide line), left-
            // aligned, no underline. Same ~150ms ease transition and the SAME subtle accent-ghost tint
            // on hover as the module rows above, so the whole rail hovers consistently (never a white
            // block) and the label brightens to ink. The rail is one level deep (only `## ` sections
            // render), so a single base indent suffices.
            .rule(
                "sidebar-section",
                // Like the module rows: hover brightens the label only, no background shade and no
                // transition, so a section list expanding under the active module never flickers a shade.
                Style.row.align(_.start).width(Length.Pct(100))
                    .fontSize(12.5.px).lineHeight(1.35).color(_.variable("muted"))
                    .padding(4.px, 8.px, 4.px, 12.px).rounded(6.px).textDecoration(_.none)
                    .hover(_.color(_.variable("ink")))
            )
            // ---- Mobile docs navigation: a floating menu button + a slide-in drawer with a scrim. All
            // hidden by default (display:none) and switched on only in the <860px block below; the wide
            // viewport keeps the inline rail untouched. ----
            // The floating menu button: a compact pill pinned bottom-left, so the menu is one tap from
            // anywhere on a long page. >=44px tall (touch target); solid surface + border + shadow so it
            // stays legible over any scrolling content.
            .rule("docs-menu-fab", Style.displayNone)
            // The hamburger glyph inside the floating button renders block at its intrinsic 20px and never
            // shrinks under the flex label.
            .rule(Selector.cls("docs-menu-fab").descendant(Selector.tag("svg")), Style.block.flexShrink(0.0))
            // The "Menu" label: line-height 1 so it does not add a tall line box that would unbalance the
            // flex row's vertical centering.
            .rule("docs-menu-fab-label", Style.lineHeight(1.0))
            // The scrim behind the open drawer: a translucent backdrop covering the viewport. Hidden until
            // the drawer opens (the reactive `-open` class switches it on in the mobile block).
            .rule("docs-drawer-backdrop", Style.displayNone)
            // The drawer header (title + close button), shown only inside the mobile drawer.
            .rule("docs-drawer-head", Style.displayNone)
            .rule(
                "docs-drawer-title",
                Style.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(11.px)
                    .letterSpacing(0.12.em).textTransform(_.uppercase).color(_.variable("faint"))
            )
            .rule(
                "docs-drawer-close",
                Style.row.align(_.center).justify(_.center).width(34.px).height(34.px)
                    .rounded(8.px).border(1.px, Color.transparent).bg(Color.transparent)
                    .color(_.variable("dim")).cursor(_.pointer).transition(150, Style.Easing.ease)
                    .hover(_.bg(_.variable("accent-ghost")))
            )
            .rule(Selector.cls("docs-drawer-close").descendant(Selector.tag("svg")), Style.block)
            // Drawer slide-in and scrim fade-in, gated behind no-preference so reduced-motion readers get
            // an instant open. The open drawer/backdrop are freshly mounted on each open (the reactive
            // disclosure replaces the node), so the entrance plays on mount as an animation, not a
            // transition.
            .keyframes(
                "drawer-in",
                Stylesheet.Keyframe.from -> Style.translate(Length.Pct(-100), 0.px),
                Stylesheet.Keyframe.to   -> Style.translate(0.px, 0.px)
            )
            .keyframes(
                "backdrop-in",
                Stylesheet.Keyframe.from -> Style.opacity(0.0),
                Stylesheet.Keyframe.to   -> Style.opacity(1.0)
            )
            .media(Stylesheet.MediaQuery.prefersReducedMotionNoPreference)(
                Stylesheet.empty
                    .rule("docs-sidebar-open", Style.animation("drawer-in", 260, _.easeOut))
                    .rule("docs-drawer-backdrop-open", Style.animation("backdrop-in", 220, _.easeOut))
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
            // prev/next pager: two cards. The prev card aligns its content left behind a leading
            // chevron; the next card aligns right with a trailing chevron. A small direction eyebrow
            // ("Previous"/"Next") sits above the target module name. A missing side is an invisible
            // `pn-spacer` holding the slot so the present card stays on its own edge.
            .rule(
                "prev-next",
                Style.row.justify(_.spaceBetween).align(_.stretch).gap(16.px).margin(56.px, 0.px, 0.px, 0.px)
            )
            .rule(
                "pn",
                Style.row.align(_.center).gap(14.px).flexGrow(1.0).flexBasis(0.px).minWidth(0.px)
                    .border(1.px, _.variable("line")).rounded(14.px).padding(13.px, 18.px)
                    .textDecoration(_.none).color(_.variable("ink")).cursor(_.pointer)
                    .hover(_.borderColor(_.variable("accent-line")).bg(_.variable("accent-ghost")))
            )
            // The prose rule `.docs-content a { display: inline }` (specificity 0,1,1) outranks the kyo-ui
            // reset's `a { display: flex }`, which would leave the card in normal flow with the chevron
            // and body stacked. Force it back to a flex row with a more specific `.docs-content .pn`
            // (0,2,0) rule. (`Style.display(_.flex)`, added to kyo-ui for exactly this kind of override.)
            .rule(Selector.cls("docs-content").descendant(Selector.cls("pn")), Style.display(_.flex))
            // next card: push the body + trailing chevron to the right edge of the card
            .rule("pn-next", Style.justify(_.end))
            // the chevron wrapper carries the icon color (faint by default); stroke=currentColor on the
            // svg inside follows it, and the whole card turns accent on hover.
            .rule("pn-chev", Style.row.align(_.center).flexShrink(0.0).color(_.variable("faint")))
            .rule(Selector.cls("pn").pseudo("hover").descendant(Selector.cls("pn-chev")), Style.color(_.variable("accent")))
            .rule("pn-body", Style.column.gap(2.px).minWidth(0.px))
            // next card body: right-align the eyebrow + name under the right-pushed content
            .rule(Selector.cls("pn-next").descendant(Selector.cls("pn-body")), Style.align(_.end).textAlign(_.right))
            .rule(
                "pn-dir",
                Style.fontSize(12.px).color(_.variable("faint")).fontWeight(_.w500).letterSpacing(0.03.em)
            )
            .rule("pn-name", Style.fontSize(15.5.px).fontWeight(_.w600).color(_.variable("ink")))
            .rule(Selector.cls("pn").pseudo("hover").descendant(Selector.cls("pn-name")), Style.color(_.variable("accent")))
            .rule("pn-spacer", Style.flexGrow(1.0).flexBasis(0.px))
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
            // Inline code in prose: an inline-block chip so its padding and tint render while the token
            // still flows within the sentence (a bare inline box clips vertical padding). Two choices keep
            // it quiet rather than noisy: line-height 1 pins the chip box to the glyph height so it HUGS
            // the text instead of inheriting the prose 1.65 line-height and ballooning into a pill taller
            // than the words around it; and there is NO border, only a faint accent wash, so a sentence
            // dense with `Type` references reads as lightly tinted code, not a row of outlined boxes.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("code")),
                Style.inlineBlock.fontFamily(Style.FontFamily.Custom("var(--mono)")).fontSize(0.92.em).lineHeight(1.0)
                    .color(_.variable("text")).bg(_.variable("accent-ghost"))
                    .padding(1.5.px, 5.px).rounded(5.px)
            )
            // Fenced code panel (.code-block): the renderer wraps every fenced block in
            // `.code-block > (button.code-copy, pre)` with no header bar. The panel owns the framing, the
            // dark ink-section fill (reads as a code surface in both site themes), a hairline top-light
            // border, a soft drop shadow that lifts it off the page, and overflow:hidden so the children
            // clip cleanly to the 12px rounded corners. position:relative anchors the floating Copy button
            // to the panel's top-right corner. The block separates from following prose by its bottom margin.
            .rule(
                "code-block",
                // The hairline top-light border is what reads as the panel edge in DARK mode, where the
                // dark ink-section surface sits on the near-black page and the drop shadow all but
                // disappears; .12 (vs a fainter .08) keeps the rounded rectangle a discrete pane there
                // while staying an unobtrusive top highlight on the light cream page.
                Style.column.position(_.relative).bg(_.variable("ink-section")).rounded(12.px).overflow(_.hidden)
                    .border(1.px, Color.rgba(255, 255, 255, 0.12))
                    .shadow(0.px, 2.px, 14.px, 0.px, Color.rgba(20, 20, 15, 0.22))
                    .margin(0.px, 0.px, 22.px, 0.px)
            )
            // Copy button: a quiet uppercase-mono pill with a copy glyph, floating in the panel's top-right
            // corner (no header bar). An opaque-ish bg lets it read over the rare long first line; it
            // brightens on hover. The bundle attaches one delegated click handler that copies the panel's
            // <pre> text and flips data-copied for a moment; the CSS below swaps the "Copy" label for
            // "Copied" off that attribute (no per-button JS, works for SPA-injected blocks too).
            .rule(
                "code-copy",
                Style.row.align(_.center).justify(_.center)
                    .position(_.absolute).top(8.px).right(8.px).zIndex(2)
                    .color(Color.rgba(255, 255, 255, 0.5))
                    .bg(Color.rgba(38, 35, 27, 0.92)).border(1.px, Color.rgba(255, 255, 255, 0.14)).rounded(7.px)
                    .padding(4.px).cursor(_.pointer)
                    .transition(150, Style.Easing.ease)
                    .hover(_.color(Color.rgba(255, 255, 255, 0.95)).borderColor(Color.rgba(255, 255, 255, 0.3))
                        .bg(Color.rgba(52, 48, 38, 0.96)))
            )
            // The copy/check glyph follows the button color (stroke=currentColor) at a small fixed size.
            .rule(
                Selector.cls("code-copy").descendant(Selector.tag("svg")),
                Style.block.width(15.px).height(15.px).flexShrink(0.0)
            )
            // "Copied" is hidden until the button carries data-copied; the [data-copied] descendant rules
            // outrank the bare class so the swap survives the .docs-content cascade.
            .rule("code-copy-done", Style.displayNone)
            .rule(
                Selector.data("copied", "true").descendant(Selector.cls("code-copy-idle")),
                Style.displayNone
            )
            .rule(
                Selector.data("copied", "true").descendant(Selector.cls("code-copy-done")),
                Style.block.color(_.variable("jade"))
            )
            // The <pre> is now just the scroll surface inside the panel: transparent (the panel owns the
            // fill), padded for the code inset, and overflow:auto so a long line scrolls horizontally
            // rather than clipping. A thin, low-contrast scrollbar replaces the chunky OS default on the
            // dark surface (current Chrome/Firefox/Safari honor scrollbar-width/scrollbar-color). The extra
            // TOP padding reserves a clear strip for the floating Copy button so it never sits over the
            // first line of code.
            .rule(
                Selector.cls("docs-content").descendant(Selector.tag("pre")),
                Style.block.bg(Color.transparent).padding(34.px, 18.px, 18.px, 18.px).margin(0.px).overflow(_.auto)
                    .scrollbarWidth(_.thin).scrollbarColor(Color.rgba(255, 255, 255, 0.22), Color.transparent)
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
            // below render as single shared row + column dividers. The previous separate-collapse
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
                // were invisible.
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
            .rule("tok-interpolation", Style.color(hex("#89DDFF")))
            .rule("tok-annotation", Style.color(hex("#FFCB6B")))
            .rule("tok-operator", Style.color(hex("#89DDFF")))
    end docsTokens

    // ---- Theme toggle: a 36px icon button in the nav. The button holds both a sun and a moon icon;
    // CSS shows exactly one based on the root data-theme so the displayed icon matches the active theme
    // with no reactive wiring (the no-flash boot script sets data-theme before paint). Light mode shows
    // the moon (the switch-to-dark affordance); dark mode shows the sun. The onClick handler that flips
    // data-theme + persists is supplied by the bundle entry (the generator passes a no-op for the SSG). ----
    private def themeToggle: Stylesheet =
        Stylesheet.empty
            .rule(
                "theme-toggle",
                Style.row.align(_.center).justify(_.center).width(36.px).height(36.px).flexShrink(0)
                    .rounded(10.px).bg(_.variable("surface")).border(1.px, _.variable("line"))
                    .color(_.variable("dim")).cursor(_.pointer)
                    .hover(_.color(_.variable("ink")).borderColor(_.variable("faint")))
            )
            .rule(Selector.cls("theme-toggle").descendant(Selector.tag("svg")), Style.width(17.px).height(17.px))
            // The shown icon is `display: block` (an explicit display that overrides the hidden icon's
            // `display: none`); `Style.row` would only set the flex props and leave `display` to the reset,
            // so it could not override `none` and BOTH icons would collapse. The 17px svg centers inside the
            // flex button regardless.
            // light (default): show moon, hide sun
            .rule(Selector.cls("theme-toggle").descendant(Selector.cls("moon")), Style.block)
            .rule(Selector.cls("theme-toggle").descendant(Selector.cls("sun")), Style.displayNone)
            // dark: show sun, hide moon
            .rule(
                Selector.data("theme", "dark").descendant(Selector.cls("theme-toggle")).descendant(Selector.cls("moon")),
                Style.displayNone
            )
            .rule(
                Selector.data("theme", "dark").descendant(Selector.cls("theme-toggle")).descendant(Selector.cls("sun")),
                Style.block
            )
    end themeToggle

    // ---- Responsive breakpoints ----
    private def responsive: Stylesheet =
        Stylesheet.empty
            // On narrow viewports the unified header wraps to two rows so all nav targets stay
            // reachable on narrow viewports. Row 1: brand + nav links. Row 2: search + version + CTA.
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
                    // instead of wrapping to two cramped rows. textWrap(noWrap) keeps the CTA label
                    // intact; flexShrink(0) keeps the pill + CTA from being squeezed below their text.
                    .rule("right", Style.width(Length.Pct(100)).margin(0.px, 0.px, 8.px, 0.px).gap(8.px).align(_.center))
                    .rule("search-input", Style.flexGrow(1.0).minWidth(0.px).padding(7.px, 10.px))
                    // The native <select> carries the pill padding directly; tighten it on small
                    // screens so the version pill shrinks instead of pushing the row to wrap.
                    .rule("ver", Style.flexShrink(0.0).padding(7.px, 24.px, 7.px, 8.px))
                    .rule("btn", Style.flexShrink(0.0).padding(9.px, 14.px).textWrap(_.noWrap))
                    .rule("btn-ghost", Style.displayNone)
            )
            // Below 560px the header's second row cannot fit the search input beside the version pill,
            // the theme toggle, and the Get-started CTA without crushing the search to a few px (it was
            // 27px at 360px). Give the search its own full row: `.right` wraps and `.search-wrap` takes a
            // 100% flex-basis so the input spans the row, while the version pill, toggle, and CTA wrap
            // onto the line below it (they keep their own widths via the 820px block's flexShrink(0)).
            .media(Stylesheet.MediaQuery.maxWidth(560.px))(
                Stylesheet.empty
                    .rule("right", Style.flexWrap(_.wrap))
                    .rule("search-wrap", Style.flexBasis(Length.Pct(100)))
            )
            // The platforms connector only aligns while the four `.pf` cards hold a single 4-up row (their
            // centers at 12.5 / 37.5 / 62.5 / 87.5%). Below ~1024px the cards wrap to 2-up or a column, so the
            // fan would land off-center; hide the connector there and let the source box sit quietly above the
            // wrapped cards. The cards themselves stay fully meaningful without it.
            .media(Stylesheet.MediaQuery.maxWidth(1023.px))(
                Stylesheet.empty
                    .rule("pf-connect", Style.displayNone)
                    .rule("pf-cards", Style.margin(24.px, 0.px, 0.px, 0.px))
            )
            // outcome + feature grids collapse: 2-up at 880px, 1-up at 560px. Cards are flex items
            // sized by flexBasis, so the narrow-viewport overrides set flexBasis too (a width
            // override would be ignored while flexBasis stays at the 3-up 30%).
            .media(Stylesheet.MediaQuery.maxWidth(880.px))(
                Stylesheet.empty
                    .rule("fcat", Style.flexBasis(Length.Pct(45)))
                    // the four adoption paths fold from a 4-up row to a tidy 2x2 grid.
                    .rule("path", Style.flexBasis(Length.Pct(45)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(900.px))(
                Stylesheet.empty
                    .rule("cell", Style.flexBasis(Length.Pct(100)))
            )
            .media(Stylesheet.MediaQuery.maxWidth(560.px))(
                Stylesheet.empty
                    .rule("fcat", Style.flexBasis(Length.Pct(100)))
                    .rule("path", Style.flexBasis(Length.Pct(100)))
                    .rule("stat", Style.column.align(_.center).textAlign(_.center).gap(14.px))
            )
            // The ladder rungs are a two-column [beat | body] row on wide viewports; the fixed 150px
            // beat plus the 380px body (and its code panel) overflow a phone, so below 640px stack each
            // rung to a single column. The beat label sits above its body, which claims the full row
            // width, and the code panel scrolls within its own bounds instead of clipping the page. The
            // base `.rung` flex-basis values (150px beat, 380px body) become heights in a column, so
            // reset both to auto here to size by content.
            .media(Stylesheet.MediaQuery.maxWidth(640.px))(
                Stylesheet.empty
                    .rule("rung", Style.column.gap(10.px).padding(26.px, 0.px).minWidth(0.px))
                    .rule("beat", Style.flexBasis(Length.Auto).margin(0.px, 0.px, 2.px, 0.px))
                    // width 100% + min-width 0 pins the body to the (now column-width) rung instead of
                    // letting it expand to the non-wrapping code's min-content and overflow the viewport
                    // (which the band's overflow:hidden then clips). The code panel scrolls inside it.
                    .rule("rung-body", Style.flexBasis(Length.Auto).width(Length.Pct(100)).minWidth(0.px))
            )
            // docs 2-pane is side-by-side on wide viewports (rail + content); below 860px the rail
            // collapses into a toggle-revealed drawer (handled by the <860px block further down).
            .media(Stylesheet.MediaQuery.minWidth(1024.px))(
                Stylesheet.empty.rule("docs-shell", Style.row.align(_.start).flexWrap(_.noWrap))
            )
            // Wide viewports show the inline rail and need no mobile chrome; the floating button, drawer
            // overlay, and scrim are all base-hidden (display:none) and only switched on below 860px, so
            // there is nothing to hide here.
            // Narrow viewports: the sidebar is collapsed by default; the floating menu button reveals it.
            // `docs-sidebar-open` is declared AFTER `docs-sidebar` so, when both classes are present (open
            // state), its `display:block` wins the equal-specificity cascade and overrides the closed
            // `display:none`, turning the sidebar into the slide-in drawer.
            .media(Stylesheet.MediaQuery.maxWidth(860.px))(
                Stylesheet.empty
                    // The shell wraps so the full-width toggle + content stack in one column; the open
                    // sidebar is a fixed overlay drawer (below), so it no longer shares the row.
                    .rule("docs-shell", Style.row.flexWrap(_.wrap))
                    // Closed: the rail is removed from layout (and the a11y/focus tree). The open class
                    // turns it into the drawer.
                    .rule("docs-sidebar", Style.displayNone)
                    // The open drawer: a fixed panel pinned to the left edge over the page, so it is
                    // reachable at any scroll position (not stacked at the top where it would scroll away).
                    // Capped to most of the viewport width, scrollable within, lifted above the content and
                    // the sticky header on its own z-index. The slide-in plays via the `drawer-in` keyframe
                    // (gated on no-preference) when this freshly-mounted open node appears.
                    .rule(
                        "docs-sidebar-open",
                        Style.block.position(_.fixed).top(0.px).left(0.px).bottom(0.px)
                            .width(304.px).maxWidth(Length.Pct(86)).zIndex(120).margin(0.px)
                            .bg(_.variable("bg")).borderRight(1.px, _.variable("line"))
                            .shadow(0.px, 0.px, 44.px, 0.px, Color.rgba(0, 0, 0, 0.3))
                            .overflowY(_.auto).maxHeight(Length.Pct(100)).padding(16.px, 18.px, 40.px, 18.px)
                    )
                    // The scrim under the open drawer: a full-viewport fixed overlay that dims the page and
                    // closes the drawer on tap. `block` re-asserts visibility over the base display:none.
                    .rule(
                        "docs-drawer-backdrop-open",
                        Style.block.position(_.overlay).zIndex(110).bg(Color.rgba(0, 0, 0, 0.5))
                    )
                    // The drawer header (title left, close button right), only inside the drawer. Explicit
                    // display:flex overrides the base display:none, and `.row` overrides the reset's
                    // flex-direction:column (a <div> defaults to a flex column) so the two sit on one row.
                    .rule(
                        "docs-drawer-head",
                        Style.display(_.flex).row.align(_.center).justify(_.spaceBetween).margin(2.px, 0.px, 16.px, 0.px)
                    )
                    // The floating menu button: pinned bottom-left, so the menu is one tap from anywhere on
                    // the page. 46px tall touch target; solid surface + border + shadow so it reads over any
                    // content beneath it.
                    .rule(
                        "docs-menu-fab",
                        Style.display(_.inlineFlex).align(_.center).gap(8.px)
                            .position(_.fixed).left(16.px).bottom(16.px).zIndex(95)
                            .minHeight(46.px).padding(0.px, 18.px, 0.px, 14.px)
                            .rounded(999.px).bg(_.variable("surface")).border(1.px, _.variable("line"))
                            .color(_.variable("ink")).fontWeight(_.w600).fontSize(14.px)
                            .shadow(0.px, 6.px, 22.px, 0.px, Color.rgba(20, 20, 15, 0.18)).cursor(_.pointer)
                            .transition(150, Style.Easing.ease).hover(_.borderColor(_.variable("faint")))
                    )
                    // flex-basis 100% so the content always claims a full row of its own; maxWidth back to
                    // 100% so the narrow article fills its row. Extra bottom padding leaves room above the
                    // floating button so it never covers the last lines of prose.
                    .rule("docs-content", Style.flexBasis(Length.Pct(100)).maxWidth(Length.Pct(100)).padding(28.px, 22.px, 96.px, 22.px))
            )
            // OS dark-mode default: re-theme the whole palette when the user's system is set to dark
            // (the same `darkVars` the explicit toggle uses). The `data-theme` toggle in `themeOverrides`
            // overrides this when the user picks a theme explicitly.
            .media(Stylesheet.MediaQuery.prefersDark)(
                Stylesheet.empty.vars(darkVars*)
            )
    end responsive

end WebsiteStyles
