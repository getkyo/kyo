// PUBLIC browser bundle entry
package kyo.website

import kyo.*
import org.scalajs.dom

/** SPA bundle entry-point.
  *
  * Bootstraps the kyo website single-page application in the browser:
  *   1. Injects `WebsiteStyles.sheet` into the document `<head>` so styles are present before the
  *      first render.
  *   2. Reads the SSG-seeded islands (`#docs-island`, `#versions-island`) and the current route
  *      (via `UILocation.current`) to build the initial body (landing for `/`, docs for a
  *      `/<prefix>/...` route).
  *   3. Mounts the unified `SiteApp` shell ONCE around one route-reactive content slot, then drives
  *      that slot from a single nav fiber on `route.next`: `/` swaps to the landing body with no
  *      reload (D1), `/<prefix>/<slug>/` fetches + transpiles `content.md` and swaps the article/TOC
  *      in place, `/<prefix>/` swaps to an empty-article docs shell, and any genuinely off-tree route
  *      falls back to a full browser navigation.
  *
  * After every in-shell content swap the nav fiber also updates `document.title` and the
  * `<link rel=canonical>` href to match the new route (SEO-4), using the SAME title/canonical string
  * formats `WebsiteGenerator` emits at build time, so the in-browser head never diverges from what
  * crawlers indexed.
  *
  * This object is the one shared ESModule entry across all doc versions (INV-008).
  */
object WebsiteBundleMain:

    // Unsafe: Frame.internal is the boundary value here. `def main(args: Array[String])` is the
    // JS entry point imposed by Scala.js and there is no user code above it to thread a Frame
    // from. Same justification as SpaHarnessMain (SpaHarnessMain.scala:22).
    private given bundleFrame: Frame = Frame.internal

    // The physical tree the landing route `/` resolves into. The SSG emits the landing header's
    // Docs/Modules/Get-started target and seeds its island from the latest version under `latest/`
    // (WebsiteGenerator.emitLanding: landingHome = docsHome(c, "latest")), so the bundle must use the
    // SAME `latest` prefix for `/`, NOT the island's version tag (which is the real tag, e.g.
    // `v1.0.0-RC2`). Using the version tag here would point docsHome at `/v1.0.0-RC2/...` and diverge
    // from the SSG header, breaking hydration parity on `/`.
    private val LatestPrefix: String = "latest"

    // Markdown cache: populated with the current-route Markdown on first load (from the JSON
    // island), then populated with fetched Markdown on navigation (DocsClient.fetchMarkdown).
    // Unsafe: mutable module-level var in a JS bundle; single-threaded JS event loop is safe.
    private val markdownCache: scala.collection.mutable.Map[String, String] =
        scala.collection.mutable.Map.empty

    private def seedMarkdownCache(route: String, markdown: String): Unit =
        markdownCache(route) = markdown

    /** Read the SSG-seeded docs island from the DOM.
      *
      * The SSG writes a `<script id="docs-island" type="application/json">` element whose text
      * content is the JSON object `WebsiteGenerator.docsIsland` produced (the current route's content
      * metadata, version list, and raw Markdown source). When the element is PRESENT, its payload is
      * parsed via `DocsClient.parseDocsIsland`. When the element is ABSENT (a non-docs page or a test
      * harness without the SSG island), a safe empty island is returned and the SPA still mounts with
      * no pre-seeded content. The absent-element branch (here) and the empty-parse branch (inside
      * `parseDocsIsland`) are distinct: this method decides absence, the parser decides malformedness.
      */
    private def readDocsIsland(): DocsClient.DocsIsland =
        val el = dom.document.querySelector("#docs-island")
        if el == null then
            DocsClient.DocsIsland(
                content = WebsiteContent(
                    intro = "",
                    groups = Chunk.empty,
                    version = WebsiteVersion("latest", "latest", true)
                ),
                versions = Chunk.empty,
                markdown = ""
            )
        else
            // Unsafe: synchronous parse at JS entry; the event loop is single-threaded and this is
            // called before any Kyo fiber is running. parseDocsIsland is Sync-only.
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(DocsClient.parseDocsIsland(el.textContent))
        end if
    end readDocsIsland

    def main(args: Array[String]): Unit =
        // Unsafe: browser entry-point bridge; single controlled crossing from JS main into the
        // Kyo scheduler. AllowUnsafe is necessary here because we have no Kyo fiber context.
        runStylesheetUnsafe()
        runMountUnsafe(build())
    end main

    /** Build the unified SPA: one content `SignalRef[UI]` initialised to the initial route's body, one
      * nav fiber routing `/ <-> docs` client-side, and the single `SiteApp.view` mount.
      *
      * The header is rendered once by `SiteApp.view`; the nav fiber only ever rewrites the content
      * signal (and, for docs pages, the shared `articleRef`/`tocRef`), so the header never remounts on
      * navigation.
      */
    private def build()(using Frame): UI < (Sync & Async) =
        val island   = readDocsIsland()
        val versions = readVersions()
        val route    = UILocation.current
        // Unsafe: reading the current signal value at JS entry via evalOrThrow; safe because
        // UILocation initialises synchronously before this call and route.current is Sync-only.
        val initialRoute: String =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(route.current)
        // The active prefix is `latest` when on `/` (the SSG seeds the landing's header + island from
        // the latest version under `latest/`); otherwise it is the path's first physical segment so a
        // reader under `/v<X>/...` keeps navigating within that tree (WARN-1).
        val prefix = activePrefix(initialRoute, island.content.version.tag)
        val home   = docsHome(island.content, prefix)
        // The physical trees a `/<prefix>/` intro route may legitimately name: `latest` plus every
        // version's own tag. A single-segment route outside this set is off-tree and full-navigates.
        val knownPrefixes: Set[String] = versions.toSeq.map(_.tag).toSet + LatestPrefix
        // The island's Markdown is the current route's README when the initial route is a module page,
        // and "" on `/` and `/<prefix>/` (the SSG seeds an empty island there). Seed the cache so the
        // module branch reuses the first-paint content instead of re-fetching it.
        seedMarkdownCache(initialRoute, island.markdown)
        for
            initialRendered <- DocsMarkdown.transpile(island.markdown)
            articleRef      <- Signal.initRef[UI](initialRendered.article)
            tocRef          <- Signal.initRef[Chunk[DocsMarkdown.Heading]](initialRendered.headings)
            // ONE docs body instance, reused on every docs navigation. Its sidebar/article/TOC react
            // to `route`/`articleRef`/`tocRef`, so swapping module-to-module only updates those refs;
            // `content` is set to this body once (when arriving from the landing) and then left alone.
            docsBody <- DocsApp.body(
                island.content,
                prefix,
                route,
                tocRef,
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(articleRef.map(a => a))
            )
            landingBody <- LandingApp.body(versions, home)
            content     <- Signal.initRef[UI](if isRootRoute(initialRoute) then landingBody else docsBody)
            queryRef    <- Signal.initRef("")
            _           <- navFiber(route, knownPrefixes, island, content, articleRef, tocRef, landingBody, docsBody)
            view <- SiteApp.view(
                versions,
                home,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                content
            )
        yield view
        end for
    end build

    /** The single nav fiber. On each new route it picks one of four branches:
      *   - root `/` (zero path segments): swap `content` to the landing body, no reload (D1).
      *   - module `/<prefix>/<slug>/` (two or more segments): fetch + transpile `content.md` (cache),
      *     update `articleRef`/`tocRef`, and swap `content` to the docs body.
      *   - intro `/<knownPrefix>/` (exactly one segment that names a real tree, `latest` or a known
      *     version tag): clear the article/TOC and swap to the empty-article docs body.
      *   - genuinely off-tree (a single segment that is NOT a known prefix): a full browser navigation
      *     as the narrow safety fallback, so the server resolves the real page or a 404 instead of the
      *     SPA rendering a docs shell for a route that does not exist.
      *
      * Same-document `#anchor` clicks never reach this fiber: `UILocation` skips same-document links
      * (`UILocation.scala:60-62`), so a TOC or landing `#how` anchor scrolls natively without changing
      * the route signal. After every in-shell branch the fiber updates the head (SEO-4).
      */
    private def navFiber(
        route: Signal[String],
        knownPrefixes: Set[String],
        island: DocsClient.DocsIsland,
        content: SignalRef[UI],
        articleRef: SignalRef[UI],
        tocRef: SignalRef[Chunk[DocsMarkdown.Heading]],
        landingBody: UI,
        docsBody: UI
    )(using Frame): Fiber[Nothing, Any] < Sync =
        Fiber.initUnscoped {
            Loop.forever {
                for
                    nextRoute <- route.next
                    segments = nextRoute.split('/').filter(_.nonEmpty)
                    _ <-
                        if segments.isEmpty then
                            // Root `/`: swap to the landing body (no reload, D1).
                            content.set(landingBody).andThen(updateHead(nextRoute, island))
                        else if segments.length >= 2 then
                            // Module route: fetch/transpile content.md, update article + TOC, show docs.
                            for
                                md <- Sync.defer(markdownCache.getOrElse(nextRoute, ""))
                                fetched <-
                                    if md.nonEmpty then Sync.defer(md)
                                    else
                                        DocsClient.fetchMarkdown(nextRoute).map { f =>
                                            seedMarkdownCache(nextRoute, f)
                                            f
                                        }
                                rendered <- DocsMarkdown.transpile(fetched)
                                _        <- articleRef.set(rendered.article)
                                _        <- tocRef.set(rendered.headings)
                                _        <- content.set(docsBody)
                                _        <- updateHead(nextRoute, island)
                            yield ()
                        else if knownPrefixes.contains(segments(0)) then
                            // Intro `/<knownPrefix>/`: empty-article docs shell, no content.md fetch.
                            articleRef.set(UI.empty)
                                .andThen(tocRef.set(Chunk.empty))
                                .andThen(content.set(docsBody))
                                .andThen(updateHead(nextRoute, island))
                        else
                            // Off-tree single-segment route: hand off to a full browser navigation so the
                            // server resolves the real page or a 404 (narrow fallback; D1).
                            // Unsafe: DOM bridge for the off-tree full-navigate fallback.
                            Sync.defer(dom.window.location.href = nextRoute)
                        end if
                yield Loop.continue
            }
        }
    end navFiber

    /** SEO-4: update `document.title` and the `<link rel=canonical>` href to match `route`, with no
      * reload. The strings mirror `WebsiteGenerator.docOpts` / the landing opts exactly:
      *   - root `/`: title `"Kyo | Build with AI. Ship something that holds."`, canonical
      *     `"https://getkyo.io/"`.
      *   - module `/<prefix>/<slug>/`: title `"<slug> | Kyo docs <label>"`, canonical
      *     `"https://getkyo.io/<prefix>/<slug>/"`.
      *   - intro `/<prefix>/`: title `"Kyo docs <label>"`, canonical `"https://getkyo.io/<prefix>/"`.
      *
      * `<label>` is the seeded island's version label, the same record the SSG used to build the head
      * for the latest tree. The canonical href is the absolute `https://getkyo.io<route>` URL, matching
      * `WebsiteGenerator.docOpts.canonical = s"https://getkyo.io$route"`.
      */
    private def updateHead(route: String, island: DocsClient.DocsIsland)(using Frame): Unit < Sync =
        Sync.defer {
            val segments = route.split('/').filter(_.nonEmpty)
            val label    = island.content.version.label
            val title =
                if segments.isEmpty then "Kyo | Build with AI. Ship something that holds."
                else if segments.length >= 2 then s"${segments(segments.length - 1)} | Kyo docs $label"
                else s"Kyo docs $label"
            val canonical =
                if segments.isEmpty then "https://getkyo.io/"
                else s"https://getkyo.io$route"
            // Unsafe: DOM bridge for the SEO-4 head update. Single-threaded JS event loop; these are
            // plain document property/attribute writes with no Kyo state involved.
            dom.document.title = title
            val link = dom.document.querySelector("link[rel=canonical]")
            // The canonical <link> is always present in the SSG head (WebsitePage.pageHead emits it);
            // guard the null case so a harness without the element does not throw into the console.
            if link != null then link.setAttribute("href", canonical)
        }
    end updateHead

    private def isRootRoute(path: String): Boolean =
        path.split('/').count(_.nonEmpty) == 0

    /** The active physical prefix for the initial route. `/` resolves to `latest` (the tree the SSG
      * seeds the landing from); any other route uses its own leading segment so the reader stays in
      * the tree they landed on (WARN-1), falling back to the seeded version tag only when the path has
      * no leading segment but is not the root (defensive; should not occur in practice).
      */
    private def activePrefix(path: String, versionTag: String): String =
        val segments = path.split('/').filter(_.nonEmpty)
        if segments.isEmpty then LatestPrefix else segments(0)
    end activePrefix

    /** The header Docs/Modules/Get-started target for the seeded `content` under `prefix`: the first
      * module's route `/<prefix>/<firstSlug>/`, falling back to the prefix root `/<prefix>/`. Mirrors
      * `WebsiteGenerator.docsHome` so the bundle and the SSG agree on the header target (parity).
      */
    private def docsHome(content: WebsiteContent, prefix: String): String =
        content.groups.flatMap(_.modules).headOption.fold(s"/$prefix/")(m => s"/$prefix/${m.slug}/")

    /** Read the available versions list from the SSG-seeded DOM island.
      *
      * The SSG writes a `<script id="versions-island" type="application/json">` element whose text
      * content is a JSON array of `{tag, label, latest}` objects. This method queries that element and
      * parses its content via `DocsClient.parseVersionsIsland`.
      *
      * When the element is absent (e.g. in a test harness without the SSG island) an empty `Chunk` is
      * returned; the SPA still mounts with no version entries.
      */
    private def readVersions(): Chunk[WebsiteVersion] =
        val el = dom.document.querySelector("#versions-island")
        if el == null then Chunk.empty
        else
            // Unsafe: synchronous parse at JS entry; the event loop is single-threaded and
            // this is called before any Kyo fiber is running. parseVersionsIsland is Sync-only.
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(DocsClient.parseVersionsIsland(el.textContent))
        end if
    end readVersions

    // Unsafe: app-entry boundary bridge; injects stylesheet before first render from JS main.
    // This is the sanctioned unsafe tier crossing (matches SpaHarnessMain pattern).
    private def runStylesheetUnsafe(): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(UI.runStylesheet(WebsiteStyles.sheet)))

    // Unsafe: app-entry boundary bridge; mounts the SPA fiber from JS main into the Kyo
    // scheduler. This is the sanctioned unsafe tier crossing (matches SpaHarnessMain pattern).
    private def runMountUnsafe(view: UI < (Sync & Async)): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(Scope.run(
                view.flatMap(v => UI.runMount(v))
            )).unit
        ))
    end runMountUnsafe

end WebsiteBundleMain
