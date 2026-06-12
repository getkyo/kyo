// PUBLIC docs app view
package kyo.website

import kyo.*
import kyo.UI.Href
import scala.language.implicitConversions

/** The 2-pane documentation content body as a kyo-ui `UI` value.
  *
  * Assembles the docs body: a left rail whose FIRST item is the Overview (the root-README intro at the
  * intro route `/<prefix>/`), above the modules listed by group, with active-route highlighting. The
  * active item (the Overview on the intro route, or the current module otherwise) auto-expands into its
  * in-page section outline: exactly its top-level (`## `) headings, one level deep. The main content
  * area embeds the transpiled article subtree. This is the content body ONLY: the persistent header
  * (logo, search, version switcher) is owned by `SiteApp` (D5), so `body` no longer renders its own
  * header, and the header-out-of-shell layout invariant is now automatic.
  *
  * The former right table-of-contents pane is gone: the current page's section headings now live
  * inside the left rail, nested under the active item's entry, and auto-collapse when the reader
  * navigates elsewhere.
  *
  * The body is a pure composition of kyo-ui elements styled via `WebsiteStyles.sheet` CSS classes.
  * No raw CSS or raw HTML is used here; the sole `UI.rawHtml` exception lives inside the
  * build-time render pipeline for the few inline image/link snippets in READMEs. The `article`
  * parameter carries the pre-rendered article subtree.
  *
  * Navigation links in the sidebar (module links AND in-page section links) are plain `<a>`
  * elements; `UILocation`'s capture-phase anchor interceptor (JS-only) converts same-origin clicks
  * to `pushState` (module links) or in-page scroll (fragment links) without per-link wiring.
  *
  * @see
  *   [[DocsApp.body]] for the main entry point
  */
object DocsApp:

    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.map(n => UI.Ast.HtmlChildVal.lift(n))
    private def html(cs: kyo.Chunk[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.toSeq.map(n => UI.Ast.HtmlChildVal.lift(n))

    /** Assemble the 2-pane docs content body for one route (header excluded, owned by `SiteApp`).
      *
      * The returned `UI < Sync` renders the `docs-shell` 2-pane row: a left rail (module groups,
      * active link highlighting, with the active module expanded to its in-page section outline as
      * indented `#slug` links) and a main content area (wrapping `article`). The content area embeds
      * `article` directly; when `article` is a `Reactive` node the kyo-ui renderer makes that region
      * reactive without additional wrapping (INV-013).
      *
      * For SSG call sites: pass the pre-rendered article from `DocsMarkdownRender.Rendered.article` as `article`.
      * For the bundle: pass `articleRef.render(a => a)` as `article` so the region re-renders on
      * navigation (the `Reactive` node is a valid `UI`).
      *
      * @param content
      *   The versioned documentation content (groups, modules, version record).
      * @param prefix
      *   The physical route tree the page is served under (`latest` or the version's own tag, e.g.
      *   `v1.2.0`). All intra-page links (sidebar, prev/next) use this prefix so a page links within
      *   its own tree (Phase-6 BLOCKER-1, Phase-7 WARN-1). The caller knows which physical tree it is
      *   emitting; the prefix is NOT re-derived from `content.version.latest` here, because the latest
      *   version is also emitted under its own `v<X>/` tree where its links must stay `/v<X>/...`.
      * @param route
      *   Signal tracking the current pathname, used to compute active sidebar state and prev/next.
      * @param tocSignal
      *   The current page's heading outline (from `DocsMarkdownRender.Rendered.headings`), as a `Signal`
      *   so the active module's nested section list re-renders on client navigation. Pass
      *   `Signal.initConst(headings)` for a static page (the SSG generator), or a `SignalRef` updated
      *   per route for the client bundle. The sidebar combines this with the active-route signal so
      *   the new module's sections expand and the previous module's collapse on a navigation.
      * @param article
      *   The transpiled article subtree to embed in the content area. May be a `Reactive` node.
      * @param contentLoading
      *   Whether the content area's article is mid-load (the bundle has cleared `articleRef`/`tocRef`
      *   and the new route's `content.md` fetch is in flight). The prev/next pager is gated on this so
      *   it stays hidden during the brief empty-article window and only appears once the article does,
      *   avoiding a footer-at-top flash. Pass `Signal.initConst(false)` for a static page (the SSG, or a
      *   test): a static page is always loaded, so the pager renders exactly as before. The bundle passes
      *   its `loadingRef`, set true at the start of a content fetch and false once the article is set.
      * @return
      *   A `UI < Sync` representing the 2-pane docs content body.
      */
    def body(
        content: WebsiteContent,
        prefix: String,
        route: Signal[String],
        tocSignal: Signal[Chunk[DocsMarkdown.Heading]],
        article: UI,
        contentLoading: Signal[Boolean]
    )(using Frame): UI < Sync =
        for
            // Mobile sidebar disclosure (B6). Below 860px the sidebar is hidden by default; this ref
            // drives a reactive open/closed class so the docs-nav-toggle button (mobile-only) can reveal
            // the module list. On wide viewports the sidebar is always shown and the toggle is hidden, so
            // the ref is inert there. Closing the disclosure on each navigation keeps the drawer from
            // covering the freshly-loaded article.
            navOpenRef <- Signal.initRef(false)
        yield
            val allModules = content.groups.flatMap(_.modules)
            // The `docs-shell` is the 2-pane row: the persistent header is owned by SiteApp and sits
            // above this body, so the header-out-of-shell layout invariant holds automatically
            // (docs-shell is flex-direction:row; a header sibling here would steal a column and squish
            // the content). The mobile nav toggle is the first child so, when the drawer opens on a
            // narrow viewport, the revealed sidebar list sits directly below the button; on wide
            // viewports the toggle is hidden and the two panes lay out side by side (B6). The former
            // right TOC pane is removed: the page's section outline now nests inside the rail under
            // the active module (the rail is the single source of in-page navigation).
            UI.div.cssClass("docs-shell")(
                drawerBackdrop(navOpenRef),
                sidebar(content, route, prefix, navOpenRef, tocSignal),
                contentArea(article, allModules, route, prefix, contentLoading),
                mobileMenuFab(navOpenRef)
            )
    end body

    // ---- Private helpers ----

    private def versionBanner(current: WebsiteVersion)(using Frame): UI =
        if current.latest then UI.empty
        else
            UI.div.cssClass("version-banner")(
                UI.span("You are viewing an older version. This is not the latest release.")
            )
        end if
    end versionBanner

    private def sidebar(
        content: WebsiteContent,
        route: Signal[String],
        prefix: String,
        navOpenRef: SignalRef[Boolean],
        tocSignal: Signal[Chunk[DocsMarkdown.Heading]]
    )(using Frame): UI =
        // Tapping a module (or the Overview) in the mobile drawer navigates cross-document and must also
        // close the drawer, which would otherwise stay open over the freshly-loaded article. The handler
        // is placed on each cross-document <a>: UILocation drives the navigation in the capture phase
        // (before this bubble-phase handler), so the kyo-ui anchor preventDefault is harmless. On wide
        // viewports navOpenRef is inert, so closing is a no-op there. (Same-document `#section` links keep
        // their native scroll and are intentionally NOT wired here.)
        val closeDrawer = navOpenRef.updateAndGet(_ => false).unit
        val nav =
            UI.nav.cssClass("sidebar-nav")(
                html(overviewItem(route, prefix, tocSignal, closeDrawer) +: content.groups.toSeq.map { group =>
                    UI.div.cssClass("sidebar-group")(
                        UI.div.cssClass("sidebar-group-name")(UI.span(groupLabel(group.name))),
                        UI.ul(
                            html(group.modules.toSeq.map { mod =>
                                val href         = s"/$prefix/${mod.slug}/"
                                val activeSignal = route.map(r => r.endsWith(s"/${mod.slug}/") || r == href)
                                // Key each module node on BOTH the active flag AND the page outline:
                                // combineLatest re-emits when EITHER changes, so the active module's
                                // nested sections appear and update with the current page (`tocSignal`),
                                // and a navigation away (the active flag flips to false) collapses them.
                                // zip would stall (it waits for both inputs to tick), so combineLatest is
                                // required, mirroring SiteApp's search dropdown.
                                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                                UI.Ast.Reactive(activeSignal.combineLatest(tocSignal).map { case (isActive, toc) =>
                                    if isActive then
                                        UI.li.cssClass("nav-item").cssClass("nav-item-active")(
                                            UI.a(mod.displayName).href(Href.Path(href)).onClick(closeDrawer),
                                            sidebarSections(toc)
                                        )
                                    else
                                        UI.li.cssClass("nav-item")(
                                            UI.a(mod.displayName).href(Href.Path(href)).onClick(closeDrawer)
                                        )
                                })
                            })*
                        )
                    )
                })*
            )
        // The sidebar carries a reactive `docs-sidebar-open` class on mobile: when the disclosure is
        // open it overrides the <860px `display:none`, revealing the module list as a drawer. On wide
        // viewports the base `docs-sidebar` rule already shows it and the class is a no-op (B6).
        // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
        // The drawer header is shown only inside the mobile drawer (hidden on the wide-viewport inline
        // rail by the stylesheet): a "Menu" title and a close (X) button that dismisses the drawer.
        val drawerHead =
            UI.div.cssClass("docs-drawer-head")(
                UI.span.cssClass("docs-drawer-title")("Menu"),
                UI.button.cssClass("docs-drawer-close").aria("label", "Close the menu").onClick(navOpenRef.updateAndGet(_ => false).unit)(
                    closeGlyph
                )
            )
        UI.Ast.Reactive(navOpenRef.map { open =>
            val base     = UI.div.cssClass("docs-sidebar")
            val withOpen = if open then base.cssClass("docs-sidebar-open") else base
            withOpen(
                drawerHead,
                versionBanner(content.version),
                nav
            )
        })
    end sidebar

    // Shorten the longest module-group headings for the sidebar rail only. The README headings the
    // groups are parsed from stay verbatim; this abbreviates the few verbose multi-word names so the
    // left rail does not wrap or crowd. Any group name not listed renders unchanged.
    private def groupLabel(name: String): String = name match
        case "Concurrent primitives"                  => "Concurrency"
        case "Direct style and combinators"           => "Syntax"
        case "Interop with other effect stacks"       => "Interop"
        case "Scheduler embedding for other runtimes" => "Scheduler bridges"
        case other                                    => other

    // The overview/home entry: the FIRST rail item, above the module groups, linking to the intro
    // route `/<prefix>/` (the root-README overview article). It mirrors the module-item mechanism: keyed
    // on BOTH the active flag AND the page outline via combineLatest, so when the reader is on the intro
    // route it is the active item and expands to its own `## ` sections (the intro's headings), and on
    // any module route the flag flips to false and the outline collapses to the bare link. The intro
    // route is the single-segment `/<prefix>/`, so active is an exact-match on that path (a module route
    // has a trailing slug segment and never matches). The item sits in its own `<ul>` so the
    // `.nav-item` / `.nav-item .a` rules apply identically to the module items below.
    private def overviewItem(
        route: Signal[String],
        prefix: String,
        tocSignal: Signal[Chunk[DocsMarkdown.Heading]],
        closeDrawer: => Any < Async
    )(using
        Frame
    ): UI =
        val href         = s"/$prefix/"
        val activeSignal = route.map(r => r == href)
        UI.ul(
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
            UI.Ast.Reactive(activeSignal.combineLatest(tocSignal).map { case (isActive, toc) =>
                if isActive then
                    UI.li.cssClass("nav-item").cssClass("nav-item-active")(
                        UI.a("Overview").href(Href.Path(href)).onClick(closeDrawer),
                        sidebarSections(toc)
                    )
                else
                    UI.li.cssClass("nav-item")(
                        UI.a("Overview").href(Href.Path(href)).onClick(closeDrawer)
                    )
            })
        )
    end overviewItem

    // A crisp 1:1 hamburger glyph (viewBox == display px, strokes on whole coords) shared by the top
    // toggle and the floating menu button. currentColor, so it takes the control's text color.
    private def menuGlyph(using Frame): UI =
        Svg.svg.viewBox(Svg.ViewBox(0, 0, 20, 20)).width(20).height(20)(
            Svg.g.stroke(Svg.Paint.CurrentColor).strokeWidth(2.0).strokeLinecap(Svg.StrokeLinecap.Round)(
                Svg.line.x1(3.0).y1(5.0).x2(17.0).y2(5.0),
                Svg.line.x1(3.0).y1(10.0).x2(17.0).y2(10.0),
                Svg.line.x1(3.0).y1(15.0).x2(17.0).y2(15.0)
            )
        )

    // A crisp 1:1 close (X) glyph for the drawer's close control.
    private def closeGlyph(using Frame): UI =
        Svg.svg.viewBox(Svg.ViewBox(0, 0, 20, 20)).width(20).height(20)(
            Svg.g.stroke(Svg.Paint.CurrentColor).strokeWidth(2.0).strokeLinecap(Svg.StrokeLinecap.Round)(
                Svg.line.x1(5.0).y1(5.0).x2(15.0).y2(15.0),
                Svg.line.x1(15.0).y1(5.0).x2(5.0).y2(15.0)
            )
        )

    // The floating menu button: the single mobile menu affordance, a compact pill pinned to the
    // bottom-left so the module and section menu is one tap from anywhere on a long page. Hidden on wide
    // viewports (where the rail is always visible) by the stylesheet. aria-label because it is glyph-led.
    private def mobileMenuFab(navOpenRef: SignalRef[Boolean])(using Frame): UI =
        UI.button
            .cssClass("docs-menu-fab")
            .aria("label", "Open the documentation menu")
            .onClick(navOpenRef.updateAndGet(_ => true).unit)(
                menuGlyph,
                UI.span.cssClass("docs-menu-fab-label")("Menu")
            )

    // The scrim behind the open mobile drawer: a translucent backdrop that dims the page and closes the
    // drawer on tap (the standard modal-drawer dismissal). Always rendered so its fade transitions both
    // ways; the reactive `open` class drives opacity + pointer-events, and the stylesheet hides it on wide
    // viewports. Decorative target, so it is aria-hidden.
    private def drawerBackdrop(navOpenRef: SignalRef[Boolean])(using Frame): UI =
        UI.Ast.Reactive(navOpenRef.map { open =>
            val base = UI.div.cssClass("docs-drawer-backdrop").aria("hidden", "true").onClick(navOpenRef.updateAndGet(_ => false).unit)
            if open then base.cssClass("docs-drawer-backdrop-open") else base
        })

    private def contentArea(
        article: UI,
        modules: Chunk[WebsiteModule],
        route: Signal[String],
        prefix: String,
        contentLoading: Signal[Boolean]
    )(using Frame): UI =
        UI.main.cssClass("docs-content")(
            article,
            // The prev/next pager is gated on `contentLoading` so it never paints at the top of an
            // empty content area while the new route's article is mid-fetch (which read as a footer
            // flash before the article filled in and pushed it down). combineLatest re-emits when EITHER
            // the route OR the loading flag changes, so the pager hides the instant a content fetch
            // starts and reappears, with the correct route's links, the instant the article is ready.
            // On the SSG and the bundle's first paint `contentLoading` is constant/false, so the pager
            // renders exactly as before (hydration parity).
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
            UI.Ast.Reactive(route.combineLatest(contentLoading).map { case (r, loading) =>
                if loading then UI.empty else prevNextNav(modules, r, prefix)
            })
        )
    end contentArea

    private def sidebarSections(toc: Chunk[DocsMarkdown.Heading])(using Frame): UI =
        // The active item's in-page section outline, nested under its rail entry. The rail is exactly
        // one level deep: group -> module -> its top-level (`## `) sections, so ONLY level-2 headings
        // render here. The level-1 heading is the page title (it duplicates the link directly above)
        // and any level-3/4 heading is dropped from the rail. An empty list (no level-2 sections)
        // yields an empty <ul> that collapses to nothing, so an item with no sub-sections shows just
        // its link.
        val sections = toc.filter(_.level == 2)
        UI.ul.cssClass("sidebar-sections")(
            sections.toSeq.map { heading =>
                // The <a> is wrapped in a bare <li> because a <ul> only accepts list-item children; the
                // base reset suppresses the marker, so the wrapper is purely structural. With a single
                // section level there is one indent hook (`sidebar-section`).
                UI.li(
                    UI.a(heading.text).cssClass("sidebar-section").href(Href.Fragment(heading.slug))
                )
            }*
        )
    end sidebarSections

    private def prevNextNav(modules: Chunk[WebsiteModule], currentRoute: String, prefix: String)(using Frame): UI =
        // The overview/intro route `/<prefix>/` is the FIRST page in the docs sequence: it has no prev
        // and its next is the first module. A module page's prev/next step within the module sequence,
        // and the first module's prev points back to the overview ("Overview", targeting `/<prefix>/`).
        // Each side is a card carrying a direction eyebrow and the target module's name; a missing side
        // renders an invisible `pn-spacer` so the present card stays pinned to its own edge.
        val overviewRoute = s"/$prefix/"
        val idx           = modules.indexWhere(m => currentRoute.endsWith(s"/${m.slug}/"))
        val (prevSlot, nextSlot) =
            if idx < 0 then
                val next = modules.headMaybe match
                    case Present(first) => pnCard("Next", first.displayName, s"/$prefix/${first.slug}/", isPrev = false)
                    case Absent         => pnSpacer
                (pnSpacer, next)
            else
                val prev =
                    if idx > 0 then
                        pnCard("Previous", modules(idx - 1).displayName, s"/$prefix/${modules(idx - 1).slug}/", isPrev = true)
                    else pnCard("Previous", "Overview", overviewRoute, isPrev = true)
                val next =
                    if idx < modules.size - 1 then
                        pnCard("Next", modules(idx + 1).displayName, s"/$prefix/${modules(idx + 1).slug}/", isPrev = false)
                    else pnSpacer
                (prev, next)
        UI.nav.cssClass("prev-next")(prevSlot, nextSlot)
    end prevNextNav

    /** One pager card: a direction eyebrow ("Previous"/"Next") above the target module's display name,
      * with a chevron on the card's outer edge (left for a prev card, right for a next card). The next
      * card carries `pn-next` so its content right-aligns; the prev card is the default.
      */
    private def pnCard(dir: String, name: String, href: String, isPrev: Boolean)(using Frame): UI =
        val chev = UI.span.cssClass("pn-chev")(chevron(pointsLeft = isPrev))
        // pn-body is a div (not a span): the kyo-ui reset makes `span { display: inline }`, so a span
        // would not be a flex column and the eyebrow + name would run together inline. A div defaults to
        // a flex column, stacking the direction eyebrow above the module name. UI.a accepts block
        // children (the landing's `fcat` anchor wraps a heading + list the same way).
        val body = UI.div.cssClass("pn-body")(UI.span.cssClass("pn-dir")(dir), UI.span.cssClass("pn-name")(name))
        val link = (if isPrev then UI.a.cssClass("pn") else UI.a.cssClass("pn").cssClass("pn-next")).href(Href.Path(href))
        if isPrev then link(chev, body) else link(body, chev)
    end pnCard

    /** An empty, borderless slot that holds a missing side's place so the present card stays on its own
      * edge (the nav's `space-between` positions the pair).
      */
    private def pnSpacer(using Frame): UI = UI.span.cssClass("pn-spacer")()

    /** A single chevron glyph drawn with the kyo-ui `Svg` DSL; `stroke = currentColor` makes it follow
      * the card's text color (and its accent hover tone). Sized in CSS via `.pn-chev svg`.
      */
    private def chevron(pointsLeft: Boolean)(using Frame): UI =
        val d =
            if pointsLeft then Svg.PathData.from(15, 6).lineTo(9, 12).lineTo(15, 18)
            else Svg.PathData.from(9, 6).lineTo(15, 12).lineTo(9, 18)
        Svg.svg
            .viewBox(Svg.ViewBox(0, 0, 24, 24))
            .width(20)
            .height(20)(
                Svg.g
                    .fill(Svg.Paint.None)
                    .stroke(Svg.Paint.CurrentColor)
                    .strokeWidth(2.0)
                    .strokeLinecap(Svg.StrokeLinecap.Round)
                    .strokeLinejoin(Svg.StrokeLinejoin.Round)(Svg.path.d(d))
            )
    end chevron

end DocsApp
