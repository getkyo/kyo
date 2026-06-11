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
                navToggle(navOpenRef),
                sidebar(content, route, prefix, navOpenRef, tocSignal),
                contentArea(article, allModules, route, prefix, contentLoading)
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
        navOpenRef: Signal[Boolean],
        tocSignal: Signal[Chunk[DocsMarkdown.Heading]]
    )(using Frame): UI =
        val nav =
            UI.nav.cssClass("sidebar-nav")(
                html(overviewItem(route, prefix, tocSignal) +: content.groups.toSeq.map { group =>
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
                                            UI.a(mod.displayName).href(Href.Path(href)),
                                            sidebarSections(toc)
                                        )
                                    else
                                        UI.li.cssClass("nav-item")(
                                            UI.a(mod.displayName).href(Href.Path(href))
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
        UI.Ast.Reactive(navOpenRef.map { open =>
            val base     = UI.div.cssClass("docs-sidebar")
            val withOpen = if open then base.cssClass("docs-sidebar-open") else base
            withOpen(
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
    private def overviewItem(route: Signal[String], prefix: String, tocSignal: Signal[Chunk[DocsMarkdown.Heading]])(using
        Frame
    ): UI =
        val href         = s"/$prefix/"
        val activeSignal = route.map(r => r == href)
        UI.ul(
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
            UI.Ast.Reactive(activeSignal.combineLatest(tocSignal).map { case (isActive, toc) =>
                if isActive then
                    UI.li.cssClass("nav-item").cssClass("nav-item-active")(
                        UI.a("Overview").href(Href.Path(href)),
                        sidebarSections(toc)
                    )
                else
                    UI.li.cssClass("nav-item")(
                        UI.a("Overview").href(Href.Path(href))
                    )
            })
        )
    end overviewItem

    // The mobile-only nav toggle reveals/hides the sidebar drawer (B6). It is hidden on wide viewports
    // by the stylesheet; the label flips between open/closed with the disclosure state.
    private def navToggle(navOpenRef: SignalRef[Boolean])(using Frame): UI =
        UI.Ast.Reactive(navOpenRef.map { open =>
            UI.button
                .cssClass("docs-nav-toggle")
                .onClick(navOpenRef.updateAndGet(!_).unit)(if open then "Close modules" else "Browse modules")
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
        // and its next is the first module. A module page's prev/next steps within the module sequence,
        // and the first module's prev points back to the overview. The overview link is labelled
        // "Overview" and targets the intro route `/<prefix>/`.
        val overviewRoute = s"/$prefix/"
        val idx           = modules.indexWhere(m => currentRoute.endsWith(s"/${m.slug}/"))
        if idx < 0 then
            // On the overview itself: no prev; next is the first module (if any).
            val nextLink = modules.headMaybe match
                case Present(first) => UI.a(s"${first.displayName} >").href(Href.Path(s"/$prefix/${first.slug}/"))
                case Absent         => UI.span.cssClass("prev-next-disabled")(">")
            UI.nav.cssClass("prev-next")(
                UI.span.cssClass("prev-next-disabled")("<"),
                nextLink
            )
        else
            val next: Maybe[WebsiteModule] = if idx < modules.size - 1 then Present(modules(idx + 1)) else Absent
            // At index 0 the prev is the overview; otherwise it is the previous module.
            val prevLink =
                if idx > 0 then UI.a(s"< ${modules(idx - 1).displayName}").href(Href.Path(s"/$prefix/${modules(idx - 1).slug}/"))
                else UI.a("< Overview").href(Href.Path(overviewRoute))
            val nextLink = next match
                case Present(m) => UI.a(s"${m.displayName} >").href(Href.Path(s"/$prefix/${m.slug}/"))
                case Absent     => UI.span.cssClass("prev-next-disabled")(">")
            UI.nav.cssClass("prev-next")(prevLink, nextLink)
        end if
    end prevNextNav

end DocsApp
