// PUBLIC browser bundle entry
package kyo.website

import kyo.*
import org.scalajs.dom

/** SPA bundle entry-point.
  *
  * Bootstraps the kyo website single-page application in the browser:
  * 1. Injects `WebsiteStyles.sheet` into the document `<head>` so styles are present before the
  *    first render.
  * 2. Routes on `window.location` (via `UILocation.current`) to select the initial body (landing
  *    for `/`, docs for a `/<prefix>/...` route).
  * 3. Wraps the body in the unified `SiteApp` shell and mounts it under the `UILocation`-driven
  *    router.
  *
  * Phase 1 keeps the bundle on a minimal two-arm initial selection while the SSG path is rebuilt
  * around `SiteApp`; the single-mount, client-side `/`-to-docs routing rewrite lands in Phase 2.
  * The docs arm wires the SPA with client routing via `UILocation.current`, reactive content driven
  * by a `SignalRef[UI]` updated on navigation, and `DocsClient.fetchMarkdown` + `DocsMarkdown.transpile`
  * for uncached routes (INV-013).
  *
  * This object is the one shared ESModule entry across all doc versions (INV-008).
  */
object WebsiteBundleMain:

    // Unsafe: Frame.internal is the boundary value here. `def main(args: Array[String])` is the
    // JS entry point imposed by Scala.js and there is no user code above it to thread a Frame
    // from. Same justification as SpaHarnessMain (SpaHarnessMain.scala:22).
    private given bundleFrame: Frame = Frame.internal

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
        // Phase 1: route on window.location (G3 removed data-boot-scenario). The root `/` mounts the
        // landing body; any `/<prefix>/...` route mounts the docs body. Both are wrapped in the one
        // SiteApp shell. The single-mount, client-side `/`-to-docs swap is the Phase 2 rewrite.
        // Unsafe: reading the current location at JS entry; UILocation initializes synchronously
        // before this call and current is a Sync-only read.
        val initialPath: String =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(UILocation.current.current)
        val isRoot = initialPath.split("/").count(_.nonEmpty) == 0
        val view: UI < (Sync & Async) =
            if isRoot then buildLanding() else buildDocs()
        runMountUnsafe(view)
    end main

    private def buildLanding()(using Frame): UI < (Sync & Async) =
        val versions = readVersions()
        val island   = readDocsIsland()
        val home     = docsHome(island.content, routePrefix("/", island.content.version.tag))
        for
            body <- LandingApp.body(versions, home)
            view <- siteShell(versions, home, body)
        yield view
        end for
    end buildLanding

    private def buildDocs()(using Frame): UI < (Sync & Async) =
        val island = readDocsIsland()
        val route  = UILocation.current
        // Seed the cache with the SSG-supplied Markdown for the current route (INV-003).
        // Unsafe: reading the current signal value at JS entry via evalOrThrow; safe because
        // UILocation initializes synchronously before this call and route.current is Sync-only.
        val initialRoute: String =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(route.current)
        // The link prefix is the physical tree the page is served under (its first path segment:
        // `latest` or `v<X>`), NOT a re-derivation from `version.latest`. A reader who landed on
        // `/v1.2.0/...` (the latest version's own versioned tree) must keep navigating within
        // `/v1.2.0/...`, never jumping to `/latest/` (WARN-1). Fall back to the seeded version's tag
        // when the route has no leading segment.
        val prefix   = routePrefix(initialRoute, island.content.version.tag)
        val home     = docsHome(island.content, prefix)
        val versions = island.versions
        seedMarkdownCache(initialRoute, island.markdown)
        for
            initialRendered <- DocsMarkdown.transpile(island.markdown)
            articleRef      <- Signal.initRef[UI](initialRendered.article)
            tocRef          <- Signal.initRef[Chunk[DocsMarkdown.Heading]](initialRendered.headings)
            // Launch a fiber that updates the article and the TOC outline on each navigation. Only a
            // docs module route (`/<prefix>/<slug>/`, two or more path segments) has a sibling
            // `content.md` to fetch and render in place. Any other route the interceptor captured (the
            // landing `/`, or a `/<prefix>/` intro page) has no content.md, so hand it off to a full
            // browser navigation: the server serves the correct page instead of the fetch 404-ing and
            // leaving stale content behind.
            _ <- Fiber.initUnscoped {
                Loop.forever {
                    for
                        nextRoute <- route.next
                        isModuleRoute = nextRoute.split("/").count(_.nonEmpty) >= 2
                        _ <-
                            if !isModuleRoute then Sync.defer(dom.window.location.href = nextRoute)
                            else
                                for
                                    md <- Sync.defer(markdownCache.getOrElse(nextRoute, ""))
                                    fetched <- if md.nonEmpty then Sync.defer(md)
                                    else
                                        DocsClient.fetchMarkdown(nextRoute).map { f =>
                                            seedMarkdownCache(nextRoute, f)
                                            f
                                        }
                                    rendered <- DocsMarkdown.transpile(fetched)
                                    _        <- articleRef.set(rendered.article)
                                    _        <- tocRef.set(rendered.headings)
                                yield ()
                    yield Loop.continue
                    end for
                }
            }
            body <- DocsApp.body(
                island.content,
                prefix,
                route,
                tocRef,
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(articleRef.map(a => a))
            )
            view <- siteShell(versions, home, body)
        yield view
        end for
    end buildDocs

    /** Wrap a route's content `body` in the unified `SiteApp` shell (the Phase 1 minimal bundle
      * touch). The search query and index are empty (search is wired in Phase 3); the content signal
      * is constant for now (the single-mount, route-reactive content swap is the Phase 2 rewrite).
      */
    private def siteShell(versions: Chunk[WebsiteVersion], home: String, body: UI)(using Frame): UI < (Sync & Async) =
        for
            queryRef <- Signal.initRef("")
            view <- SiteApp.view(
                versions,
                home,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                Signal.initConst(body)
            )
        yield view

    /** The header Docs/Modules/Get-started target for the seeded `content` under `prefix`: the first
      * module's route `/<prefix>/<firstSlug>/`, falling back to the prefix root `/<prefix>/`. Mirrors
      * `WebsiteGenerator.docsHome` so the bundle and the SSG agree on the header target (parity).
      */
    private def docsHome(content: WebsiteContent, prefix: String): String =
        content.groups.flatMap(_.modules).headOption.fold(s"/$prefix/")(m => s"/$prefix/${m.slug}/")

    /** The physical route-tree prefix for a docs path: the path's first non-empty segment (`latest`
      * or `v<X>`). This is the directory the SSG served the page from, so all intra-page links stay
      * within that tree, including the latest version's own `v<X>/` copy (WARN-1). Falls back to
      * `versionTag` when the path has no leading segment (e.g. `/`).
      */
    private def routePrefix(path: String, versionTag: String): String =
        val segments = path.split('/').filter(_.nonEmpty)
        if segments.isEmpty then versionTag else segments(0)
    end routePrefix

    /** Read the available versions list from the SSG-seeded DOM island.
      *
      * The SSG (Phase 7) writes a `<script id="versions-island" type="application/json">`
      * element whose text content is a JSON array of `{tag, label, latest}` objects.
      * This method queries that element and parses its content via `DocsClient.parseVersionsIsland`.
      *
      * When the element is absent (e.g. in test harness or pre-Phase-7 environments) an
      * empty `Chunk` is returned; the landing app still mounts with no version entries.
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
