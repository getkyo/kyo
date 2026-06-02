// PUBLIC unified site shell
package kyo.website

import kyo.*
import kyo.UI.Href
import kyo.UI.ImgSrc
import kyo.UI.Target

/** The unified single-page-app shell: one persistent header above a single route-reactive content
  * slot.
  *
  * `SiteApp` is the one shell both the JVM static-site generator and the JS bundle render, so a
  * route's server-rendered HTML and the bundle's first render produce a structurally identical
  * `data-kyo-path` tree (the hydration-parity contract, INV-003). The header is rendered once and
  * never remounts during a content swap; the content slot is a single reactive boundary at a fixed
  * position immediately below the header, so swapping the body (landing to docs, or one docs page to
  * another) does not disturb the header or the layout.
  *
  * `SiteApp` carries no `org.scalajs.dom`: the route information it needs is passed in as plain
  * values (`content` is a `Signal[UI]` the caller already built; the JVM passes a constant signal,
  * the JS bundle passes a `SignalRef` updated by its nav fiber). This keeps the shell body-agnostic;
  * it renders whatever body the caller's content signal holds.
  *
  * The header reuses the landing chrome class family (`brand`/`mark`/`links`/`right`/`btn`/`ver`)
  * plus the docs `search-input`, under two new wrapper classes: `site-header` (the full-bleed sticky
  * bar) and `site-header-inner` (the 1500px-capped flex row, matching the docs shell width).
  *
  * The `search-results` region is always present (an empty container at the empty query) so the
  * SSG shell and the bundle's first render are structurally identical; the input is rendered but
  * inert until the search wiring lands in a later phase.
  */
object SiteApp:

    /** Compose the unified shell: the persistent header above one route-reactive content slot.
      *
      * @param versions
      *   All available documentation versions, populating the header version dropdown (display-only).
      * @param docsHome
      *   The header Docs/Modules/Get-started target: the first module under the active prefix,
      *   `/<prefix>/<firstSlug>/`, falling back to `/<prefix>/` when a version has no modules.
      * @param searchIndex
      *   The search index signal. `Signal.initConst(DocsSearch.Index(Chunk.empty))` on the SSG path;
      *   a `SignalRef` filled lazily on the bundle. Inert until the search wiring phase.
      * @param queryRef
      *   The header search query reference. Empty on first render. Inert until the search wiring
      *   phase.
      * @param content
      *   The route body (landing or docs), already built by the caller. A constant signal on the SSG
      *   path; a `SignalRef[UI]` updated by the nav fiber on the bundle.
      * @return
      *   A `UI < Sync` value representing the unified shell for one route.
      */
    def view(
        versions: Chunk[WebsiteVersion],
        docsHome: String,
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String],
        content: Signal[UI]
    )(using Frame): UI < Sync =
        Sync.defer {
            // A bare flex-column wrapper (base div rule): the full-bleed header stacks above the one
            // content slot. The content slot is a single reactive boundary at a fixed position, so its
            // data-kyo-path is stable as long as the header structure is identical across SSG and
            // bundle (it is the same SiteApp.view).
            UI.div(
                siteHeader(versions, docsHome, searchIndex, queryRef),
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(content.map(c => c))
            )
        }
    end view

    // ---- Private helpers ----

    private def siteHeader(
        versions: Chunk[WebsiteVersion],
        docsHome: String,
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String]
    )(using Frame): UI =
        val versionOptions: Seq[(String, String)] = versions.toSeq.map(v => v.tag -> v.label)
        UI.header.cssClass("site-header").data("section", "header")(
            UI.div.cssClass("site-header-inner")(
                UI.a.cssClass("brand").data("role", "logo").href(Href.Path("/"))(
                    UI.img(ImgSrc.Path("/kyo.png"), "Kyo").cssClass("mark"),
                    UI.span("kyo")
                ),
                UI.nav.cssClass("links")(
                    UI.a("Docs").href(Href.Path(docsHome)),
                    UI.a("Modules").href(Href.Path(docsHome)),
                    UI.a("API")
                        .href(Href.External("https", "//javadoc.io/doc/io.getkyo/kyo-core_3"))
                        .target(Target.Blank),
                    UI.a("GitHub")
                        .href(Href.External("https", "//github.com/getkyo/kyo"))
                        .target(Target.Blank)
                ),
                UI.div.cssClass("right")(
                    UI.input.cssClass("search-input").placeholder("Search docs"),
                    searchResults(searchIndex, queryRef),
                    UI.dropdown(versionOptions*).cssClass("ver"),
                    UI.a
                        .cssClass("btn")
                        .cssClass("btn-primary")
                        .href(Href.Path(docsHome))("Get started")
                )
            )
        )
    end siteHeader

    private def searchResults(searchIndex: Signal[DocsSearch.Index], queryRef: SignalRef[String])(using Frame): UI =
        // Inert until the search wiring phase: the query is empty on first render, so the region is an
        // empty .search-results container. Reading both inputs keeps the reactive boundary structurally
        // identical to the future populated region (results update when either the query or the
        // just-loaded index changes).
        val results = queryRef.zip(searchIndex).map { case (q, idx) => resultList(DocsSearch.filter(idx, q)) }
        // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
        UI.Ast.Reactive(results)
    end searchResults

    private def resultList(hits: Chunk[DocsSearch.Hit])(using Frame): UI =
        UI.div.cssClass("search-results")(
            hits.toSeq.map { hit =>
                UI.a.cssClass("search-result").href(Href.Path(s"/latest/${hit.slug}/"))(
                    UI.span(hit.title)
                )
            }*
        )

end SiteApp
