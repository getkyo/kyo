// PUBLIC docs SPA fetch client
package kyo.website

import kyo.*
import org.scalajs.dom
import scala.concurrent.Promise
import scala.scalajs.js.Thenable.Implicits.*

/** JS-only docs SPA fetch client.
  *
  * Fetches pre-rendered article content and the route table (versions + modules) from static files
  * emitted by the SSG. The SPA consumes pre-rendered article HTML emitted by the SSG; it does not
  * call the Markdown transpiler on navigation. Per-route article HTML and heading outlines are
  * fetched from `content.html` files co-located with each route.
  *
  * The `fetch` helper is `private[website]` so tests can replace `fetchFn` with a stub without
  * involving the real DOM Fetch API. In production, `fetchFn` is `defaultFetch` (an identity
  * sentinel) and `fetch` issues a real GET via `org.scalajs.dom.Fetch.fetch`.
  *
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
      * @param headingsBySlug
      *   Each module slug mapped to its section headings (from the manifest `toc`), used to build
      *   the heading-aware search index. A slug with no `toc` entries maps to an empty `Chunk`.
      */
    final case class RouteTable(
        versions: Chunk[WebsiteVersion],
        modules: Chunk[WebsiteModule],
        headingsBySlug: Map[String, Chunk[DocsSearch.Heading]]
    ) derives CanEqual

    /** Production-default sentinel for `fetchFn`.
      *
      * Its identity (`eq`) is tested by `fetch` to distinguish the production path from a test
      * stub. When `fetchFn eq defaultFetch`, the real `org.scalajs.dom.Fetch.fetch` browser API
      * is used. Tests replace `fetchFn` with a synchronous stub; that path wraps the stub in a
      * Promise so synchronous throws become Future failures on the Async error channel.
      */
    private val defaultFetch: String => String = _ =>
        throw new RuntimeException("defaultFetch sentinel called directly; this should not happen")

    // Testability: tests replace this var with a stub before calling fetchArticle/routeTable.
    // Unsafe: mutable module-level var in a JS bundle entry; the bundle is single-threaded.
    private[website] var fetchFn: String => String = defaultFetch

    /** Fetch the pre-rendered article content and heading outline for a docs route.
      *
      * GETs `<route>/content.html`, which is the static file the SSG writes for every docs page.
      * The response body is a JSON object with two fields:
      *   - `html`: the pre-rendered article HTML (injected via `UI.rawHtml`; never re-transpiled).
      *   - `headings`: a JSON array of `{"level", "text", "slug"}` objects for the TOC outline.
      *
      * The route is the URL pathname of the docs page (e.g. `/latest/kyo-core/`). The endpoint is
      * the co-located `content.html` file; a single fetch delivers both the article and the outline
      * (one fetch per navigation). If the request fails (network error or non-200 status) the
      * returned `Async` fails; no `Abort` widening is introduced.
      *
      * @param route
      *   The URL pathname of the docs route (with or without trailing slash).
      * @return
      *   The parsed [[DocsClient.Article]] containing `html` and `headings`, wrapped in `Async`.
      */
    def fetchArticle(route: String)(using Frame): DocsClient.Article < Async =
        for
            body     <- fetch(articleUrl(route))
            headings <- parseArticleHeadings(body)
        yield Article(extractString(body, "html").getOrElse(""), headings)
    end fetchArticle

    /** Fetch and parse the route table (versions + module manifest) for the ACTIVE prefix.
      *
      * GETs `/versions.json` (for the version dropdown) and `/$activePrefix/manifest.json` (the
      * manifest of the tree the reader is browsing), then assembles a [[RouteTable]]. If either
      * request fails the returned `Async` fails.
      *
      * `activePrefix` is the caller's own tree prefix (`latest` or a version tag like `v0.9.3`), so a
      * reader on an older version gets the heading index built from THAT version's `toc`, not the
      * latest version's. Building the heading index from the latest manifest while stamping hits with
      * the old prefix would produce `/<oldPrefix>/<slug>/#<latestSlug>` fragments that land nowhere
      * (AF-8). The caller (`WebsiteBundleMain.build`, via the eager fetch fiber) already knows its
      * active prefix.
      *
      * @param activePrefix
      *   The physical tree prefix whose `manifest.json` to fetch (`latest` or a version tag).
      * @return
      *   The parsed [[DocsClient.RouteTable]], wrapped in `Async`.
      */
    def routeTable(activePrefix: String)(using Frame): DocsClient.RouteTable < Async =
        for
            versionsJson <- fetch("/versions.json")
            versions     <- parseVersions(versionsJson)
            manifestJson <- fetch(s"/$activePrefix/manifest.json")
            modules      <- parseManifest(manifestJson)
            headings     <- parseHeadings(manifestJson)
        yield RouteTable(versions, modules, headings)
    end routeTable

    /** The pre-rendered article content fetched from a docs route's `content.html` endpoint.
      *
      * Returned by [[fetchArticle]] as a single value carrying both the article body and the TOC
      * outline. The `html` field is injected verbatim via `UI.rawHtml`; it is the exact HTML the SSG
      * produced and must not be HTML-escaped again (use `UI.rawHtml`, never `UI.text`).
      *
      * The `headings` field carries `DocsMarkdown.Heading` entries (level, text, slug), not the
      * search-index `DocsSearch.Heading` type (which has only text and slug, no level). Heading ids
      * in the article HTML and the `slug` values in `headings` are always consistent (INV-004): the
      * SSG produces both from the same transpile pass so anchor scroll from a TOC entry always lands.
      *
      * @param html
      *   The pre-rendered article HTML produced by the SSG.
      * @param headings
      *   The heading outline: level, human-readable text, and anchor slug for each heading.
      */
    final case class Article(html: String, headings: Chunk[DocsMarkdown.Heading]) derives CanEqual

    private def articleUrl(route: String): String =
        val clean = if route.endsWith("/") then route else route + "/"
        s"${clean}content.html"
    end articleUrl

    private def parseArticleHeadings(body: String)(using Frame): Chunk[DocsMarkdown.Heading] < Sync =
        Sync.defer {
            val tocArray = extractArray(body, "headings").getOrElse("[]")
            Chunk.from(splitJsonArray(tocArray).flatMap(parseOutlineHeading))
        }

    private def parseOutlineHeading(obj: String): Maybe[DocsMarkdown.Heading] =
        for
            level <- extractInt(obj, "level")
            text  <- extractString(obj, "text")
            slug  <- extractString(obj, "slug")
        yield DocsMarkdown.Heading(level, text, slug)
    end parseOutlineHeading

    private def extractInt(obj: String, key: String): Maybe[Int] =
        val pattern = s""""$key"\\s*:\\s*(-?\\d+)""".r
        Maybe.fromOption(pattern.findFirstMatchIn(obj).flatMap(m => m.group(1).toIntOption))
    end extractInt

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

    /** The parsed `#docs-island` payload the SSG seeds into each docs page.
      *
      * Carries the current route's `WebsiteContent` (intro + grouped modules + version record), the
      * full version list for the dropdown, the pre-rendered article HTML for first-paint injection,
      * and the heading outline for the TOC. The article HTML is injected verbatim via `UI.rawHtml`;
      * it is never re-transpiled in the browser.
      *
      * @param content
      *   The current page's content (intro, groups, version).
      * @param versions
      *   All available versions (for the dropdown).
      * @param articleHtml
      *   The pre-rendered article HTML produced by the SSG (seeds the article cache; injected via
      *   `UI.rawHtml` for first render; never re-transpiled).
      * @param headings
      *   The heading outline for the TOC: level, text, and anchor slug for each heading.
      */
    final case class DocsIsland(
        content: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        articleHtml: String,
        headings: Chunk[DocsMarkdown.Heading]
    ) derives CanEqual

    /** Parse a JSON array of version objects from an SSG-seeded DOM island.
      *
      * The input is a JSON array of `{"tag": "...", "label": "...", "latest": true/false}` objects,
      * as written by the SSG into the `#versions-island` script element. Returns an empty `Chunk`
      * when the input is empty, not a valid JSON array, or has no parseable entries (an empty parse
      * result, distinct from the absent-element case the DOM reader handles before calling this).
      *
      * Exposed as `private[website]` so `WebsiteBundleMain.readVersions` can call it
      * synchronously at bundle entry without going through the async `routeTable` path.
      */
    private[website] def parseVersionsIsland(json: String)(using Frame): Chunk[WebsiteVersion] < Sync =
        parseVersions(json)

    /** Parse the `#docs-island` JSON object the SSG seeds into a docs page.
      *
      * The object schema, written by `WebsiteGenerator.docsIsland`, is
      * `{"version": {...}, "intro": "...", "groups": [{"name": "...", "modules": [...]}],
      * "versions": [...], "article": "...", "headings": [...]}`. Returns a `DocsIsland` whose
      * `content` carries the parsed intro/groups/version, `articleHtml` is the pre-rendered article
      * HTML, and `headings` is the outline for the TOC. When `json` is empty or not a JSON object,
      * returns an empty island (empty groups, a `latest` placeholder version, empty article, empty
      * headings); this empty-parse case is distinct from the absent-`#docs-island`-element case the
      * caller handles before parsing.
      *
      * Exposed as `private[website]` so `WebsiteBundleMain` can call it synchronously at bundle entry.
      */
    private[website] def parseDocsIsland(json: String)(using Frame): DocsIsland < Sync =
        Sync.defer {
            val trimmed = json.trim
            if !trimmed.startsWith("{") || !trimmed.endsWith("}") then emptyIsland
            else
                val versionObj  = extractObject(trimmed, "version")
                val version     = versionObj.flatMap(parseVersion).getOrElse(WebsiteVersion("latest", "latest", true))
                val intro       = extractString(trimmed, "intro").getOrElse("")
                val articleHtml = extractString(trimmed, "article").getOrElse("")
                val headingsArr = extractArray(trimmed, "headings").getOrElse("[]")
                val headings    = Chunk.from(splitJsonArray(headingsArr).flatMap(parseOutlineHeading))
                val groupsArray = extractArray(trimmed, "groups").getOrElse("[]")
                val groups      = Chunk.from(splitJsonArray(groupsArray).flatMap(parseGroup))
                val versions    = extractArray(trimmed, "versions").map(splitJsonArray).getOrElse(Seq.empty).flatMap(parseVersion)
                DocsIsland(
                    WebsiteContent(intro, groups, version),
                    Chunk.from(versions),
                    articleHtml,
                    headings
                )
            end if
        }
    end parseDocsIsland

    private def emptyIsland: DocsIsland =
        DocsIsland(
            WebsiteContent("", Chunk.empty, WebsiteVersion("latest", "latest", true)),
            Chunk.empty,
            "",
            Chunk.empty
        )

    private def parseGroup(obj: String): Maybe[WebsiteContent.Group] =
        extractString(obj, "name").map { name =>
            val modulesArray = extractArray(obj, "modules").getOrElse("[]")
            val modules      = Chunk.from(splitJsonArray(modulesArray).flatMap(parseModule))
            WebsiteContent.Group(name, modules)
        }
    end parseGroup

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
            // Whitespace-tolerant: the SSG emits `"latest": true`, the route table `"latest":true`.
            latest = obj.replaceAll("\\s", "").contains("\"latest\":true")
        yield WebsiteVersion(tag, label, latest)
    end parseVersion

    private def parseManifest(json: String)(using Frame): Chunk[WebsiteModule] < Sync =
        Sync.defer {
            val items   = splitJsonArray(json)
            val modules = items.flatMap(parseModule)
            Chunk.from(modules)
        }
    end parseManifest

    /** Parse the per-module section headings from a `manifest.json` body for the search index.
      *
      * Each manifest element carries a `toc` array of `{"level": N, "text": "...", "slug": "..."}`
      * objects (written by `WebsiteGenerator.manifestEntry`). This reads each element's `slug` and
      * its `toc`, mapping the slug to its headings (text + anchor slug, dropping `level`, which the
      * search index does not use). A module with no `toc` maps to an empty `Chunk`. Exposed
      * `private[website]` so the bundle can build the heading index and a test can assert the parse.
      */
    private[website] def parseHeadings(json: String)(using Frame): Map[String, Chunk[DocsSearch.Heading]] < Sync =
        Sync.defer {
            val items = splitJsonArray(json)
            items.flatMap { obj =>
                extractString(obj, "slug").map { slug =>
                    val tocArray = extractArray(obj, "toc").getOrElse("[]")
                    val headings = splitJsonArray(tocArray).flatMap(parseHeading)
                    slug -> Chunk.from(headings)
                }
            }.toMap
        }
    end parseHeadings

    private def parseHeading(obj: String): Maybe[DocsSearch.Heading] =
        for
            text <- extractString(obj, "text")
            slug <- extractString(obj, "slug")
        yield DocsSearch.Heading(text, slug)
    end parseHeading

    /** Parse a `search-index.json` body into a [[Chunk]] of [[DocsSearch.Entry]] values.
      *
      * Total and degrade-not-fail: a non-array body yields `Chunk.empty`; an element missing a
      * required field (`slug`, `title`, or `group`) is dropped via `Maybe`-per-field
      * for-comprehension; a malformed element degrades to a smaller index rather than crashing.
      * Returned entries have `prefix = ""` (stamped by [[fetchSearchIndex]] via `.copy`).
      *
      * @param json
      *   The raw JSON body of a `search-index.json` file.
      * @return
      *   Parsed entries, possibly empty on any malformed input, wrapped in `Sync`.
      */
    private[website] def parseSearchIndex(json: String)(using Frame): Chunk[DocsSearch.Entry] < Sync =
        Sync.defer {
            val items = splitJsonArray(json)
            Chunk.from(items.flatMap(parseSearchEntry))
        }
    end parseSearchIndex

    private def parseSearchEntry(obj: String): Maybe[DocsSearch.Entry] =
        for
            slug  <- extractString(obj, "slug")
            title <- extractString(obj, "title")
            group <- extractString(obj, "group")
        yield
            val sectionsArray = extractArray(obj, "sections").getOrElse("[]")
            val sectionObjs   = splitJsonArray(sectionsArray)
            val headings      = Chunk.from(sectionObjs.flatMap(parseHeading))
            val snippets      = sectionObjs.flatMap(s => extractString(s, "snippet"))
            val text          = (headings.map(_.text).toSeq ++ snippets).mkString(" ")
            DocsSearch.Entry(slug, title, group, "", text, headings)
    end parseSearchEntry

    /** Fetch and parse the `search-index.json` for the given active prefix.
      *
      * GETs `/$activePrefix/search-index.json`, parses the body via [[parseSearchIndex]], then stamps
      * each entry's `prefix` field with `activePrefix` via `.copy`. The row is `< Async` with no
      * `Abort` widening: an HTTP or network failure surfaces on the `Async` channel as a
      * `RuntimeException`, so the caller can wrap with `Abort.run[Throwable](Abort.catching(...))` to
      * degrade gracefully without widening this method's row.
      *
      * @param activePrefix
      *   The physical tree prefix whose `search-index.json` to fetch (`latest` or a version tag).
      * @return
      *   The parsed [[DocsSearch.Index]] with prefix-stamped entries, wrapped in `Async`.
      */
    def fetchSearchIndex(activePrefix: String)(using Frame): DocsSearch.Index < Async =
        fetch(s"/$activePrefix/search-index.json")
            .map(parseSearchIndex)
            .map(entries => DocsSearch.Index(entries.map(_.copy(prefix = activePrefix))))
    end fetchSearchIndex

    private def parseModule(obj: String): Maybe[WebsiteModule] =
        for
            slug  <- extractString(obj, "slug")
            group <- extractString(obj, "group")
            title <- extractString(obj, "title")
        // The manifest/island JSON carries only slug/group/title (the fields the client nav needs);
        // it does not serialize per-platform support or the raw README. Article content is fetched
        // on demand via fetchArticle, and `Platforms(true, true, true)` is an unused placeholder
        // here (the client never reads module.platforms), not a claim that every module is cross-platform.
        yield WebsiteModule(slug, group, title, "", WebsiteModule.Platforms(true, true, true))
    end parseModule

    /** Split a JSON array's top-level elements, respecting string literals.
      *
      * Scans the array body tracking bracket/brace depth, but counts `{ } [ ]` and the element-
      * separating `,` only OUTSIDE string literals. A `"..."` literal is detected by an unescaped
      * `"`; a `\` escapes the next character (so `\"` does not close the string). This mirrors
      * `extractBracketed`'s `inStr`/`escaped` scan, so a heading or label whose text contains an
      * unbalanced `]`/`}`/`[`/`{` inside a JSON string value (e.g. a toc entry `"Layout[A] and ]weird["`)
      * does not desync the depth counter and split an element wrongly (WARN-2).
      */
    private def splitJsonArray(json: String): Seq[String] =
        val trimmed = json.trim
        if !trimmed.startsWith("[") || !trimmed.endsWith("]") then Seq.empty
        else
            val inner = trimmed.substring(1, trimmed.length - 1).trim
            if inner.isEmpty then Seq.empty
            else
                val items   = scala.collection.mutable.ArrayBuffer.empty[String]
                var depth   = 0
                var start   = 0
                var i       = 0
                var inStr   = false
                var escaped = false
                while i < inner.length do
                    val c = inner(i)
                    if escaped then escaped = false
                    else if c == '\\' then escaped = true
                    else if c == '"' then inStr = !inStr
                    else if !inStr then
                        c match
                            case '{' | '[' => depth += 1
                            case '}' | ']' => depth -= 1
                            case ',' if depth == 0 =>
                                items += inner.substring(start, i).trim
                                start = i + 1
                            case _ =>
                        end match
                    end if
                    i += 1
                end while
                if start < inner.length then items += inner.substring(start).trim
                items.toSeq
            end if
        end if
    end splitJsonArray

    /** Extract the string value for `key` from a flat JSON object fragment.
      *
      * Handles escaped double-quotes (`\"`) inside the value and unescapes the standard JSON escape
      * sequences (`\"`, `\\`, `\/`, `\n`, `\t`, `\r`, `\uXXXX`) so the returned `String` is the
      * original text the SSG escaped (so re-transpiled Markdown matches the source byte for byte).
      * Returns `Absent` when the key is not found or the value cannot be parsed.
      */
    private def extractString(obj: String, key: String): Maybe[String] =
        // Match "key" : "value" where value may contain \" escaped quotes.
        // The inner group captures any character except an unescaped quote:
        //   (?:[^"\\]|\\.)* matches either a non-quote/non-backslash char, or a
        //   backslash followed by any character (the escape sequence).
        val pattern = s""""$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
        Maybe.fromOption(pattern.findFirstMatchIn(obj).map(m => unescapeJson(m.group(1))))
    end extractString

    /** Unescape the standard JSON string escape sequences. */
    private def unescapeJson(s: String): String =
        if !s.contains("\\") then s
        else
            val sb = new StringBuilder(s.length)
            var i  = 0
            while i < s.length do
                val c = s.charAt(i)
                if c == '\\' && i + 1 < s.length then
                    s.charAt(i + 1) match
                        case '"'  => sb.append('"'); i += 2
                        case '\\' => sb.append('\\'); i += 2
                        case '/'  => sb.append('/'); i += 2
                        case 'n'  => sb.append('\n'); i += 2
                        case 't'  => sb.append('\t'); i += 2
                        case 'r'  => sb.append('\r'); i += 2
                        case 'b'  => sb.append('\b'); i += 2
                        case 'f'  => sb.append('\f'); i += 2
                        case 'u' if i + 5 < s.length =>
                            val hex = s.substring(i + 2, i + 6)
                            sb.append(Integer.parseInt(hex, 16).toChar)
                            i += 6
                        case other => sb.append(other); i += 2
                    end match
                else
                    sb.append(c)
                    i += 1
                end if
            end while
            sb.toString
        end if
    end unescapeJson

    /** Extract a nested JSON object value (`"key": {...}`) by depth-balanced scan, returning the
      * `{...}` substring including its braces, or `Absent` when the key or a balanced object is absent.
      */
    private def extractObject(obj: String, key: String): Maybe[String] =
        extractBracketed(obj, key, '{', '}')

    /** Extract a nested JSON array value (`"key": [...]`) by depth-balanced scan, returning the
      * `[...]` substring including its brackets, or `Absent` when the key or a balanced array is absent.
      */
    private def extractArray(obj: String, key: String): Maybe[String] =
        extractBracketed(obj, key, '[', ']')

    private def extractBracketed(obj: String, key: String, open: Char, close: Char): Maybe[String] =
        val marker = s""""$key""""
        val keyIdx = obj.indexOf(marker)
        if keyIdx < 0 then Absent
        else
            val openIdx = obj.indexOf(open, keyIdx + marker.length)
            if openIdx < 0 then Absent
            else
                var depth   = 0
                var i       = openIdx
                var endIdx  = -1
                var inStr   = false
                var escaped = false
                while i < obj.length && endIdx < 0 do
                    val c = obj.charAt(i)
                    if escaped then escaped = false
                    else if c == '\\' then escaped = true
                    else if c == '"' then inStr = !inStr
                    else if !inStr && c == open then depth += 1
                    else if !inStr && c == close then
                        depth -= 1
                        if depth == 0 then endIdx = i
                    end if
                    i += 1
                end while
                if endIdx < 0 then Absent else Present(obj.substring(openIdx, endIdx + 1))
            end if
        end if
    end extractBracketed

end DocsClient
