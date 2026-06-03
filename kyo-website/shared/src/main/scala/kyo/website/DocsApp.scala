// PUBLIC docs app view
package kyo.website

import kyo.*
import kyo.UI.Href

/** The 3-pane documentation content body as a kyo-ui `UI` value.
  *
  * Assembles the docs body: a left sidebar listing modules by group with active-route
  * highlighting; a main content area embedding the transpiled article subtree; and a right
  * table-of-contents pane built from the heading outline. This is the content body ONLY: the
  * persistent header (logo, search, version switcher) is owned by `SiteApp` (D5), so `body` no
  * longer renders its own header, and the header-out-of-shell layout invariant is now automatic.
  *
  * The body is a pure composition of kyo-ui elements styled via `WebsiteStyles.sheet` CSS classes.
  * No raw CSS or raw HTML is used here; the sole `UI.rawHtml` exception lives inside
  * `DocsMarkdown.transpile` for the few inline image/link snippets in READMEs.
  *
  * Navigation links in the sidebar are plain `<a>` elements; `UILocation`'s capture-phase anchor
  * interceptor (JS-only) converts same-origin clicks to `pushState` without per-link wiring.
  *
  * @see
  *   [[DocsApp.body]] for the main entry point
  */
object DocsApp:

    /** Assemble the 3-pane docs content body for one route (header excluded, owned by `SiteApp`).
      *
      * The returned `UI < Sync` renders the `docs-shell` 3-pane row: left sidebar (module groups,
      * active link highlighting), main content area (wrapping `article`), and right TOC pane
      * (headings with `#slug` links). The content area embeds `article` directly; when `article` is
      * a `Reactive` node the kyo-ui renderer makes that region reactive without additional wrapping
      * (INV-013).
      *
      * For SSG call sites: pass the pre-transpiled `DocsMarkdown.Rendered.article` as `article`.
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
      *   The heading outline for the TOC pane (from `DocsMarkdown.Rendered.headings`), as a `Signal`
      *   so the pane re-renders on client navigation. Pass `Signal.initConst(headings)` for a static
      *   page (the SSG generator), or a `SignalRef` updated per route for the client bundle.
      * @param article
      *   The transpiled article subtree to embed in the content area. May be a `Reactive` node.
      * @return
      *   A `UI < Sync` representing the 3-pane docs content body.
      */
    def body(
        content: WebsiteContent,
        prefix: String,
        route: Signal[String],
        tocSignal: Signal[Chunk[DocsMarkdown.Heading]],
        article: UI
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
            // The `docs-shell` is the 3-pane row: the persistent header is owned by SiteApp and sits
            // above this body, so the header-out-of-shell layout invariant holds automatically
            // (docs-shell is flex-direction:row; a header sibling here would steal a column and squish
            // the content). The mobile nav toggle is the first child so, when the drawer opens on a
            // narrow viewport, the revealed sidebar list sits directly below the button; on wide
            // viewports the toggle is hidden and the three panes lay out side by side (B6).
            UI.div.cssClass("docs-shell")(
                navToggle(navOpenRef),
                sidebar(content, route, prefix, navOpenRef),
                contentArea(article, allModules, route, prefix),
                tocPane(tocSignal)
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

    private def sidebar(content: WebsiteContent, route: Signal[String], prefix: String, navOpenRef: Signal[Boolean])(using Frame): UI =
        val nav =
            UI.nav.cssClass("sidebar-nav")(
                content.groups.toSeq.map { group =>
                    UI.div.cssClass("sidebar-group")(
                        UI.div.cssClass("sidebar-group-name")(UI.span(group.name)),
                        UI.ul(
                            group.modules.toSeq.map { mod =>
                                val href         = s"/$prefix/${mod.slug}/"
                                val activeSignal = route.map(r => r.endsWith(s"/${mod.slug}/") || r == href)
                                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                                UI.Ast.Reactive(activeSignal.map { isActive =>
                                    if isActive then
                                        UI.li.cssClass("nav-item").cssClass("nav-item-active")(
                                            UI.a(mod.title).href(Href.Path(href))
                                        )
                                    else
                                        UI.li.cssClass("nav-item")(
                                            UI.a(mod.title).href(Href.Path(href))
                                        )
                                })
                            }*
                        )
                    )
                }*
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

    // The mobile-only nav toggle reveals/hides the sidebar drawer (B6). It is hidden on wide viewports
    // by the stylesheet; the label flips between open/closed with the disclosure state.
    private def navToggle(navOpenRef: SignalRef[Boolean])(using Frame): UI =
        UI.Ast.Reactive(navOpenRef.map { open =>
            UI.button
                .cssClass("docs-nav-toggle")
                .onClick(navOpenRef.updateAndGet(!_).unit)(if open then "Close modules" else "Browse modules")
        })

    private def contentArea(article: UI, modules: Chunk[WebsiteModule], route: Signal[String], prefix: String)(using Frame): UI =
        UI.main.cssClass("docs-content")(
            article,
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
            UI.Ast.Reactive(route.map(r => prevNextNav(modules, r, prefix)))
        )
    end contentArea

    private def tocPane(tocSignal: Signal[Chunk[DocsMarkdown.Heading]])(using Frame): UI =
        // Reactive so client navigation swaps the outline to the new page's headings (the article and
        // sidebar are reactive too; a static TOC would keep showing the previous module's headings).
        // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
        UI.div.cssClass("docs-toc")(
            UI.Ast.Reactive(tocSignal.map(t => tocNav(t)))
        )

    private def tocNav(toc: Chunk[DocsMarkdown.Heading])(using Frame): UI =
        UI.nav.cssClass("toc-nav")(
            toc.toSeq.map { heading =>
                // Each entry carries a distinct per-level hook (toc-h1 / toc-h2 / toc-h3) so the
                // stylesheet can indent levels independently; level-3+ also carries the `sub` hook.
                val levelClass = heading.level match
                    case 1 => "toc-item toc-h1"
                    case 2 => "toc-item toc-h2"
                    case 3 => "toc-item toc-h3 sub"
                    case _ => "toc-item toc-h4 sub"
                UI.div.cssClass(levelClass)(
                    UI.a(heading.text).href(Href.Fragment(heading.slug))
                )
            }*
        )
    end tocNav

    private def prevNextNav(modules: Chunk[WebsiteModule], currentRoute: String, prefix: String)(using Frame): UI =
        // The intro route `/<prefix>/` matches no module, so there is no page to page to. Rendering the
        // pager there left two empty unlabeled `<`/`>` boxes (B12); on the intro show a brief
        // docs-home hint pointing at the first module instead.
        val idx = modules.indexWhere(m => currentRoute.endsWith(s"/${m.slug}/"))
        if idx < 0 then
            modules.headMaybe match
                case Present(first) =>
                    UI.div.cssClass("docs-home-hint")(
                        UI.span("Pick a module from the sidebar to get started, or "),
                        UI.a(s"open ${first.title}").href(Href.Path(s"/$prefix/${first.slug}/")),
                        UI.span(".")
                    )
                case Absent => UI.empty
        else
            val prev: Maybe[WebsiteModule] = if idx > 0 then Present(modules(idx - 1)) else Absent
            val next: Maybe[WebsiteModule] = if idx < modules.size - 1 then Present(modules(idx + 1)) else Absent
            val prevLink = prev match
                case Present(m) => UI.a(s"< ${m.title}").href(Href.Path(s"/$prefix/${m.slug}/"))
                case Absent     => UI.span.cssClass("prev-next-disabled")("<")
            val nextLink = next match
                case Present(m) => UI.a(s"${m.title} >").href(Href.Path(s"/$prefix/${m.slug}/"))
                case Absent     => UI.span.cssClass("prev-next-disabled")(">")
            UI.nav.cssClass("prev-next")(prevLink, nextLink)
        end if
    end prevNextNav

end DocsApp
