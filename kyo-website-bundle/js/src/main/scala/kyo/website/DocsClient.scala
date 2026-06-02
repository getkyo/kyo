// flow-allow: PUBLIC docs SPA fetch client
package kyo.website

import kyo.*
import org.scalajs.dom
import scala.concurrent.Promise
import scala.scalajs.js.Thenable.Implicits.*

/** JS-only docs SPA fetch client.
  *
  * Fetches per-route Markdown text and the route table (versions + modules) from static files
  * emitted by the SSG. Used by `WebsiteBundleMain` to supply the reactive content region with
  * the raw Markdown the client transpiles via `DocsMarkdown.transpile` on each navigation.
  *
  * The `fetch` helper is `private[website]` so tests can replace `fetchFn` with a stub without
  * involving the real DOM Fetch API. In production, `fetchFn` is `defaultFetch` (an identity
  * sentinel) and `fetch` issues a real GET via `org.scalajs.dom.Fetch.fetch`.
  *
  * @see
  *   [[DocsClient.fetchMarkdown]] to fetch a route's raw Markdown
  * @see
  *   [[DocsClient.routeTable]] to fetch the versions and module manifest
  */
object DocsClient:

    /** The parsed route table: all available versions and the current version's module list.
      *
      * Built by [[routeTable]] from `versions.json` and the current version's `manifest.json`.
      *
      * @param versions
      *   All available documentation versions (for the dropdown).
      * @param modules
      *   All modules in the current version (for sidebar nav and prev/next).
      */
    final case class RouteTable(versions: Chunk[WebsiteVersion], modules: Chunk[WebsiteModule]) derives CanEqual

    /** Production-default sentinel for `fetchFn`.
      *
      * Its identity (`eq`) is tested by `fetch` to distinguish the production path from a test
      * stub. When `fetchFn eq defaultFetch`, the real `org.scalajs.dom.Fetch.fetch` browser API
      * is used. Tests replace `fetchFn` with a synchronous stub; that path wraps the stub in a
      * Promise so synchronous throws become Future failures on the Async error channel.
      */
    private val defaultFetch: String => String = _ =>
        throw new RuntimeException("defaultFetch sentinel called directly; this should not happen")

    // Testability: tests replace this var with a stub before calling fetchMarkdown/routeTable.
    // Unsafe: mutable module-level var in a JS bundle entry; the bundle is single-threaded.
    private[website] var fetchFn: String => String = defaultFetch

    /** Fetch the raw Markdown for a docs route.
      *
      * GETs `<route>/content.md` and returns the response body as a `String`. The route
      * is the URL pathname of the docs page (e.g. `/latest/kyo-core/`). If the request
      * fails (network error or non-200 status) the returned `Async` fails.
      *
      * @param route
      *   The URL pathname of the docs route.
      * @return
      *   The raw Markdown content of that route's `content.md`, wrapped in `Async`.
      */
    def fetchMarkdown(route: String)(using Frame): String < Async =
        fetch(markdownUrl(route))

    /** Fetch and parse the route table (versions + module manifest).
      *
      * GETs `/versions.json` and the current version's `manifest.json`, then assembles a
      * [[RouteTable]]. If either request fails the returned `Async` fails.
      *
      * @return
      *   The parsed [[DocsClient.RouteTable]], wrapped in `Async`.
      */
    def routeTable(using Frame): DocsClient.RouteTable < Async =
        for
            versionsJson <- fetch("/versions.json")
            versions     <- parseVersions(versionsJson)
            currentVersion = versions.headMaybe.map(_.tag).getOrElse("latest")
            manifestJson <- fetch(s"/$currentVersion/manifest.json")
            modules      <- parseManifest(manifestJson)
        yield RouteTable(versions, modules)
    end routeTable

    private def markdownUrl(route: String): String =
        val clean = if route.endsWith("/") then route else route + "/"
        s"${clean}content.md"
    end markdownUrl

    /** Issue a GET and return the response body text as `String < Async`.
      *
      * In production (`fetchFn eq defaultFetch`), issues a real GET via
      * `org.scalajs.dom.Fetch.fetch`, awaits the response, reads `.text()`, and lifts
      * the result via `Async.fromFuture`. A non-2xx status code fails the `Async`.
      *
      * In tests, `fetchFn` is replaced with a synchronous stub before calling the
      * public methods; the stub result (or throw) is wrapped in a `Promise` so the
      * Async error channel is preserved for failures.
      */
    private[website] def fetch(url: String)(using Frame): String < Async =
        if fetchFn eq defaultFetch then
            // Production path: real browser Fetch API via org.scalajs.dom.
            // dom.Fetch.fetch returns js.Promise[Response]; .toFuture (via Thenable.Implicits)
            // converts it to scala.concurrent.Future[Response]. .text() similarly returns a
            // js.Promise[String] converted to Future[String].
            Async.fromFuture(
                dom.Fetch.fetch(url).toFuture.flatMap { response =>
                    if response.ok then response.text().toFuture
                    else
                        scala.concurrent.Future.failed(
                            new RuntimeException(s"HTTP ${response.status}: $url")
                        )
                }(using scala.concurrent.ExecutionContext.global)
            )
        else
            // Test path: synchronous stub wrapped in a Promise so throws become Future failures.
            Async.fromFuture {
                val p = Promise[String]()
                try p.success(fetchFn(url))
                catch case e: Throwable => p.failure(e)
                p.future
            }
        end if
    end fetch

    /** Parse a JSON array of version objects from an SSG-seeded DOM island.
      *
      * The input is a JSON array of `{"tag":"...","label":"...","latest":true/false}` objects,
      * as written by the SSG into the `#versions-island` script element. Returns an empty
      * `Chunk` when the input is empty or not a valid JSON array.
      *
      * Exposed as `private[website]` so `WebsiteBundleMain.readVersions` can call it
      * synchronously at bundle entry without going through the async `routeTable` path.
      */
    private[website] def parseVersionsIsland(json: String)(using Frame): Chunk[WebsiteVersion] < Sync =
        parseVersions(json)

    private def parseVersions(json: String)(using Frame): Chunk[WebsiteVersion] < Sync =
        Sync.defer {
            val items    = splitJsonArray(json)
            val versions = items.flatMap(parseVersion)
            Chunk.from(versions)
        }
    end parseVersions

    private def parseVersion(obj: String): Maybe[WebsiteVersion] =
        for
            tag   <- extractString(obj, "tag")
            label <- extractString(obj, "label")
            latest = obj.contains("\"latest\":true")
        yield WebsiteVersion(tag, label, latest)
    end parseVersion

    private def parseManifest(json: String)(using Frame): Chunk[WebsiteModule] < Sync =
        Sync.defer {
            val items   = splitJsonArray(json)
            val modules = items.flatMap(parseModule)
            Chunk.from(modules)
        }
    end parseManifest

    private def parseModule(obj: String): Maybe[WebsiteModule] =
        for
            slug  <- extractString(obj, "slug")
            group <- extractString(obj, "group")
            title <- extractString(obj, "title")
        yield WebsiteModule(slug, group, title, "", WebsiteModule.Platforms(true, true, true))
    end parseModule

    private def splitJsonArray(json: String): Seq[String] =
        val trimmed = json.trim
        if !trimmed.startsWith("[") || !trimmed.endsWith("]") then Seq.empty
        else
            val inner = trimmed.substring(1, trimmed.length - 1).trim
            if inner.isEmpty then Seq.empty
            else
                val items = scala.collection.mutable.ArrayBuffer.empty[String]
                var depth = 0
                var start = 0
                var i     = 0
                while i < inner.length do
                    inner(i) match
                        case '{' | '[' => depth += 1
                        case '}' | ']' => depth -= 1
                        case ',' if depth == 0 =>
                            items += inner.substring(start, i).trim
                            start = i + 1
                        case _ =>
                    end match
                    i += 1
                end while
                if start < inner.length then items += inner.substring(start).trim
                items.toSeq
            end if
        end if
    end splitJsonArray

    /** Extract the string value for `key` from a flat JSON object fragment.
      *
      * Handles escaped double-quotes (`\"`) inside the value. Returns `Absent` when
      * the key is not found or the value cannot be parsed.
      */
    private def extractString(obj: String, key: String): Maybe[String] =
        // Match "key" : "value" where value may contain \" escaped quotes.
        // The inner group captures any character except an unescaped quote:
        //   (?:[^"\\]|\\.)* matches either a non-quote/non-backslash char, or a
        //   backslash followed by any character (the escape sequence).
        val pattern = s""""$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
        Maybe.fromOption(pattern.findFirstMatchIn(obj).map(_.group(1)))
    end extractString

end DocsClient
