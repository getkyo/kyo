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
  *      reload, `/<prefix>/<slug>/` and the overview `/<prefix>/` both fetch + inject pre-rendered
  *      article HTML from `content.html` and swap the article/TOC in place (the overview's
  *      `content.html` is the root-README intro rendered at build time), and any genuinely off-tree
  *      route falls back to a full browser navigation.
  *
  * After every in-shell content swap the nav fiber also updates `document.title` and the
  * `<link rel=canonical>` href to match the new route (SEO-4), using the SAME title/canonical string
  * formats `WebsiteGenerator` emits at build time, so the in-browser head never diverges from what
  * crawlers indexed.
  *
  * This object is the one shared ESModule entry across all doc versions.
  */
object WebsiteBundleMain:

    // Unsafe: Frame.internal is the boundary value here. `def main(args: Array[String])` is the
    // JS entry point imposed by Scala.js and there is no user code above it to thread a Frame
    // from.
    private given bundleFrame: Frame = Frame.internal

    // The physical tree the landing route `/` resolves into. The SSG emits the landing header's
    // Docs/Modules/Get-started target and seeds its island from the latest version under `latest/`
    // (WebsiteGenerator.emitLanding: landingHome = docsHome(c, "latest")), so the bundle must use the
    // SAME `latest` prefix for `/`, NOT the island's version tag (which is the real tag, e.g.
    // `v1.0.0-RC2`). Using the version tag here would point docsHome at `/v1.0.0-RC2/...` and diverge
    // from the SSG header, breaking hydration parity on `/`.
    private val LatestPrefix: String = "latest"

    // Article cache: pre-rendered Article per route (island seed + fetched on nav).
    // Unsafe: mutable module-level var in a JS bundle; single-threaded JS event loop is safe.
    private val articleCache: scala.collection.mutable.Map[String, DocsClient.Article] =
        scala.collection.mutable.Map.empty

    private def seedArticleCache(route: String, article: DocsClient.Article): Unit =
        articleCache(route) = article

    /** Read the SSG-seeded docs island from the DOM.
      *
      * The SSG writes a `<script id="docs-island" type="application/json">` element whose text
      * content is the JSON object `WebsiteGenerator.docsIsland` produced (the current route's content
      * metadata, version list, pre-rendered article HTML, and heading outline). When the element is
      * PRESENT, its payload is parsed via `DocsClient.parseDocsIsland`. When the element is ABSENT (a
      * non-docs page or a test harness without the SSG island), a safe empty island is returned and the
      * SPA still mounts with no pre-seeded content. The absent-element branch (here) and the
      * empty-parse branch (inside `parseDocsIsland`) are distinct: this method decides absence, the
      * parser decides malformedness.
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
                articleHtml = "",
                headings = Chunk.empty
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
    private def build()(using Frame): UI < (Sync & Async & Scope) =
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
        // reader under `/v<X>/...` keeps navigating within that tree.
        val prefix = activePrefix(initialRoute)
        val home   = docsHome(island.content, prefix)
        // The physical trees a `/<prefix>/` intro route may legitimately name: `latest`, every
        // version's own tag, AND the seeded island's own version tag. A single-segment route outside
        // this set is off-tree and full-navigates. Including the island tag decouples intro
        // classification from the `#versions-island` element: when that island is absent (a non-SSG
        // harness, or a page that omits it) `versions` is empty, but the reader is still browsing
        // within the island's own tree, so `/<islandTag>/` must classify as intro, not off-tree
        // When the versions island IS present the island tag is already in the version set,
        // so the extra term is idempotent.
        val knownPrefixes: Set[String] = knownPrefixesOf(island, versions)
        // The known module slugs for the seeded tree, derived once from the island's module list. A
        // multi-segment route whose last segment is NOT in this set is off-tree: it full-
        // navigates to a clean server 404 instead of fetching a missing content.html into a broken shell.
        val knownSlugs: Set[String] = knownSlugsOf(island)
        // The island's pre-rendered article is the current route's article when the initial route is
        // a module page, and "" on `/` and `/<prefix>/` (the SSG seeds an empty island there). Seed
        // the cache so the module branch reuses the first-paint content instead of re-fetching it.
        seedArticleCache(initialRoute, DocsClient.Article(island.articleHtml, island.headings))
        for
            // Re-apply an explicit theme choice (set by the nav toggle on a prior visit) before the body
            // mounts, so it overrides the OS `prefers-color-scheme` default. No stored choice leaves the
            // OS default in force (handled purely by the `@media` CSS, no flash).
            _ <- applyStoredTheme
            // One delegated document click listener wires every code-block Copy button (SSR + SPA-injected
            // alike), removed when the app scope closes.
            _          <- wireCodeCopy
            articleRef <- Signal.initRef[UI](UI.rawHtml(island.articleHtml))
            tocRef     <- Signal.initRef[Chunk[DocsMarkdown.Heading]](island.headings)
            // Content-loading flag, false at first paint (the boot island is already injected into
            // articleRef/tocRef above). `showContentRoute` sets it true the instant a navigation clears
            // those refs for an async content.html fetch, and false once the fetched article/TOC are set
            // (or the fetch fails), so the prev/next pager (gated on this in DocsApp.contentArea) only
            // appears together with the article and never flashes at the top of the empty content area.
            loadingRef <- Signal.initRef(false)
            // ONE docs body instance, reused on every docs navigation. Its sidebar/article/TOC react
            // to `route`/`articleRef`/`tocRef`, so swapping module-to-module only updates those refs;
            // `content` is set to this body once (when arriving from the landing) and then left alone.
            docsBody <- DocsApp.body(
                island.content,
                prefix,
                route,
                tocRef,
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(articleRef.map(a => a)),
                loadingRef
            )
            landingBody <- LandingApp.body(home)
            content     <- Signal.initRef[UI](if isRootRoute(initialRoute) then landingBody else docsBody)
            queryRef    <- Signal.initRef("")
            // One delegated document click listener closes the search dropdown on an outside / result
            // click by setting queryRef directly; removed when the app scope closes.
            _ <- wireSearchDismiss(queryRef)
            // Seed the search index with a title-only index built synchronously from the boot island
            // (titles/slugs/groups), so the very first keystroke already matches module titles without
            // waiting on a fetch. The heading-aware index is fetched eagerly on build and upgrades
            // searchIndex on success; a fetch failure leaves the title-only seed in place.
            searchIndex <- Signal.initRef(titleIndex(island.content, prefix))
            _           <- Fiber.initUnscoped(refreshSearchIndex(searchIndex, prefix))
            _ <- navFiber(route, knownPrefixes, knownSlugs, island, content, articleRef, tocRef, loadingRef, landingBody, docsBody)
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
                Kyo.unit, // onSearchFocus no-op; eager fetch makes focus-triggered loading unnecessary
                toggleTheme,
                content
            )
        yield view
        end for
    end build

    /** The localStorage key the nav theme toggle persists the explicit choice under. */
    private val themeKey = "kyo-theme"

    /** Set the document root's `data-theme` to the EFFECTIVE theme on mount: the explicit stored choice
      * (the toggle writes `themeKey` = `"dark"`/`"light"`), else the OS `prefers-color-scheme`. Pinning
      * `data-theme` to the effective theme keeps the nav toggle's icon (which keys off `data-theme`)
      * correct even for an OS-dark visitor who has not toggled. The first paint already used the right
      * palette via the `@media` CSS, so for an OS-matching visitor this sets the same colors (no flash);
      * only an explicit choice opposite to the OS repaints. `color-scheme` is set too so native
      * scrollbars and form controls match.
      */
    private def applyStoredTheme(using Frame): Unit < Sync =
        for
            stored <- UIWindow.storageGet(themeKey)
            dark   <- UIWindow.prefersColorScheme.current
            theme = stored.filter(s => s == "dark" || s == "light").getOrElse(if dark then "dark" else "light")
            // The data-theme write targets the single <html> element outside the reactive tree: a
            // one-off attribute write on the document root, not a reusable browser capability, so it
            // stays a typed bundle-local DOM call rather than a kyo-ui member.
            _ <- Sync.defer {
                val root = dom.document.documentElement
                root.setAttribute("data-theme", theme)
                setColorScheme(root, theme)
            }
        yield ()

    /** Flip the effective theme and persist the choice. The effective theme is the explicit `data-theme`
      * when one is set, otherwise the OS `prefers-color-scheme`; flipping writes the opposite as both the
      * `data-theme` attribute (so the explicit override CSS applies) and the persisted `themeKey`.
      */
    private def toggleTheme(using Frame): Unit < Sync =
        for
            dark <- UIWindow.prefersColorScheme.current
            next <- Sync.defer {
                val root = dom.document.documentElement
                val attr = root.getAttribute("data-theme")
                val effectiveDark =
                    if attr == "dark" then true
                    else if attr == "light" then false
                    else dark
                val n = if effectiveDark then "light" else "dark"
                root.setAttribute("data-theme", n)
                setColorScheme(root, n)
                n
            }
            _ <- UIWindow.storageSet(themeKey, next)
        yield ()

    // The color-scheme write needs `HTMLElement.style`, which `Element` does not expose. This is a
    // typed facade narrowing (Element -> HTMLElement) over the typed `CSSStyleDeclaration.setProperty`,
    // not an untyped dynamic. The narrowing is confined to this one helper so the theme handlers hold
    // no cast.
    private def setColorScheme(root: dom.Element, theme: String): Unit =
        root.asInstanceOf[dom.html.Element].style.setProperty("color-scheme", theme)

    /** Wire the code-block Copy buttons with ONE delegated `document` click listener, so a single
      * registration covers every code panel on the page AND every panel injected later by an SPA content
      * swap (event delegation, no per-render rebinding). On a Copy click it copies the panel's `<pre>`
      * text to the clipboard and flips the button's `data-copied` attribute for ~1.6s, which the CSS reads
      * to swap the "Copy" label for "Copied". The listener is removed when the enclosing Scope closes.
      */
    private def wireCodeCopy(using Frame): Unit < (Async & Scope) =
        UIWindow.onClick { e =>
            e.targetClosest("button.code-copy") match
                case Present(btn) =>
                    val preOpt = btn.closest(".code-block").flatMap(_.querySelector("pre"))
                    preOpt match
                        case Present(pre) =>
                            for
                                _ <- Sync.defer(btn.setAttribute("data-copied", "true"))
                                _ <- UIWindow.writeClipboard(pre.textContent)
                                _ <- Async.delay(1600.millis)(Sync.defer(btn.removeAttribute("data-copied")))
                            yield ()
                        case Absent => Kyo.unit
                    end match
                case Absent => Kyo.unit
        }

    /** One delegated document click listener that closes the header search dropdown. It closes on a
      * click EITHER outside the search box (`.search-wrap`) OR on a result row (`a.search-result`, a
      * selection: the row's `href` drives the SPA navigation, and this clears the query so the dropdown
      * does not linger over the destination). A click on the input itself, or on the dropdown chrome
      * (its padding / scrollbar, which is inside `.search-wrap` but not a row), leaves it open so the
      * user can keep typing or scrolling. Closing sets the query Signal to "" directly (the reactive
      * source of truth the search input and dropdown both read), so the dropdown hides through the
      * same reactive path a manual clear drives. The listener is removed when the enclosing Scope closes.
      */
    private def wireSearchDismiss(queryRef: SignalRef[String])(using Frame): Unit < (Async & Scope) =
        UIWindow.onClick { e =>
            val outside  = e.targetClosest(".search-wrap").isEmpty
            val onResult = e.targetClosest("a.search-result").isDefined
            if outside || onResult then queryRef.set("")
            else Kyo.unit
        }

    /** Build a title-only search index synchronously from the boot island: one entry per module
      * (title/slug/group) under `prefix`, with no headings yet. Used as the immediate synchronous
      * seed so search works on the first keystroke; the eager fetch fiber upgrades it with section
      * headings once the network request lands.
      */
    private def titleIndex(content: WebsiteContent, prefix: String): DocsSearch.Index =
        DocsSearch.seed(prefix, content.groups.flatMap(_.modules))
    end titleIndex

    /** Attempt to fetch the full heading-aware search index and upgrade the ref on success.
      *
      * Fetches `/$activePrefix/search-index.json` via [[DocsClient.fetchSearchIndex]]. On success,
      * calls `searchIndex.set(idx)` to upgrade the ref from the title-only seed to the heading+prose
      * index. On any failure (network error, non-2xx, parse error), leaves the ref unchanged so the
      * title-only seed remains in place (graceful degrade). The effect row is `< Async` with no
      * `Abort` widening, matching the `Fiber.initUnscoped` call site in [[build]].
      *
      * Exposed `private[website]` so the eager-wiring path can be tested directly without a full DOM
      * `build()` mount (the test stubs `DocsClient.fetchFn`, seeds a `SignalRef`, calls this method,
      * and asserts the ref upgraded or retained the seed).
      */
    private[website] def refreshSearchIndex(searchIndex: SignalRef[DocsSearch.Index], activePrefix: String)(using Frame): Unit < Async =
        Abort.run[Throwable](Abort.catching[Throwable](DocsClient.fetchSearchIndex(activePrefix))).map {
            case Result.Success(idx)                 => searchIndex.set(idx)
            case Result.Failure(_) | Result.Panic(_) => Kyo.unit // keep the title-only seed (graceful degrade)
        }
    end refreshSearchIndex

    /** Derive the set of known physical tree prefixes from the seeded island and the versions list.
      *
      * The set contains `latest`, every version tag from the versions list, and the island's own
      * version tag. The island tag is included unconditionally so that `/<islandTag>/` classifies as
      * [[RouteKind.Intro]] even when the `#versions-island` element is absent (i.e. `versions` is
      * empty). When the versions island IS present the island tag is already in the set, making the
      * extra term idempotent.
      */
    private[website] def knownPrefixesOf(island: DocsClient.DocsIsland, versions: Chunk[WebsiteVersion]): Set[String] =
        versions.toSeq.map(_.tag).toSet + LatestPrefix + island.content.version.tag

    /** Derive the set of known module slugs for the seeded island's content tree.
      *
      * A multi-segment route whose last segment is NOT in this set is [[RouteKind.OffTree]]: the SPA
      * full-navigates to a clean server 404 rather than fetching a missing `content.html` into a broken
      * docs shell. The set is derived directly from the island so a regression in the
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
      * navigates to a clean server 404 instead of fetching a missing `content.html` into a broken docs
      * shell. A single-segment route is an [[Intro]] only when it names a known prefix.
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
      *   - [[RouteKind.Landing]] (root `/`): swap `content` to the landing body, no reload.
      *   - [[RouteKind.Module]] (`/<prefix>/<slug>/` whose last segment is a known module slug): fetch
      *     pre-rendered article from `content.html` (cache), update `articleRef`/`tocRef`, and swap
      *     `content` to the docs body.
      *   - [[RouteKind.Intro]] (`/<knownPrefix>/`, exactly one segment naming a real tree, `latest` or a
      *     known version tag): the overview, fetched and injected exactly like a module from the route's
      *     `content.html` (the root-README intro rendered at build time), so the rail's Overview item is
      *     active and expands to the intro's `## ` sections.
      *   - [[RouteKind.OffTree]] (an unknown single-segment prefix OR a multi-segment route whose last
      *     segment is not a known module slug, e.g. `/latest/does-not-exist/`): a full browser
      *     navigation as the narrow safety fallback, so the server resolves the real page or a clean 404
      *     instead of the SPA fetching a missing `content.html` into a broken docs shell.
      *
      * Same-document `#anchor` clicks never reach this fiber: `UILocation` skips same-document links
      * (`UILocation.scala:60-62`), so a TOC or landing `#how` anchor scrolls natively without changing
      * the route signal. After every in-shell branch the fiber updates the head.
      */
    private def navFiber(
        route: Signal[String],
        knownPrefixes: Set[String],
        knownSlugs: Set[String],
        island: DocsClient.DocsIsland,
        content: SignalRef[UI],
        articleRef: SignalRef[UI],
        tocRef: SignalRef[Chunk[DocsMarkdown.Heading]],
        loadingRef: SignalRef[Boolean],
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
                                // Root `/`: swap to the landing body without a full reload.
                                content.set(landingBody).andThen(updateHead(nextRoute, island))
                            case RouteKind.Module =>
                                // Module route: fetch pre-rendered article from content.html, update article + TOC, show docs.
                                showContentRoute(nextRoute, island, content, articleRef, tocRef, loadingRef, docsBody)
                            case RouteKind.Intro =>
                                // Intro/overview `/<knownPrefix>/`: the root-README overview is served at
                                // `/<prefix>/content.html` (pre-rendered at build time), so the intro is a
                                // content route exactly like a module: fetch pre-rendered article, update
                                // article + TOC, show docs. The Overview is the active rail item (DocsApp
                                // keys it on the single-segment intro route), and its `## ` sections come
                                // from the TOC set here.
                                showContentRoute(nextRoute, island, content, articleRef, tocRef, loadingRef, docsBody)
                            case RouteKind.OffTree =>
                                // Off-tree route: a single segment that is not a known prefix, OR a multi-
                                // segment route whose last segment is not a known module slug. Hand off to a
                                // full browser navigation so the server resolves the real page or a clean 404
                                // instead of fetching a missing content.html into a broken docs shell.
                                UILocation.assign(nextRoute)
                yield Loop.continue
            }
        }
    end navFiber

    /** Show a content route (a module page OR the intro/overview): fetch the route's pre-rendered
      * article from `content.html` (from the cache when seeded, else over the network, caching the
      * result), inject via `UI.rawHtml`, update the shared `articleRef`/`tocRef`, swap `content` to
      * the one docs body, update the head, and scroll to the URL hash or the top. Both
      * the [[RouteKind.Module]] and [[RouteKind.Intro]] branches share this: the overview is fetched
      * and injected exactly like a module, so the rail's Overview item expands to the intro's `## `
      * sections and the overview prose is byte-identical to the SSG first paint.
      */
    private def showContentRoute(
        nextRoute: String,
        island: DocsClient.DocsIsland,
        content: SignalRef[UI],
        articleRef: SignalRef[UI],
        tocRef: SignalRef[Chunk[DocsMarkdown.Heading]],
        loadingRef: SignalRef[Boolean],
        docsBody: UI
    )(using Frame): Unit < Async =
        for
            // Keep the PREVIOUS module's article on screen through the (async) content.html fetch and swap
            // it atomically when the new HTML arrives, rather than blanking the column first. Blanking first
            // collapsed the article to empty for the whole fetch window (~tens of ms on a cold module): a
            // white flash in the content column, and because an empty page is short, the page scrollbar
            // dropped then re-appeared and shifted the whole layout sideways (the left rail visibly jumped).
            // The `route` signal flips synchronously on click, so the rail already keys the NEW module as
            // active and the click reads as registered; letting the old prose linger for one fetch is a
            // smooth lag, not a "did my click do anything" stall. tocRef IS still reset to empty up front so
            // the newly-active rail module expands with NO sections (a clean empty) instead of the previous
            // module's `## ` list, until the real headings arrive with the article. For an already-cached
            // route the fetch resolves in-fiber, so the article set runs back-to-back and the swap is one
            // paint with nothing stale on screen.
            //
            // `loadingRef` gates the prev/next pager hidden for the same window: it is keyed on the `route`
            // signal (which flipped synchronously), so without the gate it would paint the NEW module's
            // pager under the still-visible OLD article. `Sync.ensure` lowers the flag on EVERY exit
            // (success, fetch failure, or panic) so it never sticks true and strands the pager hidden.
            _ <- loadingRef.set(true)
            _ <- Sync.ensure(loadingRef.set(false)) {
                for
                    _ <- tocRef.set(Chunk.empty)
                    article <- Sync.defer(Maybe.fromOption(articleCache.get(nextRoute))).flatMap {
                        case Present(a) => Sync.defer(a)
                        case Absent =>
                            DocsClient.fetchArticle(nextRoute).map { a =>
                                seedArticleCache(nextRoute, a)
                                a
                            }
                    }
                    _ <- articleRef.set(UI.rawHtml(article.html))
                    _ <- tocRef.set(article.headings)
                yield ()
            }
            _ <- content.set(docsBody)
            _ <- updateHead(nextRoute, island)
            // After the article re-renders, scroll to the URL hash if present (a heading-hit search
            // result navigates to /<prefix>/<slug>/#<heading>; the anchor element only exists once this
            // branch renders the article). With NO hash (sidebar nav, prev/next) reset to the top so the
            // new page starts at its heading instead of inheriting the previous page's scrollY (B5).
            _ <- scrollToHashOrTop()
        yield ()
    end showContentRoute

    /** Update `document.title` and the `<link rel=canonical>` href to match `route`, with no
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
        val segments = route.split('/').filter(_.nonEmpty)
        val label    = island.content.version.label
        val title =
            if segments.isEmpty then "Kyo | Build with AI. Ship something that holds."
            else if segments.length >= 2 then s"${segments(segments.length - 1)} | Kyo docs $label"
            else s"Overview | Kyo docs $label"
        val canonical =
            if segments.isEmpty then "https://getkyo.io/"
            else s"https://getkyo.io$route"
        for
            _ <- UIWindow.setTitle(title)
            // The canonical <link> update is a one-off querySelector + setAttribute on the single
            // server-rendered <link rel=canonical> element, not a reusable browser capability, so it
            // stays a typed bundle-local DOM call. The element is always present in the rendered head;
            // guard the null case so a harness without it does not throw.
            _ <- Sync.defer {
                val link = dom.document.querySelector("link[rel=canonical]")
                if link != null then link.setAttribute("href", canonical)
            }
        yield ()
        end for
    end updateHead

    /** The bounded scroll-poll budget: up to [[ScrollMaxAttempts]] lookups, [[ScrollPollInterval]]
      * apart, for a total settle budget of 200ms (10 * 20ms), well within human perception of a snap
      * nav. The article patch applies on the event loop after `content.set`, so the first attempt may
      * miss; each subsequent attempt yields the event loop a chance to apply the patch.
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
      * article patch has a chance to apply before each lookup. On a large article the patch may
      * not have applied on the first attempt, so a single fixed-sleep lookup raced the render and the
      * scroll silently no-op'd; the bounded poll lands it once the anchor appears. A missing hash, or
      * an element that never appears after the full budget, is a clean no-op (no crash, no console
      * error): the slug may have changed in a new version or the article is not on this route.
      *
      * The poll is a [[Loop]] over the attempt count (not mutual recursion), so the iteration state is
      * an explicit `Int` carried by the loop and there is no self-referencing closure to leak.
      */
    /** Module-navigation scroll reset. When the URL carries a fragment, delegate to
      * [[scrollToHash]] so a heading-hit search result lands on its section. When there is NO
      * fragment (sidebar nav, prev/next, or a plain module link), scroll the window to the top so the
      * freshly-rendered article starts at its heading instead of keeping the previous page's scrollY
      * (which left the reader at the bottom of the new page).
      */
    private def scrollToHashOrTop()(using Frame): Unit < Async =
        // The hash is read with a typed bundle-local DOM call: UILocation.current reports pathname +
        // search and deliberately drops the fragment, so the fragment is read directly here.
        Sync.defer(dom.window.location.hash).map { hash =>
            if hash.length <= 1 then UIWindow.scrollToTop
            else scrollToHash()
        }
    end scrollToHashOrTop

    private def scrollToHash()(using Frame): Unit < Async =
        // The hash is read with a typed bundle-local DOM call (UILocation.current drops the fragment).
        Sync.defer(dom.window.location.hash).map { hash =>
            if hash.length <= 1 then Kyo.unit
            else
                val id = hash.substring(1)
                Loop[Int, Unit, Async](0) { attempt =>
                    UIWindow.scrollIntoViewById(id).map { scrolled =>
                        if scrolled then Loop.done
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
      * the tree they landed on.
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
    // This is the sanctioned unsafe tier crossing.
    private def runStylesheetUnsafe(): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(UI.runStylesheet(WebsiteStyles.sheet)))

    // Unsafe: app-entry boundary bridge; mounts the SPA fiber from JS main into the Kyo
    // scheduler. This is the sanctioned unsafe tier crossing.
    private def runMountUnsafe(view: UI < (Sync & Async & Scope)): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(Scope.run(
                view.flatMap(v => UI.runMount(v))
            )).unit
        ))
    end runMountUnsafe

end WebsiteBundleMain
