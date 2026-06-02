// flow-allow: PUBLIC site stylesheet
package kyo.website

import kyo.*

/** The kyo website's global stylesheet, authored as a kyo-ui `Stylesheet` value.
  *
  * Every class name here corresponds to a `cssClass` call in the UI components. The sheet
  * is rendered to a CSS string and placed in `PageHead.css` by `WebsitePage.wrap`; the same
  * value is injected client-side via `UI.runStylesheet` by the bundle entry. No raw CSS
  * string is used anywhere in the site (INV-012).
  *
  * Note: CSS Grid `grid-template-columns` is not expressible through `Style` setters (Style
  * targets inline declarations and the flex model). Where a grid layout is required,
  * `flex-wrap` + sizing approximates multi-column behaviour. True grid columns are deferred to
  * a future `Style` extension and recorded in phases/phase-02/decisions.md.
  */
object WebsiteStyles:

    val sheet: Stylesheet = buildSheet

    private def buildSheet: Stylesheet =
        Stylesheet.empty
            // CSS custom properties (palette + typography tokens from kyo-landing.html)
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
            // Layout wrapper
            .rule(
                "wrap",
                Style.maxWidth(1120.px).margin(Length.Auto, Length.Auto).padding(0.px, 28.px)
            )
            // Navigation bar
            .rule(
                "nav-bar",
                Style.row.align(_.center).justify(_.spaceBetween)
                    .padding(0.px, 28.px)
                    .height(60.px)
                    .bg(_.variable("surface"))
            )
            .rule(
                "nav-link",
                Style.color(_.variable("dim"))
                    .hover(_.color(_.variable("ink")))
            )
            // Hero section
            .rule(
                "hero",
                Style.column.align(_.center).padding(80.px, 28.px).bg(_.variable("bg"))
            )
            // Feature grid (landing page 6-card outcomes grid)
            .rule(
                "feat-grid",
                Style.row.flexWrap(_.wrap).gap(1.px).bg(_.variable("line"))
            )
            .rule(
                Selector.cls("feat-grid").descendant(Selector.cls("card")),
                Style.column.padding(26.px, 24.px, 28.px, 24.px)
                    .bg(_.variable("bg"))
                    .width(Length.Pct(33.33))
            )
            .rule(
                Selector.cls("feat-grid").descendant(Selector.cls("card")).pseudo("hover"),
                Style.bg(Style.Color.hex("#F5F1EA").getOrElse(Style.Color.transparent))
            )
            // Card
            .rule(
                "card",
                Style.column.padding(26.px, 24.px, 28.px, 24.px).bg(_.variable("surface"))
            )
            // Buttons
            .rule(
                "btn",
                Style.row.align(_.center).gap(8.px)
                    .padding(12.px, 20.px)
                    .color(_.variable("ink"))
                    .hover(_.color(_.variable("ink")))
            )
            .rule(
                "btn-primary",
                Style.bg(_.variable("accent")).color(_.white)
                    .hover(_.bg(_.variable("accent-deep")).color(_.white))
            )
            // Docs shell (3-pane layout)
            .rule(
                "docs-shell",
                Style.row.align(_.start).padding(0.px)
            )
            .rule(
                "docs-sidebar",
                Style.column.width(260.px).height(Length.Pct(100))
                    .overflow(_.auto).bg(_.variable("bg"))
            )
            .rule(
                "docs-content",
                Style.column.flexWrap(_.noWrap).padding(32.px, 48.px).overflow(_.auto)
            )
            .rule(
                "docs-toc",
                Style.column.width(220.px).overflow(_.auto).padding(24.px, 16.px)
            )
            // Navigation items in sidebar
            .rule(
                "nav-item",
                Style.row.align(_.center).padding(7.px, 10.px)
                    .color(_.variable("text-dim"))
                    .hover(_.color(_.variable("text")).bg(_.variable("surface")))
            )
            .rule(
                "nav-item-active",
                Style.color(_.variable("accent")).bg(_.variable("accent-ghost"))
            )
            // Docs header (top bar carrying logo, nav, search, version dropdown)
            .rule(
                "docs-header",
                Style.row.align(_.center).justify(_.spaceBetween)
                    .height(60.px)
                    .bg(_.variable("surface"))
                    .borderBottom(1.px, _.variable("line"))
            )
            .rule(
                "docs-header-right",
                Style.row.align(_.center).gap(12.px)
            )
            .rule(
                "docs-nav",
                Style.row.align(_.center).gap(20.px)
                    .color(_.variable("dim"))
            )
            .rule(
                "brand",
                Style.color(_.variable("ink")).fontWeight(_.bold)
                    .textDecoration(_.none)
            )
            .rule(
                "search-input",
                Style.padding(7.px, 12.px)
                    .border(1.px, _.variable("line"))
                    .bg(_.variable("surface"))
                    .color(_.variable("ink"))
            )
            .rule(
                "ver",
                Style.padding(6.px, 10.px)
                    .border(1.px, _.variable("line"))
                    .bg(_.variable("surface"))
                    .color(_.variable("ink"))
            )
            // Version banner (shown on non-latest pages)
            .rule(
                "version-banner",
                Style.row.align(_.center).padding(10.px, 16.px)
                    .bg(Style.Color.hex("#FFFBEB").getOrElse(Style.Color.transparent))
                    .color(_.variable("amber"))
            )
            // Sidebar navigation structure
            .rule(
                "sidebar-nav",
                Style.column.padding(16.px, 12.px).gap(4.px)
            )
            .rule(
                "sidebar-group",
                Style.column.padding(8.px, 0.px).gap(2.px)
            )
            .rule(
                "sidebar-group-name",
                Style.padding(6.px, 10.px)
                    .color(_.variable("faint"))
                    .fontWeight(_.w600)
            )
            // Previous/next navigation footer
            .rule(
                "prev-next",
                Style.row.align(_.center).justify(_.spaceBetween)
                    .padding(24.px, 0.px).gap(16.px)
                    .borderTop(1.px, _.variable("line"))
            )
            .rule(
                "prev-next-disabled",
                Style.color(_.variable("faint"))
            )
            // Table of contents structure (per-level indentation)
            .rule(
                "toc-nav",
                Style.column.gap(4.px)
            )
            .rule(
                "toc-item",
                Style.row.align(_.center).padding(3.px, 8.px)
                    .color(_.variable("dim"))
                    .hover(_.color(_.variable("ink")))
            )
            .rule(
                "toc-h1",
                Style.padding(3.px, 8.px).color(_.variable("ink")).fontWeight(_.w600)
            )
            .rule(
                "toc-h2",
                Style.padding(3.px, 8.px, 3.px, 16.px)
            )
            .rule(
                "toc-h3",
                Style.padding(3.px, 8.px, 3.px, 28.px)
            )
            .rule(
                "toc-h4",
                Style.padding(3.px, 8.px, 3.px, 40.px)
            )
            .rule(
                "sub",
                Style.color(_.variable("faint"))
            )
            // Callout boxes
            .rule(
                "callout-note",
                Style.row.align(_.start).padding(14.px, 16.px).bg(_.variable("surface")).gap(12.px)
            )
            .rule(
                "callout-caution",
                Style.row.align(_.start).padding(14.px, 16.px)
                    .bg(Style.Color.hex("#FFFBEB").getOrElse(Style.Color.transparent))
                    .gap(12.px)
            )
            // Code highlight token classes (used by Phase 5 syntax highlighter)
            .rule(
                "tok-keyword",
                Style.color(Style.Color.hex("#7C3AED").getOrElse(Style.Color.purple))
            )
            .rule(
                "tok-string",
                Style.color(Style.Color.hex("#059669").getOrElse(Style.Color.green))
            )
            .rule(
                "tok-comment",
                Style.color(Style.Color.hex("#6B7280").getOrElse(Style.Color.gray))
            )
            .rule(
                "tok-number",
                Style.color(Style.Color.hex("#D97706").getOrElse(Style.Color.orange))
            )
            .rule(
                "tok-type",
                Style.color(Style.Color.hex("#2563EB").getOrElse(Style.Color.blue))
            )
            .rule(
                "tok-literal",
                Style.color(Style.Color.hex("#DC2626").getOrElse(Style.Color.red))
            )
            // Section helpers
            .rule(
                "eyebrow",
                Style.color(_.variable("accent"))
            )
            .rule(
                "serif",
                Style.color(_.variable("ink"))
            )
            .rule(
                "accent",
                Style.color(_.variable("accent"))
            )
            // Mobile: hide nav links
            .media(Stylesheet.MediaQuery.maxWidth(820.px))(
                Stylesheet.empty.rule("nav-links", Style.displayNone)
            )
            // Feat-grid collapses to 2 col at 880px, 1 col at 560px
            .media(Stylesheet.MediaQuery.maxWidth(880.px))(
                Stylesheet.empty.rule(
                    Selector.cls("feat-grid").descendant(Selector.cls("card")),
                    Style.width(Length.Pct(50))
                )
            )
            .media(Stylesheet.MediaQuery.maxWidth(560.px))(
                Stylesheet.empty.rule(
                    Selector.cls("feat-grid").descendant(Selector.cls("card")),
                    Style.width(Length.Pct(100))
                )
            )
            // Docs 3-pane is side-by-side only on wide viewports (from docs.html lines 44-49)
            .media(Stylesheet.MediaQuery.minWidth(1024.px))(
                Stylesheet.empty.rule("docs-shell", Style.row.align(_.start))
            )
            // Dark mode palette override
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
    end buildSheet

end WebsiteStyles
