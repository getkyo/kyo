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
  *      reload (D1), `/<prefix>/<slug>/` and the overview `/<prefix>/` both fetch + transpile
  *      `content.md` and swap the article/TOC in place (the overview's `content.md` is the root-README
  *      intro), and any genuinely off-tree route falls back to a full browser navigation.
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

    // Guards the one-shot heading-index fetch: the manifest `toc` is fetched at most once, on the
    // first search-box focus, and cached into `searchIndex`. Re-focus reuses the loaded index.
    // Unsafe: mutable module-level flag in a JS bundle; the single-threaded JS event loop is safe.
    private var searchIndexLoaded: Boolean = false

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
        val prefix = activePrefix(initialRoute)
        val home   = docsHome(island.content, prefix)
        // The physical trees a `/<prefix>/` intro route may legitimately name: `latest`, every
        // version's own tag, AND the seeded island's own version tag. A single-segment route outside
        // this set is off-tree and full-navigates. Including the island tag decouples intro
        // classification from the `#versions-island` element: when that island is absent (a non-SSG
        // harness, or a page that omits it) `versions` is empty, but the reader is still browsing
        // within the island's own tree, so `/<islandTag>/` must classify as intro, not off-tree
        // (AF-5). When the versions island IS present the island tag is already in the version set,
        // so the extra term is idempotent.
        val knownPrefixes: Set[String] = knownPrefixesOf(island, versions)
        // The known module slugs for the seeded tree, derived once from the island's module list. A
        // multi-segment route whose last segment is NOT in this set is off-tree (AF-4): it full-
        // navigates to a clean server 404 instead of fetching a missing content.md into a broken shell.
        val knownSlugs: Set[String] = knownSlugsOf(island)
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
            landingBody <- LandingApp.body(home)
            content     <- Signal.initRef[UI](if isRootRoute(initialRoute) then landingBody else docsBody)
            queryRef    <- Signal.initRef("")
            // Seed the search index with a title-only index built synchronously from the boot island
            // (titles/slugs/groups), so the very first keystroke already matches module titles without
            // waiting on a fetch. The heading-aware index loads lazily on first focus (onSearchFocus).
            searchIndex <- Signal.initRef(titleIndex(island.content, prefix))
            _           <- navFiber(route, knownPrefixes, knownSlugs, island, content, articleRef, tocRef, landingBody, docsBody)
            view <- SiteApp.view(
                versions,
                home,
                searchIndex,
                queryRef,
                // Enter-driven result selection routes client-side through the History API; the plain
                // <a> rows route via the UILocation click interceptor on their own. After the push,
                // scroll to the hash so an Enter on a heading hit lands on the section even when the
                // module page is already loaded (the nav fiber does not re-fire for a same-route push).
                target => UILocation.push(target).andThen(scrollToHash()),
                loadSearchIndex(island.content, prefix, searchIndex),
                content
            )
        yield view
        end for
    end build

    /** Build a title-only search index synchronously from the boot island: one entry per module
      * (title/slug/group) under `prefix`, with no headings yet. Used as the immediate index so search
      * works on the first keystroke; [[loadSearchIndex]] upgrades it with section headings on focus.
      */
    private def titleIndex(content: WebsiteContent, prefix: String): DocsSearch.Index =
        val modules = content.groups.flatMap(_.modules)
        DocsSearch.headingIndex(prefix, modules, _ => Chunk.empty)
    end titleIndex

    /** Lazily fetch the version manifest `toc` once (on the first search focus) and upgrade
      * `searchIndex` to the heading-aware index, so heading matches surface a `#<slug>` anchor. The
      * fetch runs inside the search fiber (it never blocks initial load), is guarded by
      * `searchIndexLoaded` so it runs at most once, and degrades gracefully: a fetch failure leaves
      * the title-only index in place rather than throwing into the console.
      */
    private def loadSearchIndex(
        content: WebsiteContent,
        prefix: String,
        searchIndex: SignalRef[DocsSearch.Index]
    )(using Frame): Unit < Async =
        if searchIndexLoaded then Kyo.unit
        else
            searchIndexLoaded = true
            val modules = content.groups.flatMap(_.modules)
            Abort.run[Throwable](Abort.catching[Throwable](DocsClient.routeTable(prefix))).map {
                case Result.Success(table) =>
                    searchIndex.set(DocsSearch.headingIndex(prefix, modules, s => table.headingsBySlug.getOrElse(s, Chunk.empty)))
                case Result.Failure(_) | Result.Panic(_) =>
                    // Leave the title-only index in place; search still works on titles.
                    Kyo.unit
            }
        end if
    end loadSearchIndex

    /** Derive the set of known physical tree prefixes from the seeded island and the versions list.
      *
      * The set contains `latest`, every version tag from the versions list, and the island's own
      * version tag. The island tag is included unconditionally so that `/<islandTag>/` classifies as
      * [[RouteKind.Intro]] even when the `#versions-island` element is absent (i.e. `versions` is
      * empty). When the versions island IS present the island tag is already in the set, making the
      * extra term idempotent (AF-5).
      */
    private[website] def knownPrefixesOf(island: DocsClient.DocsIsland, versions: Chunk[WebsiteVersion]): Set[String] =
        versions.toSeq.map(_.tag).toSet + LatestPrefix + island.content.version.tag

    /** Derive the set of known module slugs for the seeded island's content tree.
      *
      * A multi-segment route whose last segment is NOT in this set is [[RouteKind.OffTree]]: the SPA
      * full-navigates to a clean server 404 rather than fetching a missing `content.md` into a broken
      * docs shell (AF-4). The set is derived directly from the island so a regression in the
      * slug-derivation logic is caught by tests that call this helper.
      */
    private[website] def knownSlugsOf(island: DocsClient.DocsIsland): Set[String] =
        island.content.groups.flatMap(_.modules).map(_.slug).toSet

    /** The four route kinds the nav fiber dispatches on, named so the classification is a pure,
      * testable decision separate from the effectful branch bodies in [[navFiber]].
      *
      *   - [[Landing]]: the root `/` (zero path segments).
      *   - [[Module]]: a `/<prefix>/<slug>/` page whose last segment names a known module.
      *   - [[Intro]]: a single-segment `/<knownPrefix>/` route naming a real tree.
      *   - [[OffTree]]: anything else (an unknown prefix, OR a multi-segment route whose last segment
      *     is not a known module slug), which hands off to a full browser navigation.
      */
    private[website] enum RouteKind derives CanEqual:
        case Landing, Module, Intro, OffTree

    /** Classify a route's path segments into a [[RouteKind]], the pure decision [[navFiber]] dispatches
      * on. A multi-segment route is a [[Module]] ONLY when its last segment is a known module slug;
      * an unknown multi-segment slug (e.g. `/latest/does-not-exist/`) is [[OffTree]] so it full-
      * navigates to a clean server 404 instead of fetching a missing `content.md` into a broken docs
      * shell (AF-4). A single-segment route is an [[Intro]] only when it names a known prefix.
      */
    private[website] def classifyRoute(
        segments: Array[String],
        knownPrefixes: Set[String],
        knownSlugs: Set[String]
    ): RouteKind =
        if segments.isEmpty then RouteKind.Landing
        else if segments.length >= 2 && knownSlugs.contains(segments(segments.length - 1)) then RouteKind.Module
        else if segments.length == 1 && knownPrefixes.contains(segments(0)) then RouteKind.Intro
        else RouteKind.OffTree
    end classifyRoute

    /** The single nav fiber. On each new route it dispatches on [[classifyRoute]] into one of four
      * branches:
      *   - [[RouteKind.Landing]] (root `/`): swap `content` to the landing body, no reload (D1).
      *   - [[RouteKind.Module]] (`/<prefix>/<slug>/` whose last segment is a known module slug): fetch +
      *     transpile `content.md` (cache), update `articleRef`/`tocRef`, and swap `content` to the docs
      *     body.
      *   - [[RouteKind.Intro]] (`/<knownPrefix>/`, exactly one segment naming a real tree, `latest` or a
      *     known version tag): the overview, fetched and rendered exactly like a module from the route's
      *     `content.md` (the root-README intro), so the rail's Overview item is active and expands to the
      *     intro's `## ` sections.
      *   - [[RouteKind.OffTree]] (an unknown single-segment prefix OR a multi-segment route whose last
      *     segment is not a known module slug, e.g. `/latest/does-not-exist/`, AF-4): a full browser
      *     navigation as the narrow safety fallback, so the server resolves the real page or a clean 404
      *     instead of the SPA fetching a missing `content.md` into a broken docs shell.
      *
      * Same-document `#anchor` clicks never reach this fiber: `UILocation` skips same-document links
      * (`UILocation.scala:60-62`), so a TOC or landing `#how` anchor scrolls natively without changing
      * the route signal. After every in-shell branch the fiber updates the head (SEO-4).
      */
    private def navFiber(
        route: Signal[String],
        knownPrefixes: Set[String],
        knownSlugs: Set[String],
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
                        classifyRoute(segments, knownPrefixes, knownSlugs) match
                            case RouteKind.Landing =>
                                // Root `/`: swap to the landing body (no reload, D1).
                                content.set(landingBody).andThen(updateHead(nextRoute, island))
                            case RouteKind.Module =>
                                // Module route: fetch/transpile content.md, update article + TOC, show docs.
                                showContentRoute(nextRoute, island, content, articleRef, tocRef, docsBody)
                            case RouteKind.Intro =>
                                // Intro/overview `/<knownPrefix>/`: the root-README overview is now real
                                // content served at `/<prefix>/content.md`, so the intro is a content route
                                // exactly like a module: fetch/transpile content.md, update article + TOC,
                                // show docs. The Overview is the active rail item (DocsApp keys it on the
                                // single-segment intro route), and its `## ` sections come from the TOC set
                                // here.
                                showContentRoute(nextRoute, island, content, articleRef, tocRef, docsBody)
                            case RouteKind.OffTree =>
                                // Off-tree route: a single segment that is not a known prefix, OR a multi-
                                // segment route whose last segment is not a known module slug (AF-4). Hand off
                                // to a full browser navigation so the server resolves the real page or a clean
                                // 404 instead of fetching a missing content.md into a broken docs shell (D1).
                                // Unsafe: DOM bridge for the off-tree full-navigate fallback.
                                Sync.defer(dom.window.location.href = nextRoute)
                yield Loop.continue
            }
        }
    end navFiber

    /** Show a content route (a module page OR the intro/overview): fetch the route's `content.md` (from
      * the cache when seeded, else over the network, caching the result), transpile it, update the
      * shared `articleRef`/`tocRef`, swap `content` to the one docs body, update the head (SEO-4), and
      * scroll to the URL hash or the top (B5). Both the [[RouteKind.Module]] and [[RouteKind.Intro]]
      * branches share this: the overview is fetched and rendered exactly like a module, so the rail's
      * Overview item expands to the intro's `## ` sections and the overview prose renders identically to
      * the SSG first paint (hydration parity).
      */
    private def showContentRoute(
        nextRoute: String,
        island: DocsClient.DocsIsland,
        content: SignalRef[UI],
        articleRef: SignalRef[UI],
        tocRef: SignalRef[Chunk[DocsMarkdown.Heading]],
        docsBody: UI
    )(using Frame): Unit < Async =
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
            // After the article re-renders, scroll to the URL hash if present (a heading-hit search
            // result navigates to /<prefix>/<slug>/#<heading>; the anchor element only exists once this
            // branch renders the article). With NO hash (sidebar nav, prev/next) reset to the top so the
            // new page starts at its heading instead of inheriting the previous page's scrollY (B5).
            _ <- scrollToHashOrTop()
        yield ()
    end showContentRoute

    /** SEO-4: update `document.title` and the `<link rel=canonical>` href to match `route`, with no
      * reload. The strings mirror `WebsiteGenerator.docOpts` / the landing opts exactly:
      *   - root `/`: title `"Kyo | Build with AI. Ship something that holds."`, canonical
      *     `"https://getkyo.io/"`.
      *   - module `/<prefix>/<slug>/`: title `"<slug> | Kyo docs <label>"`, canonical
      *     `"https://getkyo.io/<prefix>/<slug>/"`.
      *   - intro/overview `/<prefix>/`: title `"Overview | Kyo docs <label>"`, canonical
      *     `"https://getkyo.io/<prefix>/"`.
      *
      * The intro title is `"Overview | Kyo docs <label>"`, matching `WebsiteGenerator.introOpts` whose
      * title is the intro's H1 text (the kyo root-README intro has no H1) falling back to "Overview".
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
                else s"Overview | Kyo docs $label"
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

    /** The bounded scroll-poll budget: up to [[ScrollMaxAttempts]] lookups, [[ScrollPollInterval]]
      * apart, for a total settle budget of 200ms (10 * 20ms), well within human perception of a snap
      * nav. The article patch applies on the event loop after `content.set`, so the first attempt may
      * miss; each subsequent attempt yields the event loop a chance to apply the patch (AF-9).
      */
    private val ScrollMaxAttempts: Int       = 10
    private val ScrollPollInterval: Duration = 20.millis

    /** Scroll the element named by the current URL hash into view, if any.
      *
      * A heading-hit search result navigates to `/<prefix>/<slug>/#<heading-slug>`: the browser does
      * not auto-scroll because the article (and so the `#<heading-slug>` element) only renders after
      * the route changes and this fiber swaps the content. This reads `window.location.hash` once and,
      * when present, polls for the anchor element with a BOUNDED retry: up to [[ScrollMaxAttempts]]
      * lookups [[ScrollPollInterval]] apart, yielding the event loop between attempts so the reactive
      * article patch has a chance to apply before each lookup (AF-9). On a large article the patch may
      * not have applied on the first attempt, so a single fixed-sleep lookup raced the render and the
      * scroll silently no-op'd; the bounded poll lands it once the anchor appears. A missing hash, or
      * an element that never appears after the full budget, is a clean no-op (no crash, no console
      * error): the slug may have changed in a new version or the article is not on this route.
      *
      * The poll is a [[Loop]] over the attempt count (not mutual recursion), so the iteration state is
      * an explicit `Int` carried by the loop and there is no self-referencing closure to leak.
      */
    /** Module-navigation scroll reset (B5). When the URL carries a fragment, delegate to
      * [[scrollToHash]] so a heading-hit search result lands on its section. When there is NO
      * fragment (sidebar nav, prev/next, or a plain module link), scroll the window to the top so the
      * freshly-rendered article starts at its heading instead of keeping the previous page's scrollY
      * (which left the reader at the bottom of the new page).
      */
    private def scrollToHashOrTop()(using Frame): Unit < Async =
        // Unsafe: DOM bridge to read the fragment and reset scroll. Plain DOM reads/calls on the
        // single-threaded JS event loop; no Kyo state involved.
        Sync.defer(dom.window.location.hash).map { hash =>
            if hash.length <= 1 then Sync.defer(dom.window.scrollTo(0, 0))
            else scrollToHash()
        }
    end scrollToHashOrTop

    private def scrollToHash()(using Frame): Unit < Async =
        // Unsafe: DOM bridge to read the fragment target. Plain DOM reads on the single-threaded JS
        // event loop; no Kyo state involved.
        Sync.defer(dom.window.location.hash).map { hash =>
            if hash.length <= 1 then Kyo.unit
            else
                val id = hash.substring(1)
                Loop[Int, Unit, Async](0) { attempt =>
                    // Unsafe: DOM bridge to look up and scroll to the fragment target. Plain DOM
                    // reads/calls on the single-threaded JS event loop; no Kyo state involved.
                    Sync.defer(dom.document.getElementById(id)).map { el =>
                        if el != null then
                            el.scrollIntoView()
                            Loop.done
                        else if attempt >= ScrollMaxAttempts - 1 then
                            // Bounded retry exhausted: give up cleanly, the anchor never appeared.
                            Loop.done
                        else
                            Async.sleep(ScrollPollInterval).andThen(Loop.continue(attempt + 1))
                    }
                }
            end if
        }
    end scrollToHash

    private def isRootRoute(path: String): Boolean =
        path.split('/').count(_.nonEmpty) == 0

    /** The active physical prefix for the initial route. `/` resolves to `latest` (the tree the SSG
      * seeds the landing from); any other route uses its own leading segment so the reader stays in
      * the tree they landed on (WARN-1).
      */
    private def activePrefix(path: String): String =
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
