// flow-allow: PUBLIC docs app view
package kyo.website

import kyo.*
import kyo.UI.Href

/** The 3-pane documentation shell as a kyo-ui `UI` value.
  *
  * Assembles the docs chrome: a top header with logo, version switcher, and search input; a left
  * sidebar listing modules by group with active-route highlighting; a main content area embedding
  * the transpiled article subtree; and a right table-of-contents pane built from the heading
  * outline.
  *
  * The shell is a pure composition of kyo-ui elements styled via `WebsiteStyles.sheet` CSS
  * classes. No raw CSS or raw HTML is used here; the sole `UI.rawHtml` exception lives inside
  * `DocsMarkdown.transpile` for the few inline image/link snippets in READMEs.
  *
  * Navigation links in the sidebar are plain `<a>` elements; `UILocation`'s capture-phase anchor
  * interceptor (JS-only) converts same-origin clicks to `pushState` without per-link wiring.
  *
  * @see
  *   [[DocsApp.view]] for the main entry point
  */
object DocsApp:

    /** Assemble the complete 3-pane docs shell for one route.
      *
      * The returned `UI < Sync` renders the full chrome: header (logo + version switcher), left
      * sidebar (module groups, active link highlighting), main content area (wrapping `article`),
      * and right TOC pane (headings with `#slug` links). The content area embeds `article`
      * directly; when `article` is a `Reactive` node the kyo-ui renderer makes that region
      * reactive without additional wrapping (INV-013).
      *
      * For SSG call sites: pass the pre-transpiled `DocsMarkdown.Rendered.article` as `article`.
      * For the bundle: pass `articleRef.render(a => a)` as `article` so the region re-renders on
      * navigation (the `Reactive` node is a valid `UI`).
      *
      * @param content
      *   The versioned documentation content (groups, modules, version record).
      * @param versions
      *   All available versions for the dropdown (INV-010).
      * @param route
      *   Signal tracking the current pathname, used to compute active sidebar state and prev/next.
      * @param toc
      *   The heading outline for the TOC pane (from `DocsMarkdown.Rendered.headings`).
      * @param article
      *   The transpiled article subtree to embed in the content area. May be a `Reactive` node.
      * @return
      *   A `UI < Sync` representing the complete 3-pane docs shell.
      */
    def view(
        content: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        route: Signal[String],
        toc: Chunk[DocsMarkdown.Heading],
        article: UI
    )(using Frame): UI < Sync =
        Sync.defer {
            val allModules = content.groups.flatMap(_.modules)
            UI.div.cssClass("docs-shell")(
                docsHeader(versions),
                sidebar(content, route),
                contentArea(article, allModules, route),
                tocPane(toc)
            )
        }
    end view

    // ---- Private helpers ----

    private def docsHeader(versions: Chunk[WebsiteVersion])(using Frame): UI =
        val versionOptions: Seq[(String, String)] = versions.toSeq.map(v => v.tag -> v.label)
        UI.header.cssClass("docs-header")(
            UI.div.cssClass("wrap").cssClass("nav-bar")(
                UI.a.cssClass("brand").href(Href.Path("/"))(
                    UI.span("kyo")
                ),
                UI.nav.cssClass("docs-nav")(
                    UI.a("Docs").href(Href.Path("/latest/")),
                    UI.a("API")
                        .href(Href.External("https", "//javadoc.io/doc/io.getkyo/kyo-core_3"))
                ),
                UI.div.cssClass("docs-header-right")(
                    UI.input.cssClass("search-input").placeholder("Search docs"),
                    UI.dropdown(versionOptions*).cssClass("ver")
                )
            )
        )
    end docsHeader

    private def versionBanner(current: WebsiteVersion)(using Frame): UI =
        if current.latest then UI.empty
        else
            UI.div.cssClass("version-banner")(
                UI.span("You are viewing an older version. This is not the latest release.")
            )
        end if
    end versionBanner

    private def sidebar(content: WebsiteContent, route: Signal[String])(using Frame): UI =
        UI.div.cssClass("sidebar")(
            versionBanner(content.version),
            UI.nav.cssClass("sidebar-nav")(
                content.groups.toSeq.map { group =>
                    UI.div.cssClass("sidebar-group")(
                        UI.div.cssClass("sidebar-group-name")(UI.span(group.name)),
                        UI.ul(
                            group.modules.toSeq.map { mod =>
                                val href         = s"/latest/${mod.slug}/"
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
        )
    end sidebar

    private def contentArea(article: UI, modules: Chunk[WebsiteModule], route: Signal[String])(using Frame): UI =
        UI.main.cssClass("docs-content")(
            article,
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
            UI.Ast.Reactive(route.map(r => prevNextNav(modules, r)))
        )
    end contentArea

    private def tocPane(toc: Chunk[DocsMarkdown.Heading])(using Frame): UI =
        UI.div.cssClass("toc")(
            UI.nav.cssClass("toc-nav")(
                toc.toSeq.map { heading =>
                    val levelClass = if heading.level == 1 then "toc-h1"
                    else if heading.level >= 3 then "toc-item sub"
                    else "toc-item"
                    UI.div.cssClass(levelClass)(
                        UI.a(heading.text).href(Href.Fragment(heading.slug))
                    )
                }*
            )
        )
    end tocPane

    private def prevNextNav(modules: Chunk[WebsiteModule], currentRoute: String)(using Frame): UI =
        val (prev, next) = prevNext(modules, currentRoute)
        val prevLink = prev match
            case Present(m) => UI.a(s"← ${m.title}").href(Href.Path(s"/latest/${m.slug}/"))
            case Absent     => UI.span.cssClass("prev-next-disabled")("←")
        val nextLink = next match
            case Present(m) => UI.a(s"${m.title} →").href(Href.Path(s"/latest/${m.slug}/"))
            case Absent     => UI.span.cssClass("prev-next-disabled")("→")
        UI.nav.cssClass("prev-next")(prevLink, nextLink)
    end prevNextNav

    private def prevNext(modules: Chunk[WebsiteModule], currentRoute: String): (Maybe[WebsiteModule], Maybe[WebsiteModule]) =
        val idx = modules.indexWhere { m =>
            currentRoute.endsWith(s"/${m.slug}/") || currentRoute == s"/latest/${m.slug}/"
        }
        if idx < 0 then (Absent, Absent)
        else
            val prev = if idx > 0 then Present(modules(idx - 1)) else Absent
            val next = if idx < modules.size - 1 then Present(modules(idx + 1)) else Absent
            (prev, next)
        end if
    end prevNext

end DocsApp
