// flow-allow: PUBLIC browser bundle entry
package kyo.website

import kyo.*
import org.scalajs.dom

/** SPA bundle entry-point.
  *
  * Bootstraps the kyo website single-page application in the browser:
  * 1. Injects `WebsiteStyles.sheet` into the document `<head>` so styles are present before the
  *    first render.
  * 2. Reads `data-boot-scenario` from `document.body` to select the app arm (landing vs docs).
  * 3. Mounts the appropriate app under the `UILocation`-driven router.
  *
  * The landing arm is fully wired (Phase 3). The docs arm wires the full SPA with client routing
  * via `UILocation.current`, reactive content driven by a `SignalRef[UI]` updated on navigation,
  * and `DocsClient.fetchMarkdown` + `DocsMarkdown.transpile` for uncached routes (INV-013).
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
        val boot = dom.document.body.getAttribute("data-boot-scenario")
        val view: UI < (Sync & Async) = boot match
            case "landing" => buildLanding()
            case _         => buildDocs()
        runMountUnsafe(view)
    end main

    private def buildLanding()(using Frame): UI < (Sync & Async) =
        for
            route <- Sync.defer(UILocation.current)
            versions = readVersions()
            view <- LandingApp.view(versions)
        yield UI.Ast.Reactive(route.map(_ => view))

    private def buildDocs()(using Frame): UI < (Sync & Async) =
        val island = readDocsIsland()
        val route  = UILocation.current
        // Seed the cache with the SSG-supplied Markdown for the current route (INV-003).
        // Unsafe: reading the current signal value at JS entry via evalOrThrow; safe because
        // UILocation initializes synchronously before this call and route.current is Sync-only.
        val initialRoute: String =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(route.current)
        seedMarkdownCache(initialRoute, island.markdown)
        for
            initialRendered <- DocsMarkdown.transpile(island.markdown)
            articleRef      <- Signal.initRef[UI](initialRendered.article)
            // Launch a fiber that updates articleRef on each navigation.
            _ <- Fiber.initUnscoped {
                Loop.forever {
                    for
                        nextRoute <- route.next
                        md        <- Sync.defer(markdownCache.getOrElse(nextRoute, ""))
                        fetched <- if md.nonEmpty then Sync.defer(md)
                        else
                            DocsClient.fetchMarkdown(nextRoute).map { f =>
                                seedMarkdownCache(nextRoute, f)
                                f
                            }
                        rendered <- DocsMarkdown.transpile(fetched)
                        _        <- articleRef.set(rendered.article)
                    yield Loop.continue
                    end for
                }
            }
            view <- DocsApp.view(
                island.content,
                island.versions,
                route,
                initialRendered.headings,
                // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render.
                UI.Ast.Reactive(articleRef.map(a => a))
            )
        yield view
        end for
    end buildDocs

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
