// PUBLIC unified site shell
package kyo.website

import kyo.*
import kyo.UI.Href
import kyo.UI.ImgSrc
import kyo.UI.Keyboard
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
  * the JS bundle passes a `SignalRef` updated by its nav fiber). Client-side navigation from search
  * is injected as a `navigate` callback: the bundle passes `UILocation.push`, the generator passes a
  * no-op, so the shared shell never references the JS-only router directly.
  *
  * The header reuses the landing chrome class family (`brand`/`mark`/`links`/`right`/`btn`/`ver`)
  * plus the docs `search-input`, under two new wrapper classes: `site-header` (the full-bleed sticky
  * bar) and `site-header-inner` (the 1500px-capped flex row, matching the docs shell width).
  *
  * The header search box is live: typing writes the `queryRef`, and the `search-results` dropdown
  * runs [[DocsSearch.filter]] over the search index, rendering one `search-result` row per hit
  * (module title, plus a `search-result-sub` heading label on heading hits). Each row is a plain
  * `<a>` so a mouse click routes through the `UILocation` interceptor; Enter activates the
  * highlighted (or first) row via `navigate`, Arrow Up/Down move the `search-result-active`
  * highlight, and Escape clears the query and closes the dropdown. At the empty query the dropdown
  * is an empty container, so the SSG shell and the bundle's first render are structurally identical.
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
      *   a `SignalRef` filled lazily on the bundle (titles from the boot island, headings from the
      *   version manifest).
      * @param queryRef
      *   The header search query reference. Empty on first render; written on each keystroke.
      * @param navigate
      *   Client-side navigation for Enter-driven result selection. The bundle passes
      *   `UILocation.push`; the SSG generator passes a no-op (no keyboard handling at render time).
      * @param onSearchFocus
      *   Run once the user first focuses the search box. The bundle uses this to lazily fetch and
      *   cache the heading index (so the manifest fetch never blocks initial load); the SSG passes a
      *   no-op.
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
        navigate: String => Unit < Async,
        onSearchFocus: => Unit < Async,
        content: Signal[UI]
    )(using Frame): UI < Sync =
        for
            // The highlighted result row index (-1 = none). Driven by Arrow Up/Down; reset on input.
            activeRef <- Signal.initRef(-1)
        yield
            // A bare flex-column wrapper (base div rule): the full-bleed header stacks above the one
            // content slot. The content slot is a single reactive boundary at a fixed position, so its
            // data-kyo-path is stable as long as the header structure is identical across SSG and
            // bundle (it is the same SiteApp.view).
            UI.div(
                siteHeader(versions, docsHome, searchIndex, queryRef, activeRef, navigate, onSearchFocus),
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(content.map(c => c))
            )
    end view

    // ---- Private helpers ----

    private def siteHeader(
        versions: Chunk[WebsiteVersion],
        docsHome: String,
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String],
        activeRef: SignalRef[Int],
        navigate: String => Unit < Async,
        onSearchFocus: => Unit < Async
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
                    UI.input
                        .cssClass("search-input")
                        .placeholder("Search docs")
                        .value(queryRef)
                        .onFocus(onSearchFocus)
                        .onInput(q => queryRef.set(q).andThen(activeRef.set(-1)))
                        .onKeyDown(handleKey(searchIndex, queryRef, activeRef, navigate)),
                    searchResults(searchIndex, queryRef, activeRef),
                    UI.dropdown(versionOptions*).cssClass("ver"),
                    UI.a
                        .cssClass("btn")
                        .cssClass("btn-primary")
                        .href(Href.Path(docsHome))("Get started")
                )
            )
        )
    end siteHeader

    /** Keyboard handling on the search input.
      *
      *   - Arrow Down / Up move the highlighted row within `[0, hits.size)`, clamped at the ends.
      *   - Enter activates the highlighted row, or the first row when none is highlighted, via
      *     `navigate` (the bundle's `UILocation.push`); the query is cleared so the dropdown closes.
      *   - Escape clears the query (closing the dropdown) and resets the highlight.
      *
      * The handler reads `queryRef`/`searchIndex` at keypress time and recomputes the hits with the
      * same [[DocsSearch.filter]] the dropdown renders, so the highlight and the navigation target
      * always agree with what the user sees.
      */
    private def handleKey(
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String],
        activeRef: SignalRef[Int],
        navigate: String => Unit < Async
    )(using Frame): UI.KeyboardEvent => Any < Async =
        evt =>
            evt.key match
                case Keyboard.ArrowDown =>
                    currentHits(searchIndex, queryRef).map { hits =>
                        if hits.isEmpty then Kyo.unit
                        else activeRef.updateAndGet(i => math.min(i + 1, hits.size - 1).max(0)).unit
                    }
                case Keyboard.ArrowUp =>
                    currentHits(searchIndex, queryRef).map { hits =>
                        if hits.isEmpty then Kyo.unit
                        else activeRef.updateAndGet(i => math.max(i - 1, 0)).unit
                    }
                case Keyboard.Enter =>
                    for
                        hits   <- currentHits(searchIndex, queryRef)
                        active <- activeRef.get
                        idx = if active >= 0 && active < hits.size then active else 0
                        _ <-
                            if hits.isEmpty then Kyo.unit
                            else
                                navigate(hits(idx).route)
                                    .andThen(queryRef.set(""))
                                    .andThen(activeRef.set(-1))
                    yield ()
                case Keyboard.Escape =>
                    queryRef.set("").andThen(activeRef.set(-1))
                case _ => ()
    end handleKey

    private def currentHits(
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String]
    )(using Frame): Chunk[DocsSearch.Hit] < Sync =
        for
            q   <- queryRef.current
            idx <- searchIndex.current
        yield DocsSearch.filter(idx, q)

    private def searchResults(
        searchIndex: Signal[DocsSearch.Index],
        queryRef: SignalRef[String],
        activeRef: SignalRef[Int]
    )(using Frame): UI =
        // The dropdown reacts to the query, the index, and the highlight. combineLatest (NOT zip) is
        // required: zip emits only when ALL inputs change since the last emit, so typing alone (only
        // the query ticks) would never re-render. combineLatest re-emits when ANY input changes, so
        // the dropdown updates on each keystroke, when the heading index finishes loading, and when
        // the highlight moves.
        val results = queryRef.combineLatest(searchIndex).combineLatest(activeRef).map {
            case ((q, idx), active) => resultList(DocsSearch.filter(idx, q), active)
        }
        // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
        UI.Ast.Reactive(results)
    end searchResults

    private def resultList(hits: Chunk[DocsSearch.Hit], active: Int)(using Frame): UI =
        // The element stays in the tree on the empty query for SSG<->bundle hydration parity, but an
        // empty dropdown would otherwise paint a bare card (its bg/border/padding/shadow) as a faint
        // pill under the header. `hidden` when there are no hits makes the reset's
        // `[hidden]{display:none!important}` collapse it to nothing; a non-empty query renders the
        // card as before. Both the SSG and bundle paths call this same function, so parity holds.
        UI.div.cssClass("search-results").hidden(hits.isEmpty)(
            hits.toSeq.zipWithIndex.map { case (hit, i) =>
                val base          = UI.a.cssClass("search-result")
                val row           = if i == active then base.cssClass("search-result-active") else base
                val titleSpan: UI = UI.span.cssClass("search-result-title")(hit.title)
                val subSpan: Seq[UI] =
                    hit.sub.map(s => Seq[UI](UI.span.cssClass("search-result-sub")(s))).getOrElse(Seq.empty)
                val children: Seq[UI] = titleSpan +: subSpan
                row.href(Href.Path(hit.route))(children*)
            }*
        )

end SiteApp
